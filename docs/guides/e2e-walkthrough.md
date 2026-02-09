# End-to-End Walkthrough: Notification Processing Flow

This guide walks through the complete notification processing flow from event ingestion to message creation, demonstrating the Temporal workflow orchestrating CQRS services.

## Prerequisites

- Infrastructure running (KIND cluster with Kafka, Temporal, CNPG)
- Temporal UI accessible at http://localhost:30080
- Kafka topics created: `notification-events`, `contact-commands`, `contact-events`, `message-commands`, `message-events`

## Step 1: Verify Infrastructure

```bash
# Check KIND cluster is running
kubectl cluster-info --context kind-notification-poc

# Verify Temporal is running
kubectl get pods -n temporal

# Verify Kafka is running
kubectl get pods -n kafka

# Verify CNPG databases are running
kubectl get clusters.postgresql.cnpg.io -n default
```

## Step 2: Set Up Port-Forwarding

Temporal gRPC (port 7233) is a ClusterIP service — it needs port-forwarding for the app to connect from your host machine.

```bash
# Option A: Use the helper script (runs in background)
./scripts/port-forward.sh --background

# Option B: Manual port-forward (blocks the terminal)
kubectl port-forward -n temporal svc/temporal-frontend 7233:7233
```

Verify it's working:
```bash
lsof -i :7233  # Should show a kubectl process
```

The other services are already exposed via NodePort:
- Kafka: `localhost:30092`
- Database: `localhost:30432`
- Temporal UI: `http://localhost:30080`

## Step 3: Start the Application

```bash
# Set environment variables
export KAFKA_BOOTSTRAP_SERVERS=localhost:30092
export TEMPORAL_ADDRESS=localhost:7233
export BUSINESS_DB_PASSWORD=app

# Run the application
./gradlew :app:bootRun
```

Verify the application started successfully:
- Check logs for: "Started NotificationPocApplication"
- Health check: `curl http://localhost:8080/actuator/health`

## Step 4: Submit a Notification Event

Use the HTTP client (IntelliJ/VS Code REST Client) or `curl`:

```bash
curl -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "550e8400-e29b-41d4-a716-446655440000",
    "eventType": "RxOrderNotification",
    "payload": {
      "contacts": [
        {
          "externalIdType": "patientId",
          "externalIdValue": "patient-12345",
          "email": "patient1@example.com",
          "phone": "+1-555-0100"
        }
      ],
      "templateId": "rx-notification-v1"
    }
  }'
```

Expected response: `202 Accepted` (event published to Kafka)

## Step 5: Observe Temporal Workflow Execution

1. Open Temporal UI: http://localhost:30080
2. Navigate to **Workflows** in the default namespace
3. Find the workflow with ID `notification-550e8400-e29b-41d4-a716-446655440000`
4. Click on the workflow to view:
   - **Workflow Execution History**: See the saga steps (GetContact, CreateContact, PollForContact, CreateMessage)
   - **Timeline**: Visual representation of activity calls
   - **Input/Output**: View the workflow input (eventId, eventType, payload)

### What to Look For:

- **If contact exists**: Workflow shows `GetContact` then `CreateMessage` (no polling)
- **If contact doesn't exist**: Workflow shows `GetContact` then `CreateContact` then `PollForContact` (with retries) then `CreateMessage`
- **Multiple contacts**: Parallel execution visible in the timeline

## Step 6: Observe Kafka Events

Monitor Kafka topics to see the CQRS command/event flow:

```bash
# Watch notification-events (workflow trigger)
kubectl exec -it -n kafka poc-kafka-combined-0 -- kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic notification-events \
  --from-beginning

# Watch contact-commands (CQRS command)
kubectl exec -it -n kafka poc-kafka-combined-0 -- kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic contact-commands \
  --from-beginning

# Watch contact-events (CQRS fact event)
kubectl exec -it -n kafka poc-kafka-combined-0 -- kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic contact-events \
  --from-beginning

# Watch message-commands (CQRS command)
kubectl exec -it -n kafka poc-kafka-combined-0 -- kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic message-commands \
  --from-beginning
```

