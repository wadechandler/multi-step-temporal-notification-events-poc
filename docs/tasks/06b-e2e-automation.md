# Task 06b: End-to-End Test Automation

## Context

This task extends Task 06 by adding automated test scripts that make it easy to run end-to-end tests and verify the full notification flow.

## Deliverables

### 1. Automated Test Scripts (`scripts/`)

**`e2e-test.sh`** — Comprehensive end-to-end test:
- Verifies infrastructure (KIND, Temporal, Kafka, Database)
- Checks/starts the application automatically
- Submits multiple test events
- Verifies workflows appear in Temporal UI
- Verifies data in database
- Provides detailed pass/fail output

**`quick-test.sh`** — Quick single-event test:
- Assumes infrastructure and app are running
- Submits one test event
- Prints workflow ID to look for in Temporal UI
- Minimal output, fast execution

### 2. Usage Examples

```bash
# Full automated test
./scripts/e2e-test.sh

# Skip infrastructure checks (faster if infra is known-good)
./scripts/e2e-test.sh --skip-infra

# Don't start app (assume it's already running)
./scripts/e2e-test.sh --skip-app-start

# Quick test (app must be running)
./scripts/quick-test.sh
```

### 3. Integration with Manual Testing

The scripts complement the manual walkthrough (`docs/guides/e2e-walkthrough.md`):
- Scripts handle the repetitive verification steps
- Manual guide provides deeper understanding
- Both use the same API collection files (`docs/api/*.http`)

## Testing Workflow

### First Time Setup
1. Run infrastructure setup: `./infra/scripts/setup.sh`
2. Verify infrastructure: `./infra/scripts/verify.sh`
3. Run full e2e test: `./scripts/e2e-test.sh`

### Daily Development
1. Start infrastructure (if not running): `./infra/scripts/setup.sh`
2. Quick test: `./scripts/quick-test.sh`
3. Check Temporal UI: http://localhost:30080

### Debugging
1. Run with verbose: `./scripts/e2e-test.sh --verbose`
2. Check app logs: `tail -f /tmp/app.log`
3. Check Temporal workflows manually in UI
4. Query database directly

## Acceptance Criteria

- ✅ `e2e-test.sh` runs successfully end-to-end
- ✅ `quick-test.sh` submits events successfully
- ✅ Scripts detect when infrastructure/app is not running
- ✅ Scripts provide clear next steps for manual verification
- ✅ Workflows appear in Temporal UI after running tests
- ✅ Database contains test data after successful run

## Next Steps

Once basic e2e tests are working:
1. Add load testing script (submit N events)
2. Add performance metrics collection
3. Add CI/CD integration (run tests in pipeline)
4. Add test result reporting (JSON output for CI)
