#!/usr/bin/env bash
# =============================================================================
# teardown.sh — Destroy the Notification POC KIND cluster
#
# Usage:
#   ./infra/scripts/teardown.sh
# =============================================================================
set -euo pipefail

CLUSTER_NAME="notification-poc"

echo "============================================"
echo " Tearing down KIND cluster: ${CLUSTER_NAME}"
echo "============================================"

if kind get clusters 2>/dev/null | grep -q "^${CLUSTER_NAME}$"; then
    kind delete cluster --name "${CLUSTER_NAME}"
    echo "  Cluster '${CLUSTER_NAME}' deleted."
else
    echo "  Cluster '${CLUSTER_NAME}' does not exist. Nothing to do."
fi
