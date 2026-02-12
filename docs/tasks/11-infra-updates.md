# Task 11: Infrastructure Updates (Kafka 4.1.1, KEDA, Share Groups Enablement)

## Context

The current infrastructure (`infra/scripts/setup.sh`) deploys Kafka 4.0.0 via Strimzi and
has no KEDA operator. This task upgrades the infrastructure to support the new deployment
architecture:

1. **Kafka 4.0.0 -> 4.1.1**: Latest stable release, required for KIP-932 share groups preview.
2. **KEDA operator**: Enables event-driven autoscaling for all deployments (Temporal queue
   backlog, Kafka consumer lag, Prometheus metrics).
3. **Share groups enablement**: After Kafka 4.1.1 is running, enable the share groups feature
   (`share.version=1`) so Task 13 can demonstrate both consumer modes.

This task is **parallelizable with Task 10** (Gradle restructure) since it only touches
infrastructure files, not application code.

## Prerequisites
- Tasks 00-01 complete (KIND cluster working)
- Understanding of `infra/scripts/setup.sh` structure

## Key Files to Read
- `infra/scripts/setup.sh` — Full infrastructure bootstrap script (~600 lines)
- `infra/scripts/verify.sh` — Infrastructure health checks
- `infra/kind-config.yaml` — KIND cluster port mappings
- `.cursor/rules/10-infra.mdc` — Infrastructure patterns

## Deliverables

### 1. Upgrade Kafka to 4.1.1 in `setup.sh`

Find the Strimzi Kafka custom resource in `setup.sh` and update the version:

```yaml
spec:
  kafka:
    version: 4.1.1           # was 4.0.0
    replicas: 1
    listeners:
      # ... existing listener config unchanged ...
```

Verify that the Strimzi operator version supports Kafka 4.1.1. If the current Strimzi
operator is too old, update the Strimzi Helm chart version as well.

### 2. Enable KIP-932 Share Groups After Kafka Starts

After the Kafka cluster is ready, enable the share groups feature. Add this step to
`setup.sh` after the Kafka readiness check:

```bash
echo "Enabling Kafka share groups (KIP-932 preview)..."
# Wait for Kafka pod to be ready
kubectl wait --for=condition=ready pod/poc-kafka-combined-0 -n kafka --timeout=120s

# Enable share groups feature flag on the cluster
kubectl exec -n kafka poc-kafka-combined-0 -- \
    /opt/kafka/bin/kafka-features.sh \
    --bootstrap-server localhost:9092 \
    upgrade --feature share.version=1

echo "Share groups enabled."
```

**Important notes for the team (document in a comment in setup.sh):**
- Requires ALL brokers to be Kafka 4.1+. Our single-broker KIND setup satisfies this.
- If share groups were enabled on a 4.0 cluster (early access), that cluster CANNOT be
  upgraded to 4.1. The 4.0 early access is incompatible with the 4.1 preview.
- The feature is reversible: `kafka-features.sh downgrade --feature share.version=0`
- This is a preview feature, not recommended for production yet.

### 3. Install KEDA Operator

Add a new function to `setup.sh` (after the Prometheus/Grafana installation step):

```bash
install_keda() {
    echo "Installing KEDA operator..."

    helm repo add kedacore https://kedacore.github.io/charts 2>/dev/null || true
    helm repo update kedacore

    if helm status keda -n keda &>/dev/null; then
        echo "KEDA already installed, upgrading..."
        helm upgrade keda kedacore/keda \
            --namespace keda \
            --wait --timeout 120s
    else
        helm install keda kedacore/keda \
            --namespace keda --create-namespace \
            --wait --timeout 120s
    fi

    # Wait for KEDA operator to be ready
    kubectl wait --for=condition=ready pod -l app=keda-operator \
        -n keda --timeout=120s

    echo "KEDA operator installed and ready."
}
```

Call `install_keda` in the main setup flow, after prometheus-stack and before the final
summary output.

### 4. Update `infra/scripts/verify.sh`

Add KEDA readiness check:

```bash
echo "=== KEDA Operator ==="
kubectl get pods -n keda
kubectl wait --for=condition=ready pod -l app=keda-operator -n keda --timeout=30s \
    && echo "KEDA: OK" || echo "KEDA: NOT READY"
```

Add Kafka version check:

