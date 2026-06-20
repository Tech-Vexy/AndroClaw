import logging
import os
from typing import Optional

import httpx
from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel

log = logging.getLogger("agentphone-webhook")

GOOGLE_GENAI_API_KEY = os.environ.get("GOOGLE_GENAI_API_KEY", "")
BRIDGE_SECRET        = os.environ.get("BRIDGE_SECRET", "")
AGENTPHONE_API_KEY   = os.environ.get("AGENTPHONE_API_KEY", "")

class AgentPhonePayload(BaseModel):
    event: str
    channel: str
    timestamp: Optional[str]  = None
    agentId: Optional[str]    = None
    data: dict
    recentHistory: list       = []
    conversationState: Optional[dict] = None

def _context(payload: AgentPhonePayload) -> str:
    data    = payload.data
    from_   = data.get("from", "unknown")
    to_     = data.get("to", "unknown")
    message = data.get("message") or data.get("transcript", "")
    conv_id = data.get("conversationId", "")
    ctx     = f"[AgentPhone {payload.channel.upper()}]\nFrom: {from_}  →  To: {to_}\n"
    if conv_id:
        ctx += f"ConversationID: {conv_id}\n"
    history = payload.recentHistory[-5:] if payload.recentHistory else []
    if history:
        ctx += "\nRecent history:\n"
        for h in history:
            direction = "←" if h.get("direction") == "inbound" else "→"
            ctx += f"  {direction} {h.get('content', '')}\n"
    ctx += f"\nCurrent message: {message}"
    return ctx

async def _gemini(context: str, system_prompt: str) -> str:
    if not GOOGLE_GENAI_API_KEY:
        return "Androclaw is not configured. Please set GOOGLE_GENAI_API_KEY."
    try:
        async with httpx.AsyncClient(timeout=25) as c:
            r = await c.post(
                f"https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key={GOOGLE_GENAI_API_KEY}",
                headers={"Content-Type": "application/json"},
                json={"system_instruction": {"parts": [{"text": system_prompt}]},
                      "contents": [{"role": "user", "parts": [{"text": context}]}],
                      "generationConfig": {"maxOutputTokens": 300}},
            )
            r.raise_for_status()
            return r.json()["candidates"][0]["content"]["parts"][0]["text"].strip()
    except Exception as e:
        log.error(f"Gemini error: {e}")
        return "Samahani, kuna tatizo. Tafadhali jaribu tena."

router = APIRouter(tags=["agentphone"])

@router.get("/health")
async def health():
    return {"status": "ok"}

@router.post("/webhook/agentphone")
async def receive_event(request: Request):
    try:
        body = await request.json()
    except Exception:
        raise HTTPException(status_code=400, detail="Invalid JSON")

    payload = AgentPhonePayload(**body)
    if payload.event != "agent.message":
        return JSONResponse({"status": "acknowledged"})

    message = payload.data.get("message") or payload.data.get("transcript", "")
    if not message.strip():
        return JSONResponse({"response": ""})

    if payload.channel == "voice":
        system_prompt = (
            "You are Androclaw, a personal AI assistant answering a phone call. "
            "Keep responses SHORT — under 2 sentences. No markdown, no lists. "
            "Language: detect from the caller's message (Kiswahili or English)."
        )
    else:
        system_prompt = (
            "You are Androclaw, a personal AI assistant replying to an SMS. "
            "Keep responses concise — under 160 characters when possible. No markdown. "
            "Language: match the sender's language (Kiswahili or English)."
        )

    response = await _gemini(_context(payload), system_prompt)
    log.info(f"AgentPhone reply to {payload.data.get('from','?')}: {response[:60]}…")
    return JSONResponse({"response": response})

@router.post("/webhook/agentphone/status")
async def receive_status(request: Request):
    try:
        body = await request.json()
        log.info(f"AgentPhone status: {str(body)[:200]}")
    except Exception:
        pass
    return JSONResponse({"status": "ok"})
