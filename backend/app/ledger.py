"""The paper-trading ledger: per-user wallets (INR and USD), positions, and orders.

This is where buy and sell actually happen. Every fill moves cash and holdings inside one database
transaction, so a crash can never leave money deducted without shares (or the reverse).
"""
from __future__ import annotations

import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from decimal import ROUND_HALF_UP, Decimal
from typing import Callable

from .config import Settings
from .db import Database
from .instruments import Instrument

CENT = Decimal("0.01")


def money(value: Decimal) -> Decimal:
    return value.quantize(CENT, rounding=ROUND_HALF_UP)


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


class LedgerError(Exception):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message


@dataclass(frozen=True)
class PositionRow:
    instrument_id: int
    qty: int
    avg_cost: Decimal


@dataclass(frozen=True)
class OrderRow:
    id: int
    user_id: str
    instrument_id: int
    side: str            # BUY | SELL
    qty: int
    type: str            # MKT | LMT
    limit_price: Decimal | None
    status: str          # Working | Filled | Cancelled | Rejected
    avg_price: Decimal | None
    note: str | None
    updated_at: str

    @property
    def order_id(self) -> str:
        return str(100000 + self.id)


def _order(row) -> OrderRow:
    return OrderRow(
        id=row["id"], user_id=row["user_id"], instrument_id=row["instrument_id"], side=row["side"],
        qty=row["qty"], type=row["type"],
        limit_price=Decimal(row["limit_price"]) if row["limit_price"] else None,
        status=row["status"], avg_price=Decimal(row["avg_price"]) if row["avg_price"] else None,
        note=row["note"], updated_at=row["updated_at"],
    )


