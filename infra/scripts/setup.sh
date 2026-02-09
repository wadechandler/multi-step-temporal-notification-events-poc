#!/usr/bin/env bash
# =============================================================================
# setup.sh — Bootstrap the Notification POC infrastructure in KIND
#
# Usage:
#   ./infra/scripts/setup.sh [--db cnpg|yugabyte]
#
# Options:
#   --db cnpg       (default) Use CloudNativePG for all databases
#   --db yugabyte   Use YugabyteDB for all databases
#
# Prerequisites: kind, kubectl, helm, docker (running)
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INFRA_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
PROJECT_DIR="$(cd "${INFRA_DIR}/.." && pwd)"

CLUSTER_NAME="notification-poc"
DB_MODE="cnpg"

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

echo "============================================"
echo " Notification POC — Infrastructure Setup"
echo " DB Mode: ${DB_MODE}"
echo "============================================"

# ---------------------------------------------------------------------------
# Helper functions
# ---------------------------------------------------------------------------
info()  { echo "  [INFO]  $*"; }
warn()  { echo "  [WARN]  $*"; }
error() { echo "  [ERROR] $*" >&2; }

wait_for_pods() {
    local namespace="$1"
    local label="$2"
    local timeout="${3:-300s}"
    info "Waiting for pods in ${namespace} with label ${label} (timeout: ${timeout})..."
    if ! kubectl wait --for=condition=Ready pod -l "${label}" -n "${namespace}" --timeout="${timeout}"; then
        warn "Timed out waiting for pods in ${namespace} with label ${label}"
        kubectl get pods -n "${namespace}" -l "${label}"
        return 1
    fi
}

wait_for_crd() {
    local crd="$1"
    local timeout="${2:-60}"
    info "Waiting for CRD ${crd}..."
    local elapsed=0
    while ! kubectl get crd "${crd}" &>/dev/null; do
        if (( elapsed >= timeout )); then
            error "Timed out waiting for CRD ${crd}"
            return 1
        fi
        sleep 5
        elapsed=$((elapsed + 5))
    done
    kubectl wait --for=condition=Established "crd/${crd}" --timeout="${timeout}s"
}

wait_for_deployment() {
    local namespace="$1"
    local name="$2"
    local timeout="${3:-120s}"
    info "Waiting for deployment ${name} in ${namespace}..."
    kubectl rollout status "deployment/${name}" -n "${namespace}" --timeout="${timeout}"
}

helm_repo_add() {
    local name="$1"
    local url="$2"
    if ! helm repo list 2>/dev/null | grep -q "^${name}"; then
        helm repo add "${name}" "${url}"
    fi
}

# ---------------------------------------------------------------------------
# Step 1: Create KIND cluster
# ---------------------------------------------------------------------------
echo ""
echo ">>> Step 1: KIND Cluster"
if kind get clusters 2>/dev/null | grep -q "^${CLUSTER_NAME}$"; then
    info "Cluster '${CLUSTER_NAME}' already exists. Skipping creation."
else
    info "Creating KIND cluster '${CLUSTER_NAME}'..."
    kind create cluster --config "${INFRA_DIR}/kind-config.yaml"
fi

kubectl cluster-info --context "kind-${CLUSTER_NAME}"
echo ""

# ---------------------------------------------------------------------------
# Step 2: Install cert-manager (required by several operators)
# ---------------------------------------------------------------------------
echo ">>> Step 2: cert-manager"
helm_repo_add jetstack https://charts.jetstack.io
helm repo update jetstack

if helm list -n cert-manager 2>/dev/null | grep -q cert-manager; then
    info "cert-manager already installed. Skipping."
else
    helm install cert-manager jetstack/cert-manager \
        --namespace cert-manager \
        --create-namespace \
        --set crds.enabled=true \
        --wait --timeout 120s
fi

# Wait for cert-manager webhook to be ready (required by operators that use certs)
wait_for_deployment cert-manager cert-manager-webhook 120s
echo ""

# ---------------------------------------------------------------------------
# Step 3: Install Strimzi Operator + Kafka Cluster
# ---------------------------------------------------------------------------
echo ">>> Step 3: Strimzi (Kafka)"
helm_repo_add strimzi https://strimzi.io/charts/
helm repo update strimzi

if helm list -n kafka 2>/dev/null | grep -q strimzi; then
    info "Strimzi operator already installed. Skipping."
