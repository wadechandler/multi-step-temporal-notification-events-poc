# Task Index

This directory contains self-contained task definitions for building the Notification POC.
Each task has enough embedded context for an independent Cursor session or sub-agent to execute
it without needing the full conversation history.

## How to Use

1. **Read the task file** — each contains context, prerequisites, deliverables, and acceptance criteria.
2. **Use the prompt** — at the bottom of each task is a ready-to-use prompt. Copy it into a new
   Cursor chat or use it with the `/` sub-agent command.
3. **Follow the order** — tasks have dependencies. Check prerequisites before starting.
4. **Verify before moving on** — each task has acceptance criteria. Don't proceed until met.

## Task Lifecycle

Each task can involve up to 4 roles (run as separate sub-agents or in sequence):

- **Builder** — Implements the task deliverables.
- **Reviewer** — Reads the output, checks against acceptance criteria, flags issues.
- **Validator** — Runs the verification steps, captures evidence (logs, screenshots, outputs).
- **Debugger** — If validation fails, diagnoses and fixes issues, then re-validates.

For simple tasks, one agent can do all roles. For complex infra tasks, splitting helps.

## Task Order

### Foundation (Tasks 00-09)

| #   | Task | Status | Dependencies |
|-----|------|--------|--------------|
| 00  | [Fix setup.sh and slim for local dev](00-fix-setup-script.md) | Done | None |
| 01  | [Stand up and validate CNPG infra](01-infra-validate-cnpg.md) | Done | Task 00 |
| 02  | [Stand up and validate YugabyteDB infra](02-infra-validate-yugabyte.md) | TODO | Task 00 |
| 03  | [DataGrip connection guide + credentials script](03-datagrip-setup.md) | TODO | Task 01 |
| 04  | [Build CQRS services](04-cqrs-services.md) | Done | Task 01 |
| 04a | [Event model refinement](04a-event-model-refinement.md) | Done | Task 04 |
| 05  | [Build Temporal workflow](05-temporal-workflow.md) | Done | Task 04 |
| 06  | [API collection and end-to-end test](06-api-and-e2e.md) | Done | Task 05 |
| 06b | [E2E test automation](06b-e2e-automation.md) | Done | Task 06 |
| 07  | [SB4 auto-config fix](07-sb4-autoconfig-fix.md) | Done | Task 05 |
| 08  | [Kafka local connectivity](08-kafka-local-connectivity.md) | Done | Task 07 |
| 09  | [Onboarding script & port config](09-onboarding-and-port-config.md) | Deferred | Task 08 |

> **Task 09 note:** Deferred. Its concerns (port configurability, `start.sh`, `ports.env`) are
> superseded by the Helm-based deployment workflow introduced in Task 12. Relevant pieces
> (port configuration, developer experience) are folded into Helm values and scripts.

### Restructure & Scale (Tasks 10-16)

| #   | Task | Status | Dependencies | Parallelizable |
|-----|------|--------|--------------|----------------|
| 10  | [Multi-module Gradle restructure](10-gradle-restructure.md) | Done | Task 08 | With Task 11 |
| 11  | [Infrastructure updates (Kafka 4.1.1, KEDA)](11-infra-updates.md) | Done | Task 01 | With Task 10 |
| 12  | [Helm charts + KIND deployment](12-helm-kind-deploy.md) | Done | Tasks 10, 11 | — |
| 13  | [Kafka share groups feature flag](13-kafka-share-groups.md) | TODO | Task 12 | With Task 14 |
| 14  | [Temporal task queue splitting](14-temporal-queue-split.md) | Done | Task 12 | With Task 13 |
| 15  | [Load testing + scaling demo](15-load-test-scaling.md) | TODO | Tasks 13, 14 | — |
| 16  | [Documentation updates (final pass)](16-documentation-updates.md) | In Progress | Task 15 | — |

### Agent Parallelism

```
Phase A (parallel):  Task 10 + Task 11
Phase B (sequential): Task 12
Phase C (parallel):  Task 13 + Task 14
Phase D (sequential): Task 15
Phase E (sequential): Task 16

Task 03 is independent — can be done anytime infra is running.
```

## Key Files for Context

Any sub-agent starting a task should read these files first:
- `.cursor/rules/00-project-standards.mdc` — Tech stack, architecture, domain model
- `.cursor/rules/10-infra.mdc` — Infrastructure patterns (for infra tasks)
- `.cursor/rules/20-temporal-workflows.mdc` — Temporal patterns (for code tasks)
- `AGENTS.md` — Full project roadmap (read the relevant Phase section)
