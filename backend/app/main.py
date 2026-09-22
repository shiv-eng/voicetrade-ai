"""VoiceTrade backend: REST + WebSocket for the Android app, and the OpenAI-style endpoint the Agora agent calls."""
from __future__ import annotations

import asyncio
import json
import logging
import time
import uuid
from contextlib import asynccontextmanager
from datetime import datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation
from typing import Any, AsyncIterator

import jwt
from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import JSONResponse, StreamingResponse

from .agora.client import AgoraError
from .auth_google import AuthError, GoogleKeys, KeyResolver, verify_google_id_token
from .ledger import LedgerError
from .agora.tokens import build_rtc_token
from .config import Settings, load_settings
from .db import Database
from .dto import order_dto, quote_dto
from .llm.orchestrator import ChatClient
from .market.base import MarketData, MarketDataError
from .market.yahoo import YahooMarketData
from .risk import RiskBlock
from .sessions import Session
from .speech import SpeechBuffer, speak_money
from .state import Services, build_services
from .trading import TradeError

log = logging.getLogger("voicetrade")


class ApiError(Exception):
    def __init__(self, status: int, code: str, message: str) -> None:
        self.status, self.code, self.message = status, code, message


_TRADE_STATUS = {
    "PREVIEW_EXPIRED": 409, "PRICE_DRIFT": 409, "KILL_SWITCH": 423, "INSUFFICIENT_FUNDS": 409, "INSUFFICIENT_SHARES": 409,
    "MARKET_DATA_UNAVAILABLE": 503, "INSTRUMENT_NOT_FOUND": 404, "ORDER_NOT_FOUND": 404, "ORDER_NOT_WORKING": 409,
}


def _trade_error(e: TradeError) -> ApiError:
    code = "RISK_BLOCKED" if e.code.startswith("INSUFFICIENT") else e.code
    return ApiError(_TRADE_STATUS.get(e.code, 400), code, e.message)