Expected flow:
1. `notification-events`: Your event appears here
2. `contact-commands`: `ContactCreateRequested` events (if contacts need creation)
3. `contact-events`: `ContactCreated` events (after DB write)
4. `message-commands`: `MessageCreateRequested` events (one per contact)

## Step 7: Query the Database

Connect to the business database (port 30432) and verify data:

```sql
-- Check contacts created
SELECT id, external_id_type, external_id_value, email, phone, status, created_at
FROM contacts
ORDER BY created_at DESC
LIMIT 10;

-- Check messages created
SELECT id, contact_id, template_id, channel, content, status, created_at
FROM messages
ORDER BY created_at DESC
LIMIT 10;

-- Find contact by external ID
SELECT * FROM contacts
WHERE external_id_type = 'patientId' AND external_id_value = 'patient-12345';

-- Find messages for a specific contact
SELECT m.* FROM messages m
JOIN contacts c ON m.contact_id = c.id
WHERE c.external_id_type = 'patientId' AND c.external_id_value = 'patient-12345';
```

## Step 8: Verify via REST API

Query the created resources via the REST API:

```bash
# Find contact by external ID
curl "http://localhost:8080/contacts?externalIdType=patientId&externalIdValue=patient-12345"

# Get contact by ID (replace with actual UUID from DB)
curl "http://localhost:8080/contacts/{contact-id}"

# Get message by ID (replace with actual UUID from DB)
curl "http://localhost:8080/messages/{message-id}"
```

## Step 9: Test Eventual Consistency

Test the CQRS eventual consistency pattern:

1. **Create a contact directly** (POST /contacts) - returns 202 immediately
2. **Immediately query it** (GET /contacts?externalIdType=...&externalIdValue=...) - may return 404 (eventual consistency)
3. **Wait 2-3 seconds** and query again - should return 200 with contact data

This demonstrates why the Temporal workflow uses polling (`PollForContact`) after creating a contact.

## Step 10: Test Multiple Contacts

Submit an event with 2 contacts to see parallel processing:

```bash
curl -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "550e8400-e29b-41d4-a716-446655440001",
    "eventType": "RxOrderNotification",
    "payload": {
      "contacts": [
        {
          "externalIdType": "patientId",
          "externalIdValue": "patient-12345",
          "email": "patient1@example.com",
          "phone": "+1-555-0100"
        },
        {
          "externalIdType": "patientId",
          "externalIdValue": "patient-67890",
          "email": "patient2@example.com",
          "phone": "+1-555-0200"
        }
      ],
      "templateId": "rx-notification-v1"
    }
  }'
```

In Temporal UI, observe that both contacts are processed in parallel (timeline shows concurrent activity calls).

## Troubleshooting

### Workflow Not Starting
- Check application logs for Kafka consumer errors
- Verify `notification-events` topic exists and has messages
- Verify Temporal port-forward is running: `lsof -i :7233`

### Contact Creation Failing
- Check `contact-commands` topic has messages
- Check `ContactCommandHandler` logs for errors
- Verify database connection: `kubectl exec -it -n default business-db-1 -- psql -U app -d business`

### Polling Taking Too Long
- Check `contact-events` topic - is `ContactCreated` being published?
- Verify `ContactCommandHandler` is running and processing commands
- Check database for contact records

### Messages Not Created
- Verify workflow completed successfully in Temporal UI
- Check `message-commands` topic has messages
- Check `MessageCommandHandler` logs

## Summary

This walkthrough demonstrates:
- Event ingestion via REST API to Kafka
- Temporal workflow orchestration (saga pattern)
- CQRS command/event flow (Kafka topics)
- Eventual consistency handling (polling)
- Parallel contact processing
- End-to-end data flow: Event to Contact to Message
