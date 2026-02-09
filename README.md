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
- **Apache Kafka** (Strimzi) — Event-driven CQRS backbone.
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
- **View metrics:** http://localhost:30081 (Grafana, admin/admin)
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

./gradlew :app:bootRun
```

### Tearing Down

```bash
./infra/scripts/teardown.sh   # Destroys the entire KIND cluster
```

### Access Points

| Service         | URL / Address          | Notes                          |
|-----------------|------------------------|--------------------------------|
| Temporal UI     | http://localhost:30080  | Workflow visibility            |
| Grafana         | http://localhost:30081  | Metrics (admin/admin)          |
| Business DB     | localhost:30432         | PostgreSQL (user: `app`, db: `business`) |
| Temporal DB     | localhost:30433         | PostgreSQL (user: `temporal`)  |
| Kafka Bootstrap | localhost:30092         | Client bootstrap address       |
| App Health      | http://localhost:8080/actuator/health | Spring Boot health endpoint |

See [scripts/README.md](scripts/README.md) for script flags, troubleshooting, and details.

## Project Structure

```
.
├── app/                        # Spring Boot application
│   ├── build.gradle            # Spring Boot 4 + Temporal SDK + Kafka
│   ├── Dockerfile
│   └── src/main/
│       ├── java/com/wadechandler/notification/poc/
│       │   ├── controller/     # REST APIs (events, contacts, messages)
│       │   ├── command/        # Kafka command handlers (CQRS write side)
│       │   ├── workflow/       # Temporal workflow + activities
│       │   └── model/          # Domain entities + DTOs
│       └── resources/
│           ├── application.yml
│           └── db/migration/   # Flyway schemas
├── infra/
│   ├── kind-config.yaml        # KIND cluster + port mappings
│   └── scripts/
│       ├── setup.sh            # Full infrastructure bootstrap
│       └── teardown.sh         # Cluster cleanup
├── scripts/
│   ├── e2e-test.sh             # Full end-to-end test suite
│   ├── quick-test.sh           # Quick smoke test
│   ├── port-forward.sh         # Temporal gRPC port-forward helper
│   └── README.md               # Script details & troubleshooting
├── docs/
│   ├── api/                    # HTTP client files (events, contacts, messages)
│   ├── guides/                 # Walkthroughs (e2e-walkthrough.md)
│   └── tasks/                  # Task documents (01-09)
├── AGENTS.md                   # Architecture roadmap & AI agent instructions
├── .sdkmanrc                   # Java 25 + Gradle 8.14 pinned
└── .cursor/rules/              # Cursor AI context rules
```

## Tech Stack

| Component | Version | Purpose |
|-----------|---------|---------|
| Java | 25 | Language runtime (Virtual Threads) |
| Spring Boot | 4.0.2 | Application framework |
| Temporal SDK | 1.32.1 | Durable workflow engine |
| Kafka | 4.x (Strimzi) | Event streaming / CQRS |
| PostgreSQL | CNPG / YugabyteDB | Persistence (toggleable) |
| Flyway | 11.x | Schema migration |
| Gradle | 8.14 | Build tool (Groovy DSL) |

## License

This project is for educational and demonstration purposes.
