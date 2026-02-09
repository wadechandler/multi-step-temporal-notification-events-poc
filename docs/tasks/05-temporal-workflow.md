# Task 05: Build Temporal Workflow (Phase 4)

## Context

This task implements the Temporal workflow that orchestrates the notification processing saga.
The workflow is triggered by events on the `notification-events` Kafka topic and coordinates
contact resolution and message creation across the CQRS services built in Task 04.

### Event-Type-Driven Processing

The `notification-events` Kafka topic carries events of various types. Each event has an
`eventType` field (also available as the `X-Event-Type` Kafka header, added in Task 04a).
The Kafka consumer in this task inspects the `eventType` to determine which workflow to invoke.

For this POC, we implement one event type: `RxOrderNotification`. In a production system,
different event types would route to different workflows or bounded contexts that know how
to process their specific payload shapes.

### Workflow Logic (RxOrderNotification)

For each notification event:
1. The Kafka consumer reads the event, checks `eventType`, and parses the payload into
   a typed `NotificationPayload` (from Task 04a).
2. For each contact in the payload (1 or more per event):
   a. Call `GET /contacts?externalIdType={type}&externalIdValue={value}`
   b. If 200: Contact exists. Store the contactId.
   c. If 404: Contact doesn't exist.
      - Call `POST /contacts` with contact data. Expect 202.
      - Enter polling: Call GET again with exponential backoff until 200.
      - Store the contactId.
3. For each resolved contact:
   a. Call `POST /messages` with contactId and template data. Expect 202.
4. Workflow completes.

### Temporal Patterns

- **Activities** handle all I/O (HTTP calls to the REST services).
- **RetryOptions** on the polling activity stub provide exponential backoff automatically.
- **`Async.function()` + `Promise.allOf()`** for parallel contact processing.
- Workflow must be deterministic: no direct I/O, no `Thread.sleep`, no `Random`.

## Prerequisites
- Task 04 completed (CQRS services implemented and compiling)
- Task 04a completed (typed event DTOs: `ContactInfo`, `NotificationPayload`; Kafka headers)
- Infrastructure running (Task 01)

## Key Files to Read
- `.cursor/rules/20-temporal-workflows.mdc` — Temporal patterns, activity rules
- `.cursor/rules/00-project-standards.mdc` — Domain model
- `docs/tasks/04-cqrs-services.md` — Service endpoints and behaviors
- `docs/tasks/04a-event-model-refinement.md` — Typed DTOs and event-type pattern
- `app/build.gradle` — Temporal SDK dependency
- `app/src/main/resources/application.yml` — Temporal config
- `app/src/main/java/com/wadechandler/notification/poc/model/dto/NotificationPayload.java`
- `app/src/main/java/com/wadechandler/notification/poc/model/dto/ContactInfo.java`

## Deliverables

### Package Structure Additions
```
com.wadechandler.notification.poc/
├── workflow/
│   ├── NotificationWorkflow.java          (interface with @WorkflowMethod)
│   └── NotificationWorkflowImpl.java      (saga implementation)
├── activity/
│   ├── ContactActivities.java             (interface)
│   ├── ContactActivitiesImpl.java         (HTTP calls to /contacts)
│   ├── MessageActivities.java             (interface)
│   └── MessageActivitiesImpl.java         (HTTP calls to /messages)
├── worker/
│   ├── TemporalWorkerConfig.java          (Spring @Configuration for WorkerFactory)
│   └── NotificationEventConsumer.java     (Kafka consumer -> routes by eventType -> starts workflows)
└── model/dto/
    ├── ContactResult.java                 (returned from contact lookup activities)
    └── MessageResult.java                 (returned from message creation activity)
```

Note: `ContactResult` and `MessageResult` are workflow-layer DTOs, distinct from the
REST-layer DTOs (`ContactRequest`, `MessageRequest`) created in Task 04.

### Workflow Input Model

The workflow does NOT receive a raw `ExternalEventRequest` with an untyped payload map.
Instead, the `NotificationEventConsumer` does the parsing:

1. Consumer deserializes the Kafka message into `ExternalEventRequest`.
2. Consumer reads `eventType` from the `X-Event-Type` Kafka header (or falls back to the
   message body).
