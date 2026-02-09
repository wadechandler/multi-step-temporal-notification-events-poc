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
wait_for_pods() {
    local namespace="$1"
    local label="$2"
    local timeout="${3:-300s}"
    echo "  Waiting for pods in ${namespace} with label ${label}..."
    kubectl wait --for=condition=Ready pod -l "${label}" -n "${namespace}" --timeout="${timeout}" 2>/dev/null || true
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
    echo "  Cluster '${CLUSTER_NAME}' already exists. Skipping creation."
else
    echo "  Creating KIND cluster '${CLUSTER_NAME}'..."
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
    echo "  cert-manager already installed. Skipping."
else
    helm install cert-manager jetstack/cert-manager \
        --namespace cert-manager \
        --create-namespace \
        --set crds.enabled=true \
        --wait
fi
echo ""

# ---------------------------------------------------------------------------
# Step 3: Install Strimzi Operator + Kafka Cluster
# ---------------------------------------------------------------------------
echo ">>> Step 3: Strimzi (Kafka)"
helm_repo_add strimzi https://strimzi.io/charts/
helm repo update strimzi

if helm list -n kafka 2>/dev/null | grep -q strimzi; then
    echo "  Strimzi operator already installed. Skipping."
else
    helm install strimzi strimzi/strimzi-kafka-operator \
        --namespace kafka \
        --create-namespace \
        --wait
fi

# Apply Kafka cluster manifest (KRaft mode, no ZooKeeper)
echo "  Applying Kafka cluster manifest..."
kubectl apply -n kafka -f - <<'KAFKA_EOF'
apiVersion: kafka.strimzi.io/v1beta2
kind: KafkaNodePool
metadata:
  name: combined
  labels:
    strimzi.io/cluster: poc-kafka
spec:
  replicas: 3
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
      offsets.topic.replication.factor: 2
      transaction.state.log.replication.factor: 2
      transaction.state.log.min.isr: 1
      default.replication.factor: 2
      min.insync.replicas: 1
  entityOperator:
    topicOperator: {}
KAFKA_EOF

echo "  Waiting for Kafka cluster to be ready..."
kubectl wait kafka/poc-kafka --for=condition=Ready --timeout=300s -n kafka 2>/dev/null || echo "  (Kafka may still be starting — check with: kubectl get kafka -n kafka)"

# Create topics
echo "  Creating Kafka topics..."
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
  replicas: 2
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
        echo "  CNPG operator already installed. Skipping."
    else
        helm install cnpg cnpg/cloudnative-pg \
            --namespace cnpg-system \
            --create-namespace \
            --wait
    fi

    # Create database clusters
    echo "  Creating CNPG database clusters..."

    # Temporal persistence database
    kubectl apply -n default -f - <<'CNPG_TEMPORAL_EOF'
apiVersion: postgresql.cnpg.io/v1
kind: Cluster
metadata:
  name: temporal-db
spec:
  instances: 2
  storage:
    size: 5Gi
  bootstrap:
    initdb:
      database: temporal
      owner: temporal
      postInitSQL:
        - CREATE DATABASE temporal_visibility OWNER temporal;
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
  instances: 2
  storage:
    size: 5Gi
  bootstrap:
    initdb:
      database: business
      owner: app
  postgresql:
    parameters:
      max_connections: "200"
  nodeMaintenanceWindow:
    inProgress: false
CNPG_BUSINESS_EOF

    echo "  Waiting for CNPG clusters to be ready..."
    kubectl wait cluster/temporal-db --for=condition=Ready --timeout=300s 2>/dev/null || echo "  (temporal-db may still be starting)"
    kubectl wait cluster/business-db --for=condition=Ready --timeout=300s 2>/dev/null || echo "  (business-db may still be starting)"

    # Expose databases via NodePort for DataGrip
    kubectl apply -f - <<'NODEPORT_EOF'
apiVersion: v1
kind: Service
metadata:
  name: business-db-nodeport
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

    echo "  Installing YugabyteDB..."
    helm install yugabyte yugabytedb/yugabyte \
        --namespace yugabyte \
        --create-namespace \
        --set resource.master.requests.cpu=0.5 \
        --set resource.master.requests.memory=0.5Gi \
        --set resource.tserver.requests.cpu=0.5 \
        --set resource.tserver.requests.memory=0.5Gi \
        --set replicas.master=1 \
        --set replicas.tserver=3 \
        --set storage.master.size=5Gi \
        --set storage.tserver.size=5Gi \
        --set enableLoadBalancer=false \
        --wait --timeout 600s || echo "  (YugabyteDB may still be starting)"

    echo "  Creating databases in YugabyteDB..."
    # Wait for tserver to be ready, then create databases
    kubectl wait --for=condition=Ready pod -l app=yb-tserver -n yugabyte --timeout=300s 2>/dev/null || true
    kubectl exec -n yugabyte yb-tserver-0 -- ysqlsh -h yb-tserver-0.yb-tservers.yugabyte -c "CREATE DATABASE temporal;" 2>/dev/null || true
    kubectl exec -n yugabyte yb-tserver-0 -- ysqlsh -h yb-tserver-0.yb-tservers.yugabyte -c "CREATE DATABASE temporal_visibility;" 2>/dev/null || true
    kubectl exec -n yugabyte yb-tserver-0 -- ysqlsh -h yb-tserver-0.yb-tservers.yugabyte -c "CREATE DATABASE business;" 2>/dev/null || true
    kubectl exec -n yugabyte yb-tserver-0 -- ysqlsh -h yb-tserver-0.yb-tservers.yugabyte -c "CREATE USER temporal WITH PASSWORD 'temporal';" 2>/dev/null || true
    kubectl exec -n yugabyte yb-tserver-0 -- ysqlsh -h yb-tserver-0.yb-tservers.yugabyte -c "CREATE USER app WITH PASSWORD 'app';" 2>/dev/null || true
    kubectl exec -n yugabyte yb-tserver-0 -- ysqlsh -h yb-tserver-0.yb-tservers.yugabyte -c "GRANT ALL ON DATABASE temporal TO temporal;" 2>/dev/null || true
    kubectl exec -n yugabyte yb-tserver-0 -- ysqlsh -h yb-tserver-0.yb-tservers.yugabyte -c "GRANT ALL ON DATABASE temporal_visibility TO temporal;" 2>/dev/null || true
    kubectl exec -n yugabyte yb-tserver-0 -- ysqlsh -h yb-tserver-0.yb-tservers.yugabyte -c "GRANT ALL ON DATABASE business TO app;" 2>/dev/null || true

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
    echo "ERROR: Unknown DB mode '${DB_MODE}'. Use 'cnpg' or 'yugabyte'."
    exit 1
fi
echo ""

# ---------------------------------------------------------------------------
# Step 5: Install Temporal
# ---------------------------------------------------------------------------
echo ">>> Step 5: Temporal"
helm_repo_add temporal https://charts.temporal.io
helm repo update temporal

# Determine DB connection info based on mode
if [[ "${DB_MODE}" == "cnpg" ]]; then
    # CNPG passwords are stored in secrets created by the operator
    TEMPORAL_DB_HOST="temporal-db-rw.default.svc.cluster.local"
    TEMPORAL_DB_PORT="5432"
    VISIBILITY_DB_HOST="${TEMPORAL_DB_HOST}"
    VISIBILITY_DB_PORT="${TEMPORAL_DB_PORT}"
elif [[ "${DB_MODE}" == "yugabyte" ]]; then
    TEMPORAL_DB_HOST="yb-tservers.yugabyte.svc.cluster.local"
    TEMPORAL_DB_PORT="5433"
    VISIBILITY_DB_HOST="${TEMPORAL_DB_HOST}"
    VISIBILITY_DB_PORT="${TEMPORAL_DB_PORT}"
fi

echo "  Installing Temporal (chart 0.73.1) with Postgres persistence..."
helm install temporal temporal/temporal \
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
    --set server.config.persistence.default.sql.user=temporal \
    --set server.config.persistence.default.sql.password=temporal \
    --set server.config.persistence.visibility.driver=sql \
    --set server.config.persistence.visibility.sql.driver=postgres12 \
    --set server.config.persistence.visibility.sql.host="${VISIBILITY_DB_HOST}" \
    --set server.config.persistence.visibility.sql.port="${VISIBILITY_DB_PORT}" \
    --set server.config.persistence.visibility.sql.database=temporal_visibility \
    --set server.config.persistence.visibility.sql.user=temporal \
    --set server.config.persistence.visibility.sql.password=temporal \
    --set web.enabled=true \
    --set web.service.type=NodePort \
    --set web.service.nodePort=30080 \
    --timeout 600s \
    --wait || echo "  (Temporal may still be starting — check with: kubectl get pods -n temporal)"

echo ""

# ---------------------------------------------------------------------------
# Step 6: Prometheus + Grafana
# ---------------------------------------------------------------------------
echo ">>> Step 6: Prometheus + Grafana"
helm_repo_add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update prometheus-community

if helm list -n monitoring 2>/dev/null | grep -q kube-prometheus; then
    echo "  kube-prometheus-stack already installed. Skipping."
else
    helm install kube-prometheus prometheus-community/kube-prometheus-stack \
        --namespace monitoring \
        --create-namespace \
        --set grafana.service.type=NodePort \
        --set grafana.service.nodePort=30081 \
        --set grafana.adminPassword=admin \
        --wait --timeout 300s || echo "  (Monitoring stack may still be starting)"
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
echo "   Business DB:    localhost:30432 (for DataGrip)"
echo "   Temporal DB:    localhost:30433 (for DataGrip)"
echo "   Kafka:          localhost:30092 (bootstrap)"
echo ""
echo " Useful commands:"
echo "   kubectl get pods -A                    # See all pods"
echo "   kubectl get kafka -n kafka             # Check Kafka status"
if [[ "${DB_MODE}" == "cnpg" ]]; then
echo "   kubectl get cluster -n default         # Check CNPG clusters"
echo "   kubectl get secret temporal-db-app -o jsonpath='{.data.password}' | base64 -d  # Get temporal DB password"
echo "   kubectl get secret business-db-app -o jsonpath='{.data.password}' | base64 -d  # Get business DB password"
fi
echo "   kubectl get pods -n temporal           # Check Temporal pods"
echo ""