else
    helm install strimzi strimzi/strimzi-kafka-operator \
        --namespace kafka \
        --create-namespace \
        --wait --timeout 120s
fi

# Wait for Strimzi CRDs before applying Kafka resources
wait_for_crd kafkas.kafka.strimzi.io 120
wait_for_crd kafkanodepools.kafka.strimzi.io 120
wait_for_crd kafkatopics.kafka.strimzi.io 120

# Apply Kafka cluster manifest (KRaft mode, single combined node for local dev)
info "Applying Kafka cluster manifest (single node)..."
kubectl apply -n kafka -f - <<'KAFKA_EOF'
apiVersion: kafka.strimzi.io/v1beta2
kind: KafkaNodePool
metadata:
  name: combined
  labels:
    strimzi.io/cluster: poc-kafka
spec:
  replicas: 1
  roles:
    - controller
    - broker
  storage:
    type: jbod
    volumes:
      - id: 0
        type: persistent-claim
        size: 5Gi
        deleteClaim: true
---
apiVersion: kafka.strimzi.io/v1beta2
kind: Kafka
metadata:
  name: poc-kafka
  annotations:
    strimzi.io/kraft: enabled
    strimzi.io/node-pools: enabled
spec:
  kafka:
    version: 4.0.0
    metadataVersion: "4.0"
    listeners:
      - name: plain
        port: 9092
        type: internal
        tls: false
      - name: external
        port: 9094
        type: nodeport
        tls: false
        configuration:
          bootstrap:
            nodePort: 30092
    config:
      offsets.topic.replication.factor: 1
      transaction.state.log.replication.factor: 1
      transaction.state.log.min.isr: 1
      default.replication.factor: 1
      min.insync.replicas: 1
  entityOperator:
    topicOperator: {}
KAFKA_EOF

info "Waiting for Kafka cluster to be ready..."
kubectl wait kafka/poc-kafka --for=condition=Ready --timeout=300s -n kafka || {
    warn "Kafka may still be starting — check with: kubectl get kafka -n kafka"
    kubectl get kafka -n kafka
}

# Create topics (single replica for local dev)
info "Creating Kafka topics..."
for TOPIC in notification-events contact-commands contact-events message-commands message-events; do
    kubectl apply -n kafka -f - <<TOPIC_EOF
apiVersion: kafka.strimzi.io/v1beta2
kind: KafkaTopic
metadata:
  name: ${TOPIC}
  labels:
    strimzi.io/cluster: poc-kafka
spec:
  partitions: 6
  replicas: 1
TOPIC_EOF
done
echo ""

# ---------------------------------------------------------------------------
# Step 4: Install Database (CNPG or YugabyteDB)
# ---------------------------------------------------------------------------
echo ">>> Step 4: Database (${DB_MODE})"

if [[ "${DB_MODE}" == "cnpg" ]]; then
    # -- CNPG --
    helm_repo_add cnpg https://cloudnative-pg.github.io/charts
    helm repo update cnpg

    if helm list -n cnpg-system 2>/dev/null | grep -q cnpg; then
        info "CNPG operator already installed. Skipping."
    else
        helm install cnpg cnpg/cloudnative-pg \
            --namespace cnpg-system \
            --create-namespace \
            --wait --timeout 120s
    fi

    # Wait for CNPG CRD before applying cluster manifests
    wait_for_crd clusters.postgresql.cnpg.io 120

    # Create database clusters (single instance for local dev)
    info "Creating CNPG database clusters (1 instance each)..."

    # Temporal persistence database
    kubectl apply -n default -f - <<'CNPG_TEMPORAL_EOF'
apiVersion: postgresql.cnpg.io/v1
kind: Cluster
metadata:
  name: temporal-db
spec:
  instances: 1
  storage:
    size: 5Gi
  bootstrap:
    initdb:
      database: temporal
      owner: temporal
      postInitSQL:
        - CREATE DATABASE temporal_visibility OWNER temporal;
        - ALTER USER temporal CREATEDB;
  postgresql:
    parameters:
      max_connections: "200"
CNPG_TEMPORAL_EOF

    # Business/application database
    kubectl apply -n default -f - <<'CNPG_BUSINESS_EOF'
apiVersion: postgresql.cnpg.io/v1
kind: Cluster
metadata:
  name: business-db
