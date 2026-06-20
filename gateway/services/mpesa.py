import base64
import json
import logging
import os
import time
from datetime import datetime, timezone

import httpx
from fastapi import APIRouter, Header, HTTPException, Request
from fastapi.responses import StreamingResponse
from mcp.server.fastmcp import FastMCP
from mcp.server.sse import SseServerTransport

log = logging.getLogger("mpesa-mcp")

CONSUMER_KEY    = os.environ["MPESA_CONSUMER_KEY"]
CONSUMER_SECRET = os.environ["MPESA_CONSUMER_SECRET"]
SHORTCODE       = os.environ["MPESA_SHORTCODE"]
PASSKEY         = os.environ["MPESA_PASSKEY"]
CALLBACK_BASE   = os.environ.get("MPESA_CALLBACK_BASE_URL", "https://example.com")
MPESA_ENV       = os.environ.get("MPESA_ENV", "sandbox")
BRIDGE_SECRET   = os.environ["BRIDGE_SECRET"]

BASE_URL = "https://sandbox.safaricom.co.ke" if MPESA_ENV == "sandbox" \
           else "https://api.safaricom.co.ke"

FIREBASE_PROJECT = os.environ.get("FIREBASE_PROJECT_ID", "")
DEVICE_ID        = os.environ.get("DEVICE_ID", "default")
db               = None

if FIREBASE_PROJECT:
    try:
        import firebase_admin
        from firebase_admin import firestore as fs_module
        if not firebase_admin._apps:
            firebase_admin.initialize_app()
        db = fs_module.Client(project=FIREBASE_PROJECT)
    except Exception as e:
        log.warning(f"Firestore init failed: {e}")

# ── Helpers ───────────────────────────────────────────────────────────────────

async def _token() -> str:
    creds = base64.b64encode(f"{CONSUMER_KEY}:{CONSUMER_SECRET}".encode()).decode()
    async with httpx.AsyncClient() as c:
        r = await c.get(f"{BASE_URL}/oauth/v1/generate?grant_type=client_credentials",
                        headers={"Authorization": f"Basic {creds}"}, timeout=10)
        r.raise_for_status()
        return r.json()["access_token"]

def _ts() -> str:
    return datetime.now().strftime("%Y%m%d%H%M%S")

def _pwd(ts: str) -> str:
    return base64.b64encode(f"{SHORTCODE}{PASSKEY}{ts}".encode()).decode()

def _cb(path: str) -> str:
    return f"{CALLBACK_BASE}/mpesa/{path}"

def _store(collection: str, doc_id: str, data: dict):
    if not db:
        return
    try:
        db.collection("openclaw").document(DEVICE_ID) \
          .collection(collection).document(doc_id) \
          .set({**data, "received_at": datetime.now(timezone.utc).isoformat()}, merge=True)
    except Exception as e:
        log.error(f"Firestore write error: {e}")

async def _push_fcm(data: dict):
    if not db:
        return
    try:
        from firebase_admin import messaging as fcm
        snap      = db.collection("openclaw").document(DEVICE_ID).get()
        fcm_token = snap.to_dict().get("fcm_token", "") if snap.exists else ""
        if fcm_token:
            fcm.send(fcm.Message(
                data={k: str(v) for k, v in data.items()},
                token=fcm_token,
                android=fcm.AndroidConfig(priority="high"),
            ))
    except Exception as e:
        log.error(f"FCM push error: {e}")

# ── MCP tools ─────────────────────────────────────────────────────────────────

mcp = FastMCP("OpenClaw M-Pesa MCP")
sse = SseServerTransport("/mpesa/messages")

@mcp.tool()
async def mpesa_stk_push(phone_number: str, amount: int, description: str = "Payment") -> dict:
    """Initiate M-Pesa STK push. phone_number: 2547XXXXXXXX, amount in KES."""
    token = await _token()
    ts    = _ts()
    async with httpx.AsyncClient() as c:
        r = await c.post(f"{BASE_URL}/mpesa/stkpush/v1/processrequest",
                         headers={"Authorization": f"Bearer {token}"},
                         json={"BusinessShortCode": SHORTCODE, "Password": _pwd(ts),
                               "Timestamp": ts, "TransactionType": "CustomerPayBillOnline",
                               "Amount": amount, "PartyA": phone_number, "PartyB": SHORTCODE,
                               "PhoneNumber": phone_number, "CallBackURL": _cb("stk/callback"),
                               "AccountReference": "OpenClaw", "TransactionDesc": description[:13]},
                         timeout=15)
    d = r.json()
    return {"checkout_request_id": d.get("CheckoutRequestID"),
            "merchant_request_id": d.get("MerchantRequestID"),
            "response_code": d.get("ResponseCode"),
            "response_description": d.get("ResponseDescription"),
            "customer_message": d.get("CustomerMessage")}

