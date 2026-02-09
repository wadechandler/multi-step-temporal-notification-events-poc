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

| # | Task | Status | Dependencies |
|---|------|--------|--------------|
| 00 | [Fix setup.sh and slim for local dev](00-fix-setup-script.md) | TODO | None |
| 01 | [Stand up and validate CNPG infra](01-infra-validate-cnpg.md) | TODO | Task 00 |
| 02 | [Stand up and validate YugabyteDB infra](02-infra-validate-yugabyte.md) | TODO | Task 00 |
| 03 | [DataGrip connection guide](03-datagrip-setup.md) | TODO | Task 01 or 02 |
| 04 | [Build CQRS services](04-cqrs-services.md) | TODO | Task 01 |
| 05 | [Build Temporal workflow](05-temporal-workflow.md) | TODO | Task 04 |
| 06 | [API collection and end-to-end test](06-api-and-e2e.md) | TODO | Task 05 |

## Key Files for Context

Any sub-agent starting a task should read these files first:
- `.cursor/rules/00-project-standards.mdc` — Tech stack, architecture, domain model
- `.cursor/rules/10-infra.mdc` — Infrastructure patterns (for infra tasks)
- `.cursor/rules/20-temporal-workflows.mdc` — Temporal patterns (for code tasks)
- `AGENTS.md` — Full project roadmap (read the relevant Phase section)
