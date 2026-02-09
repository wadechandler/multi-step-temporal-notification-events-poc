# Task 04: Build CQRS Services (Phase 3)

## Context

This task implements the mock "engagement platform" services that the Temporal workflow will
interact with. These services follow the CQRS pattern: POST commands go through Kafka,
GET queries read directly from the database.

All services live in the Spring Boot app under `com.wadechandler.notification.poc`.

### Architecture Recap

```
POST /events      -> Kafka topic: notification-events     -> (Temporal consumes)
POST /contacts    -> Kafka topic: contact-commands         -> ContactCommandHandler -> DB + contact-events
GET  /contacts/id -> DB read                               -> 200 or 404
POST /messages    -> Kafka topic: message-commands         -> MessageCommandHandler -> DB + message-events
```

Key behavioral requirement: POST endpoints return `202 Accepted` immediately. The actual
object creation happens asynchronously via Kafka consumers (command handlers). This means
GET requests may return `404` for a short period after a POST (eventual consistency).
This is the exact behavior the Temporal workflow must handle.

### Domain Model

**Contact:**
```json
{
  "id": "uuid",
  "externalIds": { "patientId": "ext-uuid" },
  "email": "patient@example.com",
  "phone": "+1234567890",
  "status": "ACTIVE"
}
```

**Message:**
```json
{
  "id": "uuid",
  "contactId": "uuid",
  "templateId": "rx-notification-v1",
  "channel": "EMAIL",
  "content": "Your prescription is ready...",
  "status": "PENDING"
}
```

**External Event:**
```json
{
  "eventId": "uuid",
  "eventType": "RxOrderNotification",
  "payload": {
    "orderId": "uuid",
    "contacts": [
      { "externalIdType": "patientId", "externalIdValue": "ext-uuid-1", "email": "a@b.com" },
      { "externalIdType": "patientId", "externalIdValue": "ext-uuid-2", "email": "c@d.com" }
    ],
    "templateId": "rx-notification-v1"
  }
}
```

## Prerequisites
- Task 01 completed (infra running with CNPG)
- Java 25 and Gradle available (`sdk env`)
- App compiles: `./gradlew :app:compileJava`

## Key Files to Read
- `.cursor/rules/00-project-standards.mdc` — Full domain model, CQRS pattern, Kafka topics
- `app/build.gradle` — Dependencies
- `app/src/main/resources/application.yml` — Kafka and DB config
- `app/src/main/resources/db/migration/V1__initial_schema.sql` — DB schema
- `app/src/main/java/com/wadechandler/notification/poc/NotificationPocApplication.java`

## Deliverables

### Package Structure
```
com.wadechandler.notification.poc/
├── model/
│   ├── Contact.java           (JPA entity)
│   ├── Message.java           (JPA entity)
│   ├── ExternalEvent.java     (JPA entity)
│   └── dto/
│       ├── ContactRequest.java
│       ├── MessageRequest.java
│       └── ExternalEventRequest.java
├── repository/
│   ├── ContactRepository.java (Spring Data JPA)
│   ├── MessageRepository.java
│   └── ExternalEventRepository.java
├── controller/
│   ├── EventController.java   (POST /events -> Kafka)
│   ├── ContactController.java (POST -> Kafka, GET -> DB)
│   └── MessageController.java (POST -> Kafka, GET -> DB)
├── command/
│   ├── ContactCommandHandler.java  (Kafka consumer -> DB write -> publish ContactCreated)
│   └── MessageCommandHandler.java  (Kafka consumer -> DB write -> publish MessageCreated)
└── config/
    └── KafkaConfig.java
```

### Behaviors
1. `POST /events` — Accepts ExternalEventRequest, publishes JSON to `notification-events` topic, returns 202.
2. `POST /contacts` — Accepts ContactRequest, publishes ContactCreateRequested to `contact-commands`, returns 202.
3. `GET /contacts/{id}` — Reads from DB, returns 200 or 404.
4. `GET /contacts?externalIdType={type}&externalIdValue={value}` — Lookup by external ID, returns 200 or 404.
5. `POST /messages` — Accepts MessageRequest, publishes MessageCreateRequested to `message-commands`, returns 202.
6. `GET /messages/{id}` — Reads from DB, returns 200 or 404.
7. ContactCommandHandler — Consumes from `contact-commands`, inserts into `contacts` table, publishes ContactCreated to `contact-events`.
8. MessageCommandHandler — Consumes from `message-commands`, inserts into `messages` table, publishes MessageCreated to `message-events`.

## Acceptance Criteria
- `./gradlew :app:compileJava` succeeds
- Unit tests pass for controllers and command handlers
- With infra running, the app starts and connects to Kafka and DB
- POST /contacts returns 202, and after a brief delay, GET /contacts/{id} returns 200

## Prompt (for Builder sub-agent)

```
Read the following files for full context:
- docs/tasks/04-cqrs-services.md (this task — contains domain model, package structure, behaviors)
- .cursor/rules/00-project-standards.mdc (tech stack, CQRS pattern, coding standards)
- app/build.gradle
- app/src/main/resources/application.yml
- app/src/main/resources/db/migration/V1__initial_schema.sql
- app/src/main/java/com/wadechandler/notification/poc/NotificationPocApplication.java

Task: Implement the CQRS services per the package structure and behaviors defined
in the task document.

Key rules:
- POST endpoints return 202 Accepted and publish to Kafka. They do NOT write to DB directly.
- Kafka command handlers consume from command topics, write to DB, publish fact events.
- GET endpoints read from DB and return 200 or 404.
- Use Spring Data JPA for repositories, Spring Kafka for producers/consumers.
- Use Lombok for boilerplate reduction.
- Use Jackson for JSON serialization.
- Follow the package structure exactly as specified.

Write unit tests for controllers and command handlers.
```