3. Based on `eventType`:
   - `RxOrderNotification` → parse `payload` into `NotificationPayload` using Jackson's
     `ObjectMapper.convertValue()`, then start `NotificationWorkflow`.
   - Unknown type → log a warning, skip (or dead-letter in production).
4. The workflow method signature:
   ```java
   @WorkflowMethod
   void processNotification(UUID eventId, String eventType, NotificationPayload payload);
   ```
   Event metadata (`eventId`, `eventType`) travels with the workflow for observability
   and potential type-specific logic.

### Key Behaviors

1. **NotificationEventConsumer:** Kafka consumer on `notification-events` topic.
   Reads `eventType` to determine routing. For `RxOrderNotification`, parses the payload
   into `NotificationPayload`, creates a `WorkflowStub` with a deterministic workflow ID
   (e.g., `"notification-" + eventId`), and calls `processNotification(...)`.

2. **ContactActivities.getContact(externalIdType, externalIdValue):** HTTP GET to
   `/contacts?externalIdType={type}&externalIdValue={value}`. Returns `Optional<ContactResult>`.
   Returns `Optional.empty()` on 404 (this is NOT an error — it means "not found, proceed
   to create"). This activity uses default retry options (retries on transient errors like
   network failures, but a 404 response is a normal result, not a retryable failure).

3. **ContactActivities.createContact(contactData):** HTTP POST to `/contacts`.
   Returns void (fire-and-forget, 202). If the POST itself fails (network error, 500),
   the activity throws and Temporal retries it automatically.

4. **ContactActivities.pollForContact(externalIdType, externalIdValue):** HTTP GET
   (same endpoint as getContact). Throws `ContactNotFoundException` on 404. This activity
   stub is configured with aggressive RetryOptions so the exception triggers automatic
   retries with backoff until the contact materializes. Returns `ContactResult` on success.

5. **MessageActivities.createMessage(contactId, templateId, eventType):** HTTP POST to
   `/messages`. Returns `MessageResult`.

6. **TemporalWorkerConfig:** Spring `@Configuration` bean. See the wiring sketch below.

### Activity Retry Options

Retry options are configured on the **ActivityStub** in the workflow implementation, NOT in
the activity classes themselves. The workflow creates different stubs with different retry
policies:

**Standard activities** (getContact, createContact, createMessage):
```java
ActivityOptions standardOptions = ActivityOptions.newBuilder()
        .setStartToCloseTimeout(Duration.ofSeconds(30))
        .build();
```
These use Temporal's default retry policy, which retries on unexpected failures (network
errors, 500s) but does NOT retry on application-level results like `Optional.empty()`.

**Polling activity** (pollForContact):
```java
ActivityOptions pollingOptions = ActivityOptions.newBuilder()
        .setStartToCloseTimeout(Duration.ofSeconds(30))
        .setRetryOptions(RetryOptions.newBuilder()
                .setInitialInterval(Duration.ofSeconds(1))
                .setBackoffCoefficient(2.0)
                .setMaximumAttempts(10)
                // ContactNotFoundException IS retryable (it's the "not yet consistent" signal)
                .build())
        .build();
```

### Parallel Contact Processing

Use `Async.function()` on activity stubs and `Promise.allOf()` to process multiple contacts
in parallel. Do NOT use child workflows — that is overkill for this POC.

```java
// Sketch (inside workflow implementation):
List<Promise<ContactResult>> contactPromises = payload.contacts().stream()
        .map(contact -> Async.function(() -> resolveContact(contact)))
        .toList();
Promise.allOf(contactPromises).get();

// Then create messages for each resolved contact
```

Where `resolveContact(contact)` is a private workflow method that encapsulates the
lookup → create → poll saga for a single contact.

### TemporalWorkerConfig Wiring Sketch

Since `temporal-spring-boot-starter` may not auto-configure with Spring Boot 4, wire the
Temporal components manually:

```java
@Configuration
public class TemporalWorkerConfig {

    @Bean
    public WorkflowServiceStubs workflowServiceStubs(
            @Value("${temporal.connection.target}") String target) {
        return WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder()
                        .setTarget(target)
                        .build());
    }

    @Bean
    public WorkflowClient workflowClient(WorkflowServiceStubs serviceStubs) {
        return WorkflowClient.newInstance(serviceStubs);
    }

    @Bean
    public WorkerFactory workerFactory(WorkflowClient workflowClient) {
        return WorkerFactory.newInstance(workflowClient);
    }

    @Bean(initMethod = "start")
    public WorkerFactory startedWorkerFactory(
            WorkerFactory factory,
            @Value("${temporal.worker.task-queue}") String taskQueue,
            ContactActivitiesImpl contactActivities,
            MessageActivitiesImpl messageActivities) {

        Worker worker = factory.newWorker(taskQueue,
                WorkerOptions.newBuilder()
                        .setActivityExecutor(Executors.newVirtualThreadPerTaskExecutor())
                        .build());

        worker.registerWorkflowImplementationTypes(NotificationWorkflowImpl.class);
        worker.registerActivitiesImplementations(contactActivities, messageActivities);

        return factory;
    }
}
```

Note: The activity implementations (`ContactActivitiesImpl`, `MessageActivitiesImpl`) are
Spring-managed beans (they use `RestClient` injected by Spring). The workflow implementation
(`NotificationWorkflowImpl`) is NOT a Spring bean — Temporal instantiates it. Activities
are registered as instances; workflows are registered as types.

### Temporal Configuration
- Task Queue: `NOTIFICATION_QUEUE`
- Workflow execution timeout: 10 minutes (generous for polling)
- Activity start-to-close timeout: 30 seconds per attempt
- Polling activity retry: initialInterval=1s, backoff=2.0, maxAttempts=10

## Acceptance Criteria
- `./gradlew :app:compileJava` succeeds
- Unit tests with TestWorkflowEnvironment verify:
  - Happy path: contact exists, message created
  - Create path: contact not found → create → poll succeeds → message created
  - Multiple contacts per event handled in parallel
  - Unknown eventType is skipped by the consumer (not sent to workflow)
- With infra running, posting an event to `notification-events` triggers the workflow visible in Temporal UI

## Prompt (for Builder sub-agent)

```
Read the following files for full context:
- docs/tasks/05-temporal-workflow.md (this task — workflow logic, package structure, behaviors)
- docs/tasks/04a-event-model-refinement.md (typed DTOs and event-type pattern)
- .cursor/rules/20-temporal-workflows.mdc (Temporal patterns and rules)
- .cursor/rules/00-project-standards.mdc (domain model, tech stack)
- app/src/main/resources/application.yml (temporal connection config)
- app/build.gradle (dependencies)

Also read the CQRS service code created in Task 04/04a to understand the REST API
endpoints the activities will call and the typed DTOs:
- app/src/main/java/com/wadechandler/notification/poc/controller/ContactController.java
- app/src/main/java/com/wadechandler/notification/poc/controller/MessageController.java
- app/src/main/java/com/wadechandler/notification/poc/controller/EventController.java
- app/src/main/java/com/wadechandler/notification/poc/model/dto/NotificationPayload.java
- app/src/main/java/com/wadechandler/notification/poc/model/dto/ContactInfo.java
- app/src/main/java/com/wadechandler/notification/poc/model/dto/ExternalEventRequest.java

Task: Implement the Temporal workflow, activities, worker config, and Kafka consumer
per the package structure and behaviors in the task document.

Key rules:
- Workflows MUST be deterministic. No I/O inside workflows.
- All HTTP calls happen in Activity implementations only.
- Use Spring's RestClient for HTTP calls in activities.
- getContact returns Optional<ContactResult> — a 404 is a normal empty result, not an error.
- pollForContact throws ContactNotFoundException on 404 — this IS retryable via RetryOptions.
- Retry options are set on the ActivityStub in the workflow, NOT in the activity implementation.
- Use Async.function() + Promise.allOf() for parallel contact processing (NOT child workflows).
- Use Virtual Threads for the activity worker thread pool via WorkerOptions.setActivityExecutor().
- Register workflow and activities via a Spring @Configuration bean (manual wiring, not starter).
- NotificationEventConsumer reads eventType from Kafka header to route to the correct workflow.
- Workflow method signature: processNotification(UUID eventId, String eventType, NotificationPayload payload).

Write unit tests using TestWorkflowEnvironment for:
- Happy path (contact exists → message created)
- Create path (contact not found → create → poll → message created)
- Multiple contacts processed in parallel
- Consumer skips unknown eventType
```
