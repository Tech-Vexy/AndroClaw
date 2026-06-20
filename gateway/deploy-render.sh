#!/usr/bin/env bash
# Androclaw Gateway — Render Deploy Trigger Script
# Usage: ./deploy.sh
# 
# This script triggers a redeployment of your Render service by calling
# your Render Deploy Webhook.
#
# Get your deploy hook URL from:
# Render Dashboard → Your Web Service → Settings → Deploy Hook

set -euo pipefail

# Read from environment or local.properties
HOOK_URL="${RENDER_DEPLOY_HOOK_URL:-}"

if [ -z "${HOOK_URL}" ]; then
  # Try to read from local.properties in parent directory
  if [ -f "../local.properties" ]; then
    # Parse RENDER_DEPLOY_HOOK_URL from local.properties
    HOOK_URL=$(grep "^RENDER_DEPLOY_HOOK_URL=" ../local.properties | cut -d'=' -f2- | tr -d '\r')
  fi
fi

if [ -z "${HOOK_URL}" ] || [ "${HOOK_URL}" = "https://api.render.com/deploy/srv-dummy?key=dummy" ]; then
  echo "❌ Error: RENDER_DEPLOY_HOOK_URL is not set or is still the dummy value."
  echo "Please set your real Render Deploy Hook URL in local.properties or your environment variables, e.g.:"
  echo "RENDER_DEPLOY_HOOK_URL=https://api.render.com/deploy/srv-xxxxxx?key=yyyyyy"
  exit 1
fi

echo "▶ Triggering Render redeployment..."
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "${HOOK_URL}")
HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
BODY=$(echo "$RESPONSE" | sed '$d')

if [ "$HTTP_CODE" -eq 200 ] || [ "$HTTP_CODE" -eq 201 ] || [ "$HTTP_CODE" -eq 202 ]; then
  echo "✅ Deploy triggered successfully!"
  echo "Response: $BODY"
else
  echo "❌ Failed to trigger deploy (HTTP Status $HTTP_CODE)"
  echo "Response: $BODY"
  exit 1
fi
