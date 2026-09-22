"""Market-data interface. Yahoo is the real implementation; tests plug in a deterministic fake."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal
from typing import Protocol


class MarketDataError(Exception):
    """Market data could not be fetched (network, throttling, unknown symbol)."""


@dataclass(frozen=True)
class SymbolInfo:
    symbol: str          # provider symbol, e.g. RELIANCE.NS or AAPL
    name: str
    exchange: str        # display exchange: NSE, BSE, NASDAQ, NYSE
    currency: str        # INR or USD (the only wallets we hold)


@dataclass(frozen=True)
class RawQuote:
    info: SymbolInfo
    last: Decimal
    prev_close: Decimal
    day_high: Decimal | None
    day_low: Decimal | None
    week52_high: Decimal | None
    week52_low: Decimal | None
    volume: int | None
    market_open: bool
    as_of: datetime
    is_delayed: bool = False

    @property
    def change(self) -> Decimal:
        return self.last - self.prev_close

    @property
    def change_pct(self) -> Decimal:
        if self.prev_close == 0:
            return Decimal("0")
        return (self.change / self.prev_close * 100).quantize(Decimal("0.01"))


class MarketData(Protocol):
    async def search(self, query: str, limit: int = 6) -> list[SymbolInfo]: ...

    async def quote(self, symbol: str) -> RawQuote: ...

    async def usd_inr(self) -> Decimal: ...
