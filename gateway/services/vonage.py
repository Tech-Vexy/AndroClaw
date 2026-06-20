import base64
import json
import logging
import os
import time
import uuid
from datetime import datetime, timezone
from typing import Optional

import httpx
import jwt as pyjwt
from fastapi import APIRouter, Header, HTTPException, Query, Request
from fastapi.responses import JSONResponse, StreamingResponse
from mcp.server.fastmcp import FastMCP
from mcp.server.sse import SseServerTransport

log = logging.getLogger("vonage-mcp")

VONAGE_API_KEY     = os.environ["VONAGE_API_KEY"]
VONAGE_API_SECRET  = os.environ["VONAGE_API_SECRET"]
VONAGE_APP_ID      = os.environ.get("VONAGE_APP_ID", "")
VONAGE_PRIVATE_KEY = os.environ.get("VONAGE_PRIVATE_KEY", "")
VONAGE_FROM        = os.environ["VONAGE_FROM_NUMBER"]
BRIDGE_SECRET      = os.environ["BRIDGE_SECRET"]
FIREBASE_PROJECT   = os.environ.get("FIREBASE_PROJECT_ID", "")
GATEWAY_BASE_URL   = os.environ.get("GATEWAY_BASE_URL", "")

VONAGE_SMS_URL    = "https://rest.nexmo.com/sms/json"
VONAGE_VOICE_URL  = "https://api.nexmo.com/v1/calls"
VONAGE_VOICE_BASE = "https://api.nexmo.com/v1"

db = None
if FIREBASE_PROJECT:
    try:
        import firebase_admin
        from google.cloud import firestore
        if not firebase_admin._apps:
            firebase_admin.initialize_app()
        db = firestore.Client(project=FIREBASE_PROJECT)
    except Exception as e:
        log.warning(f"Firestore init failed: {e}")

# ── Helpers ───────────────────────────────────────────────────────────────────

def _jwt() -> str:
    if not VONAGE_APP_ID or not VONAGE_PRIVATE_KEY:
        raise RuntimeError("VONAGE_APP_ID and VONAGE_PRIVATE_KEY must be set")
    now = int(time.time())
    return pyjwt.encode(
        {"application_id": VONAGE_APP_ID, "iat": now, "exp": now + 3600, "jti": str(uuid.uuid4())},
        VONAGE_PRIVATE_KEY, algorithm="RS256",
    )

def _store_sms(sms: dict):
    if not db:
        return
    try:
        db.collection("openclaw").document("default") \
          .collection("sms_inbox").document(sms["message_id"]).set(sms, merge=True)
    except Exception as e:
        log.warning(f"Firestore SMS store failed: {e}")

def _store_call(event: dict):
    if not db:
        return
    try:
        call_id = event.get("uuid", str(uuid.uuid4()))
        db.collection("openclaw").document("default") \
          .collection("call_events").document(call_id).set(event, merge=True)
    except Exception as e:
        log.warning(f"Firestore call event store failed: {e}")

def _build_outbound_ncco(message, language="sw-KE", voice_name="Salli", follow_up_question="", call_uuid=None):
    ncco = [{"action": "talk", "text": message, "language": language,
              "voiceName": voice_name, "bargeIn": bool(follow_up_question)}]
    if follow_up_question:
        ncco.append({"action": "input", "type": ["dtmf"],
                     "dtmf": {"maxDigits": 1, "submitOnHash": False, "timeOut": 5},
                     "eventUrl": [f"{GATEWAY_BASE_URL}/vonage/webhooks/dtmf-input"],
                     "eventMethod": "POST"})
    return ncco

def _build_inbound_ncco(caller: str, language: str = "sw-KE") -> list:
    return [
        {"action": "talk", "text": "Karibu OpenClaw. Tafadhali acha ujumbe baada ya mlio.",
         "language": language, "voiceName": "Salli", "bargeIn": False},
        {"action": "record", "format": "mp3", "endOnSilence": 3, "beepStart": True,
         "eventUrl": [f"{GATEWAY_BASE_URL}/vonage/webhooks/recording"], "eventMethod": "POST"},
        {"action": "talk", "text": "Asante. Tutawasiliana nawe hivi karibuni.",
         "language": language, "voiceName": "Salli"},
    ]

