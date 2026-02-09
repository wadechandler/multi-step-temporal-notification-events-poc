# Task 08: Fix Kafka Local Connectivity for Out-of-Cluster Development

## Context

When running the app locally (outside KIND), the app can connect to the Kafka bootstrap
at `localhost:30092`, but fails when trying to produce/consume messages:

```
Topic contact-commands not present in metadata after 60000 ms.
```

### Root Cause: Strimzi Advertised Listener + KIND Port Mapping

Strimzi creates two NodePort services for external access:
- **Bootstrap** (`poc-kafka-kafka-external-bootstrap`): `9094:30092` — mapped in KIND config
- **Per-broker** (`poc-kafka-combined-0`): `9094:30523` — NOT mapped in KIND config

The Kafka protocol works like this:
1. Client connects to bootstrap (`localhost:30092`) — succeeds
2. Bootstrap returns broker metadata: "broker 0 is at `localhost:30523`"
3. Client tries to connect to `localhost:30523` — fails (port not forwarded through KIND)

We already fixed the `advertisedHost: localhost` in setup.sh (Task 07 session), so step 2
now returns `localhost` instead of the node's internal IP. But the per-broker NodePort (30523)
still isn't accessible from the host.

### What's Already Done
- `advertisedHost: localhost` added to Strimzi Kafka CRD ✅
- Strimzi API version updated to `v1` (was `v1beta2`) ✅
- Broker restarts and advertises `EXTERNAL-9094://localhost:30523` ✅

### What's Needed
Pin the per-broker NodePort to a known value and add it to KIND's `extraPortMappings`.
KIND port mappings can only be set at cluster creation time, so this requires updating
`kind-config.yaml` and recreating the cluster via `setup.sh`.

## Prerequisites
- Understanding of Strimzi NodePort listener configuration
- Willingness to recreate the KIND cluster (or use port-forward as interim)

## Deliverables

### 1. Update `infra/kind-config.yaml`

Add the broker NodePort mapping:
```yaml
extraPortMappings:
  # ... existing mappings ...
  # Kafka broker (per-broker NodePort for external listener)
  - containerPort: 30093
    hostPort: 30093
    protocol: TCP
```

### 2. Update Strimzi Kafka CRD in `infra/scripts/setup.sh`

Pin the per-broker NodePort to match the KIND mapping:
```yaml
listeners:
  - name: external
    port: 9094
    type: nodeport
    tls: false
    configuration:
      bootstrap:
        nodePort: 30092
      brokers:
        - broker: 0
          advertisedHost: localhost
          advertisedPort: 30093
          nodePort: 30093
```

### 3. Recreate the Cluster

```bash
./infra/scripts/teardown.sh
./infra/scripts/setup.sh
```

### 4. Validate

After cluster recreation:
```bash
# Start port-forward for Temporal gRPC
./scripts/port-forward.sh --background

# Start the app
export KAFKA_BOOTSTRAP_SERVERS=localhost:30092
export TEMPORAL_ADDRESS=localhost:7233
export BUSINESS_DB_PASSWORD=$(kubectl get secret business-db-app -n default -o jsonpath='{.data.password}' | base64 -d)
./gradlew :app:bootRun

# Run the quick test
./scripts/quick-test.sh

# Check Temporal UI for workflows
open http://localhost:30080
```

### 5. Alternative: Port-Forward (No Cluster Recreate)

If you don't want to recreate the cluster, you can port-forward the broker service:
```bash
# Find the broker's NodePort
BROKER_PORT=$(kubectl get svc poc-kafka-combined-0 -n kafka -o jsonpath='{.spec.ports[0].nodePort}')

# Port-forward (in addition to Temporal port-forward)
kubectl port-forward -n kafka svc/poc-kafka-combined-0 ${BROKER_PORT}:9094
```

Update `scripts/port-forward.sh` to include this.

## Acceptance Criteria

### Kafka Connectivity
- App running locally can produce to and consume from all Kafka topics
- `POST /events` returns 202 AND the event appears on `notification-events` topic
- `POST /contacts` returns 202 AND the contact is created via `contact-commands`

### Full E2E Flow (validates Tasks 06, 06b, and 08 together)
- Temporal workflow starts and completes (visible in UI at http://localhost:30080)
- Full saga executes: GetContact -> CreateContact -> PollForContact -> CreateMessage
- Database contains created contacts and messages after workflow completes
- `./scripts/quick-test.sh` runs successfully
- `./scripts/e2e-test.sh --skip-infra --skip-app-start` runs successfully
- Each request in `docs/api/events.http`, `contacts.http`, `messages.http` works as documented
- `docs/guides/e2e-walkthrough.md` is accurate (spot-check Steps 4-8)

### If any docs/scripts need correction
- Fix them and amend the commit, noting what was wrong

## Prompt (for Builder sub-agent)

```
Read the following files for context:
- docs/tasks/08-kafka-local-connectivity.md (this task)
- infra/kind-config.yaml (KIND port mappings)
- infra/scripts/setup.sh (Strimzi Kafka CRD, lines 175-205)

Task: Fix Kafka local connectivity per this task document.

1. Add broker NodePort 30093 to kind-config.yaml extraPortMappings
2. Update the Strimzi Kafka CRD in setup.sh to pin broker nodePort and advertisedPort to 30093
3. Recreate the cluster: ./infra/scripts/teardown.sh && ./infra/scripts/setup.sh
4. Verify Kafka connectivity from host
5. Start the app and run ./scripts/quick-test.sh
6. Verify workflows appear in Temporal UI at http://localhost:30080

After Kafka connectivity is confirmed, validate the FULL end-to-end flow:

6. Run ./scripts/quick-test.sh — should submit event and print workflow ID
7. Open Temporal UI (http://localhost:30080) and confirm workflow appears and completes
8. Run ./scripts/e2e-test.sh --skip-infra --skip-app-start — should pass all checks
9. Test each request in docs/api/events.http, contacts.http, messages.http via curl
10. Query database to confirm contacts and messages were created:
    kubectl exec -n default business-db-1 -c postgres -- psql -U postgres -d business \
      -c "SELECT id, external_id_type, external_id_value, email FROM contacts ORDER BY created_at DESC LIMIT 5;"
    kubectl exec -n default business-db-1 -c postgres -- psql -U postgres -d business \
      -c "SELECT id, contact_id, template_id, channel, status FROM messages ORDER BY created_at DESC LIMIT 5;"
11. Spot-check docs/guides/e2e-walkthrough.md Steps 4-8 for accuracy
12. Fix any docs/scripts that are wrong

This task rolls up validation of Tasks 06, 06b, and 08.
```
