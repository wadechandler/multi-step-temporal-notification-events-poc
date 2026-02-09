# Task 02: Stand Up and Validate YugabyteDB Infrastructure

## Context

This task validates the YugabyteDB path. It tears down the CNPG stack (if running) and
redeploys with `--db yugabyte`. The critical question this answers: **Can Temporal's postgres12
driver work against YugabyteDB's YSQL interface?**

YugabyteDB is PostgreSQL wire-compatible but not identical. Known areas of concern:
- Advisory locks (used by Temporal) — YugabyteDB supports these but behavior may differ
- Certain DDL syntax in Temporal's schema migrations
- Connection pooling behavior differences
- YSQL runs on port 5433 (not 5432)

## Prerequisites
- Task 00 completed
- Task 01 completed (CNPG path validated, confirms scripts work for the "happy path")
- Run `./infra/scripts/teardown.sh` to clean up the CNPG cluster first

## Key Files
- `infra/scripts/setup.sh`
- `infra/scripts/verify.sh`

## Deliverables
1. Evidence that `setup.sh --db yugabyte` completed (or documented failure points)
2. If Temporal schema migration fails on YugabyteDB:
   - Document the specific error
   - Research the YugabyteDB compatibility issue
   - Attempt manual schema application as a workaround
   - Document findings in `docs/research/yugabyte-temporal-compat.md`
3. Updated task status in `docs/tasks/README.md`

## Acceptance Criteria (Success)
- All pods Running/Completed
- Temporal UI loads at localhost:30080
- Can register a test namespace in Temporal
- Can connect to business DB on localhost:30432 via YSQL

## Acceptance Criteria (Partial — Expected)
If Temporal's schema jobs fail on YugabyteDB, document the failure clearly and note:
- Which specific SQL statements fail
- Whether there's a known workaround
- Whether it's viable for production use

This is explicitly a research/validation exercise. A documented failure with analysis
is a valid and useful outcome.

## Research: YugabyteDB + Temporal Compatibility

YugabyteDB publishes a PostgreSQL compatibility page. Key points:
- YSQL supports most PostgreSQL 11.2 syntax
- Advisory locks: supported (needed by Temporal)
- JSONB: supported (needed by Temporal visibility)
- Partitioned tables: partial support (may affect Temporal)
- Some system catalog differences

The Temporal Helm chart runs schema-setup init containers that execute SQL migrations.
If these fail, the Temporal pods won't start. The debugging path is:
1. Check init container logs: `kubectl logs -n temporal <pod> -c <init-container>`
2. Find the failing SQL statement
3. Test it directly against YugabyteDB via ysqlsh
4. Look for YugabyteDB-specific syntax adjustments

## Prompt (for Builder/Validator sub-agent)

```
Read the following files for context:
- docs/tasks/02-infra-validate-yugabyte.md (this task)
- .cursor/rules/10-infra.mdc
- infra/scripts/setup.sh

Task: Validate YugabyteDB infrastructure for Temporal.

Steps:
1. Ensure previous cluster is torn down: ./infra/scripts/teardown.sh
2. Run: ./infra/scripts/setup.sh --db yugabyte
3. Monitor pod startup. Pay special attention to Temporal schema-setup jobs.
4. If schema jobs fail, capture logs and diagnose.
5. Run: ./infra/scripts/verify.sh
6. Document all findings in docs/research/yugabyte-temporal-compat.md

This is a research task. A documented failure is a valid outcome.
```
