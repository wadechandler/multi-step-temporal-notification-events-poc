# Task 15: Load Testing + Scaling Demonstration

## Context

After Tasks 10-14, the system is deployed in KIND with KEDA ScaledObjects on every
component. This task adds load testing to **demonstrate that scaling actually works**:

- Kafka consumer lag triggers ev-worker scaling
- Temporal task queue backlog triggers wf-worker scaling
- HTTP p90 latency triggers service scaling

The goal is a repeatable demo: run a load test, watch pods scale up, observe metrics,
then watch them scale back down. This proves the architecture works end-to-end.

### KIND Resource Constraints

KIND runs on a laptop with limited resources. The thresholds in `local-values.yaml` are
tuned low to trigger scaling with modest load. In production, these would be calibrated
to real SLOs and much higher traffic volumes. Document this difference clearly.

## Prerequisites
- Tasks 10-14 complete (all components deployed in KIND with KEDA)
- Prometheus scraping application metrics (Micrometer actuator endpoint)
- KEDA ScaledObjects created for all deployments

### Notes on Current State
- **Split-mode pod labels.** The `watch-scaling.sh` example below includes labels for
  `notification-contact-wf-worker` and `notification-message-wf-worker`, which only exist
  when Task 14's `wfWorker.splitActivities=true` is set. In combined mode (default), only
  `notification-wf-worker` exists. Adjust the label selector accordingly.
- **Port-forward for load test.** If the app is deployed in KIND (not via local `bootRun`),
  you need `kubectl port-forward svc/notification-service 8080:8080` before hitting
  `localhost:8080`. Alternatively, use the K8s internal ClusterIP from within the cluster.
- **Prometheus service name.** The kube-prometheus-stack service in our cluster is named
  `kube-prometheus-kube-prome-prometheus.monitoring` (not `kube-prometheus-stack-prometheus`).
  This is already configured in the Helm chart values.

## Key Files to Read
- `charts/notification-poc/environments/local-values.yaml` — Current scaling thresholds
- `charts/notification-poc/values.yaml` — Default scaling configuration
- `docs/api/events.http` — Event payload format
- `scripts/e2e-test.sh` — Existing test patterns
- `notification-app/src/main/java/.../controller/EventController.java` — Event ingestion API

## Deliverables

### 1. Load Test Script

Create `scripts/load-test.sh`:

```bash
#!/usr/bin/env bash
# load-test.sh — Generate load to demonstrate KEDA autoscaling
#
# Usage:
#   ./scripts/load-test.sh                    # Default: 100 events, 10 concurrent
#   ./scripts/load-test.sh --events 500       # 500 events
#   ./scripts/load-test.sh --concurrency 20   # 20 concurrent requests
#   ./scripts/load-test.sh --duration 60      # Run for 60 seconds
set -euo pipefail

EVENTS=${EVENTS:-100}
CONCURRENCY=${CONCURRENCY:-10}
DURATION=${DURATION:-0}     # 0 = send EVENTS count, >0 = send for DURATION seconds
TARGET=${TARGET:-http://localhost:8080}
# If running against KIND, port-forward first:
#   kubectl port-forward svc/notification-service 8080:8080

# ... argument parsing ...

generate_event() {
    local event_id=$(uuidgen | tr '[:upper:]' '[:lower:]')
    local patient_id="load-test-patient-$(shuf -i 1-10000 -n 1)"
    cat <<EOF
{
  "eventId": "${event_id}",
  "eventType": "RxOrderNotification",
  "payload": {
    "contacts": [
      {
        "externalIdType": "patientId",
        "externalIdValue": "${patient_id}",
        "email": "${patient_id}@loadtest.example.com",
        "phone": "+1-555-$(shuf -i 1000-9999 -n 1)"
      }
    ],
    "templateId": "rx-notification-v1"
  }
}
EOF
}

# ... send events using curl with xargs for concurrency ...
# ... or use a simple loop with background jobs ...
```

For more sophisticated load testing, consider using **k6** or **hey**:

```bash
# Using hey (simple HTTP load generator)
hey -n ${EVENTS} -c ${CONCURRENCY} \
    -m POST \
    -H "Content-Type: application/json" \
    -D /tmp/event-payload.json \
    ${TARGET}/events
```

### 2. Scaling Observation Script

Create `scripts/watch-scaling.sh`:

```bash
#!/usr/bin/env bash
# watch-scaling.sh — Watch KEDA scaling in real-time
# Run this in a separate terminal while load-test.sh is running.

echo "=== Watching HPA status ==="
echo "Press Ctrl+C to stop"
echo ""

# Watch HPAs (KEDA creates these)
kubectl get hpa -w &
HPA_PID=$!

# Watch pod count
kubectl get pods -l 'app in (notification-service,notification-ev-worker,notification-wf-worker,notification-contact-wf-worker,notification-message-wf-worker)' -w &
POD_PID=$!

trap "kill $HPA_PID $POD_PID 2>/dev/null" EXIT
wait
```

### 3. Threshold Tuning Guide

Document how to tune thresholds for the KIND demo vs production:

**KIND demo thresholds (make scaling trigger easily):**
- Service p90 latency: 20-30ms (low — will trigger quickly under load)
- Kafka lag: 5 messages (low — a small batch triggers scaling)
- Temporal queue: 3 tasks (low — a few workflows trigger scaling)
- Max replicas: 3-4 (KIND doesn't have many resources)
- Resource limits: 100m-250m CPU per pod (constrained to make CPU scaling trigger)

**Production thresholds (calibrate to real SLOs):**
- Service p90 latency: based on SLO (e.g., 100-200ms)
- Kafka lag: based on acceptable processing delay (e.g., 100-1000)
- Temporal queue: based on acceptable scheduling delay (e.g., 50-100)
- Max replicas: based on capacity planning
- Resource limits: based on load testing and profiling

### 4. Grafana Dashboard (Optional)

If time permits, create a Grafana dashboard JSON that shows:
- Temporal task queue depth (from Temporal server metrics)
- Kafka consumer group lag (from Strimzi/Kafka metrics)
- HTTP p90 latency (from application metrics)
- Pod replica counts (from kube-state-metrics)

The kube-prometheus-stack (already installed) provides the Prometheus + Grafana infrastructure.

### 5. Demo Runbook

Create `docs/guides/scaling-demo.md`:

```markdown
# Scaling Demo Runbook

## Setup
1. Ensure all components are deployed: `kubectl get pods`
2. Verify KEDA HPAs exist: `kubectl get hpa`
3. Open three terminals

## Run the Demo

### Terminal 1: Watch scaling
./scripts/watch-scaling.sh

### Terminal 2: Port-forward the service
kubectl port-forward svc/notification-service 8080:8080

### Terminal 3: Generate load
./scripts/load-test.sh --events 200 --concurrency 20

## What to Observe
1. As events flow in, Kafka consumer lag builds → ev-worker scales up
2. Workflows queue up → wf-worker scales up
3. HTTP requests increase → if p90 crosses threshold → service scales up
4. After load stops, cooldown period passes → pods scale back down

## Troubleshooting
- If no scaling: check `kubectl describe hpa <name>` for metric values
- If KEDA can't read metrics: check KEDA operator logs
- If Prometheus metrics missing: verify actuator endpoint scraping
```

## Acceptance Criteria

- [ ] `scripts/load-test.sh` can generate configurable load against the service
- [ ] Under load, at least ONE scaling event occurs (pod count increases)
- [ ] `kubectl get hpa` shows target metrics being tracked and replicas increasing
- [ ] After load stops and cooldown passes, replicas scale back down
- [ ] `scripts/watch-scaling.sh` provides real-time visibility
- [ ] `docs/guides/scaling-demo.md` documents the full demo procedure
- [ ] Threshold tuning guidance documented (KIND vs production)

## Prompt (for Builder sub-agent)

```
Read the following files for full context:
- docs/tasks/15-load-test-scaling.md (this task)
- charts/notification-poc/environments/local-values.yaml (current scaling thresholds)
- charts/notification-poc/values.yaml (default scaling config)
- docs/api/events.http (event payload format)
- scripts/e2e-test.sh (existing test patterns)

Task: Create load testing scripts and a scaling demonstration runbook.

Steps:
1. Create scripts/load-test.sh that generates configurable load (events, concurrency).
2. Create scripts/watch-scaling.sh for real-time scaling observation.
3. Port-forward notification-service and run the load test.
4. Observe KEDA scaling: kubectl get hpa -w, kubectl get pods -w.
5. If scaling doesn't trigger, tune thresholds in local-values.yaml and redeploy.
6. Document the threshold tuning in the scaling demo guide.
7. Create docs/guides/scaling-demo.md with the full demo runbook.
8. Verify scale-up AND scale-down both occur.

Key rules:
- Thresholds should be tuned LOW for KIND (trigger with modest load).
- Document what changes for production (higher thresholds, real SLOs).
- The load test should be simple (bash + curl or hey) — no complex frameworks.
- Demonstrate at least one scaling event for at least one component.
- Java startup time means scaling takes 15-30s to become effective — document this.
```