spec:
  instances: 1
  storage:
    size: 5Gi
  bootstrap:
    initdb:
      database: business
      owner: app
  postgresql:
    parameters:
      max_connections: "200"
CNPG_BUSINESS_EOF

    info "Waiting for CNPG clusters to be ready..."
    kubectl wait cluster/temporal-db --for=condition=Ready --timeout=300s -n default || {
        warn "temporal-db may still be starting"
        kubectl get cluster -n default
    }
    kubectl wait cluster/business-db --for=condition=Ready --timeout=300s -n default || {
        warn "business-db may still be starting"
        kubectl get cluster -n default
    }

    # -------------------------------------------------------------------
    # Extract CNPG-generated passwords from K8s secrets
    # CNPG auto-creates secrets named <cluster>-app with the app user
    # password. We extract these and inject them into Temporal Helm values.
    # -------------------------------------------------------------------
    info "Extracting CNPG-generated database passwords from secrets..."
    TEMPORAL_DB_PASSWORD=$(kubectl get secret temporal-db-app -n default \
        -o jsonpath='{.data.password}' | base64 -d)
    BUSINESS_DB_PASSWORD=$(kubectl get secret business-db-app -n default \
        -o jsonpath='{.data.password}' | base64 -d)

    if [[ -z "${TEMPORAL_DB_PASSWORD}" ]]; then
        error "Failed to extract temporal-db password from secret 'temporal-db-app'"
        exit 1
    fi
    if [[ -z "${BUSINESS_DB_PASSWORD}" ]]; then
        error "Failed to extract business-db password from secret 'business-db-app'"
        exit 1
    fi
    info "Successfully extracted database passwords from CNPG secrets."

    # Expose databases via NodePort for DataGrip
    kubectl apply -n default -f - <<'NODEPORT_EOF'
apiVersion: v1
kind: Service
metadata:
  name: business-db-nodeport
  namespace: default
spec:
  type: NodePort
  selector:
    cnpg.io/cluster: business-db
    role: primary
  ports:
    - port: 5432
      targetPort: 5432
      nodePort: 30432
---
apiVersion: v1
kind: Service
metadata:
  name: temporal-db-nodeport
  namespace: default
spec:
  type: NodePort
  selector:
    cnpg.io/cluster: temporal-db
    role: primary
  ports:
    - port: 5432
      targetPort: 5432
      nodePort: 30433
NODEPORT_EOF

