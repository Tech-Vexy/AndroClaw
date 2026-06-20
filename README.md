# OpenClaw Android

A native Android port of [OpenClaw](https://openclaw.ai) — a personal AI assistant with full access to your communications stack.

Built with **Koog 1.0** (JetBrains agent framework), **Kotlin + Jetpack Compose**, and a unified gateway deployed on Render.

---

## Features

| Channel       | Capability                                              |
|---------------|---------------------------------------------------------|
| Telephony     | Send/receive SMS, place outbound calls (Vonage)         |
| Email         | Read, search, send via Gmail MCP                        |
| WhatsApp      | Send messages, read inbox cache, push notifications     |
| Telegram      | Full user account (read, send, forward, list dialogs)   |
| M-Pesa        | STK push, B2C, balance check, transaction status        |
| Calendar      | List events, create events, find free slots (GCal MCP)  |
| Voice         | Swahili/English STT (Deepgram) + TTS (Cartesia)         |
| Memory        | Persistent long-term memory across sessions (Room)      |

---

## Project structure

```
openclaw-android/
├── agent-core/          # KMP module — Koog agent, all tool implementations
│   └── src/main/kotlin/ai/androclaw/
│       ├── agent/       # OpenClawAgent, OpenClawConfig, memory
│       ├── mcp/         # McpClientManager (Gmail, Calendar, Vonage SSE)
│       └── tools/       # MpesaTools, TelephonyTools, WhatsAppTools, TelegramTools, …
│
├── app/                 # Android app module
│   └── src/main/kotlin/ai/androclaw/
│       ├── data/        # Room DB, DataStore (ConfigStore), ConfigBridge
│       ├── di/          # Koin modules
│       ├── fcm/         # FirebaseMessagingService
│       ├── receiver/    # SmsReceiver, BootReceiver
│       ├── service/     # AgentService (coroutine bridge), ForegroundService
│       ├── sync/        # WaMessageSyncer, WhatsAppMessageProvider, AppLifecycleObserver
│       ├── ui/          # Compose screens: Chat, Onboarding, Settings
│       └── voice/       # VoiceManager (Deepgram STT + Cartesia TTS)
│
└── gateway/             # Unified gateway service (FastAPI)
    └── unified/         # Main service entry point, routers, and helper scripts
```

---

## Setup

### 1. Clone and configure

```bash
git clone https://github.com/YOUR_ORG/openclaw-android
cd openclaw-android
cp local.properties.template local.properties
# Edit local.properties with your API keys
```

### 2. Add Firebase config

Follow [app/google-services.json.template](app/google-services.json.template) to download your real `google-services.json` from Firebase Console and place it at `app/google-services.json`.

Enable:
- Cloud Messaging (FCM)
- Firestore (region: `africa-south1`)

### 3. Deploy gateway services

You can deploy the unified gateway service to either **Render** or **Google Cloud Run**.

#### Option A: Deploy to Render

The gateway can be deployed as a single unified service on Render using the provided `render.yaml` Blueprint.

1. Push your repository to GitHub.
2. Go to the [Render Dashboard](https://dashboard.render.com).
3. Click **New** → **Blueprint**.
4. Connect your GitHub repository. Render will automatically detect `render.yaml` and create the `androclaw-gateway` web service.
5. In the Render Dashboard, go to your service's **Environment** tab and populate the required environment variables (e.g. API keys for Vonage, M-Pesa, Firebase, etc.).
6. To trigger redeployments from your local machine, run:
   ```bash
   cd gateway/
   ./deploy-render.sh
   ```

#### Option B: Deploy to Google Cloud Run

To build and deploy the unified gateway to Google Cloud Run using Google Cloud Build, run:

```bash
export GCP_PROJECT_ID=your-gcp-project-id
cd gateway/
./deploy-cloudrun.sh
```

Ensure that you have set up your Google Cloud project secrets (`androclaw-bridge-secret`, `gmail-access-token`, `gmail-refresh-token`, etc.) in GCP Secret Manager prior to deployment.

#### External callback URLs (once deployed):
- **Vonage Inbound SMS**: `https://your-service-url/vonage/webhooks/inbound-sms`
- **Vonage Answer**: `https://your-service-url/vonage/webhooks/answer`
- **Vonage Events**: `https://your-service-url/vonage/webhooks/event`
- **WhatsApp Inbound**: `https://your-service-url/wa/webhook/inbound`
- **WhatsApp Status**: `https://your-service-url/wa/webhook/status`
- **AgentPhone Webhook**: `https://your-service-url/agentphone/webhook/agentphone`
- **M-Pesa STK Callback**: `https://your-service-url/mpesa/stk/callback`

### 4. Authenticate Telegram

To use Telegram tools, you need a Telegram session string.

1. Navigate to the local unified gateway folder:
   ```bash
   cd gateway/unified/
   ```
2. Install dependencies and run the session helper:
   ```bash
   pip install telethon
   python auth_session.py
   ```
3. Follow the prompts to log in. Copy the resulting session string.
4. Add it to your deployment (Render environment variables or GCP Secret Manager) as the `TG_SESSION_STRING` secret/environment variable.

### 5. Build and run

```bash
./gradlew :app:assembleDebug
# Install on device or emulator
adb install app/build/outputs/apk/debug/app-debug.apk
```

The first launch shows the onboarding wizard to collect API keys. These are stored in DataStore (encrypted in release builds) and never leave the device except to call the configured APIs.

---

## Running tests

```bash
# Unit tests (no API keys needed)
./gradlew :app:test

# Integration tests (requires env vars)
ANTHROPIC_API_KEY=sk-ant-... \
MPESA_CONSUMER_KEY=... \
./gradlew :app:test --tests "*IntegrationTest*"
```

---

## Architecture

```
┌─────────────────────────────────────┐
│         Android UI (Compose)        │
│  Chat · Onboarding · Settings       │
└────────────────┬────────────────────┘
                 │ StateFlow / SharedFlow
┌────────────────▼────────────────────┐
│         Koog 1.0 Agent Runtime      │
│  Graph orchestrator · Memory · MCP  │
│  LiteRT on-device (small tasks)     │
└──────┬─────────────────────┬────────┘
       │ tool calls           │ MCP SSE
┌──────▼──────┐    ┌──────────▼──────────────┐
│ Local tools │    │ Render (Unified GW)     │
│  M-Pesa     │    │  Telegram bridge         │
│  SMS/Call   │    │  Email gateway           │
│  WhatsApp   │    │  Vonage MCP              │
│  Telegram   │    │  WhatsApp webhook        │
│  Memory     │    └─────────────────────────┘
└─────────────┘
       │
┌──────▼──────────────────────────────┐
│         LLM Backend                 │
│  Claude (Anthropic) via Koog        │
└─────────────────────────────────────┘
```

---

## Contributing

This project targets the East African market with first-class Kiswahili support, M-Pesa integration, and infrastructure optimised for African latency. PRs that improve any of these are especially welcome.

---

## License

MIT
