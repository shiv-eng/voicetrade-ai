"""JSON shapes the Android app parses (see android/docs/BACKEND_CONTRACT.md)."""
from __future__ import annotations

from decimal import Decimal
from typing import Any

from .instruments import Instrument
from .ledger import OrderRow
from .market.base import RawQuote


def money(amount: Decimal, currency: str) -> dict[str, Any]:
    return {"amount": str(amount), "currency": currency}


def quote_dto(inst: Instrument, q: RawQuote) -> dict[str, Any]:
    def opt(v):
        return None if v is None else str(v)

    return {
        "instrument": inst.to_dto(), "last": str(q.last), "change": str(q.change), "changePct": str(q.change_pct),
        "bid": None, "ask": None, "dayHigh": opt(q.day_high), "dayLow": opt(q.day_low), "prevClose": str(q.prev_close),
        "volume": q.volume, "isDelayed": False, "asOf": q.as_of.isoformat(),
        "week52High": opt(q.week52_high), "week52Low": opt(q.week52_low), "marketOpen": q.market_open,
    }


def order_status(order: OrderRow) -> dict[str, Any]:
    s: dict[str, Any] = {"state": order.status}
    if order.status == "Filled":
        s["avgPrice"] = str(order.avg_price)
        s["filled"] = order.qty
    if order.status == "Rejected":
        s["reason"] = order.note or "Rejected"
    return s


def order_dto(order: OrderRow, inst: Instrument) -> dict[str, Any]:
    return {
        "orderId": order.order_id, "instrument": inst.to_dto(), "side": order.side, "quantity": order.qty,
        "type": order.type, "limitPrice": str(order.limit_price) if order.limit_price else None,
        "status": order_status(order), "updatedAt": order.updated_at,
    }
