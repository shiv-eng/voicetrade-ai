"""Read-side views over the ledger, priced with live quotes: account, positions, P&L, watchlist."""
from __future__ import annotations

import asyncio
from datetime import datetime
from decimal import Decimal

from .db import Database
from .dto import money, quote_dto
from .instruments import Instrument, Instruments
from .ledger import Ledger, utcnow
from .market.base import MarketData, MarketDataError, RawQuote

MAX_WATCHLIST = 20


class Portfolio:
    def __init__(self, db: Database, ledger: Ledger, instruments: Instruments, market: MarketData) -> None:
        self.db, self.ledger, self.instruments, self.market = db, ledger, instruments, market

    async def _quotes(self, insts: list[Instrument]) -> dict[int, RawQuote | None]:
        async def one(i: Instrument):
            try:
                return await self.market.quote(i.symbol)
            except MarketDataError:
                return None
        results = await asyncio.gather(*(one(i) for i in insts))
        return {i.conid: r for i, r in zip(insts, results)}

    async def positions(self, user_id: str) -> list[dict]:
        rows = self.ledger.positions(user_id)
        insts = [self.instruments.by_conid(r.instrument_id) for r in rows]
        pairs = [(r, i) for r, i in zip(rows, insts) if i]
        quotes = await self._quotes([i for _, i in pairs])
        out = []
        for r, i in pairs:
            q = quotes[i.conid]
            price = q.last if q else r.avg_cost
            prev = q.prev_close if q else price
            out.append({
                "instrument": i.to_dto(), "quantity": str(r.qty), "avgCost": str(r.avg_cost),
                "marketPrice": str(price), "marketValue": str((price * r.qty).quantize(Decimal("0.01"))),
                "unrealizedPnl": str(((price - r.avg_cost) * r.qty).quantize(Decimal("0.01"))),
                "dayChange": str(((price - prev) * r.qty).quantize(Decimal("0.01"))),
            })
        return out

    async def account(self, user_id: str) -> dict:
        positions = await self.positions(user_id)
        wallets = []
        for currency in ("INR", "USD"):
            invested = sum((Decimal(p["marketValue"]) for p in positions if p["instrument"]["currency"] == currency), Decimal("0"))
            cash = self.ledger.cash(user_id, currency)
            wallets.append({
                "currency": currency, "cash": str(cash), "buyingPower": str(self.ledger.buying_power(user_id, currency)),
                "positionsValue": str(invested), "netLiquidation": str(cash + invested),
            })
        return {"accountId": user_id, "isPaper": True, "wallets": wallets}

    async def pnl(self, user_id: str) -> dict:
        positions = await self.positions(user_id)
        items = []
        for currency in ("INR", "USD"):
            mine = [p for p in positions if p["instrument"]["currency"] == currency]
            items.append({
                "currency": currency,
                "daily": str(sum((Decimal(p["dayChange"]) for p in mine), Decimal("0"))),
                "unrealized": str(sum((Decimal(p["unrealizedPnl"]) for p in mine), Decimal("0"))),
                "realized": str(self.ledger.realized_pnl(user_id, currency)),
            })
        return {"items": items}

    # ---- watchlist -------------------------------------------------------------------------------

    def watchlist_instruments(self, user_id: str) -> list[Instrument]:
        rows = self.db.query("SELECT instrument_id FROM watchlist WHERE user_id = ? ORDER BY position", (user_id,))
        return [i for r in rows if (i := self.instruments.by_conid(r["instrument_id"]))]

    async def watchlist_quotes(self, user_id: str) -> list[dict]:
        insts = self.watchlist_instruments(user_id)
        quotes = await self._quotes(insts)
        return [{"instrument": i.to_dto(), "quote": quote_dto(i, quotes[i.conid]) if quotes[i.conid] else None} for i in insts]

    def watchlist_add(self, user_id: str, inst: Instrument, now: datetime | None = None) -> bool:
        if self.db.one("SELECT 1 FROM watchlist WHERE user_id = ? AND instrument_id = ?", (user_id, inst.conid)):
            return True
        n = self.db.one("SELECT COUNT(*) AS n, COALESCE(MAX(position), -1) AS m FROM watchlist WHERE user_id = ?", (user_id,))
        if n["n"] >= MAX_WATCHLIST:
            return False
        self.db.execute("INSERT INTO watchlist (user_id, instrument_id, position, added_at) VALUES (?, ?, ?, ?)",
                        (user_id, inst.conid, n["m"] + 1, (now or utcnow()).isoformat()))
        return True

    def watchlist_remove(self, user_id: str, conid: int) -> None:
        self.db.execute("DELETE FROM watchlist WHERE user_id = ? AND instrument_id = ?", (user_id, conid))

    def watchlist_move(self, user_id: str, conid: int, up: bool) -> None:
        ids = [r["instrument_id"] for r in self.db.query("SELECT instrument_id FROM watchlist WHERE user_id = ? ORDER BY position", (user_id,))]
        if conid not in ids:
            return
        i = ids.index(conid)
        j = i - 1 if up else i + 1
        if not 0 <= j < len(ids):
            return
        ids[i], ids[j] = ids[j], ids[i]
        with self.db.transaction():
            for pos, cid in enumerate(ids):
                self.db.execute("UPDATE watchlist SET position = ? WHERE user_id = ? AND instrument_id = ?", (pos, user_id, cid))
