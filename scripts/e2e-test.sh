#!/usr/bin/env bash
# =============================================================================
# e2e-test.sh — End-to-End Test Script for Notification POC
#
# This script runs automated end-to-end tests:
# 1. Verifies infrastructure is running
# 2. Checks port-forwarding for Temporal gRPC
# 3. Checks/waits for application to be ready
# 4. Submits test events
# 5. Verifies workflows appear in Temporal
# 6. Verifies data in database and via REST API
#
# Usage:
#   ./scripts/e2e-test.sh [--skip-infra] [--skip-app-start] [--verbose]
#
# Options:
#   --skip-infra      Skip infrastructure checks
#   --skip-app-start  Don't start the app (assume it's already running)
#   --verbose         Show detailed output
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
APP_URL="http://localhost:8080"
TEMPORAL_UI_URL="http://localhost:30080"
TEMPORAL_GRPC="localhost:7233"
KAFKA_BOOTSTRAP="localhost:30092"
NAMESPACE="default"
TASK_QUEUE="NOTIFICATION_QUEUE"

# Flags
SKIP_INFRA=false
SKIP_APP_START=false
VERBOSE=false
APP_PID=""

# Parse arguments
while [[ $# -gt 0 ]]; do
    case "$1" in
        --skip-infra)
            SKIP_INFRA=true
            shift
            ;;
        --skip-app-start)
            SKIP_APP_START=true
            shift
            ;;
        --verbose)
            VERBOSE=true
            shift
            ;;
        *)
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
done

