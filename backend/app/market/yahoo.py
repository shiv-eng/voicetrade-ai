"""Real market data from Yahoo Finance's public chart and search endpoints (no API key).

Unofficial but stable for years; if it ever throttles, swap in another MarketData implementation.
"""
from __future__ import annotations

import asyncio
import logging
import time
from datetime import datetime, timezone
from decimal import Decimal
from typing import Any

import httpx

from .base import MarketDataError, RawQuote, SymbolInfo

log = logging.getLogger(__name__)

_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

# Yahoo exchange code -> (display name, wallet currency). Anything else is not tradable here.
EXCHANGES: dict[str, tuple[str, str]] = {
    "NSI": ("NSE", "INR"),
    "BSE": ("BSE", "INR"),
    "NMS": ("NASDAQ", "USD"),
    "NGM": ("NASDAQ", "USD"),
    "NCM": ("NASDAQ", "USD"),
    "NYQ": ("NYSE", "USD"),
    "ASE": ("NYSE American", "USD"),
    "PCX": ("NYSE Arca", "USD"),
}
_TRADABLE_TYPES = {"EQUITY", "ETF"}
_QUOTE_TTL_S = 5.0
_SEARCH_TTL_S = 24 * 3600


def _dec(value: Any) -> Decimal | None:
    if value is None:
        return None
    return Decimal(str(round(float(value), 4)))


class YahooMarketData:
    def __init__(self, client: httpx.AsyncClient | None = None) -> None:
        self._client = client or httpx.AsyncClient(
            headers={"User-Agent": _UA, "Accept": "application/json"}, timeout=8.0, follow_redirects=True,
        )
        self._quotes: dict[str, tuple[float, RawQuote]] = {}
        self._searches: dict[str, tuple[float, list[SymbolInfo]]] = {}

    async def aclose(self) -> None:
        await self._client.aclose()

    async def _get_json(self, url: str, params: dict[str, Any]) -> dict[str, Any]:
        last_error: Exception | None = None
        for attempt in range(3):
            try:
                resp = await self._client.get(url, params=params)
                if resp.status_code == 429 or resp.status_code >= 500:
                    raise MarketDataError(f"market data busy (HTTP {resp.status_code})")
                if resp.status_code == 404:
                    raise MarketDataError("symbol not found")
                resp.raise_for_status()
                return resp.json()
            except (httpx.HTTPError, MarketDataError) as e:
                last_error = e
                if isinstance(e, MarketDataError) and "not found" in str(e):
                    break
                await asyncio.sleep(0.4 * (attempt + 1))
        raise MarketDataError(f"could not reach market data: {last_error}")

    async def search(self, query: str, limit: int = 6) -> list[SymbolInfo]:
        q = query.strip()
        if not q:
            return []
        cached = self._searches.get(q.lower())
        if cached and time.monotonic() - cached[0] < _SEARCH_TTL_S:
            return cached[1][:limit]
        data = await self._get_json(
            "https://query2.finance.yahoo.com/v1/finance/search",
            {"q": q, "quotesCount": 12, "newsCount": 0, "listsCount": 0},
        )
        results: list[SymbolInfo] = []
        for item in data.get("quotes", []):
            if item.get("quoteType") not in _TRADABLE_TYPES:
                continue
            exch = EXCHANGES.get(item.get("exchange", ""))
            if not exch or not item.get("symbol"):
                continue
            results.append(SymbolInfo(
                symbol=item["symbol"],
                name=item.get("longname") or item.get("shortname") or item["symbol"],
                exchange=exch[0],
                currency=exch[1],
            ))
        # Prefer NSE, then other Indian, then US, so "Infosys" lands on the NSE line first.
        order = {"NSE": 0, "BSE": 1, "NASDAQ": 2, "NYSE": 3}
        results.sort(key=lambda s: order.get(s.exchange, 9))
        self._searches[q.lower()] = (time.monotonic(), results)
        return results[:limit]

    async def quote(self, symbol: str) -> RawQuote:
        cached = self._quotes.get(symbol)
        if cached and time.monotonic() - cached[0] < _QUOTE_TTL_S:
            return cached[1]
        data = await self._get_json(
            f"https://query1.finance.yahoo.com/v8/finance/chart/{symbol}", {"range": "1d", "interval": "1d"},
        )
        try:
            meta = data["chart"]["result"][0]["meta"]
        except (KeyError, IndexError, TypeError):
            raise MarketDataError("symbol not found") from None
        exch = EXCHANGES.get(meta.get("exchangeName", ""))
        if not exch:
            raise MarketDataError(f"{symbol} trades on an unsupported exchange")
        price = _dec(meta.get("regularMarketPrice"))
        if price is None:
            raise MarketDataError("no price available right now")
        prev = _dec(meta.get("chartPreviousClose") or meta.get("previousClose")) or price
        now = int(time.time())
        regular = (meta.get("currentTradingPeriod") or {}).get("regular") or {}
        is_open = bool(regular.get("start") and regular["start"] <= now <= regular.get("end", 0))
        quote = RawQuote(
            info=SymbolInfo(
                symbol=meta.get("symbol", symbol),
                name=meta.get("longName") or meta.get("shortName") or symbol,
                exchange=exch[0],
                currency=exch[1],
            ),
            last=price,
            prev_close=prev,
            day_high=_dec(meta.get("regularMarketDayHigh")),
            day_low=_dec(meta.get("regularMarketDayLow")),
            week52_high=_dec(meta.get("fiftyTwoWeekHigh")),
            week52_low=_dec(meta.get("fiftyTwoWeekLow")),
            volume=meta.get("regularMarketVolume"),
            market_open=is_open,
            as_of=datetime.fromtimestamp(meta.get("regularMarketTime", now), tz=timezone.utc),
        )
        self._quotes[symbol] = (time.monotonic(), quote)
        return quote

    async def usd_inr(self) -> Decimal:
        data = await self._get_json(
            "https://query1.finance.yahoo.com/v8/finance/chart/USDINR=X", {"range": "1d", "interval": "1d"},
        )
        try:
            return _dec(data["chart"]["result"][0]["meta"]["regularMarketPrice"]) or Decimal("83")
        except (KeyError, IndexError, TypeError):
            return Decimal("83")
