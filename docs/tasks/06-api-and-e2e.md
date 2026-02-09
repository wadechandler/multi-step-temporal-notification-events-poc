# Task 06: API Collection and End-to-End Testing

## Context

With all services and the Temporal workflow in place, this task creates a practical API
collection for testing and a guide for running the full end-to-end flow. This should be
something a teammate can use to see the whole system in action.

## Prerequisites
- Tasks 01, 04, 05 completed
- Infrastructure running, application deployed

## Deliverables

### 1. HTTP Request Collection (`docs/api/`)
Create `.http` files (IntelliJ/VS Code REST Client format) for:

**events.http:**
- POST /events — Submit a notification event with 1 contact
- POST /events — Submit a notification event with 2 contacts

**contacts.http:**
- POST /contacts — Create a contact directly
- GET /contacts/{id} — Lookup by ID
- GET /contacts?externalIdType=patientId&externalIdValue={value} — Lookup by external ID

**messages.http:**
- POST /messages — Create a message directly
- GET /messages/{id} — Lookup by ID

### 2. End-to-End Test Script (`docs/guides/e2e-walkthrough.md`)
Step-by-step guide:
1. Verify infra is running (`verify.sh`)
2. Deploy or run the application
3. Submit a notification event via POST /events
4. Watch the Temporal UI for the workflow execution
5. Observe the Kafka topics (contact-commands, message-commands) for events
6. Query the database for created contacts and messages
7. Verify the full flow completed

### 3. Load Test Sketch (Optional)
A simple script or Spring test that submits N events to Kafka for basic throughput testing.

## Acceptance Criteria
- A new team member can follow the e2e guide and see the full flow work
- API collection files are valid and tested
- Temporal UI shows completed workflow executions

## Prompt (for Builder sub-agent)

```
Read the following files for context:
- docs/tasks/06-api-and-e2e.md (this task)
- .cursor/rules/00-project-standards.mdc (domain model, API schemas)
- docs/tasks/04-cqrs-services.md (API endpoints)
- docs/tasks/05-temporal-workflow.md (workflow behavior)
- app/src/main/resources/application.yml (server port, kafka config)

Task: Create the API collection files and end-to-end walkthrough guide.

1. Create docs/api/events.http, docs/api/contacts.http, docs/api/messages.http
   with realistic sample payloads matching the domain model.
2. Create docs/guides/e2e-walkthrough.md with step-by-step instructions
   for running and observing the full notification flow.
3. Keep it practical and concise.
```
