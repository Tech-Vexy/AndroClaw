"""
Androclaw Unified Gateway
=========================
Single Render service that hosts all sub-gateways under one URL.

Route prefixes:
  /tg/…            — Telegram MTProto bridge
  /email/…         — Gmail proxy
  /mpesa/…         — M-Pesa MCP (SSE + Daraja callbacks)
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

# ── Telegram lifespan (needs connect/disconnect) ──────────────────────────────

from gateway.services.telegram import app as _tg_app, lifespan as _tg_lifespan  # noqa: E402

@asynccontextmanager
async def lifespan(app: FastAPI):
    async with _tg_lifespan(_tg_app):
        yield

# ── Main app ──────────────────────────────────────────────────────────────────

app = FastAPI(title="Androclaw Unified Gateway", lifespan=lifespan)

# ── Mount sub-routers ─────────────────────────────────────────────────────────

from gateway.services.telegram       import router as tg_router       # noqa: E402
from gateway.services.email_gw       import router as email_router     # noqa: E402
from gateway.services.mpesa          import router as mpesa_router     # noqa: E402
from gateway.services.vonage         import router as vonage_router    # noqa: E402
from gateway.services.whatsapp       import router as wa_router        # noqa: E402
from gateway.services.agentphone     import router as agentphone_router  # noqa: E402

app.include_router(tg_router,         prefix="/tg")
app.include_router(email_router,      prefix="/email")
app.include_router(mpesa_router,      prefix="/mpesa")
app.include_router(vonage_router,     prefix="/vonage")
app.include_router(wa_router,         prefix="/wa")
app.include_router(agentphone_router, prefix="/agentphone")

@app.get("/health")
async def health():
    return {"status": "ok", "service": "androclaw-unified-gateway"}
