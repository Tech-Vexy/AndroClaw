import base64
import logging
import os
from email.mime.multipart import MIMEMultipart
from email.mime.text import MIMEText

from fastapi import APIRouter, Header, HTTPException, Query
from google.auth.transport.requests import Request
from google.oauth2.credentials import Credentials
from googleapiclient.discovery import build
from pydantic import BaseModel

log = logging.getLogger("email-gateway")

BRIDGE_SECRET        = os.environ["BRIDGE_SECRET"]
GMAIL_TOKEN          = os.environ["GMAIL_ACCESS_TOKEN"]
GMAIL_REFRESH        = os.environ["GMAIL_REFRESH_TOKEN"]
GMAIL_CLIENT_ID      = os.environ["GMAIL_CLIENT_ID"]
GMAIL_CLIENT_SECRET  = os.environ["GMAIL_CLIENT_SECRET"]

def verify(x_openclaw_secret: str = Header(...)):
    if x_openclaw_secret != BRIDGE_SECRET:
        raise HTTPException(status_code=401, detail="Invalid bridge secret")

def get_gmail_service():
    creds = Credentials(
        token=GMAIL_TOKEN, refresh_token=GMAIL_REFRESH,
        token_uri="https://oauth2.googleapis.com/token",
        client_id=GMAIL_CLIENT_ID, client_secret=GMAIL_CLIENT_SECRET,
        scopes=["https://www.googleapis.com/auth/gmail.modify"],
    )
    if creds.expired and creds.refresh_token:
        creds.refresh(Request())
    return build("gmail", "v1", credentials=creds, cache_discovery=False)

def _parse_headers(headers: list) -> dict:
    return {h["name"].lower(): h["value"] for h in headers}

def _extract_body(payload: dict, prefer: str = "plain") -> str:
    mime = payload.get("mimeType", "")
    if mime == f"text/{prefer}":
        data = payload.get("body", {}).get("data", "")
        return base64.urlsafe_b64decode(data + "==").decode("utf-8", errors="replace")
    for part in payload.get("parts", []):
        result = _extract_body(part, prefer)
        if result:
            return result
    return ""

router = APIRouter(tags=["email"])

@router.get("/health")
async def health():
    return {"status": "ok"}

@router.get("/list")
async def list_messages(
    max_results: int = Query(10, le=50),
    labels: str = Query("INBOX"),
    unread_only: bool = Query(False),
    x_openclaw_secret: str = Header(...),
):
    verify(x_openclaw_secret)
    try:
        svc = get_gmail_service()
        label_ids = [l.strip() for l in labels.split(",")]
        if unread_only:
            label_ids.append("UNREAD")
        result = svc.users().messages().list(userId="me", labelIds=label_ids, maxResults=max_results).execute()
        messages = []
        for m in result.get("messages", []):
            msg = svc.users().messages().get(userId="me", id=m["id"], format="metadata",
                                             metadataHeaders=["From","To","Subject","Date"]).execute()
            hdrs = _parse_headers(msg.get("payload", {}).get("headers", []))
            messages.append({
                "id": msg["id"], "from": hdrs.get("from",""), "to": hdrs.get("to",""),
                "subject": hdrs.get("subject","(no subject)"), "snippet": msg.get("snippet",""),
                "date": hdrs.get("date",""), "is_read": "UNREAD" not in msg.get("labelIds",[]),
            })
        return {"messages": messages, "total_count": result.get("resultSizeEstimate", 0)}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@router.get("/message/{message_id}")
async def get_message(message_id: str, format: str = Query("plain"), x_openclaw_secret: str = Header(...)):
    verify(x_openclaw_secret)
    try:
        svc  = get_gmail_service()
        msg  = svc.users().messages().get(userId="me", id=message_id, format="full").execute()
        body = _extract_body(msg.get("payload", {}), prefer=format)
        attachments = [p["filename"] for p in msg.get("payload", {}).get("parts", []) if p.get("filename")]
        return {"id": message_id, "body": body, "attachments": attachments}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

class SendRequest(BaseModel):
    to: str
    subject: str
    body: str
    cc: str = ""
    reply_to_id: str = ""

@router.post("/send")
async def send_email(req: SendRequest, x_openclaw_secret: str = Header(...)):
    verify(x_openclaw_secret)
    try:
        svc = get_gmail_service()
        mime = MIMEMultipart()
        mime["to"] = req.to
        mime["subject"] = req.subject
        if req.cc:
            mime["cc"] = req.cc
        mime.attach(MIMEText(req.body, "plain"))
        raw = base64.urlsafe_b64encode(mime.as_bytes()).decode()
        body = {"raw": raw}
        if req.reply_to_id:
            orig = svc.users().messages().get(userId="me", id=req.reply_to_id,
                                               metadataHeaders=["References","Message-ID"],
                                               format="metadata").execute()
            body["threadId"] = orig.get("threadId")
        sent = svc.users().messages().send(userId="me", body=body).execute()
        return {"id": sent["id"], "status": "sent", "to": req.to}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@router.get("/search")
async def search_emails(
    q: str = Query(...),
    max_results: int = Query(10, le=50),
    x_openclaw_secret: str = Header(...),
):
    verify(x_openclaw_secret)
    try:
        svc    = get_gmail_service()
        result = svc.users().messages().list(userId="me", q=q, maxResults=max_results).execute()
        messages = []
        for m in result.get("messages", []):
            msg  = svc.users().messages().get(userId="me", id=m["id"], format="metadata",
                                              metadataHeaders=["From","Subject","Date"]).execute()
            hdrs = _parse_headers(msg.get("payload", {}).get("headers", []))
            messages.append({
                "id": msg["id"], "from": hdrs.get("from",""),
                "subject": hdrs.get("subject","(no subject)"),
                "snippet": msg.get("snippet",""), "date": hdrs.get("date",""),
                "is_read": "UNREAD" not in msg.get("labelIds",[]),
            })
        return {"messages": messages, "total_count": result.get("resultSizeEstimate", 0)}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