elif [[ "${DB_MODE}" == "yugabyte" ]]; then
    # -- YugabyteDB --
    helm_repo_add yugabytedb https://charts.yugabyte.com
    helm repo update yugabytedb

    if helm list -n yugabyte 2>/dev/null | grep -q yugabyte; then
        info "YugabyteDB already installed. Skipping."
    else
        info "Installing YugabyteDB (1 master, 1 tserver for local dev)..."
        helm install yugabyte yugabytedb/yugabyte \
            --namespace yugabyte \
            --create-namespace \
            --set resource.master.requests.cpu=0.5 \
            --set resource.master.requests.memory=0.5Gi \
            --set resource.tserver.requests.cpu=0.5 \
            --set resource.tserver.requests.memory=0.5Gi \
            --set replicas.master=1 \
            --set replicas.tserver=1 \
            --set storage.master.size=5Gi \
            --set storage.tserver.size=5Gi \
            --set enableLoadBalancer=false \
            --set "gflags.master.replication_factor=1" \
            --set "gflags.tserver.ysql_num_shards_per_tserver=2" \
            --wait --timeout 600s || {
                warn "YugabyteDB may still be starting"
                kubectl get pods -n yugabyte
            }
    fi

    info "Waiting for YugabyteDB tserver to be ready..."
    kubectl wait --for=condition=Ready pod -l app=yb-tserver -n yugabyte --timeout=300s || {
        warn "YugabyteDB tserver not ready yet"
        kubectl get pods -n yugabyte
    }

    info "Creating databases and users in YugabyteDB..."
    kubectl exec -n yugabyte yb-tserver-0 -- \
        ysqlsh -h yb-tserver-0.yb-tservers.yugabyte \
        -c "CREATE DATABASE temporal;" 2>/dev/null || true
    kubectl exec -n yugabyte yb-tserver-0 -- \
        ysqlsh -h yb-tserver-0.yb-tservers.yugabyte \
        -c "CREATE DATABASE temporal_visibility;" 2>/dev/null || true
    kubectl exec -n yugabyte yb-tserver-0 -- \
        ysqlsh -h yb-tserver-0.yb-tservers.yugabyte \
        -c "CREATE DATABASE business;" 2>/dev/null || true
    kubectl exec -n yugabyte yb-tserver-0 -- \
        ysqlsh -h yb-tserver-0.yb-tservers.yugabyte \
        -c "CREATE USER temporal WITH PASSWORD 'temporal';" 2>/dev/null || true
    kubectl exec -n yugabyte yb-tserver-0 -- \
        ysqlsh -h yb-tserver-0.yb-tservers.yugabyte \
        -c "CREATE USER app WITH PASSWORD 'app';" 2>/dev/null || true
    kubectl exec -n yugabyte yb-tserver-0 -- \
        ysqlsh -h yb-tserver-0.yb-tservers.yugabyte \
        -c "GRANT ALL ON DATABASE temporal TO temporal;" 2>/dev/null || true
    kubectl exec -n yugabyte yb-tserver-0 -- \
        ysqlsh -h yb-tserver-0.yb-tservers.yugabyte \
        -c "GRANT ALL ON DATABASE temporal_visibility TO temporal;" 2>/dev/null || true
    kubectl exec -n yugabyte yb-tserver-0 -- \
        ysqlsh -h yb-tserver-0.yb-tservers.yugabyte \
        -c "GRANT ALL ON DATABASE business TO app;" 2>/dev/null || true

    # Verify databases were created successfully
    info "Verifying YugabyteDB databases exist..."
    for DB_NAME in temporal temporal_visibility business; do
        if kubectl exec -n yugabyte yb-tserver-0 -- \
            ysqlsh -h yb-tserver-0.yb-tservers.yugabyte \
            -d "${DB_NAME}" -c "SELECT 1;" &>/dev/null; then
            info "Database '${DB_NAME}' verified."
        else
            error "Database '${DB_NAME}' was not created successfully in YugabyteDB"
            exit 1
        fi
    done

    # YugabyteDB passwords are known (we set them explicitly above)
    TEMPORAL_DB_PASSWORD="temporal"
    BUSINESS_DB_PASSWORD="app"

    # Expose via NodePort for DataGrip
    kubectl apply -n yugabyte -f - <<'YB_NODEPORT_EOF'
apiVersion: v1
kind: Service
metadata:
  name: yb-nodeport
spec:
  type: NodePort
  selector:
    app: yb-tserver
  ports:
    - name: ysql
      port: 5433
      targetPort: 5433
      nodePort: 30432
YB_NODEPORT_EOF

else
    error "Unknown DB mode '${DB_MODE}'. Use 'cnpg' or 'yugabyte'."
    exit 1
fi
echo ""

# ---------------------------------------------------------------------------
# Step 5: Install Temporal
# ---------------------------------------------------------------------------
echo ">>> Step 5: Temporal"
helm_repo_add temporal https://go.temporal.io/helm-charts
helm repo update temporal

# Determine DB connection info based on mode
if [[ "${DB_MODE}" == "cnpg" ]]; then
    TEMPORAL_DB_HOST="temporal-db-rw.default.svc.cluster.local"
    TEMPORAL_DB_PORT="5432"
    TEMPORAL_DB_USER="temporal"
elif [[ "${DB_MODE}" == "yugabyte" ]]; then
    TEMPORAL_DB_HOST="yb-tservers.yugabyte.svc.cluster.local"
    TEMPORAL_DB_PORT="5433"
    TEMPORAL_DB_USER="temporal"
fi

# Password was extracted/set in Step 4 (TEMPORAL_DB_PASSWORD)
info "Installing Temporal (chart 0.73.1) with external Postgres persistence..."
info "  DB Host: ${TEMPORAL_DB_HOST}:${TEMPORAL_DB_PORT}"
info "  DB User: ${TEMPORAL_DB_USER}"

if helm list -n temporal 2>/dev/null | grep -q "^temporal"; then
    info "Temporal already installed. Upgrading with current config..."
    HELM_CMD="upgrade"
else
    HELM_CMD="install"
fi

