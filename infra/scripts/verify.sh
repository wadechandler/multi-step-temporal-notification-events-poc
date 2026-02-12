#!/usr/bin/env bash
# =============================================================================
# verify.sh — Validate that all Notification POC infrastructure is healthy
#
# Usage:
#   ./infra/scripts/verify.sh [--db cnpg|yugabyte]
#
# Checks:
#   1. KIND cluster nodes are Ready
#   2. All pods in all namespaces are Running/Completed
#   3. Database connectivity (temporal, temporal_visibility, business)
#   4. Temporal frontend responds on gRPC port
#   5. Temporal UI is accessible
#   6. Kafka (version, share groups, topics)
#   7. Monitoring (Prometheus + Grafana)
#   8. KEDA operator
#   Connection info summary
# =============================================================================
set -euo pipefail

CLUSTER_NAME="notification-poc"
DB_MODE="cnpg"
PASS_COUNT=0
FAIL_COUNT=0
WARN_COUNT=0

# ---------------------------------------------------------------------------
# Parse arguments
# ---------------------------------------------------------------------------
while [[ $# -gt 0 ]]; do
    case "$1" in
        --db)
            DB_MODE="$2"
            shift 2
            ;;
        *)
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
done

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
pass() { echo "  ✅ $*"; PASS_COUNT=$((PASS_COUNT + 1)); }
fail() { echo "  ❌ $*"; FAIL_COUNT=$((FAIL_COUNT + 1)); }
skip() { echo "  ⏭️  $*"; WARN_COUNT=$((WARN_COUNT + 1)); }
section() { echo ""; echo ">>> $*"; }

echo "============================================"
echo " Notification POC — Infrastructure Verify"
echo " DB Mode: ${DB_MODE}"
echo "============================================"

# ---------------------------------------------------------------------------
# Check 1: KIND cluster nodes
# ---------------------------------------------------------------------------
section "Check 1: KIND Cluster Nodes"

if ! kind get clusters 2>/dev/null | grep -q "^${CLUSTER_NAME}$"; then
    fail "KIND cluster '${CLUSTER_NAME}' does not exist"
    echo ""
    echo "Run: ./infra/scripts/setup.sh to create it."
    exit 1
fi

# Verify kubectl context
if ! kubectl cluster-info --context "kind-${CLUSTER_NAME}" &>/dev/null; then
    fail "Cannot connect to KIND cluster '${CLUSTER_NAME}'"
    exit 1
fi
pass "KIND cluster '${CLUSTER_NAME}' exists and is reachable"

# Check all nodes are Ready
NOT_READY=$(kubectl get nodes --no-headers 2>/dev/null | grep -v " Ready" | wc -l | tr -d ' ' || true)
TOTAL_NODES=$(kubectl get nodes --no-headers 2>/dev/null | wc -l | tr -d ' ')
if [[ "${NOT_READY}" -eq 0 ]]; then
    pass "All ${TOTAL_NODES} nodes are Ready"
else
    fail "${NOT_READY} of ${TOTAL_NODES} nodes are NOT Ready"
    kubectl get nodes
fi

# ---------------------------------------------------------------------------
# Check 2: Pod health across namespaces
# ---------------------------------------------------------------------------
section "Check 2: Pod Health (all namespaces)"

# Look for pods that are not Running, Completed, or Succeeded
PROBLEM_PODS=$(kubectl get pods -A --no-headers 2>/dev/null \
    | grep -v -E "Running|Completed|Succeeded" \
    | grep -v -E "^\s*$" || true)

if [[ -z "${PROBLEM_PODS}" ]]; then
    TOTAL_PODS=$(kubectl get pods -A --no-headers 2>/dev/null | wc -l | tr -d ' ')
    pass "All ${TOTAL_PODS} pods are Running/Completed"
else
    PROBLEM_COUNT=$(echo "${PROBLEM_PODS}" | wc -l | tr -d ' ')
    fail "${PROBLEM_COUNT} pod(s) have issues:"
    echo "${PROBLEM_PODS}" | while IFS= read -r line; do
        echo "       ${line}"
    done
fi

# ---------------------------------------------------------------------------
# Check 3: Database connectivity
# ---------------------------------------------------------------------------
section "Check 3: Database Connectivity"