@mcp.tool()
async def mpesa_b2c_send(phone_number: str, amount: int, occasion: str = "") -> dict:
    """Send money B2C to phone_number (2547XXXXXXXX), amount in KES."""
    token = await _token()
    ts    = _ts()
    async with httpx.AsyncClient() as c:
        r = await c.post(f"{BASE_URL}/mpesa/b2c/v3/paymentrequest",
                         headers={"Authorization": f"Bearer {token}"},
                         json={"OriginatorConversationID": f"openclaw-{int(time.time())}",
                               "InitiatorName": "openclaw", "SecurityCredential": _pwd(ts),
                               "CommandID": "BusinessPayment", "Amount": amount,
                               "PartyA": SHORTCODE, "PartyB": phone_number,
                               "Remarks": "OpenClaw B2C",
                               "QueueTimeOutURL": _cb("b2c/timeout"),
                               "ResultURL": _cb("b2c/result"), "Occasion": occasion},
                         timeout=15)
    d = r.json()
    return {"conversation_id": d.get("ConversationID"),
            "originator_conversation_id": d.get("OriginatorConversationID"),
            "response_code": d.get("ResponseCode"),
            "response_description": d.get("ResponseDescription")}

@mcp.tool()
async def mpesa_check_balance() -> dict:
    """Query M-Pesa account balance (async — result delivered to callback)."""
    token = await _token()
    ts    = _ts()
    async with httpx.AsyncClient() as c:
        r = await c.post(f"{BASE_URL}/mpesa/accountbalance/v1/query",
                         headers={"Authorization": f"Bearer {token}"},
                         json={"Initiator": "openclaw", "SecurityCredential": _pwd(ts),
                               "CommandID": "AccountBalance", "PartyA": SHORTCODE,
                               "IdentifierType": "4", "Remarks": "Balance check",
                               "QueueTimeOutURL": _cb("balance/timeout"),
                               "ResultURL": _cb("balance/result")},
                         timeout=15)
    d = r.json()
    return {"conversation_id": d.get("ConversationID"),
            "response_code": d.get("ResponseCode"),
            "response_description": d.get("ResponseDescription"),
            "note": "Balance delivered to callback asynchronously."}

@mcp.tool()
async def mpesa_transaction_status(transaction_id: str) -> dict:
    """Query status of an M-Pesa transaction by ID (e.g. QHX71YZ3C5)."""
    token = await _token()
    ts    = _ts()
    async with httpx.AsyncClient() as c:
        r = await c.post(f"{BASE_URL}/mpesa/transactionstatus/v1/query",
                         headers={"Authorization": f"Bearer {token}"},
                         json={"Initiator": "openclaw", "SecurityCredential": _pwd(ts),
                               "CommandID": "TransactionStatusQuery",
                               "TransactionID": transaction_id, "PartyA": SHORTCODE,
                               "IdentifierType": "4", "ResultURL": _cb("status/result"),
                               "QueueTimeOutURL": _cb("status/timeout"),
                               "Remarks": "Status check", "Occasion": ""},
                         timeout=15)
    d = r.json()
    return {"conversation_id": d.get("ConversationID"),
            "response_code": d.get("ResponseCode"),
            "response_description": d.get("ResponseDescription")}

@mcp.tool()
async def mpesa_reverse_transaction(transaction_id: str, amount: int, remarks: str = "Reversal") -> dict:
    """Reverse a B2B/B2C M-Pesa transaction. Cannot reverse STK push (C2B)."""
    token = await _token()
    ts    = _ts()
    async with httpx.AsyncClient() as c:
        r = await c.post(f"{BASE_URL}/mpesa/reversal/v1/request",
                         headers={"Authorization": f"Bearer {token}"},
                         json={"Initiator": "openclaw", "SecurityCredential": _pwd(ts),
                               "CommandID": "TransactionReversal",
                               "TransactionID": transaction_id, "Amount": amount,
                               "ReceiverParty": SHORTCODE, "ReceiverIdentifierType": "4",
                               "ResultURL": _cb("reversal/result"),
                               "QueueTimeOutURL": _cb("reversal/timeout"),
                               "Remarks": remarks, "Occasion": ""},
                         timeout=15)
    d = r.json()
    return {"conversation_id": d.get("ConversationID"),
            "response_code": d.get("ResponseCode"),
            "response_description": d.get("ResponseDescription")}

# ── Router ────────────────────────────────────────────────────────────────────

router = APIRouter(tags=["mpesa"])

@router.get("/health")
async def health():
    return {"status": "ok", "env": MPESA_ENV}

@router.get("/sse")
async def sse_endpoint(x_bridge_secret: str = Header(...)):
    if x_bridge_secret != BRIDGE_SECRET:
        raise HTTPException(status_code=401)

    async def gen():
        async with mcp.run_sse_async(sse) as session:
            async for event in session:
                yield event

    return StreamingResponse(gen(), media_type="text/event-stream")

@router.post("/messages")
async def handle_message(request: Request):
    return await sse.handle_post_message(request)

@router.get("/result/{collection}/{doc_id}")
async def get_result(collection: str, doc_id: str, x_bridge_secret: str = Header(...)):
    if x_bridge_secret != BRIDGE_SECRET:
        raise HTTPException(status_code=401)
    if not db:
        raise HTTPException(status_code=503, detail="Firestore not configured")
    try:
        doc = db.collection("openclaw").document(DEVICE_ID) \
                .collection(collection).document(doc_id).get()
        return doc.to_dict() if doc.exists else {"pending": True, "doc_id": doc_id}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@router.post("/stk/callback")
