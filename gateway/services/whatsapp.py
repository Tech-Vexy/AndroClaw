import hashlib
import hmac
import json
import logging
import os
from datetime import datetime, timezone

import firebase_admin
from fastapi import APIRouter, HTTPException, Query, Request
from firebase_admin import messaging
from google.cloud import firestore

log = logging.getLogger("wa-webhook")

VONAGE_API_SECRET = os.environ.get("VONAGE_API_SECRET", "")
BRIDGE_SECRET     = os.environ.get("BRIDGE_SECRET", "")
FIREBASE_PROJECT  = os.environ.get("FIREBASE_PROJECT_ID", "")
DEVICE_ID         = os.environ.get("DEVICE_ID", "default")

db = None
if FIREBASE_PROJECT:
    try:
        if not firebase_admin._apps:
            firebase_admin.initialize_app()
        db = firestore.Client(project=FIREBASE_PROJECT)
    except Exception as e:
        log.error(f"Failed to initialize Firestore: {e}")

# ── Helpers ───────────────────────────────────────────────────────────────────

def _verify_sig(raw_body: bytes, sig_header: str) -> bool:
    if not sig_header:
        return False
    expected = hmac.new(VONAGE_API_SECRET.encode(), raw_body, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, sig_header)

def _parse_text(payload: dict) -> str:
    t = payload.get("message_type", "")
    if t == "text":      return payload.get("text", "")
    if t == "image":     return f"[Image] {payload.get('image',{}).get('caption','')}".strip()
    if t == "audio":     return "[Voice message]"
    if t == "video":     return f"[Video] {payload.get('video',{}).get('caption','')}".strip()
    if t == "file":      return f"[File: {payload.get('file',{}).get('name','file')}]"
    if t == "location":
        loc = payload.get("location", {})
        return f"[Location: {loc.get('name','')} {loc.get('address','')}]".strip()
    if t == "reply":
        r = payload.get("reply", {})
        return f"[Reply: {r.get('title','')} (id={r.get('id','')})]"
    return f"[{t}]"

async def _send_fcm(device_token: str, from_number: str, text: str):
    if not device_token:
        return
    try:
        messaging.send(messaging.Message(
            data={"type": "whatsapp_message", "from_number": from_number, "preview": text[:100]},
            token=device_token,
            android=messaging.AndroidConfig(
                priority="high",
                notification=messaging.AndroidNotification(
                    title=f"WA: {from_number}", body=text[:80], channel_id="androclaw_messages"),
            ),
        ))
    except Exception as e:
        log.error(f"FCM error: {e}")

async def _store(device_id: str, payload: dict, text: str):
    doc = {"id": payload.get("message_uuid",""), "from": payload.get("from",""),
           "body": text, "type": payload.get("message_type","text"),
           "timestamp": int(datetime.now(timezone.utc).timestamp()),
           "is_read": False, "channel": payload.get("channel","whatsapp"),
           "raw": json.dumps(payload), "created_at": datetime.now(timezone.utc).isoformat()}
    db.collection("androclaw").document(device_id).collection("wa_messages") \
      .document(doc["id"] or doc["created_at"]).set(doc, merge=True)

async def _get_fcm_token(device_id: str) -> str:
    try:
        snap = db.collection("androclaw").document(device_id).get()
        return snap.to_dict().get("fcm_token", "") if snap.exists else ""
    except Exception as e:
        log.error(f"Firestore read error: {e}")
        return ""

# ── Router ────────────────────────────────────────────────────────────────────

router = APIRouter(tags=["whatsapp"])

@router.get("/health")
async def health():
    return {"status": "ok"}

@router.post("/webhook/inbound")
async def inbound_webhook(request: Request):
    raw_body  = await request.body()
    signature = request.headers.get("X-Vonage-Signature", "")
    if not _verify_sig(raw_body, signature):
        raise HTTPException(status_code=401, detail="Invalid signature")
    payload     = json.loads(raw_body)
    text        = _parse_text(payload)
    from_number = payload.get("from", "")
    await _store(DEVICE_ID, payload, text)
    fcm_token = await _get_fcm_token(DEVICE_ID)
    await _send_fcm(fcm_token, from_number, text)
    return {"status": "ok"}

@router.post("/webhook/status")
async def status_webhook(request: Request):
    raw_body  = await request.body()
    signature = request.headers.get("X-Vonage-Signature", "")
    if not _verify_sig(raw_body, signature):
        raise HTTPException(status_code=401, detail="Invalid signature")
    payload    = json.loads(raw_body)
    msg_uuid   = payload.get("message_uuid", "")
    status_val = payload.get("status", "")
    if msg_uuid:
        try:
            db.collection("androclaw").document(DEVICE_ID).collection("wa_messages") \
              .document(msg_uuid).set({"delivery_status": status_val}, merge=True)
        except Exception as e:
            log.warning(f"Firestore status update failed: {e}")
    return {"status": "ok"}

@router.post("/register-device")
async def register_device(request: Request):
    if request.headers.get("X-Androclaw-Secret") != BRIDGE_SECRET:
        raise HTTPException(status_code=401)
    body      = await request.json()
    device_id = body.get("device_id")
    fcm_token = body.get("fcm_token")
    if not device_id or not fcm_token:
        raise HTTPException(status_code=400, detail="device_id and fcm_token required")
    db.collection("androclaw").document(device_id).set(
        {"fcm_token": fcm_token, "registered_at": datetime.now(timezone.utc).isoformat()}, merge=True)
    return {"status": "registered"}

@router.get("/messages/{device_id}")
async def get_messages(device_id: str, request: Request, limit: int = 50, unread_only: bool = False):
    if request.query_params.get("secret") != BRIDGE_SECRET:
        raise HTTPException(status_code=401)
    try:
        query = db.collection("androclaw").document(device_id).collection("wa_messages") \
                  .order_by("timestamp", direction=firestore.Query.DESCENDING).limit(min(limit, 200))
        if unread_only:
            query = query.where("is_read", "==", False)
        return {"messages": [d.to_dict() for d in query.stream()]}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