if [[ "${DB_MODE}" == "cnpg" ]]; then
    # Check CNPG cluster status via Ready condition
    if kubectl get cluster temporal-db -n default &>/dev/null; then
        TEMPORAL_DB_READY=$(kubectl get cluster temporal-db -n default \
            -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null || echo "Unknown")
        if [[ "${TEMPORAL_DB_READY}" == "True" ]]; then
            pass "CNPG temporal-db cluster is Ready"
        else
            fail "CNPG temporal-db cluster is not Ready (condition: ${TEMPORAL_DB_READY})"
        fi
    else
        fail "CNPG temporal-db cluster not found"
    fi

    if kubectl get cluster business-db -n default &>/dev/null; then
        BUSINESS_DB_READY=$(kubectl get cluster business-db -n default \
            -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null || echo "Unknown")
        if [[ "${BUSINESS_DB_READY}" == "True" ]]; then
            pass "CNPG business-db cluster is Ready"
        else
            fail "CNPG business-db cluster is not Ready (condition: ${BUSINESS_DB_READY})"
        fi
    else
        fail "CNPG business-db cluster not found"
    fi

    # Test actual SQL connectivity via pod exec
    # Note: We use -U postgres for pod-local exec because CNPG uses peer auth,
    # and the container runs as the postgres OS user. Application users (temporal, app)
    # authenticate via password over TCP, not via local socket peer auth.
    TEMPORAL_POD=$(kubectl get pods -n default -l cnpg.io/cluster=temporal-db,role=primary --no-headers -o custom-columns=":metadata.name" 2>/dev/null | head -1)
    if [[ -n "${TEMPORAL_POD}" ]]; then
        # Test temporal database
        if kubectl exec -n default "${TEMPORAL_POD}" -- psql -U postgres -d temporal -c "SELECT 1;" &>/dev/null; then
            pass "Can connect to 'temporal' database"
        else
            fail "Cannot connect to 'temporal' database"
        fi
        # Test temporal_visibility database
        if kubectl exec -n default "${TEMPORAL_POD}" -- psql -U postgres -d temporal_visibility -c "SELECT 1;" &>/dev/null; then
            pass "Can connect to 'temporal_visibility' database"
        else
            fail "Cannot connect to 'temporal_visibility' database"
        fi
    else
        fail "No temporal-db primary pod found"
    fi

    BUSINESS_POD=$(kubectl get pods -n default -l cnpg.io/cluster=business-db,role=primary --no-headers -o custom-columns=":metadata.name" 2>/dev/null | head -1)
    if [[ -n "${BUSINESS_POD}" ]]; then
        if kubectl exec -n default "${BUSINESS_POD}" -- psql -U postgres -d business -c "SELECT 1;" &>/dev/null; then
            pass "Can connect to 'business' database"
        else
            fail "Cannot connect to 'business' database"
        fi
    else
        fail "No business-db primary pod found"
    fi

elif [[ "${DB_MODE}" == "yugabyte" ]]; then
    # Check YugabyteDB pods
    if kubectl get pods -n yugabyte -l app=yb-tserver --no-headers 2>/dev/null | grep -q "Running"; then
        pass "YugabyteDB tserver is running"
    else
        fail "YugabyteDB tserver is not running"
    fi

    if kubectl get pods -n yugabyte -l app=yb-master --no-headers 2>/dev/null | grep -q "Running"; then
        pass "YugabyteDB master is running"
    else
        fail "YugabyteDB master is not running"
    fi

    # Test SQL connectivity
    for DB_NAME in temporal temporal_visibility business; do
        if kubectl exec -n yugabyte yb-tserver-0 -- \
            ysqlsh -h yb-tserver-0.yb-tservers.yugabyte -d "${DB_NAME}" -c "SELECT 1;" &>/dev/null; then
            pass "Can connect to '${DB_NAME}' database (YugabyteDB)"
        else
            fail "Cannot connect to '${DB_NAME}' database (YugabyteDB)"
        fi
    done
fi

# ---------------------------------------------------------------------------
# Check 4: Temporal frontend (gRPC)
# ---------------------------------------------------------------------------
section "Check 4: Temporal"

