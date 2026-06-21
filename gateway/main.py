"""
Androclaw Unified Gateway
=========================
Single Render service that hosts all sub-gateways under one URL.

Route prefixes:
  /vonage/…        — Vonage telephony MCP (SSE + webhooks)
  /wa/…            — WhatsApp webhook (Vonage Messages)
  /agentphone/…    — AgentPhone webhook

All env vars from the individual services are still required;
they are now set on this single Render service.
"""

import logging
from contextlib import asynccontextmanager

from dotenv import load_dotenv
load_dotenv()

from fastapi import FastAPI

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("unified-gateway")

# ── Main app ──────────────────────────────────────────────────────────────────

app = FastAPI(title="Androclaw Unified Gateway")

# ── Mount sub-routers ─────────────────────────────────────────────────────────

from gateway.services.vonage         import router as vonage_router    # noqa: E402
from gateway.services.whatsapp       import router as wa_router        # noqa: E402
from gateway.services.agentphone     import router as agentphone_router  # noqa: E402

app.include_router(vonage_router,     prefix="/vonage")
app.include_router(wa_router,         prefix="/wa")
app.include_router(agentphone_router, prefix="/agentphone")

@app.get("/health")
async def health():
    return {"status": "ok", "service": "androclaw-unified-gateway"}
