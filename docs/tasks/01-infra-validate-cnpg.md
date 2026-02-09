# Task 01: Stand Up and Validate CNPG Infrastructure

## Context

After Task 00 fixes the setup script, this task actually runs it with `--db cnpg` mode and
validates that all infrastructure components are healthy. This is the critical "does it work"
gate before any application code gets written.

The infrastructure stack for CNPG mode:
- KIND cluster (1 control-plane + 3 workers)
- cert-manager
- Strimzi operator + Kafka cluster (KRaft, single node for local dev)
- CNPG operator + 3 database clusters (temporal, temporal_visibility, business)
- Temporal server + web UI (Helm chart 0.73.1, postgres12 driver)
- kube-prometheus-stack (Prometheus + Grafana)

## Prerequisites
- Task 00 completed (setup.sh and verify.sh are fixed)
- Docker Desktop running with sufficient resources (recommend 8GB+ RAM allocated)

## Key Files
- `infra/scripts/setup.sh`
- `infra/scripts/verify.sh`
- `infra/scripts/teardown.sh`
- `infra/kind-config.yaml`

## Deliverables
1. Evidence that `setup.sh --db cnpg` completed successfully (captured output)
2. Evidence that `verify.sh` passes all checks
3. Screenshots or logs showing:
   - Temporal UI accessible at localhost:30080
   - Grafana accessible at localhost:30081
   - Database connection working on localhost:30432 (business) and localhost:30433 (temporal)
4. Updated `docs/tasks/README.md` marking this task as DONE

## Acceptance Criteria
- All pods are Running or Completed (`kubectl get pods -A` shows no errors)
- Temporal Web UI loads and shows the "default" namespace
- Can create a test namespace in Temporal: `kubectl exec -n temporal deploy/temporal-admintools -- tctl namespace register test-ns`
- Can connect to business_db on localhost:30432 with credentials from K8s secret
- Kafka topics exist: `kubectl get kafkatopics -n kafka`
- Grafana loads and Prometheus data source is configured

## Prompt (for Builder/Validator sub-agent)

```
Read the following files for context:
- docs/tasks/01-infra-validate-cnpg.md (this task)
- .cursor/rules/10-infra.mdc
- infra/scripts/setup.sh
- infra/scripts/verify.sh

Task: Run the CNPG infrastructure setup and validate it.

Steps:
1. Run: ./infra/scripts/setup.sh --db cnpg
2. Wait for all pods to stabilize
3. Run: ./infra/scripts/verify.sh
4. If any verification fails, diagnose and fix the issue in the scripts, then retry.
5. Capture the final verify.sh output as evidence.

Important: If setup.sh fails at any step, do NOT just retry blindly.
Read the error, check pod logs (kubectl logs), check events (kubectl get events),
and fix the root cause before retrying.
```