# ── MCP tools ─────────────────────────────────────────────────────────────────

mcp = FastMCP("OpenClaw Telephony MCP")
sse_transport = SseServerTransport("/vonage/messages")

@mcp.tool()
async def vonage_send_sms(to: str, text: str, from_number: str = "") -> dict:
    """Send SMS via Vonage. to: E.164 e.g. +254712345678."""
    sender = from_number or VONAGE_FROM
    async with httpx.AsyncClient() as c:
        r = await c.post(VONAGE_SMS_URL, json={
            "api_key": VONAGE_API_KEY, "api_secret": VONAGE_API_SECRET,
            "from": sender.lstrip("+"), "to": to.lstrip("+"), "text": text,
            "type": "unicode" if any(ord(ch) > 127 for ch in text) else "text",
        }, timeout=15)
    msgs = r.json().get("messages", [{}])
    msg  = msgs[0]
    if msg.get("status") != "0":
        raise ValueError(f"SMS send failed: {msg.get('error-text', 'unknown')}")
    return {"message_id": msg.get("message-id"), "status": "sent", "to": to,
            "segments": len(msgs), "remaining_balance": msg.get("remaining-balance"),
            "cost": msg.get("message-price")}

@mcp.tool()
async def vonage_read_sms_inbox(limit: int = 20, from_number: str = "") -> dict:
    """Read recent inbound SMS from Firestore cache."""
    if not db:
        return {"messages": [], "error": "Firestore not configured"}
    try:
        from google.cloud import firestore
        query = db.collection("openclaw").document("default").collection("sms_inbox") \
                  .order_by("timestamp", direction=firestore.Query.DESCENDING).limit(min(limit, 100))
        if from_number:
            query = query.where("from", "==", from_number.lstrip("+"))
        docs = list(query.stream())
        return {"messages": [d.to_dict() for d in docs], "count": len(docs)}
    except Exception as e:
        return {"messages": [], "error": str(e)}

@mcp.tool()
async def vonage_make_call(to: str, tts_message: str, language: str = "sw-KE",
                           voice_name: str = "Salli", follow_up_question: str = "") -> dict:
    """Place an outbound call with TTS. to: E.164 e.g. +254712345678."""
    ncco = _build_outbound_ncco(tts_message, language, voice_name, follow_up_question)
    async with httpx.AsyncClient() as c:
        r = await c.post(VONAGE_VOICE_URL, headers={"Authorization": f"Bearer {_jwt()}"},
                         json={"to": [{"type": "phone", "number": to.lstrip("+")}],
                               "from": {"type": "phone", "number": VONAGE_FROM.lstrip("+")},
                               "ncco": ncco, "event_url": [f"{GATEWAY_BASE_URL}/vonage/webhooks/event"],
                               "answer_url": []}, timeout=15)
    data = r.json()
    if r.status_code not in (200, 201):
        raise ValueError(f"Call failed {r.status_code}: {data}")
    return {"call_uuid": data.get("uuid"), "status": data.get("status"),
            "to": to, "conversation": data.get("conversation_uuid")}

@mcp.tool()
async def vonage_get_call_status(call_uuid: str) -> dict:
    """Get current status of a call by UUID."""
    async with httpx.AsyncClient() as c:
        r = await c.get(f"{VONAGE_VOICE_URL}/{call_uuid}", headers={"Authorization": f"Bearer {_jwt()}"}, timeout=10)
    return r.json()

@mcp.tool()
async def vonage_end_call(call_uuid: str) -> dict:
    """Hang up an active call."""
    async with httpx.AsyncClient() as c:
        r = await c.put(f"{VONAGE_VOICE_URL}/{call_uuid}", headers={"Authorization": f"Bearer {_jwt()}"},
                        json={"action": "hangup"}, timeout=10)
    return {"call_uuid": call_uuid, "action": "hangup", "status_code": r.status_code}

