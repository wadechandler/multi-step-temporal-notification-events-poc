# Task 05: Build Temporal Workflow (Phase 4)

## Context

This task implements the Temporal workflow that orchestrates the notification processing saga.
The workflow is triggered by events on the `notification-events` Kafka topic and coordinates
contact resolution and message creation across the CQRS services built in Task 04.

### Workflow Logic

For each notification event:
1. Parse the event to extract contacts and template info.
2. For each contact (1 or more per event):
   a. Call `GET /contacts?externalIdType={type}&externalIdValue={value}`
   b. If 200: Contact exists. Store the contactId.
   c. If 404: Contact doesn't exist.
      - Call `POST /contacts` with contact data. Expect 202.
      - Enter polling loop: Call GET again with backoff until 200.
      - Store the contactId.
3. For each resolved contact:
   a. Call `POST /messages` with contactId and template data. Expect 202.
4. Workflow completes.

### Temporal Patterns

- **Activities** handle all I/O (HTTP calls to the REST services).
- **RetryOptions** on the polling activity provide exponential backoff automatically.
- **Child Workflows** or async activity execution for parallel contact processing.
- Workflow must be deterministic: no direct I/O, no Thread.sleep, no Random.

## Prerequisites
- Task 04 completed (CQRS services implemented and compiling)
- Infrastructure running (Task 01)

## Key Files to Read
- `.cursor/rules/20-temporal-workflows.mdc` — Temporal patterns, activity rules
- `.cursor/rules/00-project-standards.mdc` — Domain model
- `docs/tasks/04-cqrs-services.md` — Service endpoints and behaviors
- `app/build.gradle` — Temporal SDK dependency
- `app/src/main/resources/application.yml` — Temporal config

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
│   ├── TemporalWorkerConfig.java          (Spring bean config for WorkerFactory)
│   └── NotificationEventConsumer.java     (Kafka consumer -> starts workflows)
└── model/dto/
    ├── ContactResult.java                 (returned from contact activities)
    └── MessageResult.java                 (returned from message activities)
```

### Key Behaviors
1. **NotificationEventConsumer:** Kafka consumer on `notification-events` group.
   For each event, creates a `WorkflowStub` and calls `processNotification(event)`.
2. **ContactActivities.getContact(externalIdType, externalIdValue):** HTTP GET. Returns ContactResult or throws ContactNotFoundException.
3. **ContactActivities.createContact(contactData):** HTTP POST. Returns void (fire-and-forget, 202).
4. **ContactActivities.pollForContact(externalIdType, externalIdValue):** Same as getContact but configured with RetryOptions (initialInterval=1s, backoffCoefficient=2.0, maxAttempts=10). Throws on 404 (retryable).
5. **MessageActivities.createMessage(contactId, templateId):** HTTP POST. Returns MessageResult.
6. **TemporalWorkerConfig:** Registers the workflow and activity implementations on the `NOTIFICATION_QUEUE` task queue. Uses Virtual Threads for activity execution.

### Temporal Configuration
- Task Queue: `NOTIFICATION_QUEUE`
- Workflow execution timeout: 10 minutes (generous for polling)
- Activity start-to-close timeout: 30 seconds per attempt
- Polling activity retry: initialInterval=1s, backoff=2.0, maxAttempts=10

## Acceptance Criteria
- `./gradlew :app:compileJava` succeeds
- Unit tests with TestWorkflowEnvironment verify:
  - Happy path: contact exists, message created
  - Create path: contact not found -> create -> poll succeeds -> message created
  - Multiple contacts per event handled
- With infra running, posting an event to `notification-events` triggers the workflow visible in Temporal UI

## Prompt (for Builder sub-agent)

```
Read the following files for full context:
- docs/tasks/05-temporal-workflow.md (this task — workflow logic, package structure, behaviors)
- .cursor/rules/20-temporal-workflows.mdc (Temporal patterns and rules)
- .cursor/rules/00-project-standards.mdc (domain model, tech stack)
- app/src/main/resources/application.yml (temporal connection config)
- app/build.gradle (dependencies)

Also read the CQRS service code created in Task 04 to understand the REST API
endpoints the activities will call.

Task: Implement the Temporal workflow, activities, worker config, and Kafka consumer
per the package structure and behaviors in the task document.

Key rules:
- Workflows MUST be deterministic. No I/O inside workflows.
- All HTTP calls happen in Activity implementations only.
- Use Activity RetryOptions for the polling pattern (NOT Workflow.sleep loops).
- Use Virtual Threads for the activity worker thread pool.
- Use Spring's RestClient for HTTP calls in activities.
- Register workflow and activities via a Spring @Configuration bean.

Write unit tests using TestWorkflowEnvironment.
```
