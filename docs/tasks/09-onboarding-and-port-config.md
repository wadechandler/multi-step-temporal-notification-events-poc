# Task 09: Onboarding Script & Port Configurability

## Context

After completing Tasks 01-08, the POC is fully functional: a teammate can clone the repo, run
`./infra/scripts/setup.sh` followed by `./scripts/e2e-test.sh`, and have the entire system up
with passing end-to-end tests. Temporal UI is accessible at `http://localhost:30080`.

However, two gaps remain for real team adoption:

### 1. No single "start everything" command
Today, a developer who already has infrastructure running needs to remember:
- Port-forward Temporal gRPC
- Extract the CNPG database password from a K8s secret
- Set 3 environment variables
- Run `./gradlew :app:bootRun`

The `e2e-test.sh` script automates this, but its primary purpose is testing, not daily
development. A dedicated `start.sh` (or similar) would be a better developer experience for
the "boot the app for local work" use case.

### 2. No port configurability
All NodePort values are hardcoded across 6+ files. If a teammate has a port collision (e.g.,
another KIND cluster already using 30080, or a local Postgres on 5432 shadowing 30432), they
must manually hunt through files to change ports. This is error-prone and undocumented.

## Prerequisites
- Tasks 01-08 complete
- Familiarity with the scripts and infra layout

## Deliverables

### 1. Create `ports.env` — Central Port Configuration

A single file that all scripts source for port values:

```bash
# ports.env — Central port configuration for the Notification POC
# Edit these values if you have port conflicts on your machine.

# NodePort services (exposed through KIND to host)
TEMPORAL_UI_PORT=30080
GRAFANA_PORT=30081
BUSINESS_DB_PORT=30432
TEMPORAL_DB_PORT=30433
KAFKA_BOOTSTRAP_PORT=30092
KAFKA_BROKER_PORT=30093

# Port-forwarded services
TEMPORAL_GRPC_PORT=7233

# Application
APP_PORT=8080
```

### 2. Update All Scripts & Configs to Read from `ports.env`

Files that need port parameterization:

| File | Ports Used | Notes |
|------|-----------|-------|
| `infra/kind-config.yaml` | 30080-30093 | Cannot be templated directly (YAML). Use `envsubst` or a generator script. |
| `infra/scripts/setup.sh` | 30080, 30081, 30092, 30093, 30432, 30433 | Kafka CRD, DB NodePorts, Temporal Helm, Grafana Helm |
| `app/src/main/resources/application.yml` | 30432 | DB URL. Already uses env var override but the default is hardcoded. |
| `scripts/e2e-test.sh` | 30080, 30092, 7233, 8080 | Test script config section |
| `scripts/quick-test.sh` | 8080, 30080 | App and Temporal UI URLs |
| `scripts/port-forward.sh` | 7233 | Temporal gRPC |
| `docs/guides/e2e-walkthrough.md` | 30080, 30092, 30432 | Documentation references |
| `docs/api/*.http` | 8080 | HTTP client files |

**Approach options:**

**Option A: `source ports.env` in bash scripts + `envsubst` for YAML**
- Scripts `source ports.env` at the top
- `kind-config.yaml` becomes `kind-config.yaml.tmpl`, processed with `envsubst` during setup
- `application.yml` already supports env vars, just need defaults to reference `ports.env` values
- Simplest approach, POSIX-compatible

**Option B: Single `.env` file with a Makefile**
- `Makefile` reads `.env` and passes values to all sub-commands
- `make setup`, `make start`, `make test`, `make teardown`
- More ergonomic but adds a Makefile dependency

**Recommendation:** Option A. It's simpler, doesn't add tooling, and bash scripts already dominate the project.

### 3. Create `scripts/start.sh` — Developer Start Script

A lightweight script for daily development (not testing):

