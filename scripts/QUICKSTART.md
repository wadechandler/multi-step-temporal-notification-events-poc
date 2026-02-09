# Quick Start: Running End-to-End Tests

## TL;DR

```bash
# Terminal 1: Port-forward Temporal gRPC
./scripts/port-forward.sh --background

# Terminal 2: Start the app
export KAFKA_BOOTSTRAP_SERVERS=localhost:30092
export TEMPORAL_ADDRESS=localhost:7233
export BUSINESS_DB_PASSWORD=app
./gradlew :app:bootRun

# Terminal 3: Run quick test
./scripts/quick-test.sh

# Check Temporal UI: http://localhost:30080
```

## Why Port-Forward?

The app can run **locally** (outside KIND) and connect to:
- ✅ **Kafka**: `localhost:30092` (exposed via NodePort)
- ✅ **Database**: `localhost:30432` (exposed via NodePort)  
- ❌ **Temporal gRPC**: `localhost:7233` (NOT exposed, needs port-forward)

Temporal UI is accessible at `localhost:30080` (NodePort), but the gRPC API (used by the app) needs port-forwarding.

## Step-by-Step

### 1. Start Port-Forward (Terminal 1)

```bash
./scripts/port-forward.sh --background
```

This runs: `kubectl port-forward -n temporal svc/temporal-frontend 7233:7233`

**Verify it's working:**
```bash
# Check if port 7233 is listening
lsof -i :7233

# Or check the log
tail -f /tmp/temporal-port-forward.log
```

### 2. Start Application (Terminal 2)

```bash
cd /path/to/project

# Set environment variables
export KAFKA_BOOTSTRAP_SERVERS=localhost:30092
export TEMPORAL_ADDRESS=localhost:7233
export BUSINESS_DB_PASSWORD=app

# Start the app
./gradlew :app:bootRun
```

**Wait for:** "Started NotificationPocApplication"

### 3. Run Tests (Terminal 3)

**Quick test (single event):**
```bash
./scripts/quick-test.sh
```

**Full e2e test (comprehensive):**
```bash
./scripts/e2e-test.sh --skip-infra --skip-app-start
```

### 4. Verify in Temporal UI

Open: http://localhost:30080

Navigate to: **Workflows** → **NOTIFICATION_QUEUE**

Look for workflows with IDs like: `notification-{event-id}`

## Troubleshooting

### Port 7233 already in use
```bash
# Kill existing port-forward
kill $(lsof -ti :7233)

# Or use the PID file
kill $(cat /tmp/temporal-port-forward.pid)
```

### App can't connect to Temporal
- Verify port-forward is running: `lsof -i :7233`
- Check Temporal pods: `kubectl get pods -n temporal`
- Check app logs for connection errors

### App can't connect to Kafka
- Verify Kafka is running: `kubectl get pods -n kafka`
- Test connectivity: `telnet localhost 30092` (should connect)

### App can't connect to Database
- Verify database is running: `kubectl get pods -n postgres`
- Test connectivity: `psql -h localhost -p 30432 -U app -d business`

## Alternative: Run App in KIND

If you prefer to run the app **inside** KIND (no port-forward needed):

1. Build Docker image
2. Load into KIND: `kind load docker-image notification-poc:latest`
3. Deploy as K8s Deployment
4. App connects to services via ClusterIP (no port-forward needed)

But for local development, port-forwarding is simpler and faster for iteration.