def create_app(
    settings: Settings | None = None, market: MarketData | None = None, chat: ChatClient | None = None,
    background: bool = True, db: "Database | None" = None, google_keys: "KeyResolver | None" = None,
) -> FastAPI:
    settings = settings or load_settings()
    market = market or YahooMarketData()
    svc = build_services(settings, market, chat, db)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        task = asyncio.create_task(_limit_order_poller(svc)) if background else None
        warm = asyncio.create_task(_keep_warm(svc)) if background else None
        if background:
            print(f"\n  VoiceTrade backend ready.  Public URL: {settings.public_base_url}\n"
                  f"  Database: {'PostgreSQL' if svc.db.is_pg else 'SQLite (dev only)'}   Agora: {settings.agora_configured}   "
                  f"LLM: {'configured' if settings.llm_api_key else 'basic keyless mode'}\n")
        yield
        if task:
            task.cancel()
        if warm:
            warm.cancel()
        for s in svc.sessions.all():
            if s.agent_id:
                await svc.agora.stop_agent(s.agent_id)
        await svc.agora.aclose()

    app = FastAPI(title="VoiceTrade AI", lifespan=lifespan)
    app.state.svc = svc

    @app.exception_handler(ApiError)
    async def api_error(_: Request, e: ApiError):
        return JSONResponse({"code": e.code, "message": e.message}, status_code=e.status)

    # ---- auth ------------------------------------------------------------------------------------

    def issue_token(user_id: str) -> str:
        now = int(time.time())
        return jwt.encode({"sub": user_id, "iat": now, "exp": now + 365 * 24 * 3600}, settings.jwt_secret, algorithm="HS256")

    registrations: dict[str, list[float]] = {}
    keys = google_keys or GoogleKeys()

    def client_ip(request: Request) -> str:
        if settings.trust_proxy:
            fwd = request.headers.get("x-forwarded-for", "")
            if fwd:
                return fwd.split(",")[0].strip()
        return request.client.host if request.client else "unknown"

    def user_from_token(token: str) -> str:
        try:
            user_id = jwt.decode(token, settings.jwt_secret, algorithms=["HS256"])["sub"]
        except jwt.PyJWTError:
            raise ApiError(401, "UNAUTHORIZED", "This device isn't linked. Pair it again.") from None
        if not svc.ledger.user_exists(user_id):
            raise ApiError(401, "UNAUTHORIZED", "This device isn't linked. Pair it again.")
        return user_id

    def current_user(authorization: str | None = Header(default=None)) -> str:
        if not authorization or not authorization.lower().startswith("bearer "):
            raise ApiError(401, "UNAUTHORIZED", "Missing device token.")
        return user_from_token(authorization[7:].strip())

    @app.post("/auth/google")
    async def auth_google(body: dict):
        """Sign in with the Google ID token the app obtained. Creates the account (with paper money) on first sign-in."""
        try:
            profile = verify_google_id_token(str(body.get("idToken", "")), settings.google_client_ids, keys)
            user_id, created = svc.ledger.sign_in_google(profile.sub, profile.email, profile.name, profile.picture)
        except AuthError as e:
            raise ApiError(401, "GOOGLE_AUTH_FAILED", str(e)) from e
        except LedgerError as e:
            raise ApiError(403, e.code, e.message) from e
        return {"token": issue_token(user_id), "newAccount": created, "profile": svc.ledger.profile(user_id)}

    @app.post("/auth/guest")
    async def auth_guest(body: dict, request: Request):
        """Development shortcut (ALLOW_GUEST_LOGIN=true): an anonymous account, rate limited per IP."""
        if not settings.allow_guest_login:
            raise ApiError(404, "NOT_FOUND", "Guest sign-in is disabled.")
        ip, now = client_ip(request), time.time()
        recent = [t for t in registrations.get(ip, []) if now - t < 3600]
        if len(recent) >= settings.max_registrations_per_hour:
            raise ApiError(429, "RATE_LIMITED", "Too many new accounts from this network. Try again later.")
        registrations[ip] = [*recent, now]
        user_id = svc.ledger.create_user("Guest " + uuid.uuid4().hex[:4], None, None, None)
        return {"token": issue_token(user_id), "newAccount": True, "profile": svc.ledger.profile(user_id)}

    @app.get("/me")
    async def me(user: str = Depends(current_user)):
        return svc.ledger.profile(user)

    @app.get("/healthz")
    async def health():
        return {"ok": True, "agoraConfigured": settings.agora_configured, "llmConfigured": bool(settings.llm_api_key)}

    @app.get("/broker/status")
    async def broker_status(user: str = Depends(current_user)):
        return {"authenticated": True, "accountId": user, "paper": True}

    # ---- market and account ----------------------------------------------------------------------

    @app.get("/search")
    async def search(q: str, user: str = Depends(current_user)):
        try:
            return [i.to_dto() for i in await svc.instruments.search(q, 8)]
        except MarketDataError as e:
            raise ApiError(503, "MARKET_DATA_UNAVAILABLE", str(e)) from e

    @app.get("/quote")
    async def quote(conid: int, user: str = Depends(current_user)):
        inst = svc.instruments.by_conid(conid)
        if not inst:
            raise ApiError(404, "INSTRUMENT_NOT_FOUND", "Unknown stock.")
        try:
            return quote_dto(inst, await market.quote(inst.symbol))
        except MarketDataError as e:
            raise ApiError(503, "MARKET_DATA_UNAVAILABLE", str(e)) from e

    # ---- research: chart, company overview, IPOs (used by the stock and IPO screens) ---------------

    def _research():
        if svc.research is None:
            raise ApiError(503, "MARKET_DATA_UNAVAILABLE", "Research data is not available.")
        return svc.research

    @app.get("/stocks/{conid}/chart")
    async def stock_chart(conid: int, period: str = "1m", user: str = Depends(current_user)):
        inst = svc.instruments.by_conid(conid)
        if not inst:
            raise ApiError(404, "INSTRUMENT_NOT_FOUND", "Unknown stock.")
        try:
            h = await _research().history(inst.symbol, period)
        except Exception as e:
            raise ApiError(503, "MARKET_DATA_UNAVAILABLE", str(e)) from e
        return {"kind": "chart", "instrument": inst.to_dto(), "period": h["period"], "points": h["points"], "first": h["first"], "last": h["last"],
                "high": h["high"], "low": h["low"], "changePct": h["change_pct"], "currency": inst.currency}

    @app.get("/stocks/{conid}/overview")
    async def stock_overview(conid: int, user: str = Depends(current_user)):
        inst = svc.instruments.by_conid(conid)
        if not inst:
            raise ApiError(404, "INSTRUMENT_NOT_FOUND", "Unknown stock.")
        try:
            data = await svc.tools.overview_data(inst)
            q = await market.quote(inst.symbol)
        except Exception as e:
            raise ApiError(503, "MARKET_DATA_UNAVAILABLE", str(e)) from e
        return {"kind": "overview", "instrument": inst.to_dto(), "quote": quote_dto(inst, q), "rows": data["rows"], "headlines": data["headlines"]}

    @app.get("/ipos")
    async def ipos(market_code: str = Query("IN", alias="market"), user: str = Depends(current_user)):
        try:
            data = await _research().ipos(market_code)
        except Exception as e:
            raise ApiError(503, "MARKET_DATA_UNAVAILABLE", str(e)) from e
        return svc.tools.ipo_card(data)

    @app.get("/ipos/{symbol}")
    async def ipo_detail(symbol: str, series: str = "EQ", name: str | None = None, user: str = Depends(current_user)):
        try:
            return await svc.tools.ipo_detail_card(symbol.upper(), "SME" if series.upper() == "SME" else "EQ", name)
        except Exception as e:
            raise ApiError(503, "MARKET_DATA_UNAVAILABLE", str(e)) from e

    # ---- alerts, market overview, briefing, portfolio history -------------------------------------

    @app.get("/alerts")
    async def alerts_list(user: str = Depends(current_user)):
        return svc.alerts.list(user)

    @app.post("/alerts", status_code=201)
    async def alerts_add(body: dict, user: str = Depends(current_user)):
        inst = svc.instruments.by_conid(int(body.get("conid", 0)))
        if not inst:
            raise ApiError(404, "INSTRUMENT_NOT_FOUND", "Unknown stock.")
        from .alerts import AlertError
        try:
            target = Decimal(str(body.get("target")))
            direction = str(body.get("direction") or "").lower()
            if direction not in ("above", "below"):
                last = (await market.quote(inst.symbol)).last
                direction = "above" if target > last else "below"
            return {"id": svc.alerts.add(user, inst, direction, target)}
        except (InvalidOperation, AlertError) as e:
            raise ApiError(400, getattr(e, "code", "BAD_PRICE"), getattr(e, "message", "That price doesn't look right.")) from e

    @app.delete("/alerts/{alert_id}")
    async def alerts_cancel(alert_id: int, user: str = Depends(current_user)):
        return {"ok": svc.alerts.cancel(user, alert_id)}

    @app.get("/alerts/pending")
    async def alerts_pending(user: str = Depends(current_user)):
        return svc.alerts.unseen(user)

    @app.post("/alerts/ack")
    async def alerts_ack(body: dict, user: str = Depends(current_user)):
        svc.alerts.ack(user, [int(i) for i in body.get("ids", [])])
        return {"ok": True}

    @app.get("/market/overview")
    async def market_overview(user: str = Depends(current_user)):
        return await svc.insights.overview()

    @app.get("/briefing")
    async def briefing(lang: str = "en", user: str = Depends(current_user)):
        return await svc.insights.briefing(user, lang)

    @app.get("/portfolio/history")
    async def portfolio_history(currency: str = "INR", days: int = 90, user: str = Depends(current_user)):
        return await svc.insights.portfolio_history(user, currency, max(7, min(days, 365)))

    @app.get("/account")
    async def account(user: str = Depends(current_user)):
        return await svc.portfolio.account(user)

    @app.get("/positions")
    async def positions(user: str = Depends(current_user)):
        return await svc.portfolio.positions(user)

    @app.get("/pnl")
    async def pnl(user: str = Depends(current_user)):
        return await svc.portfolio.pnl(user)

    @app.get("/orders")
    async def orders(status: str = "open", user: str = Depends(current_user)):
        out = []
        for o in svc.ledger.orders(user, status):
            inst = svc.instruments.by_conid(o.instrument_id)
            if inst:
                out.append(order_dto(o, inst))
        return out

    @app.post("/orders/{order_id}/cancel-preview")
    async def cancel_preview(order_id: str, user: str = Depends(current_user)):
        result = await svc.trading.preview_cancel(user, None, order_id)
        if result.get("blocked"):
            raise ApiError(409, result["code"], result["reason"])
        return result["_dto"]

    # ---- watchlist -------------------------------------------------------------------------------

    @app.get("/watchlist")
    async def watchlist(user: str = Depends(current_user)):
        return await svc.portfolio.watchlist_quotes(user)

    @app.put("/watchlist/{conid}")
    async def watchlist_add(conid: int, user: str = Depends(current_user)):
        inst = svc.instruments.by_conid(conid)
        if not inst:
            raise ApiError(404, "INSTRUMENT_NOT_FOUND", "Unknown stock.")
        if not svc.portfolio.watchlist_add(user, inst):
            raise ApiError(409, "WATCHLIST_FULL", "Your watchlist is full (20 stocks).")
        return {"ok": True}

    @app.delete("/watchlist/{conid}")
    async def watchlist_remove(conid: int, user: str = Depends(current_user)):
        svc.portfolio.watchlist_remove(user, conid)
        return {"ok": True}

    @app.post("/watchlist/{conid}/move")
    async def watchlist_move(conid: int, up: bool, user: str = Depends(current_user)):
        svc.portfolio.watchlist_move(user, conid, up)
        return {"ok": True}

    # ---- settings --------------------------------------------------------------------------------

    def limits_dto(user: str) -> dict:
        lim = svc.risk.limits(user)
        return {"maxOrderValue": str(lim.max_order_value_inr), "maxQty": lim.max_qty, "maxOrdersPerDay": lim.max_orders_per_day,
                "killSwitch": lim.kill_switch,
                "serverMax": {"maxOrderValue": str(settings.server_max_order_value_inr), "maxQty": settings.server_max_qty,
                              "maxOrdersPerDay": settings.server_max_orders_per_day}}

    @app.get("/settings/risk")
    async def get_risk(user: str = Depends(current_user)):
        return limits_dto(user)

    @app.put("/settings/risk")
    async def put_risk(body: dict, user: str = Depends(current_user)):
        try:
            svc.risk.update_limits(user, Decimal(str(body["maxOrderValue"])), int(body["maxQty"]), int(body["maxOrdersPerDay"]))
        except (KeyError, ValueError, InvalidOperation):
            raise ApiError(400, "BAD_REQUEST", "Those limits aren't valid numbers.") from None
        except RiskBlock as b:
            raise ApiError(409, "RISK_BLOCKED", b.reason) from b
        return limits_dto(user)

    @app.put("/settings/kill-switch")
    async def put_kill(body: dict, user: str = Depends(current_user)):
        on = bool(body.get("on"))
        svc.risk.set_kill_switch(user, on)
        return {"on": on}

    # ---- previews (tap to confirm / cancel) ------------------------------------------------------

    @app.post("/previews/{preview_id}/confirm")
    async def confirm(preview_id: str, user: str = Depends(current_user)):
        try:
            order = await svc.trading.confirm(user, preview_id)
        except TradeError as e:
            raise _trade_error(e) from e
        inst = svc.instruments.by_conid(order.instrument_id)
        await announce(svc, user, svc.trading.order_spoken(order, inst))  # type: ignore[arg-type]
        return {"orderId": order.order_id, "status": order.status}

    @app.post("/previews/{preview_id}/reject")
    async def reject(preview_id: str, user: str = Depends(current_user)):
        svc.trading.discard(user, preview_id)
        return {"ok": True}

    # ---- voice sessions --------------------------------------------------------------------------

    @app.post("/sessions", status_code=201)
    async def start_session(body: dict, user: str = Depends(current_user)):
        t_start = time.monotonic()
        for old in [s for s in svc.sessions.all() if s.user_id == user]:  # one live session per user
            # ending the old one means a call to Agora; the user must not wait for it
            asyncio.create_task(end_session_impl(svc, old.id))
        lang, voice, rate = str(body.get("language", "en")), "female", float(body.get("speechRate", 1.0))
        lang = "en" if lang == "en" else "hinglish"  # the user chose one language up front; speech and voice are pinned to it
        session, secret = svc.sessions.create(user, lang, voice, rate)
        svc.hub.register_session(user, session.id)
        for turn in body.get("resumeHistory") or []:  # continuing a past conversation from the phone's own history
            role, text = str(turn.get("role", "")), str(turn.get("text", "")).strip()
            if role in ("user", "assistant") and text:
                session.history.append({"role": role, "content": text})
                if role == "assistant":
                    session.last_reply = text
        del session.history[:-20]
        user_uid = 2000 + (uuid.uuid4().int % 900_000)
        session.user_uid = user_uid
        agora: dict[str, Any] = {"appId": "", "channel": session.channel, "token": "", "uid": user_uid}
        if settings.agora_configured:
            row = svc.db.one("SELECT name FROM users WHERE id = ?", (user,))
            first = str((row["name"] if row else "") or "").strip().split(" ")[0]
            if first.lower() in ("", "guest"):
                first = ""
            resuming = bool(session.history)
            if resuming and lang != "en" and settings.sarvam_api_key:
                greeting = f"वापसी पर स्वागत है{', ' + first if first else ''}! हम जहाँ रुके थे वहीं से जारी रखते हैं।"
            elif resuming and lang != "en":
                greeting = f"Wapasi par swagat hai{', ' + first if first else ''}! Hum jahan ruke the wahin se jaari rakhte hain."
            elif resuming:
                greeting = f"Welcome back{', ' + first if first else ''}! Picking up where we left off."
            elif lang != "en" and settings.sarvam_api_key:
                greeting = f"नमस्ते{' ' + first if first else ''}! मैं मीरा हूँ। आज मार्केट या आपके पोर्टफोलियो के बारे में क्या जानना है?"
            elif lang != "en":
                greeting = f"Namaste{' ' + first if first else ''}! Main Mira hoon. Aaj market ya aapke portfolio ke baare mein kya jaanna hai?"
            else:
                greeting = f"Hi{' ' + first if first else ''}! I'm Mira. What would you like to look at today?"
            t_agent = time.monotonic()
            try:
                agent_token = build_rtc_token(settings.agora_app_id, settings.agora_app_certificate, session.channel, settings.agora_agent_uid)
                session.agent_id = await svc.agora.start_agent(
                    svc.agora.agent_body(session.id, session.channel, agent_token, user_uid, secret, greeting, voice, rate, lang))
            except (AgoraError, Exception) as e:
                await end_session_impl(svc, session.id)
                raise ApiError(502, "AGENT_FAILED", f"The voice assistant couldn't start: {e}") from e
            agora.update({"appId": settings.agora_app_id, "uid": user_uid,
                          "token": build_rtc_token(settings.agora_app_id, settings.agora_app_certificate, session.channel, user_uid)})
        else:
            log.warning("Agora is not configured: session %s is text-only.", session.id)
        logging.getLogger("uvicorn.error").info(
            "session %s ready in %.2fs (agora start %.2fs)", session.id, time.monotonic() - t_start,
            time.monotonic() - t_agent if settings.agora_configured else 0.0)
        scheme = "wss" if settings.public_base_url.startswith("https") else "ws"
        host = settings.public_base_url.split("://", 1)[-1]
        return {"sessionId": session.id, "agora": agora, "wsUrl": f"{scheme}://{host}/sessions/{session.id}/events", "paper": True}

    def owned_session(session_id: str, user: str) -> Session:
        s = svc.sessions.get(session_id)
        if not s or s.user_id != user:
            raise ApiError(404, "SESSION_NOT_FOUND", "That session has ended.")
        return s

    @app.delete("/sessions/{session_id}")
    async def end_session(session_id: str, user: str = Depends(current_user)):
        owned_session(session_id, user)
        await end_session_impl(svc, session_id)
        return {"ok": True}

    @app.post("/sessions/{session_id}/token")
    async def renew_token(session_id: str, user: str = Depends(current_user)):
        s = owned_session(session_id, user)
        return {"token": build_rtc_token(settings.agora_app_id, settings.agora_app_certificate, s.channel, s.user_uid)}

    @app.post("/sessions/{session_id}/text")
    async def send_text(session_id: str, body: dict, user: str = Depends(current_user)):
        s = owned_session(session_id, user)
        text = str(body.get("text", "")).strip()
        if not text:
            raise ApiError(400, "BAD_REQUEST", "Empty message.")
        asyncio.create_task(run_text_turn(svc, s, text))
        return {"accepted": True}

    @app.post("/sessions/{session_id}/pause")
    async def pause(session_id: str, user: str = Depends(current_user)):
        s = owned_session(session_id, user)
        await pause_session(svc, s)
        return {"paused": True}

    @app.post("/sessions/{session_id}/resume")
    async def resume(session_id: str, user: str = Depends(current_user)):
        s = owned_session(session_id, user)
        await unpause_session(svc, s)
        return {"paused": False}

    @app.post("/sessions/{session_id}/replay")
    async def replay(session_id: str, user: str = Depends(current_user)):
        s = owned_session(session_id, user)
        return {"paused": False, "replayed": await replay_session(svc, s)}

    @app.websocket("/sessions/{session_id}/events")
    async def events(ws: WebSocket, session_id: str, lastSeq: int = 0):
        auth = ws.headers.get("authorization", "")
        try:
            user = user_from_token(auth[7:].strip()) if auth.lower().startswith("bearer ") else ""
        except ApiError:
            user = ""
        s = svc.sessions.get(session_id)
        if not user or not s or s.user_id != user:
            await ws.close(code=4401)
            return
        await ws.accept()
        q = svc.hub.subscribe(session_id, lastSeq)
        try:
            while True:
                await ws.send_text(json.dumps(await q.get()))
        except (WebSocketDisconnect, RuntimeError):
            pass
        finally:
            svc.hub.unsubscribe(session_id, q)

    # ---- the "LLM" the Agora agent calls ---------------------------------------------------------

    @app.post("/v1/chat/completions")
    async def chat_completions(request: Request, s: str | None = Query(default=None), authorization: str | None = Header(default=None)):
        secret = s or (authorization[7:].strip() if authorization and authorization.lower().startswith("bearer ") else "")
        session = svc.sessions.by_secret(secret) if secret else None
        if not session:
            raise ApiError(401, "UNAUTHORIZED", "Unknown or finished session.")
        body = await request.json()
        convo = [{"role": m["role"], "content": _flatten(m.get("content"))} for m in body.get("messages", [])
                 if m.get("role") in ("user", "assistant") and _flatten(m.get("content"))]
        last_user = next((m["content"] for m in reversed(convo) if m["role"] == "user"), "")
        model = body.get("model", "voicetrade-orchestrator")
        if not last_user:
            return _sse_response(_empty_stream(model))
        if body.get("stream", True):
            return _sse_response(voice_turn_stream(svc, session, convo, last_user, model))
        text = "".join([p async for p in svc.orchestrator.reply(session, convo, last_user)])
        return {"id": "chatcmpl-" + uuid.uuid4().hex[:12], "object": "chat.completion", "created": int(time.time()), "model": model,
                "choices": [{"index": 0, "message": {"role": "assistant", "content": text}, "finish_reason": "stop"}]}

    return app


