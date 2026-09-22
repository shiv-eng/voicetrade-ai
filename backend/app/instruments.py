"""Instrument registry. The app identifies a stock by a numeric conid; we hand out our own row id."""
from __future__ import annotations

from dataclasses import dataclass

from .db import Database
from .market.aliases import resolve_alias
from .market.base import MarketData, MarketDataError, SymbolInfo


@dataclass(frozen=True)
class Instrument:
    conid: int
    symbol: str      # provider symbol (RELIANCE.NS)
    name: str
    exchange: str
    currency: str

    @property
    def ticker(self) -> str:
        """Short symbol for display and speech: RELIANCE.NS -> RELIANCE."""
        return self.symbol.split(".")[0]

    def to_dto(self) -> dict:
        return {"conid": self.conid, "symbol": self.ticker, "name": self.name,
                "exchange": self.exchange, "currency": self.currency}


class Instruments:
    def __init__(self, db: Database, market: MarketData) -> None:
        self._db = db
        self._market = market

    def _row(self, row) -> Instrument:
        return Instrument(row["id"], row["symbol"], row["name"], row["exchange"], row["currency"])

    def by_conid(self, conid: int) -> Instrument | None:
        row = self._db.one("SELECT * FROM instruments WHERE id = ?", (conid,))
        return self._row(row) if row else None

    def ensure(self, info: SymbolInfo) -> Instrument:
        self._db.insert_ignore(
            "instruments", ["symbol", "name", "exchange", "currency"],
            [info.symbol, info.name, info.exchange, info.currency], "symbol",
        )
        row = self._db.one("SELECT * FROM instruments WHERE symbol = ?", (info.symbol,))
        return self._row(row)

    async def search(self, query: str, limit: int = 5) -> list[Instrument]:
        """Resolve a spoken name to real, tradable instruments. Known aliases win, then live search."""
        found: list[SymbolInfo] = []
        alias = resolve_alias(query)
        if alias:
            try:
                found.append((await self._market.quote(alias)).info)
            except MarketDataError:
                pass
        if not found:
            found = await self._market.search(query, limit)
        seen: set[str] = set()
        out: list[Instrument] = []
        for info in found:
            if info.symbol in seen:
                continue
            seen.add(info.symbol)
            out.append(self.ensure(info))
        return out[:limit]
