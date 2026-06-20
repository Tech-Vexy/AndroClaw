#!/usr/bin/env bash
# Androclaw Gateway — Unified Google Cloud Run Deploy
# Usage: GCP_PROJECT_ID=your-project-id ./deploy-cloudrun.sh
set -euo pipefail

PROJECT="${GCP_PROJECT_ID:?Set GCP_PROJECT_ID environment variable}"
REGION="africa-south1"
SERVICE="androclaw-gateway"
IMAGE="gcr.io/${PROJECT}/${SERVICE}"

echo "▶ Building unified gateway image on GCP Artifact Registry / Cloud Build…"
gcloud builds submit gateway/unified \
  --tag "${IMAGE}" \
  --project "${PROJECT}"

echo "▶ Deploying ${SERVICE} to Cloud Run region ${REGION}…"
gcloud run deploy "${SERVICE}" \
  --image        "${IMAGE}" \
  --region       "${REGION}" \
  --platform     managed \
  --allow-unauthenticated \
  --memory       1Gi \
  --cpu          1 \
  --concurrency  80 \
  --set-secrets  "BRIDGE_SECRET=androclaw-bridge-secret:latest,\
GMAIL_ACCESS_TOKEN=gmail-access-token:latest,\
GMAIL_REFRESH_TOKEN=gmail-refresh-token:latest,\
GMAIL_CLIENT_ID=gmail-client-id:latest,\
GMAIL_CLIENT_SECRET=gmail-client-secret:latest,\
MPESA_CONSUMER_KEY=mpesa-consumer-key:latest,\
MPESA_CONSUMER_SECRET=mpesa-consumer-secret:latest,\
MPESA_SHORTCODE=mpesa-shortcode:latest,\
MPESA_PASSKEY=mpesa-passkey:latest,\
VONAGE_API_KEY=vonage-api-key:latest,\
VONAGE_API_SECRET=vonage-api-secret:latest,\
VONAGE_APP_ID=vonage-app-id:latest,\
VONAGE_PRIVATE_KEY=vonage-private-key:latest,\
VONAGE_FROM_NUMBER=vonage-from-number:latest,\
TG_API_ID=tg-api-id:latest,\
TG_API_HASH=tg-api-hash:latest,\
TG_SESSION_STRING=tg-session-string:latest,\
GOOGLE_GENAI_API_KEY=google-genai-api-key:latest,\
AGENTPHONE_API_KEY=agentphone-api-key:latest,\
FIREBASE_PROJECT_ID=firebase-project-id:latest" \
  --project "${PROJECT}"

URL=$(gcloud run services describe "${SERVICE}" \
  --region "${REGION}" --project "${PROJECT}" \
  --format "value(status.url)")

echo ""
echo "✅ Deployed to Cloud Run: ${URL}"
echo ""
echo "Update your webhook/MCP URLs:"
echo "  Vonage inbound SMS : ${URL}/vonage/webhooks/inbound-sms"
echo "  Vonage answer      : ${URL}/vonage/webhooks/answer"
echo "  Vonage events      : ${URL}/vonage/webhooks/event"
echo "  WhatsApp inbound   : ${URL}/wa/webhook/inbound"
echo "  WhatsApp status    : ${URL}/wa/webhook/status"
echo "  AgentPhone webhook : ${URL}/agentphone/webhook/agentphone"
echo "  M-Pesa STK Callback: ${URL}/mpesa/stk/callback"
echo "  Email gateway      : ${URL}/email/list"
echo "  MCP SSE (M-Pesa)   : ${URL}/mpesa/sse"
echo "  MCP SSE (Vonage)   : ${URL}/vonage/sse"
