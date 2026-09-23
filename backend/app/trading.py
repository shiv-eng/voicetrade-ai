"""Order previews and confirmation: the safety gate between a spoken request and a paper trade.

`preview_order` never trades. It validates, fetches a fresh real price, applies the risk rules and stores
a preview that expires after 60 s. Only `confirm` (a matching, live, unused preview) moves money.
"""
from __future__ import annotations

import json
import secrets
from datetime import datetime, timedelta
from decimal import ROUND_DOWN, Decimal
from typing import Awaitable, Callable

from .config import Settings
from .db import Database
from .dto import money, order_dto
from .events import Hub
from .instruments import Instrument, Instruments
from .ledger import Ledger, LedgerError, OrderRow, utcnow
from .market.base import MarketData, MarketDataError, RawQuote
from .risk import RiskBlock, RiskEngine
from .speech import speak_money

Notifier = Callable[[str, str], Awaitable[None]]


class TradeError(Exception):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message


class TradingService:
    def __init__(
        self, db: Database, ledger: Ledger, instruments: Instruments, market: MarketData,
        risk: RiskEngine, settings: Settings, hub: Hub, clock: Callable[[], datetime] = utcnow,
    ) -> None:
        self.db, self.ledger, self.instruments, self.market = db, ledger, instruments, market
        self.risk, self.settings, self.hub, self.clock = risk, settings, hub, clock
        self.notify: Notifier | None = None  # makes the voice agent speak a result (set by the app wiring)

    # ---- preview ---------------------------------------------------------------------------------

    async def preview_order(
        self, user_id: str, session_id: str | None, conid: int, side: str, quantity: int | None = None,
        amount: Decimal | None = None, order_type: str = "MKT", limit_price: Decimal | None = None,
        sell_fraction: str | None = None,
    ) -> dict:
        inst = self.instruments.by_conid(conid)
        if not inst:
            return self._blocked("INSTRUMENT_NOT_FOUND", "I couldn't find that stock. Could you say the company name again?")
        side = side.upper()
        order_type = "LMT" if order_type.upper() in ("LMT", "LIMIT") else "MKT"
        if side not in ("BUY", "SELL"):
            return self._blocked("RISK_BLOCKED", "Is that a buy or a sell?")
        if order_type == "LMT" and (limit_price is None or limit_price <= 0):
            return self._blocked("RISK_BLOCKED", "What limit price would you like?")
        if sum(x is not None for x in (quantity, amount, sell_fraction)) > 1:
            return self._blocked("AMBIGUOUS_QUANTITY", "Do you want a number of shares, or a rupee amount? Tell me one of them.")
        try:
            quote = await self.market.quote(inst.symbol)
        except MarketDataError:
            return self._blocked("MARKET_DATA_UNAVAILABLE", f"I couldn't get a live price for {inst.name} right now, so I won't set up an order.")

        price = limit_price if order_type == "LMT" else quote.last
        qty = self._resolve_qty(user_id, inst, side, quantity, amount, sell_fraction, price)
        if isinstance(qty, dict):
            return qty
        warnings: list[str] = []
        if not quote.market_open:
            if not self.settings.fill_when_market_closed:
                return self._blocked("MARKET_CLOSED", f"The {inst.exchange} market is closed right now.")
            warnings.append(f"The {inst.exchange} market is closed. In paper mode this fills at the last price, {speak_money(quote.last, inst.currency)}.")
        if order_type == "LMT" and abs(limit_price - quote.last) / quote.last > Decimal("0.2"):
            warnings.append("Your limit price is more than 20 percent away from the market price.")
        try:
            await self.risk.check_order(user_id, inst, side, qty, price)
        except RiskBlock as b:
            return self._blocked(b.code, b.reason)

        self._supersede(user_id)
        pid = "p_" + secrets.token_hex(5)
        now = self.clock()
        expires = now + timedelta(seconds=self.settings.preview_ttl_s)
        value = (price * qty).quantize(Decimal("0.01"))
        payload = {"conid": inst.conid, "side": side, "qty": qty, "type": order_type,
                   "limit": str(limit_price) if limit_price else None, "value": str(value)}
        self.db.execute(
            "INSERT INTO previews (id, user_id, session_id, kind, payload_json, price_at_preview, expires_at, status) VALUES (?, ?, ?, 'PLACE', ?, ?, ?, 'ACTIVE')",
            (pid, user_id, session_id, json.dumps(payload), str(quote.last), expires.isoformat()),
        )
        dto = self._preview_dto(pid, inst, side, qty, order_type, limit_price, value, warnings, expires, "PLACE")
        self.hub.emit_user(user_id, "preview_created", {"preview": dto})
        kind = f"limit {side.lower()} at {speak_money(limit_price, inst.currency)}" if order_type == "LMT" else f"market {side.lower()}"
        speech = (f"That's a {kind} of {qty} {inst.name} shares on {inst.exchange}, about "
                  f"{speak_money(value, inst.currency)}. Paper account. Should I place it?")
        return {"preview_id": pid, "expires_at": expires.isoformat(), "summary_for_speech": speech,
                "est_value": str(value), "currency": inst.currency, "warnings": warnings, "_dto": dto}

    def _resolve_qty(self, user_id, inst, side, quantity, amount, sell_fraction, price) -> int | dict:
        if sell_fraction:
            if side != "SELL":
                return self._blocked("RISK_BLOCKED", "\"All\" and \"half\" only make sense when selling.")
            held = self.ledger.sellable_qty(user_id, inst.conid)
            if held <= 0:
                return self._blocked("RISK_BLOCKED", f"You don't hold any {inst.name}.")
            qty = held if sell_fraction.lower() == "all" else held // 2
            if qty < 1:
                return self._blocked("RISK_BLOCKED", "You'd need to hold at least two shares to sell half.")
            return qty
        if amount is not None:
            qty = int((amount / price).to_integral_value(rounding=ROUND_DOWN)) if price > 0 else 0
            if qty < 1:
                return self._blocked("RISK_BLOCKED", f"{speak_money(amount, inst.currency)} isn't enough for one share of {inst.name} at {speak_money(price, inst.currency)}.")
            return qty
        if quantity is None:
            return self._blocked("AMBIGUOUS_QUANTITY", "How many shares?")
        if quantity != int(quantity) or quantity < 1:
            return self._blocked("RISK_BLOCKED", "I can only trade whole shares, at least one.")
        return int(quantity)

    def _blocked(self, code: str, reason: str) -> dict:
        return {"blocked": True, "code": code, "reason": reason}

    def _supersede(self, user_id: str) -> None:
        for r in self.db.query("SELECT id FROM previews WHERE user_id = ? AND status = 'ACTIVE'", (user_id,)):
            self._close(user_id, r["id"], "REPLACED")

    def _close(self, user_id: str, preview_id: str, status: str) -> None:
        cur = self.db.execute("UPDATE previews SET status = ? WHERE id = ? AND status = 'ACTIVE'", (status, preview_id))
        if cur.rowcount:
            self.hub.emit_user(user_id, "preview_closed", {"previewId": preview_id, "reason": status.lower()})

    def _preview_dto(self, pid, inst: Instrument, side, qty, order_type, limit, value: Decimal, warnings, expires, kind) -> dict:
        return {
            "previewId": pid, "instrument": inst.to_dto(), "side": side, "quantity": qty, "type": order_type,
            "limitPrice": str(limit) if limit else None, "estimatedValue": money(value, inst.currency),
            "estimatedFees": None, "warnings": warnings, "expiresAt": expires.isoformat(), "kind": kind,
        }

    def active_preview(self, user_id: str) -> dict | None:
        r = self.db.one("SELECT * FROM previews WHERE user_id = ? AND status = 'ACTIVE' AND expires_at > ? ORDER BY expires_at DESC LIMIT 1",
                        (user_id, self.clock().isoformat()))
        if not r:
            return None
        p = json.loads(r["payload_json"])
        inst = self.instruments.by_conid(p["conid"])
        return {"preview_id": r["id"], "kind": r["kind"], "side": p.get("side"), "qty": p.get("qty"),
                "symbol": inst.ticker if inst else None, "expires_at": r["expires_at"]}

    def discard(self, user_id: str, preview_id: str) -> bool:
        row = self.db.one("SELECT status FROM previews WHERE id = ? AND user_id = ?", (preview_id, user_id))
        if not row or row["status"] != "ACTIVE":
            return False
        self._close(user_id, preview_id, "REJECTED")
        return True

    async def preview_cancel(self, user_id: str, session_id: str | None, order_id: str) -> dict:
        try:
            internal = int(order_id) - 100000
        except ValueError:
            return self._blocked("ORDER_NOT_FOUND", "I couldn't find that order.")
        order = self.ledger.order(user_id, internal)
        if not order or order.status != "Working":
            return self._blocked("ORDER_NOT_FOUND", "That order isn't open, so there's nothing to cancel.")
        inst = self.instruments.by_conid(order.instrument_id)
        assert inst
        self._supersede(user_id)
        pid = "p_" + secrets.token_hex(5)
        expires = self.clock() + timedelta(seconds=self.settings.preview_ttl_s)
        price = order.limit_price or Decimal("0")
        payload = {"conid": inst.conid, "side": order.side, "qty": order.qty, "type": order.type,
                   "limit": str(order.limit_price) if order.limit_price else None, "order_id": order.id,
                   "value": str((price * order.qty).quantize(Decimal("0.01")))}
        self.db.execute(
            "INSERT INTO previews (id, user_id, session_id, kind, payload_json, price_at_preview, expires_at, status) VALUES (?, ?, ?, 'CANCEL', ?, NULL, ?, 'ACTIVE')",
            (pid, user_id, session_id, json.dumps(payload), expires.isoformat()),
        )
        dto = self._preview_dto(pid, inst, order.side, order.qty, order.type, order.limit_price, Decimal(payload["value"]), [], expires, "CANCEL")
        self.hub.emit_user(user_id, "preview_created", {"preview": dto})
        return {"preview_id": pid, "expires_at": expires.isoformat(), "_dto": dto,
                "summary_for_speech": f"Cancel your {order.side.lower()} order for {order.qty} {inst.name} shares? Say confirm to cancel it."}

    # ---- confirm ---------------------------------------------------------------------------------

    async def confirm(self, user_id: str, preview_id: str) -> OrderRow:
        """Send the order. Raises TradeError; the app maps its code to a plain-language message."""
        row = self.db.one("SELECT * FROM previews WHERE id = ? AND user_id = ?", (preview_id, user_id))
        if not row or row["status"] != "ACTIVE":
            raise TradeError("PREVIEW_EXPIRED", "That preview is no longer valid.")
        if datetime.fromisoformat(row["expires_at"]) <= self.clock():
            self._close(user_id, preview_id, "EXPIRED")
            raise TradeError("PREVIEW_EXPIRED", "That preview expired.")
        payload = json.loads(row["payload_json"])
        inst = self.instruments.by_conid(payload["conid"])
        if not inst:
            raise TradeError("INSTRUMENT_NOT_FOUND", "Unknown instrument.")

        if row["kind"] == "CANCEL":
            claimed = self._claim(preview_id)
            if not claimed:
                raise TradeError("PREVIEW_EXPIRED", "That preview was already used.")
            try:
                order = self.ledger.cancel(user_id, payload["order_id"])
            except LedgerError as e:
                raise TradeError(e.code, e.message) from e
            self._announce(user_id, preview_id, order, inst)
            return order

        try:
            quote = await self.market.quote(inst.symbol)
        except MarketDataError as e:
            raise TradeError("MARKET_DATA_UNAVAILABLE", "I couldn't get a live price to check this order.") from e
        was = Decimal(row["price_at_preview"])
        if was > 0 and abs(quote.last - was) / was * 100 > self.settings.price_drift_pct:
            self._close(user_id, preview_id, "EXPIRED")
            raise TradeError("PRICE_DRIFT", "The price moved more than 2 percent since the preview.")
        if not self._claim(preview_id):
            raise TradeError("PREVIEW_EXPIRED", "That preview was already used.")
        note = None if quote.market_open else f"{inst.exchange} is closed; filled at the last price."
        try:
            order = self.ledger.place(
                user_id, inst, payload["side"], payload["qty"], payload["type"],
                Decimal(payload["limit"]) if payload["limit"] else None, quote.last, preview_id, note,
            )
        except LedgerError as e:
            raise TradeError(e.code, e.message) from e
        self.db.execute("UPDATE previews SET order_id = ? WHERE id = ?", (order.id, preview_id))
        self._announce(user_id, preview_id, order, inst)
        return order

    def _claim(self, preview_id: str) -> bool:
        """Atomically move ACTIVE to CONFIRMED. Two taps, or a tap and a spoken 'yes', place one order."""
        cur = self.db.execute(
            "UPDATE previews SET status = 'CONFIRMED', confirmed_at = ? WHERE id = ? AND status = 'ACTIVE'",
            (self.clock().isoformat(), preview_id),
        )
        return cur.rowcount == 1

    def _announce(self, user_id: str, preview_id: str, order: OrderRow, inst: Instrument) -> None:
        self.hub.emit_user(user_id, "preview_closed", {"previewId": preview_id, "reason": "confirmed"})
        self.hub.emit_user(user_id, "order_update", {"order": order_dto(order, inst)})

    def order_spoken(self, order: OrderRow, inst: Instrument) -> str:
        if order.status == "Filled":
            held = self.ledger.held_qty(order.user_id, inst.conid)
            verb = "bought" if order.side == "BUY" else "sold"
            tail = f" You now hold {held} {inst.name}." if held else f" You no longer hold {inst.name}."
            return f"Done. {verb.capitalize()} {order.qty} {inst.name} at {speak_money(order.avg_price, inst.currency)}.{tail}"
        if order.status == "Working":
            return f"Your limit order for {order.qty} {inst.name} shares is placed and waiting for the price."
        if order.status == "Cancelled":
            return f"Your {inst.name} order is cancelled."
        return f"The order was rejected. {order.note or ''}".strip()

    async def fill_due_limit_orders(self) -> list[tuple[OrderRow, Instrument]]:
        """Poller entry point: fill working limit orders whose price has been reached."""
        filled: list[tuple[OrderRow, Instrument]] = []
        for o in self.ledger.working_orders_all():
            if o.type != "LMT" or o.limit_price is None:
                continue
            inst = self.instruments.by_conid(o.instrument_id)
            if not inst:
                continue
            try:
                q: RawQuote = await self.market.quote(inst.symbol)
            except MarketDataError:
                continue
            hit = q.last <= o.limit_price if o.side == "BUY" else q.last >= o.limit_price
            if hit and (q.market_open or self.settings.fill_when_market_closed):
                done = self.ledger.fill_working(o, inst, q.last)
                if done and done.status != "Working":
                    self.hub.emit_user(o.user_id, "order_update", {"order": order_dto(done, inst)})
                    filled.append((done, inst))
        return filled
