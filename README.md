# Idempotent Background Transaction Processing Service

A small Java 17 / Spring Boot service demonstrating safe background processing for duplicate, out-of-order, and retry-prone transaction requests. It uses only local in-memory state and has a lightweight browser console at `http://localhost:8080`.

## Reviewer quick start

- **Run instructions:** Start with the [local setup](#run-locally-windows) below.
- **Sample input:** [`samples/sample-batch.json`](samples/sample-batch.json) contains out-of-order events, a duplicate, an invalid transaction, and a retryable failure.
- **Expected output:** [`samples/expected-summary.json`](samples/expected-summary.json) shows the final status counts and balances after submitting the sample batch.
- **One-command API smoke test:** Run [`scripts/test-sample.ps1`](scripts/test-sample.ps1) after starting the app; it checks application health and prints PASS/FAIL results for the sample behavior.
- **Interactive API testing:** Open [Swagger UI](http://localhost:8080/swagger-ui/index.html) after starting the service. The [OpenAPI JSON](http://localhost:8080/v3/api-docs) is also available.
- **Health check:** Open [http://localhost:8080/actuator/health](http://localhost:8080/actuator/health) or run `Invoke-RestMethod http://localhost:8080/actuator/health`.
- **Tests and evidence:** Run `mvn test`; [processor tests](src/test/java/com/example/transactions/TransactionProcessorTest.java) cover transaction behavior, and [health endpoint tests](src/test/java/com/example/transactions/HealthEndpointTest.java) check `/actuator/health` and exposed actuator links.
- **Architecture diagram:** View the [SVG diagram](docs/diagrams/architecture.svg) or edit its [PlantUML source](docs/diagrams/architecture.puml).
- **Design decisions:** See the [design note](DESIGN.md).

## Run locally (Windows)

Prerequisites: Java 17+ and Maven 3.8+.

```powershell
cd C:\Users\Gangadhar\USECASE
mvn test
mvn spring-boot:run
```

Open [http://localhost:8080](http://localhost:8080), select **Load sample batch**, then **Submit batch**. The expected summary is in [`samples/expected-summary.json`](samples/expected-summary.json).

For interactive API testing, open [Swagger UI](http://localhost:8080/swagger-ui/index.html). Expand an endpoint, choose **Try it out**, provide a request body if required, and select **Execute**. The batch endpoint includes an out-of-order example. OpenAPI documentation is available at [http://localhost:8080/v3/api-docs](http://localhost:8080/v3/api-docs).

Check application health at [http://localhost:8080/actuator/health](http://localhost:8080/actuator/health). A healthy response is `{"status":"UP"}`. From PowerShell, use:

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
```

Only the health actuator endpoint is exposed; component details and other actuator endpoints are disabled.

Alternatively, in a second PowerShell window, submit the checked-in sample file:

```powershell
$batch = Get-Content .\samples\sample-batch.json -Raw
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/transactions/batch `
  -ContentType "application/json" -Body $batch
Start-Sleep -Seconds 1
Invoke-RestMethod http://localhost:8080/api/summary
```

The sample is intended for a fresh application process because state is in memory. In the result, the invalid record is isolated, the duplicate has no second balance effect, and the one-time simulated failure succeeds on retry.

To run the automated client smoke test against the running service, open another PowerShell window in the project folder:

```powershell
.\scripts\test-sample.ps1
```

The script generates unique transaction, request, and account IDs each run, so it can be repeated against the same running in-memory service without resetting it. To test a different local port or host, pass a base URL, for example `.\scripts\test-sample.ps1 -BaseUrl "http://localhost:8081"`.

The HTTP API is also available directly:

```powershell
$body = @{
  transactionId = "TX-1"
  requestId = "REQ-1"
  accountId = "ACC-1"
  transactionType = "CREDIT"
  amount = 100
  currency = "USD"
  sequenceNumber = 1
  simulatedFailures = 0
} | ConvertTo-Json

Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/transactions `
  -ContentType "application/json" -Body $body
Invoke-RestMethod http://localhost:8080/api/transactions
Invoke-RestMethod http://localhost:8080/api/summary
Invoke-RestMethod 'http://localhost:8080/api/accounts/ACC-1/balance?currency=USD'
```

Useful endpoints:

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/transactions` | Accept one transaction |
| `POST` | `/api/transactions/batch` | Accept records independently; invalid records do not stop the batch |
| `GET` | `/api/transactions` | Inspect accepted requests and their statuses |
| `POST` | `/api/transactions/{transactionId}/retry` | Re-queue a failed transaction |
| `GET` | `/api/summary` | Counts by status and current account balances |
| `GET` | `/api/accounts/{accountId}/balance?currency=USD` | Inspect one currency balance |

`simulatedFailures` is an optional demo-only field specifying how many transient attempts to fail before succeeding. Requests are retried up to three times per run; a record that still fails can be manually retried. Do not expose this control in a production ingestion API.

## Design note

The assessment design, idempotency and ordering choices, retry behavior, observability, AI-assisted development, and production hardening recommendations are documented in [DESIGN.md](DESIGN.md).

## Tests

`mvn test` exercises out-of-order arrival and sequence draining, duplicate detection, invalid amount validation, retry success and exhaustion, manual recovery, sequence conflicts, balance safety, and status summaries. The PowerShell smoke test also verifies that `/actuator/health` reports `UP`.