@mcp.tool()
async def vonage_transfer_call(call_uuid: str, to_number: str = "", ncco_message: str = "") -> dict:
    """Transfer an active call to another number or inject a new NCCO."""
    if to_number:
        ncco = [{"action": "connect", "endpoint": [{"type": "phone", "number": to_number.lstrip("+")}]}]
    elif ncco_message:
        ncco = [{"action": "talk", "text": ncco_message, "bargeIn": False}]
    else:
        raise ValueError("Provide either to_number or ncco_message")
    async with httpx.AsyncClient() as c:
        r = await c.put(f"{VONAGE_VOICE_URL}/{call_uuid}", headers={"Authorization": f"Bearer {_jwt()}"},
                        json={"action": "transfer", "destination": {"type": "ncco", "ncco": ncco}}, timeout=10)
    return {"call_uuid": call_uuid, "action": "transfer", "status_code": r.status_code}

@mcp.tool()
async def vonage_send_dtmf(call_uuid: str, digits: str) -> dict:
    """Send DTMF tones into an active call."""
    async with httpx.AsyncClient() as c:
        r = await c.put(f"{VONAGE_VOICE_BASE}/calls/{call_uuid}/dtmf",
                        headers={"Authorization": f"Bearer {_jwt()}"},
                        json={"digits": digits}, timeout=10)
    return {"call_uuid": call_uuid, "digits": digits, "status_code": r.status_code}

@mcp.tool()
async def vonage_list_recent_calls(status: str = "", limit: int = 20) -> dict:
    """List recent calls from Vonage call log."""
    params: dict = {"page_size": min(limit, 100)}
    if status:
        params["status"] = status
    async with httpx.AsyncClient() as c:
        r = await c.get(VONAGE_VOICE_URL, headers={"Authorization": f"Bearer {_jwt()}"},
                        params=params, timeout=10)
    calls = r.json().get("_embedded", {}).get("calls", [])
    return {"calls": [{"uuid": c.get("uuid"), "to": c.get("to",[{}])[0].get("number"),
                       "from": c.get("from",{}).get("number"), "status": c.get("status"),
                       "duration": c.get("duration"), "start_time": c.get("start_time"),
                       "end_time": c.get("end_time"), "direction": c.get("direction")}
                      for c in calls], "count": len(calls)}

# ── Router ────────────────────────────────────────────────────────────────────

router = APIRouter(tags=["vonage"])

@router.get("/health")
async def health():
    return {"status": "ok", "jwt_configured": bool(VONAGE_APP_ID)}

@router.get("/sse")
async def sse_endpoint(x_bridge_secret: str = Header(...)):
    if x_bridge_secret != BRIDGE_SECRET:
        raise HTTPException(status_code=401)

    async def gen():
        async with mcp.run_sse_async(sse_transport) as session:
            async for event in session:
                yield event

    return StreamingResponse(gen(), media_type="text/event-stream")

@router.post("/messages")
async def handle_mcp_message(request: Request):
    return await sse_transport.handle_post_message(request)

@router.get("/webhooks/answer")
@router.post("/webhooks/answer")
async def answer_webhook(request: Request):
    params  = dict(request.query_params)
    body    = {}
    try:
        body = await request.json()
    except Exception:
        pass
    caller  = params.get("from") or body.get("from", "unknown")
    call_id = params.get("uuid") or body.get("uuid", "")
    log.info(f"Inbound call from {caller} uuid={call_id}")
    _store_call({"uuid": call_id, "from": caller, "direction": "inbound",
                 "status": "answered", "timestamp": datetime.now(timezone.utc).isoformat()})
    if db:
        try:
            import firebase_admin.messaging as fcm
            snap      = db.collection("openclaw").document("default").get()
            fcm_token = snap.to_dict().get("fcm_token", "") if snap.exists else ""
            if fcm_token:
                fcm.send(fcm.Message(data={"type": "call_incoming", "from": caller, "call_uuid": call_id},
                                     token=fcm_token, android=fcm.AndroidConfig(priority="high")))
        except Exception as e:
            log.warning(f"FCM notify failed: {e}")
    return JSONResponse(content=_build_inbound_ncco(caller))