```bash
#!/usr/bin/env bash
# start.sh — Start the app for local development
#
# Handles: port-forwarding, DB password extraction, environment setup, app start.
# Assumes infrastructure is already running (run setup.sh first).

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/../ports.env" 2>/dev/null || true

# 1. Verify infrastructure is running
kubectl cluster-info --context kind-notification-poc &>/dev/null || {
    echo "KIND cluster not running. Run: ./infra/scripts/setup.sh"
    exit 1
}

# 2. Port-forward Temporal gRPC (if not already running)
"${SCRIPT_DIR}/port-forward.sh" --background

# 3. Extract DB password from CNPG secret
export KAFKA_BOOTSTRAP_SERVERS="localhost:${KAFKA_BOOTSTRAP_PORT:-30092}"
export TEMPORAL_ADDRESS="localhost:${TEMPORAL_GRPC_PORT:-7233}"
export BUSINESS_DB_PASSWORD=$(kubectl get secret business-db-app -n default \
    -o jsonpath='{.data.password}' | base64 -d)

# 4. Start the app
echo "Starting application..."
echo "  Kafka:    ${KAFKA_BOOTSTRAP_SERVERS}"
echo "  Temporal: ${TEMPORAL_ADDRESS}"
echo "  DB:       localhost:${BUSINESS_DB_PORT:-30432}"
echo ""

exec ./gradlew :app:bootRun
```

### 4. Update `scripts/README.md`

The README was updated in Task 08 with a "Quick Start" section. Extend it to reference
`start.sh` for daily development vs `e2e-test.sh` for validation.

### 5. Update `infra/kind-config.yaml` Generation

Since KIND port mappings are immutable after cluster creation, `kind-config.yaml` cannot
simply read environment variables at runtime. Two approaches:

**Preferred:** Keep `kind-config.yaml.tmpl` as a template with `${VARIABLE}` placeholders.
Have `setup.sh` run `envsubst < kind-config.yaml.tmpl > kind-config.yaml` before
`kind create cluster`. Add `kind-config.yaml` to `.gitignore` (only the template is checked in).

**Alternative:** Keep `kind-config.yaml` as-is with default ports. Document that changing ports
requires editing this file AND recreating the cluster.

## Acceptance Criteria

- [ ] `ports.env` exists with all port values and clear comments
- [ ] All bash scripts source `ports.env` and use variables instead of hardcoded ports
- [ ] `kind-config.yaml` generation uses port variables (via `envsubst` or similar)
- [ ] `scripts/start.sh` exists and starts the app with one command
- [ ] A new teammate can clone the repo, edit `ports.env` if needed, and run:
      ```
      ./infra/scripts/setup.sh
      ./scripts/e2e-test.sh
      ```
      with all tests passing
- [ ] Changing a port in `ports.env` (e.g., `TEMPORAL_UI_PORT=31080`) works after cluster recreate
- [ ] `scripts/README.md` documents the `ports.env` file and `start.sh`

## Scope Notes

- This task does NOT change any application Java code (only scripts, config templates, docs)
- The `application.yml` already supports env var overrides for ports — just ensure defaults
  align with `ports.env`
- Kafka topic names, database names, Temporal namespace — these are not ports and should
  remain hardcoded (they don't cause conflicts)

## Prompt (for Builder sub-agent)

```
Read the following files for context:
- docs/tasks/09-onboarding-and-port-config.md (this task)
- scripts/README.md (current getting-started docs)
- infra/kind-config.yaml (port mappings)
- infra/scripts/setup.sh (all hardcoded ports)
- scripts/e2e-test.sh (port references)
- scripts/quick-test.sh (port references)
- scripts/port-forward.sh (port references)
- app/src/main/resources/application.yml (DB port in datasource URL)

Task: Implement port configurability and the start.sh developer script.

1. Create ports.env with all port values
2. Convert kind-config.yaml to kind-config.yaml.tmpl with envsubst placeholders
3. Update setup.sh to source ports.env and generate kind-config.yaml via envsubst
4. Update e2e-test.sh, quick-test.sh, port-forward.sh to source ports.env
5. Create scripts/start.sh
6. Update scripts/README.md with ports.env docs and start.sh usage
7. Test: edit a port in ports.env, recreate cluster, verify everything works with new port

This is a scripts-only task — no Java code changes.
```
