import asyncio
import logging
import os
from contextlib import asynccontextmanager
from typing import Optional

from fastapi import APIRouter, FastAPI, HTTPException, Header, Query
from fastapi.responses import JSONResponse
from pydantic import BaseModel
from telethon import TelegramClient
from telethon.tl.types import User, Chat, Channel

log = logging.getLogger("tg-bridge")

TG_API_ID      = int(os.environ["TG_API_ID"])
TG_API_HASH    = os.environ["TG_API_HASH"]
TG_SESSION_STR = os.environ.get("TG_SESSION_STRING", "")
BRIDGE_SECRET  = os.environ["BRIDGE_SECRET"]

client: TelegramClient = None

@asynccontextmanager
async def lifespan(app: FastAPI):
    global client
    from telethon.sessions import StringSession
    session = StringSession(TG_SESSION_STR) if TG_SESSION_STR else "openclaw"
    client = TelegramClient(session, TG_API_ID, TG_API_HASH)
    await client.connect()
    if not await client.is_user_authorized():
        log.error("Telegram not authorised — run auth_session.py")
    else:
        me = await client.get_me()
        log.info(f"Telegram connected as {me.first_name} (@{me.username})")
    yield
    await client.disconnect()

# Keep a FastAPI instance so lifespan can be passed to main.py
app = FastAPI()

def verify(x_openclaw_secret: str = Header(...)):
    if x_openclaw_secret != BRIDGE_SECRET:
        raise HTTPException(status_code=401, detail="Invalid bridge secret")

class SendRequest(BaseModel):
    chat_id: str
    text: str
    parse_mode: str = "markdown"

class ForwardRequest(BaseModel):
    from_chat_id: str
    to_chat_id: str
    message_id: int

def _resolve(chat_id: str):
    try:
        return int(chat_id)
    except ValueError:
        return chat_id

def _peer(entity) -> dict:
    if isinstance(entity, User):
        return {"id": entity.id, "title": f"{entity.first_name or ''} {entity.last_name or ''}".strip(), "type": "user"}
    if isinstance(entity, Channel):
        return {"id": entity.id, "title": entity.title, "type": "channel"}
    if isinstance(entity, Chat):
        return {"id": entity.id, "title": entity.title, "type": "group"}
    return {"id": 0, "title": "Unknown", "type": "unknown"}

router = APIRouter(tags=["telegram"])

@router.get("/health")
async def health():
    return {"status": "ok" if (client and client.is_connected()) else "disconnected"}

@router.post("/send")
async def send_message(body: SendRequest, x_openclaw_secret: str = Header(...)):
    verify(x_openclaw_secret)
    try:
        parse = "md" if body.parse_mode == "markdown" else "html"
        msg = await client.send_message(_resolve(body.chat_id), body.text, parse_mode=parse)
        return {"message_id": msg.id, "chat_id": body.chat_id, "status": "sent"}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@router.get("/messages")
async def get_messages(
    chat_id: str = Query(...),
    limit: int = Query(20, le=100),
    offset_date: Optional[int] = Query(None),
    x_openclaw_secret: str = Header(...),
):
    verify(x_openclaw_secret)
    try:
        from datetime import datetime, timezone
        offset_dt = datetime.fromtimestamp(offset_date, tz=timezone.utc) if offset_date else None
        messages = await client.get_messages(_resolve(chat_id), limit=limit, offset_date=offset_dt)
        return {"messages": [
            {"id": m.id, "chat_id": chat_id,
             "from": (m.sender.username or str(m.sender_id)) if m.sender else "unknown",
             "text": m.text or "", "date": int(m.date.timestamp()),
             "is_reply": m.is_reply,
             "reply_to_id": m.reply_to.reply_to_msg_id if m.reply_to else None}
            for m in messages
        ]}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@router.get("/dialogs")
async def list_dialogs(
    limit: int = Query(20, le=100),
    unread_only: bool = Query(False),
    x_openclaw_secret: str = Header(...),
):
    verify(x_openclaw_secret)
    try:
        dialogs = await client.get_dialogs(limit=limit)
        result = []
        for d in dialogs:
            if unread_only and d.unread_count == 0:
                continue
            result.append({
                "id": str(d.id), "title": d.title or d.name or "",
                "type": "channel" if d.is_channel else "group" if d.is_group else "user",
                "unread_count": d.unread_count,
                "last_message": d.message.text[:100] if d.message and d.message.text else "",
                "date": int(d.date.timestamp()) if d.date else 0,
            })
        return {"chats": result}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@router.post("/forward")
async def forward_message(body: ForwardRequest, x_openclaw_secret: str = Header(...)):
    verify(x_openclaw_secret)
    try:
        msgs = await client.forward_messages(_resolve(body.to_chat_id), body.message_id, _resolve(body.from_chat_id))
        fwd = msgs[0] if isinstance(msgs, list) else msgs
        return {"message_id": fwd.id, "chat_id": body.to_chat_id, "status": "forwarded"}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
