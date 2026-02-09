# Multi-Step Temporal Notification Events POC

A Proof-of-Concept demonstrating a **high-throughput, durable notification processing engine**
using [Temporal.io](https://temporal.io) workflows, CQRS with Kafka, and Kubernetes-native databases.

## The Problem

An engagement platform receives >1M notification events per day. Each event may reference multiple
contacts (people/patients). For each contact, the system must:

1. **Look up** the contact in the platform.
2. **Create** the contact if they don't exist, then wait for eventual consistency (202 -> 200).
3. **Send** a personalized message (PCM) once the contact is resolved.

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

```bash
# 1. Install correct Java and Gradle versions
sdk env install

# 2. Build the application
./gradlew :app:build

# 3. Stand up the infrastructure (KIND + all services)
./infra/scripts/setup.sh            # Default: CNPG
# OR
./infra/scripts/setup.sh --db yugabyte  # Use YugabyteDB instead

# 4. Access points (after setup completes)
#    Temporal UI:  http://localhost:30080
#    Grafana:      http://localhost:30081 (admin/admin)
#    Business DB:  localhost:30432 (DataGrip)
#    Kafka:        localhost:30092

# 5. Tear down
./infra/scripts/teardown.sh
```

## Project Structure

```
.
├── .cursor/rules/          # Cursor AI context rules
│   ├── 00-project-standards.mdc
│   ├── 10-infra.mdc
│   └── 20-temporal-workflows.mdc
├── infra/
│   ├── kind-config.yaml    # 3-node KIND cluster
│   ├── helm/               # Helm value overrides
│   └── scripts/
│       ├── setup.sh        # One-click infrastructure bootstrap
│       └── teardown.sh     # Cluster cleanup
├── app/
│   ├── build.gradle        # Spring Boot 4 + Temporal SDK
│   ├── Dockerfile
│   └── src/
│       └── main/
│           ├── java/com/wadechandler/notification/poc/
│           └── resources/
│               ├── application.yml
│               └── db/migration/   # Flyway scripts
├── AGENTS.md               # Architecture roadmap & phase guide
├── build.gradle            # Root Gradle build
├── settings.gradle
└── .sdkmanrc               # Java 25 + Gradle 8.14 pinned
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
