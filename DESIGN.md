# Design Note: Idempotent Background Transaction Processing

## Architecture

The application is a Java 17 Spring Boot service. `TransactionController` provides HTTP endpoints and a simple static browser console provides a local demo UI. SpringDoc exposes the API contract and interactive Swagger UI for local testing. Spring Boot Actuator exposes a minimal health endpoint at `/actuator/health`. The controller delegates accepted requests to `TransactionProcessor`, which owns validation, idempotency and sequence checks, background processing, retry simulation, balances, transaction statuses, and summary counts.

The processor uses in-memory maps for transaction/request state, per-account sequence slots, next expected sequence, and account/currency balances. A small fixed thread pool runs processing away from the HTTP request. This keeps the demo easy to run without external services while separating the HTTP input adapter from the processing rules.

See the [architecture diagram](docs/diagrams/architecture.svg) for the visual overview and the editable [PlantUML source](docs/diagrams/architecture.puml).

## Idempotency and duplicate detection

- `transactionId` is the business transaction key. The first valid request reserves that ID before it is scheduled.
- Replaying an identical `requestId` returns its previously recorded request. Reusing that request ID for different request content is rejected as a conflict.
- A new request ID with an existing transaction ID is marked `DUPLICATE` when its account, type, amount, currency, and sequence match the original transaction. It is recorded for visibility, but it does not reserve a second sequence slot or update the balance.
- Reusing a transaction ID with different business data, or assigning a different transaction to an occupied account/sequence, is rejected with a conflict.
- Balance effects are applied only after the simulated processing attempt succeeds. The update and `PROCESSED` status change happen within the processor's synchronized processing operation, preventing a retry from applying the same in-memory effect twice.

## Out-of-order handling

Each account has an independent sequence beginning at 1. A request at a future sequence is accepted as `WAITING_FOR_SEQUENCE`. When the missing expected event arrives, the background worker processes that event and drains any consecutive waiting events in sequence order.

This favors ordered ledger updates over availability: a record that exhausts retries remains `FAILED` and blocks later events for that account. An operator can retry the failed transaction through the retry endpoint; on success, sequence draining resumes. The demo does not infer a missing event or skip a sequence automatically.

## Retry and failure handling

Input validation rejects missing identifiers, unsupported transaction types, non-positive amounts, malformed currency codes, invalid sequences, and negative simulated failure counts. Batch submission handles records individually so a rejected record does not prevent other valid records from being accepted.

For demonstration, `simulatedFailures` controls the number of transient attempts to fail. The processor retries up to three times per processing run. If it still cannot succeed, it records `FAILED` and does not update the balance. A failed record can be manually re-queued. Retry attempts are cumulative on the record, while the three-attempt limit applies to each run. In a real integration, the simulation would be replaced by classified transient errors, bounded backoff, and durable retry scheduling.

## Observability

The service exposes transaction records and statuses through `GET /api/transactions`, per-status counts and account/currency balances through `GET /api/summary`, and individual balances through the account balance endpoint. `GET /actuator/health` reports whether the application is up; component details are not exposed, and other actuator endpoints are not enabled. This is a process-level health signal, not a check of durable storage or a queue (neither is present in this demo). Logs mark receipt, processing, retry, duplicate, success, and exhaustion events; they include transaction/request identifiers for correlation, but omit account IDs and amounts.

The lifecycle includes `RECEIVED`, `PROCESSING`, `PROCESSED`, `DUPLICATE`, `WAITING_FOR_SEQUENCE`, `RETRY_PENDING`, and `FAILED`. Some intermediate statuses can be brief because processing happens asynchronously and quickly.

## AI-assisted development and validation

AI assistance was used to scaffold and implement the service, UI, tests, and documentation. The implementation was checked against the assessment requirements, and the behavior was validated with `mvn test` plus a local HTTP smoke test of the sample batch. The smoke test verifies application health, ordered processing, duplicate suppression, transient retry, invalid-record isolation, summary counts, and expected balances. The tests and local run instructions are included in this repository so the behavior can be reproduced.

## Production hardening recommendations

This is a single-process demonstration, not a durable or horizontally scalable transaction system. Restarting the process loses all requests, idempotency state, sequence cursors, and balances. Although the processor is synchronized to protect in-memory state, that lock serializes processing across accounts and does not coordinate multiple application instances.

For production:

1. Persist request/idempotency state, account sequence cursors, and immutable ledger entries in a database. Apply the ledger entry, balance update, and processed status atomically; enforce uniqueness for transaction IDs and account/sequence pairs.
2. Replace the in-process executor with a durable queue. Define delivery, acknowledgment, retry, dead-letter, replay, and operator recovery behavior explicitly.
3. Use per-account partitioning or database concurrency controls to preserve ordering while allowing unrelated accounts to process concurrently across workers.
4. Classify transient versus permanent failures, add bounded exponential backoff with jitter, and expose failed/pending work for operational recovery.
5. Add authentication, authorization, rate limits, input size limits, data-retention controls, metrics, tracing, and alerts. Avoid logging sensitive transaction/customer data.
6. Use an outbox or equivalent reliable handoff for downstream side effects, and reconcile account balances from the immutable ledger.
