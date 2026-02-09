# Scripts

## Quick Start (New Teammate)

Clone the repo and run two commands:

```bash
# 1. Build infrastructure (~5 minutes, one-time)
./infra/scripts/setup.sh

# 2. Run the full end-to-end test (starts app, runs tests)
./scripts/e2e-test.sh
```

That's it. The `e2e-test.sh` script handles port-forwarding, DB password extraction,
application startup, event submission, and verification automatically.

After it completes:
- **Temporal UI:** http://localhost:30080 — browse workflows, see the saga execution
- **Grafana:** http://localhost:30081 (admin/admin)
- **App health:** http://localhost:8080/actuator/health

To tear everything down: `./infra/scripts/teardown.sh`

---

## Scripts Reference

### `e2e-test.sh` — Full End-to-End Test

Comprehensive test script that:
- Verifies infrastructure (KIND, Temporal, Kafka, Database)
- Sets up Temporal gRPC port-forwarding if needed
- Extracts the CNPG database password automatically
- Starts the application if not already running
- Submits multiple test events
- Verifies workflows appear and complete in Temporal
- Verifies data in the database
- Queries the REST API to confirm contacts are created

```bash
# Full test (checks infra, starts app, runs tests)
./scripts/e2e-test.sh

# Skip infrastructure checks (assume infra is running)
./scripts/e2e-test.sh --skip-infra

# Don't start app (assume it's already running)
./scripts/e2e-test.sh --skip-app-start

# Verbose output
./scripts/e2e-test.sh --verbose
```

### `quick-test.sh` — Quick Smoke Test

Submits a single test event. Assumes infrastructure and application are already running.

```bash
./scripts/quick-test.sh
```

This will:
1. Submit a test event to `POST /events`
2. Print the workflow ID to look for in Temporal UI
3. Provide next steps

### `port-forward.sh` — Port Forward Helper

Sets up `kubectl port-forward` for Temporal gRPC (required for the local app to connect to Temporal in the cluster).

```bash
# Run in background (recommended)
./scripts/port-forward.sh --background

# Run in foreground (blocks terminal)
./scripts/port-forward.sh
```

**Why needed:** Temporal gRPC (port 7233) is a ClusterIP service, not exposed via NodePort.
The local app needs port-forwarding to reach it. The `e2e-test.sh` script handles this
automatically, but if you're running the app manually you'll need this.

---

## Running the App Manually

If you prefer to start the application yourself instead of letting `e2e-test.sh` do it:

```bash
# 1. Port-forward Temporal gRPC
./scripts/port-forward.sh --background

# 2. Set environment variables
export KAFKA_BOOTSTRAP_SERVERS=localhost:30092
export TEMPORAL_ADDRESS=localhost:7233
export BUSINESS_DB_PASSWORD=$(kubectl get secret business-db-app -n default -o jsonpath='{.data.password}' | base64 -d)

# 3. Start the app
./gradlew :app:bootRun

# 4. (In another terminal) Run a quick test
./scripts/quick-test.sh
```

## NodePort Services (accessible from host)

| Service        | Port  | Notes                              |
|----------------|-------|------------------------------------|
| Temporal UI    | 30080 | http://localhost:30080              |
| Grafana        | 30081 | http://localhost:30081 (admin/admin)|
| Business DB    | 30432 | PostgreSQL — user: `app`, db: `business` |
| Temporal DB    | 30433 | PostgreSQL — user: `temporal`      |
| Kafka Bootstrap| 30092 | Kafka client bootstrap address     |
| Kafka Broker   | 30093 | Per-broker NodePort (external listener) |

## Prerequisites

- Docker running
- `kind`, `kubectl`, `helm` installed
- Java 25+ (for `./gradlew :app:bootRun`)
- `curl` (for test scripts)
- `jq` (optional, for JSON parsing in verbose mode)

## Troubleshooting

### Application won't start
- Check logs: `tail -f /tmp/app.log`
- Verify infrastructure: `kubectl get pods -A`
- Check port 8080 is available: `lsof -i :8080`
- Check DB password: the CNPG-generated password changes on each cluster creation.
  Re-extract it: `kubectl get secret business-db-app -n default -o jsonpath='{.data.password}' | base64 -d`

### Workflows not appearing in Temporal
- Wait 10-30 seconds after submitting events
- Check Temporal UI: http://localhost:30080
- Check application logs for Kafka consumer errors
- Verify Temporal port-forward: `lsof -i :7233`

### Database queries fail
- Verify CNPG clusters: `kubectl get clusters.postgresql.cnpg.io -n default`
- Connect manually: `kubectl exec -it -n default business-db-1 -c postgres -- psql -U postgres -d business`
- Verify NodePort: database should be accessible on `localhost:30432`

### Port conflicts
- If ports 30080-30093, 7233, or 8080 are already in use, you'll get bind errors.
- See [Task 09](../docs/tasks/09-onboarding-and-port-config.md) for planned port configurability.
- Workaround: stop the conflicting process, or modify the port in the relevant config files.