TEMPORAL_FRONTEND_POD=$(kubectl get pods -n temporal -l app.kubernetes.io/component=frontend --no-headers -o custom-columns=":metadata.name" 2>/dev/null | head -1)
if [[ -n "${TEMPORAL_FRONTEND_POD}" ]]; then
    TEMPORAL_FRONTEND_STATUS=$(kubectl get pod "${TEMPORAL_FRONTEND_POD}" -n temporal -o jsonpath='{.status.phase}' 2>/dev/null)
    if [[ "${TEMPORAL_FRONTEND_STATUS}" == "Running" ]]; then
        pass "Temporal frontend pod is Running (${TEMPORAL_FRONTEND_POD})"
    else
        fail "Temporal frontend pod status: ${TEMPORAL_FRONTEND_STATUS}"
    fi

    # Test actual gRPC connectivity on port 7233
    if kubectl exec -n temporal "${TEMPORAL_FRONTEND_POD}" -- \
        sh -c 'nc -z localhost 7233 2>/dev/null || (echo > /dev/tcp/localhost/7233) 2>/dev/null || ss -tlnp 2>/dev/null | grep -q :7233 || netstat -tlnp 2>/dev/null | grep -q :7233' 2>/dev/null; then
        pass "Temporal frontend gRPC port (7233) is listening"
    else
        fail "Temporal frontend gRPC port (7233) is not accessible"
    fi
else
    fail "No Temporal frontend pod found"
fi

# Check all Temporal server components
for COMPONENT in frontend history matching worker; do
    POD=$(kubectl get pods -n temporal -l "app.kubernetes.io/component=${COMPONENT}" --no-headers -o custom-columns=":metadata.name" 2>/dev/null | head -1)
    if [[ -n "${POD}" ]]; then
        STATUS=$(kubectl get pod "${POD}" -n temporal -o jsonpath='{.status.phase}' 2>/dev/null)
        if [[ "${STATUS}" == "Running" ]]; then
            pass "Temporal ${COMPONENT} is Running"
        else
            fail "Temporal ${COMPONENT} status: ${STATUS}"
        fi
    else
        # Worker component may not exist in the Helm chart by default
        if [[ "${COMPONENT}" == "worker" ]]; then
            skip "Temporal ${COMPONENT} pod not found (may not be deployed)"
        else
            fail "Temporal ${COMPONENT} pod not found"
        fi
    fi
done

# Check Temporal web UI pod
TEMPORAL_WEB_POD=$(kubectl get pods -n temporal -l app.kubernetes.io/component=web --no-headers -o custom-columns=":metadata.name" 2>/dev/null | head -1)
if [[ -n "${TEMPORAL_WEB_POD}" ]]; then
    WEB_STATUS=$(kubectl get pod "${TEMPORAL_WEB_POD}" -n temporal -o jsonpath='{.status.phase}' 2>/dev/null)
    if [[ "${WEB_STATUS}" == "Running" ]]; then
        pass "Temporal web UI pod is Running"
    else
        fail "Temporal web UI pod status: ${WEB_STATUS}"
    fi
else
    fail "No Temporal web UI pod found"
fi

# ---------------------------------------------------------------------------
# Check 5: Temporal UI accessibility (HTTP via NodePort)
# ---------------------------------------------------------------------------
section "Check 5: Temporal UI (HTTP)"

if command -v curl &>/dev/null; then
    if curl -sf -o /dev/null --connect-timeout 5 "http://localhost:30080" 2>/dev/null; then
        pass "Temporal UI is accessible at http://localhost:30080"
    else
        fail "Temporal UI is NOT accessible at http://localhost:30080"
    fi
else
    skip "curl not available — cannot test Temporal UI HTTP access"
fi

# ---------------------------------------------------------------------------
# Check 6: Kafka topics
# ---------------------------------------------------------------------------
section "Check 6: Kafka"

KAFKA_POD=$(kubectl get pods -n kafka -l strimzi.io/cluster=poc-kafka --no-headers -o custom-columns=":metadata.name" 2>/dev/null | head -1)
if [[ -z "${KAFKA_POD}" ]]; then
    fail "No Kafka pod found"