# ---------------------------------------------------------------------------
# Helper functions
# ---------------------------------------------------------------------------
info()  { echo -e "${BLUE}[INFO]${NC}  $*"; }
pass()  { echo -e "${GREEN}[PASS]${NC}  $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
fail()  { echo -e "${RED}[FAIL]${NC}  $*"; }
debug() { if [[ "${VERBOSE}" == "true" ]]; then echo -e "${YELLOW}[DEBUG]${NC} $*"; fi; }

section() {
    echo ""
    echo "============================================"
    echo " $*"
    echo "============================================"
}

check_command() {
    if ! command -v "$1" &>/dev/null; then
        fail "Required command not found: $1"
        return 1
    fi
}

check_port() {
    local port=$1
    if command -v lsof &>/dev/null; then
        lsof -Pi :"${port}" -sTCP:LISTEN -t >/dev/null 2>&1
    else
        (echo > /dev/tcp/localhost/"${port}") 2>/dev/null
    fi
}

wait_for_url() {
    local url="$1"
    local timeout="${2:-30}"
    local elapsed=0

    info "Waiting for ${url} to be available (timeout: ${timeout}s)..."
    while ! curl -sf -o /dev/null --connect-timeout 2 "${url}" 2>/dev/null; do
        if (( elapsed >= timeout )); then
            fail "Timeout waiting for ${url}"
            return 1
        fi
        sleep 1
        elapsed=$((elapsed + 1))
        echo -n "."
    done
    echo ""
    pass "${url} is available"
}

check_app_running() {
    curl -sf "${APP_URL}/actuator/health" &>/dev/null
}

# =============================================================================
# Step 1: Check Prerequisites
# =============================================================================
section "Step 1: Prerequisites"

check_command curl
check_command kubectl
check_command jq || warn "jq not found — JSON parsing will be limited"

# =============================================================================
# Step 2: Infrastructure Checks
# =============================================================================
if [[ "${SKIP_INFRA}" != "true" ]]; then
    section "Step 2: Infrastructure Checks"

    # Check KIND cluster (try both common context names)
    CLUSTER_CONTEXT=""
    if kubectl cluster-info --context kind-notification-poc &>/dev/null; then
        CLUSTER_CONTEXT="kind-notification-poc"
    elif kubectl cluster-info --context kind-kind &>/dev/null; then
        CLUSTER_CONTEXT="kind-kind"
    else
        fail "KIND cluster not found. Tried: kind-notification-poc, kind-kind"
        exit 1
    fi
    pass "KIND cluster is accessible (context: ${CLUSTER_CONTEXT})"

    # Check Temporal pods
    if ! kubectl get pods -n temporal --no-headers 2>/dev/null | grep -q Running; then
        fail "Temporal pods not running"
        exit 1
    fi
    pass "Temporal pods are running"

    # Check Temporal UI
    wait_for_url "${TEMPORAL_UI_URL}/api/v1/namespaces/${NAMESPACE}/workflows" 10 || {
        warn "Temporal UI not accessible — workflows may not be visible"
    }

    # Check Kafka
    KAFKA_POD=$(kubectl get pods -n kafka -l strimzi.io/cluster=poc-kafka --no-headers -o custom-columns=":metadata.name" 2>/dev/null | head -1)
    if [[ -z "${KAFKA_POD}" ]]; then
        fail "Kafka pod not found"
        exit 1
    fi
    pass "Kafka is running (pod: ${KAFKA_POD})"

    # Check database (CNPG pods are in the default namespace)
    DB_POD=$(kubectl get pods -n default -l cnpg.io/cluster=business-db --no-headers -o custom-columns=":metadata.name" 2>/dev/null | head -1)
    if [[ -z "${DB_POD}" ]]; then
        warn "CNPG business-db pod not found (may be using YugabyteDB)"
    else
        pass "Database is running (pod: ${DB_POD})"
    fi
else
    info "Skipping infrastructure checks"
fi

# =============================================================================
# Step 3: Port Forwarding (for Temporal gRPC)
# =============================================================================
section "Step 3: Port Forwarding"

info "Checking if Temporal gRPC port-forward is running..."
if check_port 7233; then
    pass "Port 7233 is listening (Temporal gRPC accessible)"
else
    warn "Temporal gRPC (7233) not accessible. Starting port-forward..."
    kubectl port-forward -n temporal svc/temporal-frontend 7233:7233 > /tmp/temporal-port-forward.log 2>&1 &
    PF_PID=$!
    echo "${PF_PID}" > /tmp/temporal-port-forward.pid
    sleep 2

    if check_port 7233; then
        pass "Port-forward started (PID: ${PF_PID})"
    else
        fail "Failed to start Temporal port-forward. Check: cat /tmp/temporal-port-forward.log"
        exit 1
    fi
fi

# =============================================================================
# Step 4: Application Check/Start
# =============================================================================
section "Step 4: Application"

if check_app_running; then
    pass "Application is already running at ${APP_URL}"
else
    if [[ "${SKIP_APP_START}" == "true" ]]; then
        fail "Application is not running and --skip-app-start was specified"
        exit 1
    fi

    info "Application not running. Starting..."

    export KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP}"
    export TEMPORAL_ADDRESS="${TEMPORAL_GRPC}"
    export BUSINESS_DB_PASSWORD="${BUSINESS_DB_PASSWORD:-app}"

    cd "${PROJECT_DIR}"
    info "Starting application with: ./gradlew :app:bootRun"
    ./gradlew :app:bootRun > /tmp/app.log 2>&1 &
    APP_PID=$!

    info "Waiting for application to start (PID: ${APP_PID})..."
    wait_for_url "${APP_URL}/actuator/health" 90 || {
        fail "Application failed to start. Check: tail -50 /tmp/app.log"
        kill "${APP_PID}" 2>/dev/null || true
        exit 1
    }

    pass "Application started successfully"
fi

# =============================================================================
# Step 5: Submit Test Events
# =============================================================================
section "Step 5: Submit Test Events"

# Generate unique event IDs
EVENT_ID_1=$(uuidgen 2>/dev/null || python3 -c 'import uuid; print(uuid.uuid4())' 2>/dev/null || echo "e2e-test-$(date +%s)-1")
EVENT_ID_2=$(uuidgen 2>/dev/null || python3 -c 'import uuid; print(uuid.uuid4())' 2>/dev/null || echo "e2e-test-$(date +%s)-2")