helm ${HELM_CMD} temporal temporal/temporal \
    --namespace temporal \
    --create-namespace \
    --version 0.73.1 \
    --set server.replicaCount=1 \
    --set cassandra.enabled=false \
    --set mysql.enabled=false \
    --set postgresql.enabled=false \
    --set elasticsearch.enabled=false \
    --set prometheus.enabled=false \
    --set grafana.enabled=false \
    --set server.config.persistence.default.driver=sql \
    --set server.config.persistence.default.sql.driver=postgres12 \
    --set server.config.persistence.default.sql.host="${TEMPORAL_DB_HOST}" \
    --set server.config.persistence.default.sql.port="${TEMPORAL_DB_PORT}" \
    --set server.config.persistence.default.sql.database=temporal \
    --set server.config.persistence.default.sql.user="${TEMPORAL_DB_USER}" \
    --set server.config.persistence.default.sql.password="${TEMPORAL_DB_PASSWORD}" \
    --set server.config.persistence.visibility.driver=sql \
    --set server.config.persistence.visibility.sql.driver=postgres12 \
    --set server.config.persistence.visibility.sql.host="${TEMPORAL_DB_HOST}" \
    --set server.config.persistence.visibility.sql.port="${TEMPORAL_DB_PORT}" \
    --set server.config.persistence.visibility.sql.database=temporal_visibility \
    --set server.config.persistence.visibility.sql.user="${TEMPORAL_DB_USER}" \
    --set server.config.persistence.visibility.sql.password="${TEMPORAL_DB_PASSWORD}" \
    --set web.enabled=true \
    --set web.service.type=NodePort \
    --set web.service.nodePort=30080 \
    --timeout 600s \
    --wait || {
        warn "Temporal may still be starting — check with: kubectl get pods -n temporal"
        kubectl get pods -n temporal
    }

# Register the 'default' namespace (Temporal doesn't auto-create it)
info "Waiting for Temporal frontend to be ready before registering namespaces..."
wait_for_pods temporal app.kubernetes.io/component=frontend 300s || true
info "Registering 'default' namespace in Temporal..."
kubectl exec -n temporal deploy/temporal-admintools -- \
    temporal operator namespace create default --address temporal-frontend:7233 2>/dev/null \
    || info "'default' namespace may already exist"

echo ""

# ---------------------------------------------------------------------------
# Step 6: Prometheus + Grafana
# ---------------------------------------------------------------------------
echo ">>> Step 6: Prometheus + Grafana"
helm_repo_add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update prometheus-community

if helm list -n monitoring 2>/dev/null | grep -q kube-prometheus; then
    info "kube-prometheus-stack already installed. Skipping."
else
    helm install kube-prometheus prometheus-community/kube-prometheus-stack \
        --namespace monitoring \
        --create-namespace \
        --set grafana.service.type=NodePort \
        --set grafana.service.nodePort=30081 \
        --set grafana.adminPassword=admin \
        --wait --timeout 300s || {
            warn "Monitoring stack may still be starting"
            kubectl get pods -n monitoring
        }
fi
echo ""

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
echo "============================================"
echo " Setup Complete!"
echo "============================================"
echo ""
echo " KIND Cluster:     ${CLUSTER_NAME}"
echo " DB Mode:          ${DB_MODE}"
echo ""
echo " Access Points (localhost):"
echo "   Temporal UI:    http://localhost:30080"
echo "   Grafana:        http://localhost:30081 (admin/admin)"
if [[ "${DB_MODE}" == "cnpg" ]]; then
echo "   Business DB:    localhost:30432 (user: app, db: business)"
echo "   Temporal DB:    localhost:30433 (user: temporal, db: temporal)"
elif [[ "${DB_MODE}" == "yugabyte" ]]; then
echo "   YugabyteDB:     localhost:30432 (YSQL port 5433)"
echo "                   Databases: temporal, temporal_visibility, business"
fi
echo "   Kafka:          localhost:30092 (bootstrap)"
echo ""
echo " Useful commands:"
echo "   kubectl get pods -A                    # See all pods"
echo "   kubectl get kafka -n kafka             # Check Kafka status"
if [[ "${DB_MODE}" == "cnpg" ]]; then
echo "   kubectl get cluster -n default         # Check CNPG clusters"
fi
echo "   kubectl get pods -n temporal           # Check Temporal pods"
echo ""
echo " Verify the setup:"
echo "   ./infra/scripts/verify.sh"
echo ""