else
    pass "Kafka cluster pod found: ${KAFKA_POD}"

    # Check Kafka cluster status
    KAFKA_STATUS=$(kubectl get kafka poc-kafka -n kafka -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null || echo "Unknown")
    if [[ "${KAFKA_STATUS}" == "True" ]]; then
        pass "Kafka cluster 'poc-kafka' is Ready"
    else
        fail "Kafka cluster 'poc-kafka' is not Ready (status: ${KAFKA_STATUS})"
    fi

    # Check Kafka version via Strimzi CR status (authoritative source)
    KAFKA_VERSION=$(kubectl get kafka poc-kafka -n kafka \
        -o jsonpath='{.status.kafkaVersion}' 2>/dev/null || echo "")
    if [[ -z "${KAFKA_VERSION}" ]]; then
        # Fallback: read from the spec if status isn't populated yet
        KAFKA_VERSION=$(kubectl get kafka poc-kafka -n kafka \
            -o jsonpath='{.spec.kafka.version}' 2>/dev/null || echo "")
    fi
    if [[ -n "${KAFKA_VERSION}" ]]; then
        if [[ "${KAFKA_VERSION}" =~ ^4\.1\. ]]; then
            pass "Kafka version: ${KAFKA_VERSION}"
        else
            fail "Expected Kafka 4.1.x but got: ${KAFKA_VERSION}"
        fi
    else
        fail "Could not determine Kafka version from Strimzi CR"
    fi

    # Check share groups feature (KIP-932)
    SHARE_GROUPS=$(kubectl exec -n kafka "${KAFKA_POD}" -- \
        /opt/kafka/bin/kafka-features.sh \
        --bootstrap-server localhost:9092 describe 2>/dev/null || echo "")
    if echo "${SHARE_GROUPS}" | grep -q "share.version.*1"; then
        pass "Kafka share groups enabled (share.version=1)"
    else
        fail "Kafka share groups NOT enabled (expected share.version=1)"
    fi

    # Check each expected topic
    EXPECTED_TOPICS="notification-events contact-commands contact-events message-commands message-events"
    for TOPIC in ${EXPECTED_TOPICS}; do
        if kubectl get kafkatopic "${TOPIC}" -n kafka &>/dev/null; then
            TOPIC_READY=$(kubectl get kafkatopic "${TOPIC}" -n kafka -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null || echo "Unknown")
            if [[ "${TOPIC_READY}" == "True" ]]; then
                pass "Kafka topic '${TOPIC}' exists and is Ready"
            else
                fail "Kafka topic '${TOPIC}' exists but is not Ready"
            fi
        else
            fail "Kafka topic '${TOPIC}' not found"
        fi
    done
fi

# ---------------------------------------------------------------------------
# Check 7: Monitoring
# ---------------------------------------------------------------------------
section "Check 7: Monitoring (Prometheus + Grafana)"

GRAFANA_POD=$(kubectl get pods -n monitoring -l app.kubernetes.io/name=grafana --no-headers -o custom-columns=":metadata.name" 2>/dev/null | head -1)
if [[ -n "${GRAFANA_POD}" ]]; then
    GRAFANA_STATUS=$(kubectl get pod "${GRAFANA_POD}" -n monitoring -o jsonpath='{.status.phase}' 2>/dev/null)
    if [[ "${GRAFANA_STATUS}" == "Running" ]]; then
        pass "Grafana pod is Running"
    else
        fail "Grafana pod status: ${GRAFANA_STATUS}"
    fi
else
    fail "No Grafana pod found"
fi

if command -v curl &>/dev/null; then
    if curl -sf -o /dev/null --connect-timeout 5 "http://localhost:30081" 2>/dev/null; then
        pass "Grafana UI is accessible at http://localhost:30081"
    else
        fail "Grafana UI is NOT accessible at http://localhost:30081"
    fi
fi

# ---------------------------------------------------------------------------
# Check 8: KEDA Operator
# ---------------------------------------------------------------------------
section "Check 8: KEDA (Event-Driven Autoscaling)"

