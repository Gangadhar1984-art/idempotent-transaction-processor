# Architecture Diagram

This diagram summarizes the local Java Spring Boot transaction-processing demo, its in-memory state, and the request path from the browser/API through background processing.

## Application Architecture

<!-- mermaid-checked: no \n, no em-dash/en-dash, no {} in labels, subgraphs are id["label"], arrows are -->|"label"|, all subgraphs closed by end, ids unique -->
~~~mermaid
flowchart TD
    subgraph ClientLayer["Client Layer"]
        Browser["Browser console"]
        Caller["HTTP API caller"]
    end
    subgraph ApplicationLayer["Application Layer - Spring Boot 3.3.5"]
        StaticUi["Static HTML and JavaScript"]
        RestApi["Spring MVC REST API"]
        Health["Actuator health endpoint"]
        Processor["Transaction processor"]
        Workers["Fixed pool of 2 workers"]
        Logs["SLF4J lifecycle logs"]
    end
    subgraph DataLayer["Data Layer"]
        State[("In-memory maps")]
    end

    Browser -->|"HTTP requests"| StaticUi
    Browser -->|"HTTP requests"| RestApi
    Caller -->|"GET health"| Health
    StaticUi -->|"fetch API"| RestApi
    RestApi -->|"submit, retry, query"| Processor
    Processor -->|"schedule work"| Workers
    Workers -->|"process account sequence"| Processor
    Processor -->|"atomic in-process state changes"| State
    Processor -->|"lifecycle events"| Logs
~~~

### Technology Stack Summary

| Layer | Technology | Version | Purpose |
|---|---|---|---|
| Language | Java | 17 | Service implementation |
| Application | Spring Boot | 3.3.5 | HTTP API, dependency injection, static UI hosting |
| Web | Spring MVC | Spring Boot managed | Transaction and summary endpoints |
| Health | Spring Boot Actuator | Spring Boot managed | Minimal process health endpoint |
| Background processing | Java ExecutorService | Java 17 | Fixed pool of two worker threads |
| Persistence | In-memory Java maps | N/A | Demo-only transaction, sequence, and balance state |
| Tests | JUnit 5 and Spring Boot Test | Spring Boot managed | Unit and health endpoint testing |
| Build | Maven | 3.8+ | Build and test |

### Data Storage & External Services

The application has no database, cache, message broker, or external service integration. It stores request/transaction records, sequence slots, account cursors, and account/currency balances in synchronized in-memory state. Restarting the process loses all state.

### Key Architectural Decisions

- Keep the HTTP adapter separate from the processing service so a different input adapter can call the same business logic.
- Use per-account sequence ordering and an in-process lock to make the demo's ordering and update boundary explicit.
- Use only local dependencies and volatile storage to keep setup and testing straightforward.

## Component Relationships

<!-- mermaid-checked: no \n, no em-dash/en-dash, no {} in labels, subgraphs are id["label"], arrows are -->|"label"|, all subgraphs closed by end, ids unique -->
~~~mermaid
flowchart LR
    subgraph PresentationLayer["Presentation"]
        cBrowser["Browser console"]
        cController["TransactionController"]
        cHealth["Actuator health endpoint"]
    end
    subgraph BusinessLayer["Business Logic"]
        cProcessor["TransactionProcessor"]
        cRecord["TransactionRecord"]
        cRequest["TransactionRequest validation"]
        cExecutor["Fixed thread pool"]
    end
    subgraph StateLayer["In-memory state"]
        cTransactions["Transaction and request maps"]
        cSequences["Sequence slots and cursors"]
        cBalances["Account and currency balances"]
    end
    subgraph CrossCutting["Cross-cutting"]
        cLogs["SLF4J event logs"]
    end

    cBrowser -->|"calls HTTP endpoints"| cController
    cBrowser -->|"checks process health"| cHealth
    cController -->|"submits and queries"| cProcessor
    cProcessor -->|"validates"| cRequest
    cProcessor -->|"creates and updates"| cRecord
    cProcessor -->|"schedules account work"| cExecutor
    cExecutor -->|"runs ordered processing"| cProcessor
    cProcessor -->|"deduplicates requests"| cTransactions
    cProcessor -->|"reserves sequence"| cSequences
    cProcessor -->|"applies successful effect"| cBalances
    cProcessor -->|"logs lifecycle events"| cLogs
~~~

### Component Inventory

| Component | Layer | Type | Responsibility |
|---|---|---|---|
| Browser console | Presentation | Static web UI | Submit sample batches and inspect results |
| TransactionController | Presentation | Spring REST controller | Accept requests, batches, retries, and queries |
| Actuator health endpoint | Presentation | Spring Boot Actuator | Report application health without exposing component details |
| TransactionProcessor | Business Logic | Spring service | Validate, deduplicate, sequence, retry, and apply transaction effects |
| TransactionRequest | Business Logic | Java record | Define and validate mandatory request fields |
| TransactionRecord | Business Logic | Java state model | Hold request status, attempts, and timestamps |
| Fixed thread pool | Business Logic | Java ExecutorService | Run account processing away from the HTTP request |
| Transaction and request maps | In-memory state | Map-backed storage | Track accepted business transactions and request IDs |
| Sequence slots and cursors | In-memory state | Map-backed storage | Track account sequence assignment and next expected sequence |
| Account and currency balances | In-memory state | Map-backed storage | Hold resulting demo balances |
| SLF4J event logs | Cross-cutting | Logging | Record receipt, processing, retry, duplicate, success, and failure events |