@router.post("/b2c/result")
@router.post("/b2c/timeout")
@router.post("/balance/result")
@router.post("/balance/timeout")
@router.post("/status/result")
@router.post("/status/timeout")
@router.post("/reversal/result")
@router.post("/reversal/timeout")
async def daraja_callback(request: Request):
    """Unified Daraja async callback receiver."""
    path = request.url.path.split("/mpesa/", 1)[-1]
    try:
        body = await request.json()
    except Exception:
        body = {}
    log.info(f"Daraja callback [{path}]: {json.dumps(body)[:200]}")

    if "stk" in path and "timeout" not in path:
        cb          = body.get("Body", {}).get("stkCallback", {})
        checkout_id = cb.get("CheckoutRequestID", "unknown")
        result_code = cb.get("ResultCode", -1)
        items       = cb.get("CallbackMetadata", {}).get("Item", []) if result_code == 0 else []
        meta        = {i["Name"]: i.get("Value", "") for i in items}
        doc         = {"checkout_request_id": checkout_id, "result_code": result_code,
                       "result_desc": cb.get("ResultDesc", ""),
                       "amount": str(meta.get("Amount", "")),
                       "mpesa_receipt": str(meta.get("MpesaReceiptNumber", "")),
                       "phone": str(meta.get("PhoneNumber", "")),
                       "success": result_code == 0}
        _store("mpesa_stk", checkout_id, doc)
        await _push_fcm({"type": "mpesa_stk_result", "checkout_id": checkout_id,
                         "success": str(result_code == 0), **{k: str(v) for k, v in doc.items()}})

    elif "b2c" in path and "result" in path:
        result      = body.get("Result", {})
        conv_id     = result.get("ConversationID", "unknown")
        result_code = result.get("ResultCode", -1)
        params      = {p["Key"]: p["Value"] for p in result.get("ResultParameters", {}).get("ResultParameter", [])}
        doc         = {"conversation_id": conv_id, "result_code": result_code,
                       "result_desc": result.get("ResultDesc", ""),
                       "amount": str(params.get("TransactionAmount", "")),
                       "mpesa_receipt": str(params.get("TransactionReceipt", "")),
                       "success": result_code == 0}
        _store("mpesa_b2c", conv_id, doc)
        await _push_fcm({"type": "mpesa_b2c_result", "conv_id": conv_id,
                         "success": str(result_code == 0), "amount": doc["amount"]})

    elif "balance" in path and "result" in path:
        result      = body.get("Result", {})
        conv_id     = result.get("ConversationID", "unknown")
        result_code = result.get("ResultCode", -1)
        params      = result.get("ResultParameters", {}).get("ResultParameter", [])
        balance_str = next((str(p.get("Value","")) for p in params if p.get("Key") == "AccountBalance"), "")
        _store("mpesa_balance", conv_id, {"conversation_id": conv_id,
               "result_code": result_code, "balance": balance_str, "success": result_code == 0})
        await _push_fcm({"type": "mpesa_balance_result", "conv_id": conv_id,
                         "balance": balance_str, "success": str(result_code == 0)})

    elif "status" in path and "result" in path:
        result      = body.get("Result", {})
        conv_id     = result.get("ConversationID", "unknown")
        result_code = result.get("ResultCode", -1)
        params      = {p["Key"]: p["Value"] for p in result.get("ResultParameters", {}).get("ResultParameter", [])}
        _store("mpesa_status", conv_id, {"conversation_id": conv_id, "result_code": result_code,
               "transaction_status": str(params.get("TransactionStatus", "")),
               "receipt": str(params.get("ReceiptNo", "")),
               "amount": str(params.get("Amount", "")), "success": result_code == 0})
        await _push_fcm({"type": "mpesa_status_result", "conv_id": conv_id,
                         "status": str(params.get("TransactionStatus", "")),
                         "success": str(result_code == 0)})

    elif "reversal" in path and "result" in path:
        result      = body.get("Result", {})
        conv_id     = result.get("ConversationID", "unknown")
        result_code = result.get("ResultCode", -1)
        _store("mpesa_reversal", conv_id, {"conversation_id": conv_id, "result_code": result_code,
               "result_desc": result.get("ResultDesc", ""), "success": result_code == 0})
        await _push_fcm({"type": "mpesa_reversal_result", "conv_id": conv_id,
                         "success": str(result_code == 0)})

    elif "timeout" in path:
        conv_id = (body.get("Body", {}).get("stkCallback", {}).get("CheckoutRequestID")
                   or body.get("Result", {}).get("ConversationID", "unknown"))
        log.warning(f"Daraja timeout [{path}] conv_id={conv_id}")
        await _push_fcm({"type": "mpesa_timeout", "path": path, "conv_id": conv_id})

    return {"ResultCode": 0, "ResultDesc": "Accepted"}
