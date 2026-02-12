# Task 16: Documentation Updates (Final Pass)

## Context

Tasks 10-15 make significant structural and architectural changes to the project. Each task
may have updated some documentation as it went, but this final task ensures everything is
consistent and complete. It's the "tie up loose ends" pass.

## Prerequisites
- Tasks 10-15 complete

## Deliverables

### 1. Update `AGENTS.md`

Add new implementation phases (6-11) covering the work done in Tasks 10-15:

- **Phase 6: Multi-Module Restructure** — Module structure, shared libraries, Spring profiles
- **Phase 7: Infrastructure Updates** — Kafka 4.1.1, KEDA operator, share groups enablement
- **Phase 8: Helm Charts + KIND Deployment** — Chart structure, KEDA ScaledObjects, Docker image
- **Phase 9: Kafka Share Groups** — Dual-mode consumer, feature flag, KIP-932 implications
- **Phase 10: Temporal Task Queue Splitting** — Per-queue activities, split worker profiles
- **Phase 11: Load Testing + Scaling** — Load generation, threshold tuning, demo runbook

Update the Architecture Overview diagram to show the multi-deployment architecture.

Update the Key Decisions table with new entries:
- KEDA for autoscaling (why: native Temporal + Kafka + Prometheus scalers, composable)
- Kafka share groups (why: queue-like semantics for independent events, feature-flagged)
- Single image with profiles (why: simpler CI, feature-flag pattern, same image deployed N ways)

### 2. Update `.cursor/rules/00-project-standards.mdc`

Verify and update:
- Module structure section (multi-module with shared libraries)
- Spring profiles section (service, ev-worker, wf-worker, contact-wf-worker, message-wf-worker)
- Kafka version (4.1.1, share groups)
- KEDA autoscaling pattern
- Helm chart conventions
- Naming conventions (-service, -ev-worker, -wf-worker)

### 3. Update `.cursor/rules/10-infra.mdc`

Verify and update:
- KEDA operator section
- Kafka 4.1.1 + share groups enablement
- Helm chart structure and patterns
- ScaledObject template pattern
- KIND port mappings (any new ones)

### 4. Update `.cursor/rules/20-temporal-workflows.mdc`

Verify and update:
- Task queue definitions (NOTIFICATION_QUEUE, CONTACT_ACTIVITY_QUEUE, MESSAGE_ACTIVITY_QUEUE)
- Per-queue activity stubs pattern
- Worker profile strategy (combined vs split)
- KEDA Temporal scaler configuration

Verify glob pattern matches new file path: `notification-app/src/**/*.java`

### 5. Update Root `README.md`

The README should provide:
- Updated architecture diagram (multi-deployment)
- Quick start instructions (setup.sh, build-and-load.sh, helm install)
- Deployment architecture section (what each deployment does)
- Scaling section (KEDA triggers per component)
- Database access section (link to DataGrip guide + db-credentials.sh)
- Link to scaling demo guide
- Development section (how to run locally with all profiles)

### 6. Update `scripts/README.md`

Update to reflect:
- New scripts (build-and-load.sh, load-test.sh, watch-scaling.sh, db-credentials.sh)
- Helm-based deployment workflow
- How to run locally (bootRun with all profiles) vs in KIND (helm install)

### 7. Update `docs/tasks/README.md`

This was done as part of this task's creation, but verify the index is accurate
and all statuses are correct.

### 8. Verify All Cross-References

Check that file paths in documentation match the actual structure:
- `app/` references should be `notification-app/`
- `:app:bootRun` should be `:notification-app:bootRun`
- Any hardcoded paths in guides should match reality

## Acceptance Criteria

- [ ] `AGENTS.md` reflects the complete project state including all new phases
- [ ] All three `.cursor/rules/*.mdc` files are accurate and up-to-date
- [ ] Root `README.md` provides a clear overview of the multi-deployment architecture
- [ ] `scripts/README.md` documents all scripts including new ones
- [ ] `docs/tasks/README.md` index is complete with correct statuses
- [ ] No stale references to `app/` in documentation (should be `notification-app/`)
- [ ] A new team member reading the README can understand the architecture and get started

## Prompt (for Builder sub-agent)

```
Read the following files for full context:
- docs/tasks/16-documentation-updates.md (this task)
- AGENTS.md (current content — needs new phases added)
- .cursor/rules/00-project-standards.mdc (needs multi-module, profiles, KEDA updates)
- .cursor/rules/10-infra.mdc (needs KEDA, Kafka 4.1.1, share groups updates)
- .cursor/rules/20-temporal-workflows.mdc (needs task queue split, per-queue stubs updates)
- README.md (root — needs architecture, deployment, scaling sections)
- scripts/README.md (needs new script documentation)
- docs/tasks/README.md (verify index accuracy)

Also read the actual project structure to verify documentation matches reality:
- settings.gradle (module list)
- charts/notification-poc/values.yaml (scaling config)
- notification-app/src/main/resources/ (profile YAML files)

Task: Final documentation consistency pass.

Steps:
1. Update AGENTS.md with Phases 6-11, new architecture diagram, new decisions table.
2. Update .cursor/rules/00-project-standards.mdc with module structure, profiles, KEDA, Kafka 4.1.1.
3. Update .cursor/rules/10-infra.mdc with KEDA, share groups, Helm patterns.
4. Update .cursor/rules/20-temporal-workflows.mdc with task queues, per-queue stubs, worker profiles.
   Also update the globs line to match notification-app/src/**/*.java.
5. Update root README.md with architecture, deployment, scaling, database access sections.
6. Update scripts/README.md with new scripts.
7. Verify docs/tasks/README.md index is accurate.
8. Search for stale app/ references across all .md and .mdc files and fix them.

Key rules:
- Do NOT change any code — this is documentation only.
- Keep documentation concise and practical (no fluff).
- All file paths in documentation must match actual project structure.
- The README should let a new team member understand and get started in minutes.
```
