#!/usr/bin/env bash
# =============================================================================
# build-and-load.sh — Build the Docker image and load it into the KIND cluster
#
# Usage:
#   ./scripts/build-and-load.sh [image-name] [image-tag]
#
# Defaults:
#   image-name: notification-poc
#   image-tag:  latest
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

IMAGE_NAME="${1:-notification-poc}"
IMAGE_TAG="${2:-latest}"

echo "============================================"
echo " Building Docker image: ${IMAGE_NAME}:${IMAGE_TAG}"
echo "============================================"
echo ""

cd "${PROJECT_DIR}"

docker build -t "${IMAGE_NAME}:${IMAGE_TAG}" -f Dockerfile .

echo ""
echo "Loading image into KIND cluster..."
kind load docker-image "${IMAGE_NAME}:${IMAGE_TAG}" --name notification-poc

echo ""
echo "============================================"
echo " Image loaded successfully!"
echo "============================================"
echo ""
echo " Deploy with:"
echo "   helm upgrade --install notification-poc charts/notification-poc \\"
echo "       -f charts/notification-poc/environments/local-values.yaml \\"
echo "       --namespace default --wait --timeout 300s"
echo ""