@router.post("/webhooks/event")
async def event_webhook(request: Request):
    try:
        event = await request.json()
    except Exception:
        event = dict(request.query_params)
    log.info(f"Call event: uuid={event.get('uuid')} status={event.get('status')}")
    _store_call({**event, "received_at": datetime.now(timezone.utc).isoformat()})
    return {"status": "ok"}

@router.post("/webhooks/dtmf-input")
async def dtmf_webhook(request: Request):
    try:
        event = await request.json()
    except Exception:
        event = {}
    call_id = event.get("uuid", "")
    dtmf    = event.get("dtmf", {}).get("digits", "")
    _store_call({"uuid": call_id, "dtmf": dtmf, "type": "dtmf_input",
                 "received_at": datetime.now(timezone.utc).isoformat()})
    responses = {
        "1": "Umekubali. Asante.",
        "2": "Umeghairi. Kuaga.",
    }
    text = responses.get(dtmf, "Samahani, sikuelewa. Tafadhali jaribu tena.")
    return JSONResponse(content=[{"action": "talk", "text": text, "language": "sw-KE"}])

@router.post("/webhooks/recording")
async def recording_webhook(request: Request):
    try:
        event = await request.json()
    except Exception:
        event = {}
    call_id       = event.get("uuid", "")
    recording_url = event.get("recording_url", "")
    if db:
        try:
            db.collection("openclaw").document("default").collection("voicemails").document(call_id).set({
                "call_uuid": call_id, "recording_url": recording_url,
                "duration": event.get("recording_duration"), "from": event.get("start_time"),
                "received_at": datetime.now(timezone.utc).isoformat(),
            }, merge=True)
        except Exception as e:
            log.warning(f"Voicemail store failed: {e}")
    return {"status": "ok"}

@router.post("/webhooks/inbound-sms")
async def inbound_sms_webhook(request: Request):
    try:
        data = await request.json()
    except Exception:
        data = dict(request.query_params)
    from_number = data.get("msisdn", data.get("from", "unknown"))
    message_id  = data.get("messageId", str(uuid.uuid4()))
    text        = data.get("text", "")
    log.info(f"Inbound SMS from {from_number}: {text[:60]}")
    _store_sms({"message_id": message_id, "from": from_number, "text": text,
                "timestamp": data.get("message-timestamp", datetime.now(timezone.utc).isoformat()),
                "received_at": datetime.now(timezone.utc).isoformat(),
                "is_read": False, "auto_replied": False})
    import asyncio
    asyncio.create_task(_auto_reply(from_number, text, message_id))
    return {"status": "ok"}

async def _auto_reply(from_number: str, text: str, message_id: str):
    lower = text.lower().strip()
    if any(kw in lower for kw in ["mpesa", "m-pesa", "confirmed", "ksh"]):
        reply = "Tumeipokea taarifa yako ya M-Pesa. OpenClaw imesajili."
    elif lower in ("stop", "unsubscribe", "acha", "simama"):
        reply = "Umefutwa. Tuma 'START' kuanza tena."
    elif lower in ("help", "info", "msaada"):
        reply = "OpenClaw AI Assistant. Tuma ujumbe wako na tutakusaidia."
    else:
        return
    try:
        async with httpx.AsyncClient() as c:
            await c.post(VONAGE_SMS_URL, json={
                "api_key": VONAGE_API_KEY, "api_secret": VONAGE_API_SECRET,
                "from": VONAGE_FROM.lstrip("+"), "to": from_number.lstrip("+"), "text": reply,
            }, timeout=10)
        if db:
            db.collection("openclaw").document("default").collection("sms_inbox") \
              .document(message_id).set({"auto_replied": True, "auto_reply_text": reply}, merge=True)
    except Exception as e:
        log.error(f"Auto-reply failed: {e}")

@router.post("/webhooks/status")
async def sms_status_webhook(request: Request):
    try:
        data = await request.json()
    except Exception:
        data = dict(request.query_params)
    message_id = data.get("messageId", "")
    status_val = data.get("status", "")
    log.info(f"SMS delivery: {message_id} → {status_val}")
    if db and message_id:
        try:
            db.collection("openclaw").document("default").collection("sms_inbox") \
              .document(message_id).set({"delivery_status": status_val}, merge=True)
        except Exception:
            pass
    return {"status": "ok"}
