#!/usr/bin/env bash
# =============================================================================
# quick-test.sh — Quick Test Script for Notification POC
#
# Simple script to submit a test event and check if it appears in Temporal.
# Assumes infrastructure and application are already running.
#
# Usage:
#   ./scripts/quick-test.sh
# =============================================================================
set -euo pipefail

APP_URL="http://localhost:8080"
TEMPORAL_UI_URL="http://localhost:30080"

# Generate unique event ID
EVENT_ID=$(uuidgen 2>/dev/null || echo "test-$(date +%s)")

echo "🚀 Quick Test: Submitting notification event..."
echo "   Event ID: ${EVENT_ID}"
echo ""

# Submit event
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "${APP_URL}/events" \
    -H "Content-Type: application/json" \
    -d "{
      \"eventId\": \"${EVENT_ID}\",
      \"eventType\": \"RxOrderNotification\",
      \"payload\": {
        \"contacts\": [
          {
            \"externalIdType\": \"patientId\",
            \"externalIdValue\": \"quick-test-patient-001\",
            \"email\": \"quick-test@example.com\",
            \"phone\": \"+1-555-9999\"
          }
        ],
        \"templateId\": \"rx-notification-v1\"
      }
    }")

HTTP_CODE=$(echo "${RESPONSE}" | tail -n1)

if [[ "${HTTP_CODE}" == "202" ]]; then
    echo "✅ Event submitted successfully!"
    echo ""
    echo "📊 Next steps:"
    echo "   1. View workflow in Temporal UI:"
    echo "      ${TEMPORAL_UI_URL}/namespaces/default/workflows"
    echo ""
    echo "   2. Look for workflow ID: notification-${EVENT_ID}"
    echo ""
    echo "   3. Wait 10-30 seconds for workflow to appear"
    echo ""
else
    echo "❌ Failed to submit event. HTTP Code: ${HTTP_CODE}"
    echo "   Response: $(echo "${RESPONSE}" | head -n-1)"
    exit 1
fi