# ---- helpers ---------------------------------------------------------------------------------------

def _flatten(content: Any) -> str:
    if isinstance(content, str):
        return content.strip()
    if isinstance(content, list):
        return " ".join(p.get("text", "") for p in content if isinstance(p, dict)).strip()
    return ""


def _sse_response(gen: AsyncIterator[str]) -> StreamingResponse:
    return StreamingResponse(gen, media_type="text/event-stream", headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


def sse_chunk(cid: str, model: str, delta: dict, finish: str | None = None) -> str:
    payload = {"id": cid, "object": "chat.completion.chunk", "created": int(time.time()), "model": model,
               "choices": [{"index": 0, "delta": delta, "finish_reason": finish}]}
    return f"data: {json.dumps(payload)}\n\n"


async def _empty_stream(model: str) -> AsyncIterator[str]:
    cid = "chatcmpl-" + uuid.uuid4().hex[:12]
    yield sse_chunk(cid, model, {"role": "assistant", "content": ""}, "stop")
    yield "data: [DONE]\n\n"


def _emit_transcript(svc: Services, session: Session, role: str, text: str, final: bool, message_id: str) -> None:
    svc.hub.emit(session.id, "transcript", {"role": role, "text": text, "isFinal": final, "messageId": message_id})


def _settle_agent_state(svc: Services, session: Session, spoken_chars: int) -> None:
    """The agent has no state callback for us, so estimate when it stops talking (~15 chars a second)."""
    if session.speaking_task:
        session.speaking_task.cancel()

    async def settle():
        await asyncio.sleep(min(20.0, spoken_chars / 15 + 0.6))
        awaiting = svc.trading.active_preview(session.user_id) is not None
        svc.hub.emit(session.id, "agent_state", {"state": "awaiting_confirmation" if awaiting else "listening"})

    session.speaking_task = asyncio.create_task(settle())


async def run_turn(svc: Services, session: Session, convo: list[dict], last_user: str) -> AsyncIterator[str]:
    """Shared by voice (streamed to Agora) and typed input. Yields the pieces to speak."""
    turn = uuid.uuid4().hex[:6]
    if session.speaking_task:
        session.speaking_task.cancel()
    session.last_user_text = last_user
    _emit_transcript(svc, session, "user", last_user, True, f"u_{turn}")
    svc.hub.emit(session.id, "agent_state", {"state": "thinking"})
    buffer: list[str] = []
    last_push = 0.0
    completed = False
    t0 = time.monotonic()
    try:
        async for piece in svc.orchestrator.reply(session, convo, last_user):
            if not buffer:
                logging.getLogger("uvicorn.error").info("turn %s first words after %.2fs: %r", turn, time.monotonic() - t0, last_user[:60])
                svc.hub.emit(session.id, "agent_state", {"state": "speaking"})
            buffer.append(piece)
            now = time.monotonic()
            if now - last_push > 0.35:
                last_push = now
                _emit_transcript(svc, session, "agent", "".join(buffer).strip(), False, f"a_{turn}")
            yield piece
        completed = True
    finally:
        text = "".join(buffer).strip()
        if text:
            _emit_transcript(svc, session, "agent", text, True, f"a_{turn}")
        if text:
            session.last_reply = text
        if completed or text:
            session.history.append({"role": "user", "content": last_user})
            if text:
                session.history.append({"role": "assistant", "content": text})
            del session.history[:-20]
        _settle_agent_state(svc, session, len(text))


_PAUSE_WORDS = {
    "pause", "wait", "hold on", "hold on a second", "hold on a minute", "one second", "one minute", "just a second", "just a minute",
    "stop", "stop talking", "be quiet", "ruko", "ruk jao", "ek minute", "ek second", "ek minute ruko", "thoda ruko",
    "रुको", "रुक जाओ", "एक मिनट", "एक सेकंड", "थोड़ा रुको", "पॉज", "पॉज़", "चुप", "चुप हो जाओ",
}
_RESUME_WORDS = {
    "continue", "resume", "go on", "go ahead", "carry on", "keep going", "start again", "start over", "repeat", "repeat that",
    "say that again", "say it again", "again", "once more", "phir se bolo", "phir se", "dobara bolo", "aage bolo", "jaari rakho",
    "फिर से बोलो", "फिर से", "दोबारा बोलो", "आगे बोलो", "जारी रखो", "कंटिन्यू", "रिज्यूम", "फिर से शुरू करो", "दोबारा",
}


def voice_command(text: str) -> str | None:
    """'pause' or 'resume' when the whole utterance is just that; anything else is a normal question."""
    t = " ".join("".join(ch if ch.isalnum() or ch == " " or "ऀ" <= ch <= "ॿ" else " " for ch in text.lower()).split())
    if t.startswith(("please ", "mira ", "मीरा ")):
        t = t.split(" ", 1)[1]
    if t in _PAUSE_WORDS:
        return "pause"
    if t in _RESUME_WORDS:
        return "resume"
    return None


def _playback(svc: Services, session: Session, resumable: bool) -> None:
    """Tell the app whether Mira is paused and whether there is an answer she can say again."""
    svc.hub.emit(session.id, "playback", {"paused": session.paused, "resumable": resumable and bool(session.last_reply)})


async def pause_session(svc: Services, session: Session) -> None:
    session.paused = True
    if session.speaking_task:
        session.speaking_task.cancel()
    if session.agent_id:
        await svc.agora.interrupt(session.agent_id)
    svc.hub.emit(session.id, "agent_state", {"state": "listening"})
    _playback(svc, session, True)


async def unpause_session(svc: Services, session: Session) -> None:
    """Leave the pause and just start listening again: nothing is repeated."""
    session.paused = False
    svc.hub.emit(session.id, "agent_state", {"state": "listening"})
    _playback(svc, session, False)


async def replay_session(svc: Services, session: Session) -> bool:
    """Say the last answer again from the start: an explicit ask ("repeat that"), never automatic on a plain unpause."""
    session.paused = False
    text = session.last_reply
    _playback(svc, session, False)
    if not text or not session.agent_id:
        return False
    svc.hub.emit(session.id, "agent_state", {"state": "speaking"})
    _settle_agent_state(svc, session, len(text))
    await svc.agora.speak(session.agent_id, text)
    return True


def _was_talking(session: Session) -> bool:
    """True if Mira was (by our estimate) still speaking when the user started to talk."""
    return bool(session.speaking_task and not session.speaking_task.done())


_FILLERS = {"hmm", "hm", "hmmm", "uh", "uhh", "um", "umm", "ah", "aah", "er", "huh", "oh", "ooh", "mm", "mhm", "हम्म", "हूँ", "अह", "उम"}


def is_noise(text: str) -> bool:
    """True for an empty or sound-only transcript (breathing, 'hmm', a stray letter)."""
    t = "".join(ch for ch in text.lower() if ch.isalnum() or "ऀ" <= ch <= "ॿ").strip()
    words = [w.strip(".,!?") for w in text.lower().split()]
    return len(t) < 2 or (bool(words) and all(w in _FILLERS for w in words))


async def voice_turn_stream(svc: Services, session: Session, convo: list[dict], last_user: str, model: str) -> AsyncIterator[str]:
    cid = "chatcmpl-" + uuid.uuid4().hex[:12]
    async with session.lock:
        yield sse_chunk(cid, model, {"role": "assistant", "content": ""})
        command = voice_command(last_user)
        if is_noise(last_user) or command:
            interrupted = _was_talking(session)
            if interrupted and session.speaking_task:
                session.speaking_task.cancel()
            if command == "pause":
                await pause_session(svc, session)
            elif command == "resume":
                await replay_session(svc, session)  # a spoken "continue"/"repeat" is an explicit ask to say it again
            elif interrupted and not session.paused:
                # A cough or a stray word cut Mira off: stay quiet, but let the user bring the answer back.
                svc.hub.emit(session.id, "agent_state", {"state": "listening"})
                _playback(svc, session, True)
            yield sse_chunk(cid, model, {}, "stop")  # not a question: say nothing new
            yield "data: [DONE]\n\n"
            return
        if session.paused or _was_talking(session):
            session.paused = False  # a real question ends the pause and replaces any cut-off answer
            _playback(svc, session, False)
        hindi = None
        if svc.settings.sarvam_api_key:
            from .llm.prompts import reply_language
            hindi = reply_language(last_user, session.language) == "hindi"
        speech = SpeechBuffer(hindi)  # digits -> words in the language of this answer, before the voice sees them
        async for piece in run_turn(svc, session, convo, last_user):
            if session.paused:
                break
            if chunk := speech.feed(piece):
                yield sse_chunk(cid, model, {"content": chunk})
        if tail := speech.flush():
            yield sse_chunk(cid, model, {"content": tail})
        yield sse_chunk(cid, model, {}, "stop")
        yield "data: [DONE]\n\n"


async def run_text_turn(svc: Services, session: Session, text: str) -> None:
    async with session.lock:
        convo = [*session.history, {"role": "user", "content": text}]
        reply = "".join([p async for p in run_turn(svc, session, convo, text)]).strip()
    if reply and session.agent_id:
        await svc.agora.speak(session.agent_id, reply)


async def announce(svc: Services, user_id: str, text: str) -> None:
    """Tell the user something out loud and in the transcript (tap-confirmed orders, limit fills)."""
    for sid in svc.hub.sessions_of(user_id):
        session = svc.sessions.get(sid)
        if not session:
            continue
        _emit_transcript(svc, session, "agent", text, True, "n_" + uuid.uuid4().hex[:6])
        if session.agent_id:
            await svc.agora.speak(session.agent_id, text)


async def end_session_impl(svc: Services, session_id: str) -> None:
    session = svc.sessions.end(session_id)
    if not session:
        return
    if session.speaking_task:
        session.speaking_task.cancel()
    svc.hub.unregister_session(session.user_id, session_id)
    svc.hub.drop_session(session_id)
    if session.agent_id:
        await svc.agora.stop_agent(session.agent_id)


async def _keep_warm(svc: Services) -> None:
    """Fetch the slow public data ahead of time so nobody waits for it: IPOs, indices and top movers."""
    tick = 0
    while True:
        try:
            if svc.insights:
                await svc.insights.overview()
            if svc.research and tick % 6 == 0:
                await svc.research.ipos("IN")
        except Exception:
            log.warning("cache warm-up failed", exc_info=True)
        tick += 1
        await asyncio.sleep(45)


async def _limit_order_poller(svc: Services) -> None:
    while True:
        await asyncio.sleep(svc.settings.limit_poll_interval_s)
        try:
            for order, inst in await svc.trading.fill_due_limit_orders():
                await announce(svc, order.user_id, svc.trading.order_spoken(order, inst))
        except Exception:
            log.exception("limit order poll failed")
        try:
            for alert in await svc.alerts.check_all():
                cur = alert["currency"]
                await announce(svc, alert["userId"], f"Price alert: {alert['name']} is now {speak_money(Decimal(alert['triggerPrice']), cur)}, "
                                                     f"which is {alert['direction']} your level of {speak_money(Decimal(alert['target']), cur)}.")
        except Exception:
            log.exception("alert check failed")


def get_app() -> FastAPI:
    return create_app()
