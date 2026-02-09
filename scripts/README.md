# Test Scripts

This directory contains automated test scripts for the Notification POC.

## Scripts

### `e2e-test.sh` — Full End-to-End Test

Comprehensive test script that:
- Verifies infrastructure (KIND, Temporal, Kafka, Database)
- Checks/starts the application
- Submits multiple test events
- Verifies workflows appear in Temporal
- Verifies data in database
- Provides detailed output

**Usage:**
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

### `quick-test.sh` — Quick Test

Simple script to submit a single test event. Assumes infrastructure and application are already running.

**Usage:**
```bash
./scripts/quick-test.sh
```

This will:
1. Submit a test event to `/events`
2. Print the workflow ID to look for in Temporal UI
3. Provide next steps

### `port-forward.sh` — Port Forward Helper

Sets up `kubectl port-forward` for Temporal gRPC (required for local app to connect to Temporal).

**Usage:**
```bash
# Run in background (recommended)
./scripts/port-forward.sh --background

# Run in foreground (blocks terminal)
./scripts/port-forward.sh
```

**Why needed:** Temporal gRPC (port 7233) is not exposed via NodePort, so a local app needs port-forwarding to connect.

## Prerequisites

- Infrastructure running (KIND cluster with Temporal, Kafka, CNPG)
- **Port-forward for Temporal gRPC** (required for local app):
  ```bash
  ./scripts/port-forward.sh --background
  # OR manually:
  kubectl port-forward -n temporal svc/temporal-frontend 7233:7233
  ```
- Application running (or use `--skip-app-start` and start manually)
- `curl`, `kubectl` installed
- `jq` (optional, for JSON parsing)
- `lsof` (optional, for port checking)

## Environment Variables

The scripts use these defaults (can be overridden):
- `KAFKA_BOOTSTRAP_SERVERS=localhost:30092`
- `TEMPORAL_ADDRESS=localhost:7233`
- `BUSINESS_DB_PASSWORD=app`

## Troubleshooting

### Application won't start
- Check logs: `tail -f /tmp/app.log`
- Verify infrastructure is running: `./infra/scripts/verify.sh`
- Check port 8080 is available

### Workflows not appearing in Temporal
- Wait 10-30 seconds after submitting events
- Check Temporal UI: http://localhost:30080
- Check application logs for Kafka consumer errors
- Verify Kafka topics exist: `kubectl exec -n kafka <kafka-pod> -- kafka-topics --list`

### Database queries fail
- Verify CNPG cluster is running: `kubectl get clusters -n postgres`
- Check database pod: `kubectl get pods -n postgres`
- Verify port forwarding: database should be accessible on port 30432