# Test 1: Single contact
info "Test 1: Submit event with single contact"
RESPONSE_1=$(curl -s -w "\n%{http_code}" -X POST "${APP_URL}/events" \
    -H "Content-Type: application/json" \
    -d "{
      \"eventId\": \"${EVENT_ID_1}\",
      \"eventType\": \"RxOrderNotification\",
      \"payload\": {
        \"contacts\": [
          {
            \"externalIdType\": \"patientId\",
            \"externalIdValue\": \"e2e-test-patient-001\",
            \"email\": \"e2e-test-1@example.com\",
            \"phone\": \"+1-555-1001\"
          }
        ],
        \"templateId\": \"rx-notification-v1\"
      }
    }")

HTTP_CODE_1=$(echo "${RESPONSE_1}" | tail -n1)
BODY_1=$(echo "${RESPONSE_1}" | sed '$d')

if [[ "${HTTP_CODE_1}" == "202" ]]; then
    pass "Test 1: Event submitted (HTTP 202)"
    debug "Response: ${BODY_1}"
else
    fail "Test 1: Expected HTTP 202, got ${HTTP_CODE_1}"
    debug "Response: ${BODY_1}"
fi

sleep 2

# Test 2: Multiple contacts
info "Test 2: Submit event with two contacts"
RESPONSE_2=$(curl -s -w "\n%{http_code}" -X POST "${APP_URL}/events" \
    -H "Content-Type: application/json" \
    -d "{
      \"eventId\": \"${EVENT_ID_2}\",
      \"eventType\": \"RxOrderNotification\",
      \"payload\": {
        \"contacts\": [
          {
            \"externalIdType\": \"patientId\",
            \"externalIdValue\": \"e2e-test-patient-002\",
            \"email\": \"e2e-test-2@example.com\",
            \"phone\": \"+1-555-1002\"
          },
          {
            \"externalIdType\": \"patientId\",
            \"externalIdValue\": \"e2e-test-patient-003\",
            \"email\": \"e2e-test-3@example.com\",
            \"phone\": \"+1-555-1003\"
          }
        ],
        \"templateId\": \"rx-notification-v1\"
      }
    }")

HTTP_CODE_2=$(echo "${RESPONSE_2}" | tail -n1)
BODY_2=$(echo "${RESPONSE_2}" | sed '$d')

if [[ "${HTTP_CODE_2}" == "202" ]]; then
    pass "Test 2: Event submitted (HTTP 202)"
    debug "Response: ${BODY_2}"
else
    fail "Test 2: Expected HTTP 202, got ${HTTP_CODE_2}"
    debug "Response: ${BODY_2}"
fi

# =============================================================================
# Step 6: Verify Workflows in Temporal
# =============================================================================
section "Step 6: Verify Workflows in Temporal"

WORKFLOW_ID_1="notification-${EVENT_ID_1}"
WORKFLOW_ID_2="notification-${EVENT_ID_2}"

info "Waiting for workflows to appear in Temporal (up to 30 seconds)..."
sleep 5

if curl -sf "${TEMPORAL_UI_URL}/api/v1/namespaces/${NAMESPACE}/workflows" &>/dev/null; then
    info "Querying Temporal API for workflows..."

    FOUND_1=false
    FOUND_2=false
    for _ in $(seq 1 25); do
        WORKFLOWS=$(curl -sf "${TEMPORAL_UI_URL}/api/v1/namespaces/${NAMESPACE}/workflows" 2>/dev/null || echo "")

        if echo "${WORKFLOWS}" | grep -q "${WORKFLOW_ID_1}" 2>/dev/null; then
            FOUND_1=true
        fi
        if echo "${WORKFLOWS}" | grep -q "${WORKFLOW_ID_2}" 2>/dev/null; then
            FOUND_2=true
        fi

        if [[ "${FOUND_1}" == "true" ]] && [[ "${FOUND_2}" == "true" ]]; then
            break
        fi

        sleep 1
        echo -n "."
    done
    echo ""

    if [[ "${FOUND_1}" == "true" ]]; then
        pass "Workflow 1 found: ${WORKFLOW_ID_1}"
    else
        warn "Workflow 1 not found: ${WORKFLOW_ID_1}"
        info "Check Temporal UI: ${TEMPORAL_UI_URL}/namespaces/${NAMESPACE}/workflows"
    fi

    if [[ "${FOUND_2}" == "true" ]]; then
        pass "Workflow 2 found: ${WORKFLOW_ID_2}"
    else
        warn "Workflow 2 not found: ${WORKFLOW_ID_2}"
        info "Check Temporal UI: ${TEMPORAL_UI_URL}/namespaces/${NAMESPACE}/workflows"
    fi
