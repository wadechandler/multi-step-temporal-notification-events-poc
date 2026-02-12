# Multi-Step Temporal Notification Events POC

A Proof-of-Concept demonstrating a **high-throughput, durable notification processing engine**
using [Temporal.io](https://temporal.io) workflows, CQRS with Kafka, and Kubernetes-native databases.

## The Problem

An engagement platform receives >1M notification events per day. Each event may reference multiple
contacts (people/patients). For each contact, the system must:

1. **Look up** the contact in the platform.
2. **Create** the contact if they don't exist, then wait for eventual consistency (202 -> 200).
3. **Send** a personalized message once the contact is resolved.

This multi-step process must survive crashes, handle retries gracefully, and scale horizontally.

## Architecture

- **Temporal.io** — Durable workflow orchestration (saga pattern).
- **Apache Kafka 4.1.1** (Strimzi) — Event-driven CQRS backbone.
- **Spring Boot 4.0.2** — Application framework (Java 25, Virtual Threads).
- **PostgreSQL** (CNPG or YugabyteDB) — Toggleable persistence.
- **Kubernetes** (KIND) — Local development cluster.

See [AGENTS.md](AGENTS.md) for the full architecture diagram and implementation roadmap.

## Prerequisites

- [Docker](https://www.docker.com/) (running)
- [KIND](https://kind.sigs.k8s.io/) (v0.31+)
- [Helm](https://helm.sh/) (v4+)
- [kubectl](https://kubernetes.io/docs/tasks/tools/)
- [SDKMAN](https://sdkman.io/) (for Java/Gradle version management)

## Quick Start

Clone the repo, `cd` into it, and run three commands:

```bash
cd multi-step-temporal-notification-events-poc

# 1. Install correct Java and Gradle versions (requires SDKMAN, reads .sdkmanrc)
sdk env install

# 2. Stand up infrastructure: KIND cluster, Kafka, Temporal, PostgreSQL, Grafana (~5 min)
./infra/scripts/setup.sh

# 3. Run the full end-to-end test (starts the app, submits events, verifies workflows)
./scripts/e2e-test.sh
```

That's it. After step 3 completes, the app is running and you can:

- **Browse workflows:** http://localhost:30080 (Temporal UI)
- **View metrics:** http://localhost:30081 (Grafana, login: admin/admin)
- **Run a quick smoke test:** `./scripts/quick-test.sh`
- **Query the database:** `kubectl exec -n default business-db-1 -c postgres -- psql -U postgres -d business`

### Running the App Manually

If you already have infrastructure up and want to start the app yourself:

```bash
./scripts/port-forward.sh --background  # Temporal gRPC (one-time)

export KAFKA_BOOTSTRAP_SERVERS=localhost:30092
export TEMPORAL_ADDRESS=localhost:7233
export BUSINESS_DB_PASSWORD=$(kubectl get secret business-db-app -n default \
    -o jsonpath='{.data.password}' | base64 -d)

./gradlew :notification-app:bootRun --args='--spring.profiles.active=service,ev-worker,wf-worker'
```

### Deploying to Kubernetes (KIND)

Instead of running locally via `bootRun`, you can deploy all five workloads into the KIND cluster
as separate deployments with KEDA autoscaling:

| Deployment | Profile | Role |
|---|---|---|
| `notification-service` | `service` | REST API + CQRS command handlers |
| `notification-ev-worker` | `ev-worker` | Kafka event consumer, starts Temporal workflows |
| `notification-wf-worker` | `wf-worker-orchestrator` | Workflow orchestration (scheduling only, no I/O) |
| `notification-contact-wf-worker` | `contact-wf-worker` | Contact resolution activities |
| `notification-message-wf-worker` | `message-wf-worker` | Message creation activities |

```bash
# Build the Docker image and load it into KIND
./scripts/build-and-load.sh

# Install the Helm chart
helm install notification-poc charts/notification-poc \
    -f charts/notification-poc/environments/local-values.yaml \
    --namespace default --wait --timeout 300s

# Verify all 5 pods are running
kubectl get pods | grep notification

# Test via port-forward
kubectl port-forward svc/notification-service 8080:8080
curl http://localhost:8080/actuator/health
```

KEDA creates ScaledObjects for each deployment: `kubectl get scaledobjects` shows Kafka lag,
Temporal queue backlog, and Prometheus latency triggers alongside baseline cpu/memory scaling.
The three Temporal worker deployments each target their own task queue for independent scaling.

All infrastructure endpoints (Kafka, Temporal, Prometheus, DB) and scaling thresholds are
configurable via `charts/notification-poc/values.yaml`.

### Tearing Down

```bash
./infra/scripts/teardown.sh   # Destroys the entire KIND cluster
```

### Access Points

| Service         | URL / Address          | Notes                          |
|-----------------|------------------------|--------------------------------|
| Temporal UI     | http://localhost:30080  | Workflow visibility            |
| Grafana         | http://localhost:30081  | Metrics (login: admin/admin)   |
| Business DB     | localhost:30432         | PostgreSQL (user: `app`, db: `business`) |
| Temporal DB     | localhost:30433         | PostgreSQL (user: `temporal`)  |
| Kafka Bootstrap | localhost:30092         | Client bootstrap address       |
| App Health      | http://localhost:8080/actuator/health | Spring Boot health endpoint |

See [scripts/README.md](scripts/README.md) for script flags, troubleshooting, and details.

## Project Structure

```
.
├── notification-common/        # Shared DTOs, topic constants (java-library)
├── notification-repository/    # JPA entities, repositories, Flyway (java-library)
├── notification-messaging/     # Kafka topic bean definitions (java-library)
├── notification-app/           # Spring Boot application (single boot module)
│   ├── build.gradle            # Spring Boot 4 + Temporal SDK + module deps
│   └── src/main/
│       ├── java/com/wadechandler/notification/poc/
│       │   ├── controller/     # REST APIs — @Profile("service")
│       │   ├── command/        # Kafka command handlers — @Profile("service")
│       │   ├── consumer/       # Kafka event consumer — @Profile("ev-worker")
│       │   ├── workflow/       # Temporal workflow interfaces + impls
│       │   ├── activity/       # Temporal activities — @Profile per activity type
│       │   └── worker/         # Temporal worker + client config
│       └── resources/
│           ├── application.yml            # Shared config
│           ├── application-service.yml                # DB, JPA, Flyway
│           ├── application-ev-worker.yml              # Kafka consumer + Temporal client
│           ├── application-wf-worker.yml              # Combined worker (local dev)
│           ├── application-wf-worker-orchestrator.yml  # Workflow-only worker (K8s)
│           ├── application-contact-wf-worker.yml       # Contact activity worker (K8s)
│           └── application-message-wf-worker.yml       # Message activity worker (K8s)
├── charts/notification-poc/    # Helm chart for K8s deployment
│   ├── Chart.yaml
│   ├── values.yaml             # Default config (infra endpoints, scaling)
│   ├── environments/
│   │   └── local-values.yaml   # KIND-specific resource/threshold overrides
│   └── templates/              # service/, ev-worker/, wf-worker/, contact-wf-worker/, message-wf-worker/
├── infra/
│   ├── kind-config.yaml        # KIND cluster + port mappings
│   └── scripts/
│       ├── setup.sh            # Full infrastructure bootstrap (+ optional app deploy)
│       ├── teardown.sh         # Cluster cleanup
│       └── verify.sh           # Infrastructure health check
├── scripts/
│   ├── build-and-load.sh       # Build Docker image + load into KIND
│   ├── e2e-test.sh             # Full end-to-end test suite
│   ├── quick-test.sh           # Quick smoke test
│   ├── port-forward.sh         # Temporal gRPC port-forward helper
│   └── README.md               # Script details & troubleshooting
├── Dockerfile                  # Multi-stage build (project root context)
├── docs/
│   ├── api/                    # HTTP client files (events, contacts, messages)
│   ├── guides/                 # Walkthroughs (e2e-walkthrough.md)
│   └── tasks/                  # Task documents (01-16)
├── AGENTS.md                   # Architecture roadmap & AI agent instructions
├── .sdkmanrc                   # Java 25 + Gradle 8.14 pinned
└── .cursor/rules/              # Cursor AI context rules
```

### Spring Profiles

One Docker image, deployed multiple times with different `SPRING_PROFILES_ACTIVE`:

| Profile | Role | Components |
|---------|------|------------|
| `service` | REST API + CQRS | Controllers, command handlers, DataSource, Flyway |
| `ev-worker` | Event consumer | Kafka consumer, Temporal client (starts workflows) |
| `wf-worker` | Combined worker (local dev) | All Temporal workers + activities in one JVM |
| `wf-worker-orchestrator` | Workflow orchestrator (K8s) | Workflow scheduling only, no activities |
| `contact-wf-worker` | Contact activity worker (K8s) | Contact resolution activities |
| `message-wf-worker` | Message activity worker (K8s) | Message creation activities |

**Local dev:** All three base profiles run together: `--spring.profiles.active=service,ev-worker,wf-worker`

**Kubernetes:** Each deployment uses a single profile. The `wf-worker` profile is split into three
separate deployments (`wf-worker-orchestrator`, `contact-wf-worker`, `message-wf-worker`) for
independent scaling of workflow orchestration vs. contact resolution vs. message creation.

## Tech Stack

| Component | Version | Purpose |
|-----------|---------|---------|
| Java | 25 | Language runtime (Virtual Threads) |
| Spring Boot | 4.0.2 | Application framework |
| Temporal SDK | 1.32.1 | Durable workflow engine |
| Kafka | 4.1.1 (Strimzi) | Event streaming / CQRS |
| KEDA | 2.19.0 | Event-driven autoscaling |
| PostgreSQL | CNPG / YugabyteDB | Persistence (toggleable) |
| Flyway | 11.x | Schema migration |
| Gradle | 8.14 | Build tool (Groovy DSL) |

## License

This project is for educational and demonstration purposes.