class Ledger:
    def __init__(self, db: Database, settings: Settings, clock: Callable[[], datetime] = utcnow) -> None:
        self.db = db
        self.settings = settings
        self.clock = clock

    # ---- users and wallets ------------------------------------------------------------------------

    def create_user(self, name: str, email: str | None = None, google_sub: str | None = None, picture: str | None = None) -> str:
        """New account with fresh paper wallets."""
        user_id = "u_" + uuid.uuid4().hex[:12]
        now = self.clock().isoformat()
        with self.db.transaction():
            self.db.execute(
                "INSERT INTO users (id, google_sub, email, name, picture, created_at, last_login_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                (user_id, google_sub, email, name, picture, now, now),
            )
            for currency, cash in (("INR", self.settings.start_cash_inr), ("USD", self.settings.start_cash_usd)):
                self.db.execute("INSERT INTO wallets (user_id, currency, cash) VALUES (?, ?, ?)", (user_id, currency, str(cash)))
        return user_id

    def user_exists(self, user_id: str) -> bool:
        row = self.db.one("SELECT revoked FROM users WHERE id = ?", (user_id,))
        return bool(row) and not row["revoked"]

    def sign_in_google(self, google_sub: str, email: str, name: str, picture: str | None) -> tuple[str, bool]:
        """Find the user for this Google account or create them. Returns (user_id, created).
        Signing in again on a new phone lands on the same wallets, holdings and watchlist."""
        row = self.db.one("SELECT id, revoked FROM users WHERE google_sub = ?", (google_sub,))
        now = self.clock().isoformat()
        if row:
            if row["revoked"]:
                raise LedgerError("ACCOUNT_DISABLED", "This account has been disabled.")
            self.db.execute("UPDATE users SET email = ?, name = ?, picture = ?, last_login_at = ? WHERE id = ?",
                            (email, name, picture, now, row["id"]))
            return row["id"], False
        return self.create_user(name, email, google_sub, picture), True

    def profile(self, user_id: str) -> dict:
        row = self.db.one("SELECT email, name, picture, created_at FROM users WHERE id = ?", (user_id,))
        if not row:
            return {}
        return {"userId": user_id, "email": row["email"], "name": row["name"], "picture": row["picture"], "createdAt": row["created_at"]}

    def cash(self, user_id: str, currency: str) -> Decimal:
        row = self.db.one("SELECT cash FROM wallets WHERE user_id = ? AND currency = ?", (user_id, currency))
        return Decimal(row["cash"]) if row else Decimal("0")

    def realized_pnl(self, user_id: str, currency: str) -> Decimal:
        row = self.db.one("SELECT realized_pnl FROM wallets WHERE user_id = ? AND currency = ?", (user_id, currency))
        return Decimal(row["realized_pnl"]) if row else Decimal("0")

    def reserved_cash(self, user_id: str, currency: str) -> Decimal:
        """Cash tied up by working buy-limit orders."""
        rows = self.db.query(
            "SELECT o.qty, o.limit_price FROM orders o JOIN instruments i ON i.id = o.instrument_id "
            "WHERE o.user_id = ? AND o.status = 'Working' AND o.side = 'BUY' AND i.currency = ?",
            (user_id, currency),
        )
        return sum((Decimal(r["limit_price"]) * r["qty"] for r in rows), Decimal("0"))

    def buying_power(self, user_id: str, currency: str) -> Decimal:
        return self.cash(user_id, currency) - self.reserved_cash(user_id, currency)

    # ---- positions --------------------------------------------------------------------------------

    def positions(self, user_id: str) -> list[PositionRow]:
        rows = self.db.query("SELECT * FROM positions WHERE user_id = ? AND qty > 0 ORDER BY instrument_id", (user_id,))
        return [PositionRow(r["instrument_id"], r["qty"], Decimal(r["avg_cost"])) for r in rows]

    def held_qty(self, user_id: str, instrument_id: int) -> int:
        row = self.db.one("SELECT qty FROM positions WHERE user_id = ? AND instrument_id = ?", (user_id, instrument_id))
        return row["qty"] if row else 0

    def sellable_qty(self, user_id: str, instrument_id: int) -> int:
        """Held shares minus those already promised to working sell orders (no shorting)."""
        row = self.db.one(
            "SELECT CAST(COALESCE(SUM(qty), 0) AS INTEGER) AS q FROM orders WHERE user_id = ? AND instrument_id = ? AND status = 'Working' AND side = 'SELL'",
            (user_id, instrument_id),
        )
        return self.held_qty(user_id, instrument_id) - row["q"]

    def _today(self) -> str:
        return self.clock().date().isoformat()

    # ---- orders -----------------------------------------------------------------------------------

    def orders(self, user_id: str, status: str = "all") -> list[OrderRow]:
        sql = "SELECT * FROM orders WHERE user_id = ?"
        if status == "open":
            sql += " AND status = 'Working'"
        elif status == "filled":
            rows = self.db.query(
                sql + " AND status = 'Filled' AND substr(updated_at, 1, 10) = ? ORDER BY id DESC LIMIT 50",
                (user_id, self._today()),
            )
            return [_order(r) for r in rows]
        rows = self.db.query(sql + " ORDER BY id DESC LIMIT 50", (user_id,))
        return [_order(r) for r in rows]

    def order(self, user_id: str, order_id: int) -> OrderRow | None:
        row = self.db.one("SELECT * FROM orders WHERE id = ? AND user_id = ?", (order_id, user_id))
        return _order(row) if row else None

    def orders_today(self, user_id: str) -> int:
        row = self.db.one(
            "SELECT COUNT(*) AS n FROM orders WHERE user_id = ? AND status != 'Rejected' AND substr(created_at, 1, 10) = ?",
            (user_id, self._today()),
        )
        return row["n"]

    def place(
        self, user_id: str, inst: Instrument, side: str, qty: int, order_type: str, limit_price: Decimal | None,
        market_price: Decimal, preview_id: str | None, note: str | None = None,
    ) -> OrderRow:
        """Create the order and fill it now if it is marketable. Atomic, and re-validates funds/holdings."""
        now = self.clock().isoformat()
        marketable = order_type == "MKT" or (
            limit_price is not None and ((side == "BUY" and limit_price >= market_price) or (side == "SELL" and limit_price <= market_price))
        )
        with self.db.transaction(lock_key=user_id):
            if side == "BUY":
                need = money((limit_price if (order_type == "LMT" and not marketable) else market_price) * qty)
                if self.buying_power(user_id, inst.currency) < need:
                    raise LedgerError("INSUFFICIENT_FUNDS", "There isn't enough cash in your paper wallet for this order.")
            elif self.sellable_qty(user_id, inst.conid) < qty:
                raise LedgerError("INSUFFICIENT_SHARES", "You don't hold enough shares to sell that many.")
            order_id = self.db.insert_id(
                "INSERT INTO orders (user_id, instrument_id, side, qty, type, limit_price, status, note, preview_id, created_at, updated_at) "
                "VALUES (?, ?, ?, ?, ?, ?, 'Working', ?, ?, ?, ?)",
                (user_id, inst.conid, side, qty, order_type, str(limit_price) if limit_price else None, note, preview_id, now, now),
            )
            if marketable:
                self._fill(order_id, user_id, inst, side, qty, market_price, now)
        return self.order(user_id, order_id)  # type: ignore[return-value]

    def _fill(self, order_id: int, user_id: str, inst: Instrument, side: str, qty: int, price: Decimal, now: str) -> None:
        value = money(price * qty)
        if side == "BUY":
            self.db.execute("UPDATE wallets SET cash = ? WHERE user_id = ? AND currency = ?",
                            (str(self.cash(user_id, inst.currency) - value), user_id, inst.currency))
            row = self.db.one("SELECT qty, avg_cost FROM positions WHERE user_id = ? AND instrument_id = ?", (user_id, inst.conid))
            if row:
                new_qty = row["qty"] + qty
                new_avg = ((Decimal(row["avg_cost"]) * row["qty"]) + value) / new_qty
                self.db.execute("UPDATE positions SET qty = ?, avg_cost = ? WHERE user_id = ? AND instrument_id = ?",
                                (new_qty, str(new_avg.quantize(Decimal("0.0001"))), user_id, inst.conid))
            else:
                self.db.execute("INSERT INTO positions (user_id, instrument_id, qty, avg_cost) VALUES (?, ?, ?, ?)",
                                (user_id, inst.conid, qty, str((value / qty).quantize(Decimal("0.0001")))))
        else:
            row = self.db.one("SELECT qty, avg_cost FROM positions WHERE user_id = ? AND instrument_id = ?", (user_id, inst.conid))
            if not row or row["qty"] < qty:
                raise LedgerError("INSUFFICIENT_SHARES", "You don't hold enough shares to sell that many.")
            gain = money((price - Decimal(row["avg_cost"])) * qty)
            self.db.execute(
                "UPDATE wallets SET cash = ?, realized_pnl = ? WHERE user_id = ? AND currency = ?",
                (str(self.cash(user_id, inst.currency) + value), str(self.realized_pnl(user_id, inst.currency) + gain), user_id, inst.currency),
            )
            remaining = row["qty"] - qty
            if remaining:
                self.db.execute("UPDATE positions SET qty = ? WHERE user_id = ? AND instrument_id = ?", (remaining, user_id, inst.conid))
            else:
                self.db.execute("DELETE FROM positions WHERE user_id = ? AND instrument_id = ?", (user_id, inst.conid))
        self.db.execute("UPDATE orders SET status = 'Filled', avg_price = ?, updated_at = ? WHERE id = ?", (str(price), now, order_id))

    def fill_working(self, order: OrderRow, inst: Instrument, price: Decimal) -> OrderRow | None:
        """Called by the poller when a working limit order becomes marketable."""
        now = self.clock().isoformat()
        with self.db.transaction(lock_key=order.user_id):
            fresh = self.order(order.user_id, order.id)
            if not fresh or fresh.status != "Working":
                return None
            try:
                self._fill(order.id, order.user_id, inst, order.side, order.qty, price, now)
            except LedgerError as e:
                self.db.execute("UPDATE orders SET status = 'Rejected', note = ?, updated_at = ? WHERE id = ?", (e.message, now, order.id))
        return self.order(order.user_id, order.id)

    def cancel(self, user_id: str, order_id: int) -> OrderRow:
        now = self.clock().isoformat()
        with self.db.transaction(lock_key=user_id):
            order = self.order(user_id, order_id)
            if not order:
                raise LedgerError("ORDER_NOT_FOUND", "I couldn't find that order.")
            if order.status != "Working":
                raise LedgerError("ORDER_NOT_WORKING", f"That order is already {order.status.lower()}.")
            self.db.execute("UPDATE orders SET status = 'Cancelled', updated_at = ? WHERE id = ?", (now, order_id))
        return self.order(user_id, order_id)  # type: ignore[return-value]

    def working_orders_all(self) -> list[OrderRow]:
        return [_order(r) for r in self.db.query("SELECT * FROM orders WHERE status = 'Working'")]
