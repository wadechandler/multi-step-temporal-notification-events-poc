#!/usr/bin/env bash
# =============================================================================
# port-forward.sh — Port Forward Helper for Local Development
#
# Sets up kubectl port-forwarding for services that aren't exposed via NodePort.
# This allows the app to run locally and connect to services in KIND.
#
# Usage:
#   ./scripts/port-forward.sh [--background]
#
# Options:
#   --background    Run port-forwards in background (default: foreground)
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

BACKGROUND=false
if [[ "${1:-}" == "--background" ]]; then
    BACKGROUND=true
fi

# Colors
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m'

info() { echo -e "${BLUE}[INFO]${NC}  $*"; }
pass() { echo -e "${GREEN}[PASS]${NC}  $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC}  $*"; }

# Check if port-forward is already running
check_port() {
    local port=$1
    if lsof -Pi :${port} -sTCP:LISTEN -t >/dev/null 2>&1; then
        return 0  # Port is in use
    else
        return 1  # Port is free
    fi
}

# Kill existing port-forwards on the ports we need
cleanup() {
    info "Cleaning up existing port-forwards..."
    for port in 7233; do
        if check_port ${port}; then
            PID=$(lsof -ti :${port} 2>/dev/null || echo "")
            if [[ -n "${PID}" ]]; then
                info "Killing existing process on port ${port} (PID: ${PID})"
                kill ${PID} 2>/dev/null || true
            fi
        fi
    done
    sleep 1
}

# Port-forward Temporal gRPC
forward_temporal() {
    local port=7233
    local service="temporal-frontend"
    local namespace="temporal"
    
    if check_port ${port}; then
        warn "Port ${port} is already in use. Skipping Temporal port-forward."
        return 0
    fi
    
    info "Setting up port-forward for Temporal gRPC (${service}:7233 -> localhost:${port})..."
    
    if [[ "${BACKGROUND}" == "true" ]]; then
        kubectl port-forward -n ${namespace} svc/${service} ${port}:7233 > /tmp/temporal-port-forward.log 2>&1 &
        PF_PID=$!
        echo ${PF_PID} > /tmp/temporal-port-forward.pid
        sleep 2
        
        if kill -0 ${PF_PID} 2>/dev/null; then
            pass "Temporal port-forward running in background (PID: ${PF_PID})"
            info "Logs: tail -f /tmp/temporal-port-forward.log"
        else
            warn "Port-forward may have failed. Check logs: cat /tmp/temporal-port-forward.log"
        fi
    else
        info "Port-forward running in foreground. Press Ctrl+C to stop."
        kubectl port-forward -n ${namespace} svc/${service} ${port}:7233
    fi
}

# Main
echo "============================================"
echo " Port Forward Helper"
echo "============================================"
echo ""

cleanup

if [[ "${BACKGROUND}" == "true" ]]; then
    forward_temporal
    echo ""
    info "Port-forwards are running in background."
    info "To stop: kill \$(cat /tmp/temporal-port-forward.pid)"
    echo ""
    info "Services accessible:"
    echo "  - Temporal gRPC: localhost:7233"
    echo "  - Kafka: localhost:30092 (NodePort)"
    echo "  - Database: localhost:30432 (NodePort)"
    echo "  - Temporal UI: http://localhost:30080 (NodePort)"
else
    forward_temporal
fi