else
    warn "Cannot query Temporal API — check workflows manually in UI: ${TEMPORAL_UI_URL}"
    info "Expected workflow IDs: ${WORKFLOW_ID_1} and ${WORKFLOW_ID_2}"
fi

# =============================================================================
# Step 7: Verify Data in Database
# =============================================================================
section "Step 7: Verify Database"

DB_POD=$(kubectl get pods -n default -l cnpg.io/cluster=business-db --no-headers -o custom-columns=":metadata.name" 2>/dev/null | head -1)

if [[ -n "${DB_POD}" ]]; then
    info "Waiting for eventual consistency, then querying database..."
    sleep 5

    CONTACTS=$(kubectl exec -n default "${DB_POD}" -- psql -U app -d business -t -c \
        "SELECT COUNT(*) FROM contacts WHERE external_id_value LIKE 'e2e-test-patient-%';" 2>/dev/null | tr -d ' ' || echo "0")

    if [[ "${CONTACTS}" -gt 0 ]]; then
        pass "Found ${CONTACTS} test contact(s) in database"
    else
        warn "No test contacts found yet (eventual consistency delay or workflow still running)"
    fi

    MESSAGES=$(kubectl exec -n default "${DB_POD}" -- psql -U app -d business -t -c \
        "SELECT COUNT(*) FROM messages WHERE template_id = 'rx-notification-v1';" 2>/dev/null | tr -d ' ' || echo "0")

    if [[ "${MESSAGES}" -gt 0 ]]; then
        pass "Found ${MESSAGES} message(s) in database"
    else
        warn "No messages found yet (workflows may still be processing)"
    fi
else
    warn "Database pod not found — skipping database verification"
fi

# =============================================================================
# Step 8: Verify via REST API
# =============================================================================
section "Step 8: Verify via REST API"

info "Checking if contacts are queryable via REST API..."
sleep 3

CONTACT_RESPONSE=$(curl -s -w "\n%{http_code}" \
    "${APP_URL}/contacts?externalIdType=patientId&externalIdValue=e2e-test-patient-001" 2>/dev/null || echo -e "\n000")
CONTACT_CODE=$(echo "${CONTACT_RESPONSE}" | tail -n1)

if [[ "${CONTACT_CODE}" == "200" ]]; then
    pass "Contact e2e-test-patient-001 queryable via REST API"
    CONTACT_BODY=$(echo "${CONTACT_RESPONSE}" | sed '$d')
    debug "Contact: ${CONTACT_BODY}"
elif [[ "${CONTACT_CODE}" == "404" ]]; then
    warn "Contact not yet queryable (eventual consistency — this is expected for fresh data)"
else
    warn "Unexpected HTTP code from /contacts: ${CONTACT_CODE}"
fi

# =============================================================================
# Summary
# =============================================================================
section "Test Summary"

echo ""
info "Test Events Submitted:"
echo "  Event 1: ${EVENT_ID_1}"
echo "      Workflow: ${WORKFLOW_ID_1}"
echo "  Event 2: ${EVENT_ID_2}"
echo "      Workflow: ${WORKFLOW_ID_2}"
echo ""
info "Next Steps:"
echo "  1. View workflows in Temporal UI: ${TEMPORAL_UI_URL}/namespaces/${NAMESPACE}/workflows"
echo "  2. Check application logs: tail -f /tmp/app.log"
echo "  3. Query database: kubectl exec -n default ${DB_POD:-business-db-1} -- psql -U app -d business"
echo "  4. Re-run database check after workflows complete"
echo ""

if [[ -n "${APP_PID}" ]]; then
    info "Application is running in background (PID: ${APP_PID})"
    info "To stop: kill ${APP_PID}"
fi

pass "End-to-end test script completed!"
echo ""