KEDA_POD=$(kubectl get pods -n keda -l app=keda-operator --no-headers -o custom-columns=":metadata.name" 2>/dev/null | head -1)
if [[ -n "${KEDA_POD}" ]]; then
    KEDA_STATUS=$(kubectl get pod "${KEDA_POD}" -n keda -o jsonpath='{.status.phase}' 2>/dev/null)
    if [[ "${KEDA_STATUS}" == "Running" ]]; then
        pass "KEDA operator pod is Running (${KEDA_POD})"
    else
        fail "KEDA operator pod status: ${KEDA_STATUS}"
    fi
else
    fail "No KEDA operator pod found"
fi

# Check KEDA metrics API server
KEDA_METRICS_POD=$(kubectl get pods -n keda -l app=keda-operator-metrics-apiserver --no-headers -o custom-columns=":metadata.name" 2>/dev/null | head -1)
if [[ -n "${KEDA_METRICS_POD}" ]]; then
    KEDA_METRICS_STATUS=$(kubectl get pod "${KEDA_METRICS_POD}" -n keda -o jsonpath='{.status.phase}' 2>/dev/null)
    if [[ "${KEDA_METRICS_STATUS}" == "Running" ]]; then
        pass "KEDA metrics API server is Running"
    else
        fail "KEDA metrics API server status: ${KEDA_METRICS_STATUS}"
    fi
else
    skip "KEDA metrics API server pod not found (may use different labels)"
fi

# Check KEDA CRDs are installed
if kubectl get crd scaledobjects.keda.sh &>/dev/null; then
    pass "KEDA ScaledObject CRD is installed"
else
    fail "KEDA ScaledObject CRD not found"
fi

# ---------------------------------------------------------------------------
# Connection Info Summary
# ---------------------------------------------------------------------------
section "Connection Info Summary"
echo ""
echo "  ┌──────────────────────────────────────────────────────────────┐"
echo "  │ Service            │ URL / Connection String                 │"
echo "  ├──────────────────────────────────────────────────────────────┤"
echo "  │ Temporal UI        │ http://localhost:30080                  │"
echo "  │ Grafana            │ http://localhost:30081 (admin/admin)    │"
echo "  │ Kafka Bootstrap    │ localhost:30092                         │"

if [[ "${DB_MODE}" == "cnpg" ]]; then
    # Extract passwords for display
    TEMPORAL_PW=$(kubectl get secret temporal-db-app -n default -o jsonpath='{.data.password}' 2>/dev/null | base64 -d 2>/dev/null || echo "<unknown>")
    BUSINESS_PW=$(kubectl get secret business-db-app -n default -o jsonpath='{.data.password}' 2>/dev/null | base64 -d 2>/dev/null || echo "<unknown>")
    echo "  │ Temporal DB        │ localhost:30433                         │"
    echo "  │   user/db          │ temporal / temporal                     │"
    echo "  │   password          │ ${TEMPORAL_PW}"
    echo "  │ Temporal Vis. DB   │ localhost:30433                         │"
    echo "  │   user/db          │ temporal / temporal_visibility          │"
    echo "  │   password          │ ${TEMPORAL_PW}"
    echo "  │ Business DB        │ localhost:30432                         │"
    echo "  │   user/db          │ app / business                         │"
    echo "  │   password          │ ${BUSINESS_PW}"
elif [[ "${DB_MODE}" == "yugabyte" ]]; then
    echo "  │ YugabyteDB (YSQL)  │ localhost:30432 (port 5433 internally) │"
    echo "  │   temporal DB      │ user: temporal / password: temporal     │"
    echo "  │   visibility DB    │ user: temporal / password: temporal     │"
    echo "  │   business DB      │ user: app / password: app              │"
fi

echo "  └──────────────────────────────────────────────────────────────┘"
echo ""

# ---------------------------------------------------------------------------
# Final Summary
# ---------------------------------------------------------------------------
echo "============================================"
echo " Verification Results"
echo "============================================"
echo "  Passed:  ${PASS_COUNT}"
echo "  Failed:  ${FAIL_COUNT}"
echo "  Skipped: ${WARN_COUNT}"
echo ""

if [[ "${FAIL_COUNT}" -gt 0 ]]; then
    echo "  ❌ SOME CHECKS FAILED — review output above."
    exit 1
else
    echo "  ✅ ALL CHECKS PASSED"
    exit 0
fi
