# Task 04a: Event Model Refinement

## Context

Task 04 implemented the CQRS services with a generic `ExternalEventRequest` that carries
`Map<String, Object> payload`. This works for ingestion, but the downstream Temporal workflow
(Task 05) needs typed access to the event payload.

This task adds:
1. Typed payload DTOs that represent the shape of specific event types.
2. Event-type awareness in the `EventController` (validation, Kafka headers).

These changes keep the generic `ExternalEventRequest` intact (the `/events` API still accepts
any event type with any payload shape), but provide typed representations the workflow layer
can deserialize into.

### Design Note: Schema-Driven Event Ingestion

In a production system, the `/events` endpoint would validate inbound payloads against
JSON Schemas keyed by `eventType`. Different event types would have different schemas, and
potentially route to different Kafka topics or bounded contexts for processing.

This POC implements the structural pattern (event-type metadata drives downstream behavior)
without full schema validation. The `eventType` field and Kafka header establish the routing
contract; the typed DTOs represent what a schema-validated payload looks like after parsing.

## Prerequisites
- Task 04 completed (CQRS services implemented and compiling)

## Key Files to Read
- `app/src/main/java/com/wadechandler/notification/poc/controller/EventController.java`
- `app/src/main/java/com/wadechandler/notification/poc/model/dto/ExternalEventRequest.java`
- `app/src/main/java/com/wadechandler/notification/poc/config/KafkaConfig.java`

## Deliverables

### 1. New Typed Payload DTOs in `model/dto/`

**`ContactInfo.java`** — Represents one contact reference within a notification event payload:
```java
public record ContactInfo(
        String externalIdType,
        String externalIdValue,
        String email,
        String phone
) {}
```

**`NotificationPayload.java`** — The typed shape of an `RxOrderNotification` event's payload:
```java
public record NotificationPayload(
        List<ContactInfo> contacts,
        String templateId
) {}
```

These are *parallel* DTOs used by the workflow layer (Task 05). They do NOT replace
`ExternalEventRequest` or its `Map<String, Object> payload`. The workflow consumer will
parse the raw payload map into `NotificationPayload` using Jackson when the `eventType`
is `RxOrderNotification`.

### 2. EventController Refinements

Update `EventController.createEvent()` with the following changes:

**a. Validate `eventType` is present:**
- If `eventType` is null or blank, return `400 Bad Request` with a meaningful error message.
- This is the minimum validation the ingestion API should enforce regardless of event type.

**b. Add `eventType` as a Kafka header:**
- Set a `X-Event-Type` header on the Kafka `ProducerRecord` so downstream consumers can
  inspect the event type without deserializing the full message body.
- Use Spring Kafka's `Message` builder or `ProducerRecord` with headers.
- The `eventType` is still in the JSON body too — the header is for efficient routing.

**c. Add a code comment about schema validation:**
- Where the validation check happens, add a comment like:
  ```java
  // Production: validate request.payload() against JSON Schema for this eventType.
  // See the schema-driven ingestion pattern in the project design docs.
  ```

**d. Keep the existing behavior:**
- `eventId.toString()` as Kafka message key (already done).
- Serialize full `ExternalEventRequest` as the message value (already done).
- Return `202 Accepted` on success (already done).

### 3. No Changes To
- `ContactController`, `MessageController`, command handlers
- `ExternalEventRequest` DTO (keep `Map<String, Object> payload` as-is)
- Repositories, JPA entities, database schema
- Kafka topic configuration
- `application.yml`

## Acceptance Criteria
- `./gradlew :app:compileJava` succeeds
- Existing Task 04 unit tests still pass (no regressions)
- New unit test: POST /events with missing/blank `eventType` returns 400
- New unit test: POST /events with valid event produces a Kafka message with `X-Event-Type` header
- `ContactInfo` and `NotificationPayload` records compile and are usable from other packages

## Prompt (for Builder sub-agent)

```
Read the following files for full context:
- docs/tasks/04a-event-model-refinement.md (this task)
- .cursor/rules/00-project-standards.mdc (tech stack, coding standards)
- app/src/main/java/com/wadechandler/notification/poc/controller/EventController.java
- app/src/main/java/com/wadechandler/notification/poc/model/dto/ExternalEventRequest.java
- app/src/main/java/com/wadechandler/notification/poc/model/dto/ContactRequest.java (for DTO pattern reference)
- app/src/main/java/com/wadechandler/notification/poc/config/KafkaConfig.java
- app/src/test/java/com/wadechandler/notification/poc/controller/EventControllerTest.java (existing tests)

Task: Implement the event model refinements per the task document.

Key rules:
- Add ContactInfo and NotificationPayload as Java records in model/dto/ package.
- Update EventController to validate eventType and add X-Event-Type Kafka header.
- Do NOT modify ExternalEventRequest — the Map<String, Object> payload stays as-is.
- Do NOT modify any other controllers, command handlers, or services.
- Keep changes minimal and focused.
- Existing tests must continue to pass.
- Write new unit tests for the eventType validation (400 on blank) and Kafka header.
- Follow existing code patterns (Lombok, records, Spring conventions).
```
