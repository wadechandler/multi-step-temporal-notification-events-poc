# Agent Instructions: Multi-Step Temporal Notification Engine POC

> **Origin:** This architecture was designed in a collaborative session with Google Gemini (Feb 2026),
> then refined and implemented using Cursor. The Gemini session established the requirements,
> CQRS patterns, and infrastructure choices. Cursor handles the actual implementation.

## Project Summary

This is a Proof-of-Concept demonstrating how to build a **high-throughput, durable notification
processing engine** using Temporal.io workflows, CQRS with Kafka, and Kubernetes-native databases.

The scenario: An upstream system sends notification events (e.g., prescription orders). Each event
may reference 1 or more contacts (patients/people). The system must:
1. Look up each contact in the engagement platform.
2. If a contact doesn't exist, create it and wait for eventual consistency.
3. Once all contacts are resolved, create personalized messages for each.

This is a multi-step, potentially long-running process that must be **durable** (survive crashes),
**scalable** (>1M events/day), and **observable**.

## Architecture Overview

```
                    ┌──────────────┐
                    │  POST /events│ (External Event Ingestion)
                    └──────┬───────┘
                           │ Publishes to Kafka
                           ▼
                ┌─────────────────────┐
                │  notification-events│ (Kafka Topic)
                │      topic          │
                └──────────┬──────────┘
                           │ Consumed by Temporal Workflow Starter
                           ▼
                ┌─────────────────────┐
                │  Temporal Workflow   │ (NotificationWorkflow)
                │  "Saga Orchestrator"│
                └──────────┬──────────┘
                           │
              ┌────────────┼────────────┐
              ▼            ▼            ▼
        ┌──────────┐ ┌──────────┐ ┌──────────┐
        │GetContact│ │CreateCont│ │CreateMsg │ (Temporal Activities)
        │ Activity │ │ Activity │ │ Activity │
        └────┬─────┘ └────┬─────┘ └────┬─────┘
             │            │            │
             ▼            ▼            ▼
        ┌──────────────────────────────────┐
        │  Mock REST Services (Spring MVC) │
        │  GET/POST /contacts              │
        │  POST /messages                  │
        └──────────────┬───────────────────┘
                       │ CQRS: POST -> Kafka -> Worker -> DB
                       ▼
                ┌──────────────┐
                │  PostgreSQL  │ (CNPG or YugabyteDB)
                │  business_db │
                └──────────────┘
```

## Implementation Phases

### Phase 1: Infrastructure
**Goal:** Running KIND cluster with all operators and services.

Tasks:
1. `infra/kind-config.yaml` — 3-worker-node KIND cluster with port mappings.
2. `infra/scripts/setup.sh` — Idempotent bootstrap script:
   - Create KIND cluster.
   - Install cert-manager (dependency for operators).
   - Install Strimzi operator + create Kafka cluster (KRaft mode, no ZooKeeper).
   - Install CNPG operator + create database clusters (temporal_db, temporal_visibility_db, business_db).
   - Install Temporal via Helm (chart 0.73.1) configured for Postgres persistence + Postgres Advanced Visibility.
   - Install kube-prometheus-stack for Prometheus + Grafana.
   - Expose services via NodePort for local access (Temporal UI, Grafana, databases for DataGrip).
3. `infra/scripts/teardown.sh` — Destroy the KIND cluster cleanly.
4. `infra/helm/temporal-values.yaml` — Custom Helm values for Temporal.
5. `infra/helm/values-cnpg.yaml` — CNPG cluster definitions.
6. `infra/helm/values-yugabyte.yaml` — YugabyteDB deployment (alternative to CNPG).

### Phase 2: Application Skeleton
**Goal:** Compilable Spring Boot 4 application with Gradle build.

Tasks:
1. `settings.gradle` / `app/build.gradle` — Multi-project Gradle build with:
   - Spring Boot 4.0.2 plugin
   - Temporal SDK 1.32.1
   - Spring Kafka
   - Flyway
   - Lombok, Jackson
   - OpenTelemetry
2. `app/src/main/resources/application.yml` — Config for:
   - Temporal worker connection (task queue: `NOTIFICATION_QUEUE`)
   - Kafka consumer/producer settings
   - DataSource config (Flyway-managed)
3. `Dockerfile` for the application.

### Phase 3: Domain Model & CQRS Services
**Goal:** The mock "engagement platform" services.

Tasks:
1. **Contact Service:**
   - `POST /contacts` — Publishes `ContactCreateRequested` to Kafka, returns `202 Accepted`.
   - `GET /contacts/{id}` — Reads from DB. Returns `404` if not yet materialized.
   - `GET /contacts?externalIdType={type}&externalIdValue={value}` — Lookup by external ID.
   - Kafka consumer: Listens on `contact-commands`, writes to DB, publishes `ContactCreated`.
