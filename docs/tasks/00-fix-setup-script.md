# Task 00: Fix setup.sh and Slim for Local Dev

## Context

The `infra/scripts/setup.sh` script bootstraps a KIND cluster with Kafka, databases, Temporal,
and monitoring. It was generated in the initial project scaffolding and has known issues that must
be fixed before it can run successfully.

### Known Issues

1. **CNPG password mismatch:** The CNPG operator auto-generates passwords in K8s secrets
   (e.g., `temporal-db-app`). But the Temporal Helm install hardcodes `password=temporal`.
   Fix: Either pre-create secrets with known passwords, or extract the CNPG-generated password
   from the secret and pass it to the Temporal Helm install.

2. **Resource sizing:** The script creates HA configurations (2-instance CNPG, 3-broker Kafka)
   that are heavy for a laptop. For local dev, slim down to:
   - CNPG: 1 instance per cluster (not 2)
   - Kafka: 1 combined node (not 3) — Strimzi KRaft supports this
   - YugabyteDB: 1 master, 1 tserver (not 1+3)
   - Temporal: already 1 replica, good

3. **Temporal Helm chart 0.73.1 nuances:** The chart bundles its own Postgres/Cassandra/ES
   sub-charts. We disable all of them and point to our external CNPG. Need to verify the exact
   Helm value paths for external Postgres configuration. The chart's schema-setup jobs need
   access to the DB before Temporal server starts.

4. **YugabyteDB Helm chart:** Need to verify the correct chart repository and values for a
   minimal single-node YugabyteDB deployment that Temporal can connect to.

## Prerequisites
- Docker Desktop running
- KIND, Helm, kubectl installed (already verified on this machine)

## Key Files
- `infra/scripts/setup.sh` — The script to fix
- `infra/kind-config.yaml` — KIND cluster config (likely fine as-is)
- `infra/scripts/teardown.sh` — Cleanup script (likely fine as-is)

## Deliverables
1. Updated `infra/scripts/setup.sh` with:
   - CNPG password extraction and injection into Temporal Helm values
   - Slimmed resource configuration for local dev
   - Proper error handling and status checking between steps
2. A `infra/scripts/verify.sh` script that checks:
   - KIND cluster nodes are Ready
   - All pods in all namespaces are Running/Completed
   - Can connect to each database (temporal, temporal_visibility, business)
   - Temporal frontend responds on gRPC port
   - Temporal UI is accessible
   - Kafka topics exist
   - Prints connection info summary (hosts, ports, credentials)

## Acceptance Criteria
- `./infra/scripts/setup.sh --db cnpg` completes without errors
- `./infra/scripts/verify.sh` passes all checks
- `kubectl get pods -A` shows all pods Running or Completed (no CrashLoopBackOff)
- Temporal UI loads at http://localhost:30080
- Can query the business_db via port 30432

## Research Notes: YugabyteDB + Temporal

YugabyteDB is PostgreSQL wire-compatible (YSQL interface on port 5433). Temporal's `postgres12`
SQL driver should work against YugabyteDB's YSQL interface. However, there may be DDL
compatibility issues with Temporal's schema migrations (e.g., certain index types, advisory locks,
or partition syntax). When implementing `--db yugabyte` mode:
- Use the YSQL port (5433), not YCQL
- Test schema migrations manually first
- If Temporal's auto-setup jobs fail, consider running the schema SQL manually

Temporal's supported persistence stores are: PostgreSQL, MySQL, Cassandra, SQLite.
YugabyteDB is NOT officially supported but should work via the postgres12 driver given its
wire compatibility. This is explicitly what the POC validates.

## Prompt (for Builder sub-agent)

```
Read the following files for context:
- docs/tasks/00-fix-setup-script.md (this task definition)
- .cursor/rules/00-project-standards.mdc
- .cursor/rules/10-infra.mdc
- infra/scripts/setup.sh
- infra/kind-config.yaml

Task: Fix the infra/scripts/setup.sh script per the "Known Issues" in the task doc.
Also create infra/scripts/verify.sh per the "Deliverables" section.

Key fixes needed:
1. CNPG creates random passwords in K8s secrets. After CNPG clusters are ready,
   extract the password from the secret and use it in the Temporal Helm install.
2. Slim down to single-instance CNPG, single-node Kafka, minimal YugabyteDB.
3. Ensure proper wait/readiness checks between each infrastructure step.
4. Create verify.sh that validates all components are healthy.

Do NOT start the cluster or run the scripts. Just fix and create the files.
```