```bash
echo "=== Kafka Version ==="
kubectl exec -n kafka poc-kafka-combined-0 -- \
    /opt/kafka/bin/kafka-broker-api-versions.sh \
    --bootstrap-server localhost:9092 2>/dev/null | head -1
```

Add share groups feature check:

```bash
echo "=== Kafka Share Groups ==="
kubectl exec -n kafka poc-kafka-combined-0 -- \
    /opt/kafka/bin/kafka-features.sh \
    --bootstrap-server localhost:9092 describe 2>/dev/null | grep share \
    && echo "Share groups: ENABLED" || echo "Share groups: NOT ENABLED"
```

### 5. Update `infra/kind-config.yaml` (If Needed)

Review whether KEDA requires any additional ports. Typically KEDA runs entirely within the
cluster and doesn't need external port mappings. No changes expected, but verify.

### 6. Update `.cursor/rules/10-infra.mdc`

Add KEDA and share groups to the infrastructure standards:

```markdown
## KEDA (Kubernetes Event-Driven Autoscaling)
- Installed via Helm: `kedacore/keda` in `keda` namespace.
- Used for all autoscaling: Temporal queue backlog, Kafka consumer lag, Prometheus metrics.
- Each deployment gets a `ScaledObject` with cpu + memory baseline triggers plus
  application-specific triggers.
- Do NOT create separate HPA objects -- KEDA manages HPAs internally.

## Kafka Share Groups (KIP-932 Preview)
- Enabled on the Kafka 4.1.1 cluster via `kafka-features.sh upgrade --feature share.version=1`.
- Allows queue-like consumption where the broker distributes individual records across consumers.
- Application feature flag (`kafka.consumer-mode`) controls whether a consumer uses
  traditional consumer groups or share groups.
- Preview only -- not for production. Document upgrade implications in setup.sh comments.
```

## Acceptance Criteria

- [ ] `./infra/scripts/setup.sh` completes successfully (full cluster rebuild)
- [ ] Kafka version is 4.1.1: verify with `kafka-broker-api-versions.sh`
- [ ] Share groups feature is enabled: verify with `kafka-features.sh describe`
- [ ] KEDA operator pods are running: `kubectl get pods -n keda` shows ready pods
- [ ] `./infra/scripts/verify.sh` passes all checks including new KEDA and share groups checks
- [ ] Existing Kafka topics still work (quick-test.sh or manual test via `kafka-console-producer.sh`)
- [ ] `.cursor/rules/10-infra.mdc` updated with KEDA and share groups sections
- [ ] setup.sh comments document the KIP-932 upgrade implications

## Prompt (for Builder sub-agent)

```
Read the following files for full context:
- docs/tasks/11-infra-updates.md (this task)
- infra/scripts/setup.sh (full infrastructure bootstrap — read the entire file)
- infra/scripts/verify.sh (health checks to update)
- infra/kind-config.yaml (port mappings — check if changes needed)
- .cursor/rules/10-infra.mdc (infrastructure patterns to update)

Task: Update the infrastructure for Kafka 4.1.1, KEDA, and share groups enablement.

Steps:
1. In setup.sh, update the Strimzi Kafka CR spec.kafka.version from 4.0.0 to 4.1.1.
2. Check if the current Strimzi operator version supports Kafka 4.1.1. If not, update
   the Strimzi Helm chart version.
3. Add a step after Kafka readiness to enable share groups via kafka-features.sh.
   Include detailed comments about KIP-932 upgrade implications.
4. Add an install_keda() function to setup.sh that installs the KEDA operator via Helm.
   Make it idempotent (handle already-installed case).
5. Call install_keda in the main setup flow after prometheus-stack.
6. Update verify.sh with KEDA, Kafka version, and share groups checks.
7. Update .cursor/rules/10-infra.mdc with KEDA and share groups documentation.
8. Rebuild the cluster: ./infra/scripts/teardown.sh && ./infra/scripts/setup.sh
9. Run verify.sh and confirm all checks pass.
10. Run a quick Kafka test to verify topics still work with 4.1.1.

Key rules:
- setup.sh must remain idempotent (safe to run multiple times).
- Use set -euo pipefail (already present).
- Follow existing script style (functions, echo statements, helm patterns).
- KEDA install goes in the keda namespace.
- Do NOT change application code — this is infrastructure only.
```