2. **Message Service:**
   - `POST /messages` — Publishes `MessageCreateRequested` to Kafka, returns `202 Accepted`.
   - Kafka consumer: Listens on `message-commands`, writes to DB, publishes `MessageCreated`.
3. **External Events API:**
   - `POST /events` — Accepts an external event, validates `eventType` is present, publishes to `notification-events` Kafka topic with `X-Event-Type` Kafka header.
   - Event schema: `{ eventId, eventType, payload: { contacts: [...], templateId, ... } }`
   - `eventType` is first-class metadata: it drives downstream routing (which workflow processes the event) and, in production, would drive JSON Schema validation of the payload.
   - Typed payload DTOs (e.g., `NotificationPayload`, `ContactInfo`) represent the parsed shape of specific event types for use by the workflow layer.

### Phase 4: Temporal Workflow Implementation
**Goal:** The durable saga logic.

Tasks:
1. **Activities Interface:** `ContactActivities` (getContact, createContact, pollForContact), `MessageActivities` (createMessage).
2. **Activity Implementations:** HTTP calls to the mock services using Spring's RestClient.
3. **Workflow Interface:** `NotificationWorkflow` with `@WorkflowMethod processNotification(UUID eventId, String eventType, NotificationPayload payload)`.
4. **Workflow Implementation:**
   - For each contact in the payload (already typed, parsed by the consumer):
     - Try getContact by externalId (returns `Optional<ContactResult>`; 404 is empty, not an error).
     - If not found: createContact (gets 202), then pollForContact using Activity RetryOptions with backoff.
     - Once contact resolved: createMessage for that contact.
   - Use `Async.function()` + `Promise.allOf()` for parallel contact processing.
5. **Workflow Starter:** Kafka consumer on `notification-events` that reads `eventType` to determine which workflow to invoke, parses the payload into a typed DTO, and starts the workflow.

### Phase 5: Testing & Load Simulation
**Goal:** Validate the architecture.

Tasks:
1. Unit tests with `TestWorkflowEnvironment`.
2. Integration tests with Testcontainers (Postgres, Kafka).
3. A `TestDataController` or CLI tool to inject bulk events for load testing.
4. Grafana dashboards for Temporal metrics.

## Cursor Workflow Tips

### Using Cursor Rules
The `.cursor/rules/` directory contains context-specific rules:
- `00-project-standards.mdc` — Always active. Tech stack, patterns, domain model.
- `10-infra.mdc` — Active when editing `infra/**`. K8s/Helm/operator patterns.
- `20-temporal-workflows.mdc` — Active when editing Java source. Temporal-specific patterns.

### Suggested Prompts for Building Each Phase
When working with Cursor on each phase, reference this file and the specific phase:

- **Phase 1:** "Read AGENTS.md Phase 1 and the infra rules. Generate the KIND config and setup script."
- **Phase 2:** "Read AGENTS.md Phase 2. Generate the Gradle build files and application.yml."
- **Phase 3:** "Read AGENTS.md Phase 3 and the project standards. Implement the Contact and Message CQRS services."
- **Phase 4:** "Read AGENTS.md Phase 4 and the temporal workflow rules. Implement the NotificationWorkflow and Activities."

## Key Decisions & Rationale

| Decision | Choice | Why |
|----------|--------|-----|
| Workflow Engine | Temporal.io OSS | Durable execution, built-in retries, visibility, proven at scale |
| DB for Temporal | Postgres (CNPG/Yugabyte) | Avoid Elasticsearch complexity; Advanced Visibility supports Postgres since Temporal 1.20+ |
| Messaging | Kafka via Strimzi | Already used in production; Strimzi simplifies K8s deployment; Kafka 4.x KRaft removes ZooKeeper |
| CQRS Pattern | Commands via Kafka | Matches production architecture; enables eventual consistency testing with Temporal |
| DB Toggle | CNPG vs YugabyteDB | Evaluate both for team; Yugabyte offers distributed SQL if infra team can support it |
| Java 25 + Virtual Threads | Performance | Virtual Threads ideal for I/O-heavy Temporal activities; latest LTS features |
| Spring Boot 4 | Latest stable | Jakarta EE 11, Spring Framework 7, native Kafka 4.x support |

## Compatibility Notes

- **Temporal SDK 1.32.1 + Spring Boot 4:** The Temporal `temporal-spring-boot-starter` was released
  before Spring Boot 4.0.0. If auto-configuration doesn't work with SB4, fall back to manual
  `WorkflowClient` and `WorkerFactory` bean configuration. The core `temporal-sdk` artifact has
  no Spring dependency and will work regardless.
- **Spring Boot 4.0.2** uses Jackson 3.0.4 (with `jackson-bom`). Ensure Temporal SDK's Jackson
  usage is compatible, or exclude and align versions.
