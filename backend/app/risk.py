"""Risk rules. Enforced in code, not in the prompt: the LLM cannot talk its way past these."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from decimal import Decimal

from .config import Settings
from .db import Database
from .instruments import Instrument
from .ledger import Ledger
from .market.base import MarketData
from .speech import speak_money


class RiskBlock(Exception):
    def __init__(self, code: str, reason: str) -> None:
        super().__init__(reason)
        self.code = code
        self.reason = reason


@dataclass(frozen=True)
class Limits:
    max_order_value_inr: Decimal
    max_qty: int
    max_orders_per_day: int
    kill_switch: bool


class RiskEngine:
    def __init__(self, db: Database, ledger: Ledger, market: MarketData, settings: Settings) -> None:
        self.db = db
        self.ledger = ledger
        self.market = market
        self.settings = settings

    def limits(self, user_id: str) -> Limits:
        r = self.db.one("SELECT * FROM risk_settings WHERE user_id = ?", (user_id,))
        if not r:
            s = self.settings
            return Limits(s.default_max_order_value_inr, s.default_max_qty, s.default_max_orders_per_day, False)
        return Limits(Decimal(r["max_order_value_inr"]), r["max_qty"], r["max_orders_per_day"], bool(r["kill_switch"]))

    def update_limits(self, user_id: str, max_value: Decimal, max_qty: int, max_orders: int) -> Limits:
        s = self.settings
        if not (0 < max_value <= s.server_max_order_value_inr and 0 < max_qty <= s.server_max_qty
                and 0 < max_orders <= s.server_max_orders_per_day):
            raise RiskBlock("RISK_BLOCKED", "Those limits are above the maximum this server allows.")
        self.db.execute(
            "UPDATE risk_settings SET max_order_value_inr = ?, max_qty = ?, max_orders_per_day = ?, updated_at = ? WHERE user_id = ?",
            (str(max_value), max_qty, max_orders, datetime.now(timezone.utc).isoformat(), user_id),
        )
        return self.limits(user_id)

    def set_kill_switch(self, user_id: str, on: bool) -> None:
        self.db.execute("UPDATE risk_settings SET kill_switch = ? WHERE user_id = ?", (1 if on else 0, user_id))

    def kill_switch(self, user_id: str) -> bool:
        return self.limits(user_id).kill_switch

    async def value_in_inr(self, value: Decimal, currency: str) -> Decimal:
        return value if currency == "INR" else value * await self.market.usd_inr()

    async def check_order(self, user_id: str, inst: Instrument, side: str, qty: int, price: Decimal) -> None:
        """Raise RiskBlock with a spoken-friendly reason if the order breaks any rule."""
        lim = self.limits(user_id)
        if lim.kill_switch:
            raise RiskBlock("KILL_SWITCH", "Trading is switched off. You can turn it back on in Settings.")
        if qty <= 0:
            raise RiskBlock("RISK_BLOCKED", "The quantity has to be at least one share.")
        if qty > lim.max_qty:
            raise RiskBlock("RISK_BLOCKED", f"That's {qty} shares, above your limit of {lim.max_qty} shares per order.")
        value = price * qty
        value_inr = await self.value_in_inr(value, inst.currency)
        if value_inr > lim.max_order_value_inr:
            cap = lim.max_order_value_inr if inst.currency == "INR" else lim.max_order_value_inr / await self.market.usd_inr()
            raise RiskBlock(
                "RISK_BLOCKED",
                f"That's about {speak_money(value, inst.currency)}, which is above your per-order limit of "
                f"{speak_money(cap, inst.currency)}. Change the limit in Settings or try a smaller order.",
            )
        if self.ledger.orders_today(user_id) >= lim.max_orders_per_day:
            raise RiskBlock("RISK_BLOCKED", f"You've reached today's limit of {lim.max_orders_per_day} orders.")
        if side == "BUY":
            bp = self.ledger.buying_power(user_id, inst.currency)
            if value > bp:
                affordable = int(bp // price) if price > 0 else 0
                raise RiskBlock(
                    "RISK_BLOCKED",
                    f"That's about {speak_money(value, inst.currency)}, but you only have {speak_money(bp, inst.currency)} "
                    f"available. You could afford about {affordable} shares.",
                )
        else:
            held = self.ledger.sellable_qty(user_id, inst.conid)
            if held < qty:
                raise RiskBlock("RISK_BLOCKED", f"You can sell at most {held} shares of {inst.name} right now.")
