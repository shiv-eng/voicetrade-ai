"""Price alerts: "tell me when Reliance crosses 1,300". Checked every few seconds by the background poller; a fired
alert is announced by voice if a session is open, and otherwise waits for the phone to fetch it and notify."""
from __future__ import annotations

import logging
from datetime import datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation
from typing import Any

from .db import Database
from .instruments import Instrument, Instruments
from .market.base import MarketData

log = logging.getLogger(__name__)


class AlertError(Exception):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code, self.message = code, message


class Alerts:
    MAX_ACTIVE = 20

    def __init__(self, db: Database, instruments: Instruments, market: MarketData) -> None:
        self.db, self.instruments, self.market = db, instruments, market

    # ---- create / read / delete -----------------------------------------------------------------

    def add(self, user_id: str, inst: Instrument, direction: str, target: Decimal) -> int:
        if direction not in ("above", "below"):
            raise AlertError("BAD_DIRECTION", "An alert must be for the price going above or below a level.")
        if target <= 0:
            raise AlertError("BAD_PRICE", "The price for an alert must be more than zero.")
        active = self.db.one("SELECT COUNT(*) AS n FROM alerts WHERE user_id = ? AND active = 1", (user_id,))
        if active and int(active["n"]) >= self.MAX_ACTIVE:
            raise AlertError("TOO_MANY", f"You already have {self.MAX_ACTIVE} alerts. Remove one first.")
        dup = self.db.one(
            "SELECT id FROM alerts WHERE user_id = ? AND instrument_id = ? AND direction = ? AND target = ? AND active = 1",
            (user_id, inst.conid, direction, str(target)))
        if dup:
            return int(dup["id"])
        return self.db.insert_id(
            "INSERT INTO alerts (user_id, instrument_id, direction, target, created_at) VALUES (?, ?, ?, ?, ?)",
            (user_id, inst.conid, direction, str(target), datetime.now(timezone.utc).isoformat()))

    def _dto(self, row: Any) -> dict[str, Any]:
        inst = self.instruments.by_conid(int(row["instrument_id"]))
        return {
            "id": int(row["id"]), "conid": int(row["instrument_id"]), "symbol": inst.ticker if inst else "?",
            "name": inst.name if inst else "?", "currency": inst.currency if inst else "INR",
            "direction": row["direction"], "target": row["target"], "createdAt": row["created_at"],
            "triggeredAt": row["triggered_at"], "triggerPrice": row["trigger_price"], "active": bool(row["active"]),
        }

    def list(self, user_id: str) -> list[dict[str, Any]]:
        """Active alerts, plus ones that fired in the last week."""
        since = (datetime.now(timezone.utc) - timedelta(days=7)).isoformat()
        rows = self.db.query(
            "SELECT * FROM alerts WHERE user_id = ? AND (active = 1 OR triggered_at >= ?) ORDER BY active DESC, id DESC",
            (user_id, since))
        return [self._dto(r) for r in rows]

    def cancel(self, user_id: str, alert_id: int) -> bool:
        return self.db.execute("UPDATE alerts SET active = 0 WHERE id = ? AND user_id = ? AND active = 1", (alert_id, user_id)).rowcount > 0

    def cancel_instrument(self, user_id: str, conid: int) -> int:
        return self.db.execute("UPDATE alerts SET active = 0 WHERE user_id = ? AND instrument_id = ? AND active = 1", (user_id, conid)).rowcount

    # ---- checking -------------------------------------------------------------------------------

    async def check_all(self) -> list[dict[str, Any]]:
        """Fire every alert whose level has been crossed. Returns the alerts that fired just now."""
        rows = self.db.query("SELECT * FROM alerts WHERE active = 1")
        if not rows:
            return []
        prices: dict[int, Decimal | None] = {}
        for row in rows:
            iid = int(row["instrument_id"])
            if iid in prices:
                continue
            inst = self.instruments.by_conid(iid)
            try:
                prices[iid] = (await self.market.quote(inst.symbol)).last if inst else None
            except Exception as e:  # one bad symbol must not stop the others
                log.warning("alert price check failed for %s: %s", iid, e)
                prices[iid] = None
        fired: list[dict[str, Any]] = []
        for row in rows:
            last = prices.get(int(row["instrument_id"]))
            if last is None:
                continue
            try:
                target = Decimal(row["target"])
            except InvalidOperation:
                continue
            if (row["direction"] == "above" and last >= target) or (row["direction"] == "below" and last <= target):
                now = datetime.now(timezone.utc).isoformat()
                if self.db.execute("UPDATE alerts SET active = 0, triggered_at = ?, trigger_price = ? WHERE id = ? AND active = 1",
                                   (now, str(last), int(row["id"]))).rowcount:
                    fired.append({**self._dto({**dict(row), "triggered_at": now, "trigger_price": str(last), "active": 0}), "userId": row["user_id"]})
        return fired

    # ---- phone notifications --------------------------------------------------------------------

    def unseen(self, user_id: str) -> list[dict[str, Any]]:
        rows = self.db.query("SELECT * FROM alerts WHERE user_id = ? AND active = 0 AND triggered_at IS NOT NULL AND notified = 0", (user_id,))
        return [self._dto(r) for r in rows]

    def ack(self, user_id: str, ids: list[int]) -> None:
        for i in ids:
            self.db.execute("UPDATE alerts SET notified = 1 WHERE id = ? AND user_id = ?", (int(i), user_id))
