"""Market overview (indices, movers), the morning briefing, and the portfolio value history."""
from __future__ import annotations

import asyncio
import logging
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal
from typing import Any

from .config import Settings
from .db import Database
from .instruments import Instruments
from .ledger import Ledger
from .market.base import MarketData, SymbolInfo
from .market.research import Research
from .portfolio import Portfolio

log = logging.getLogger(__name__)
_IST = timezone(timedelta(hours=5, minutes=30))
INDICES = (("Nifty 50", "^NSEI"), ("Sensex", "^BSESN"), ("Nasdaq", "^IXIC"), ("S&P 500", "^GSPC"))


def _pct(value: float) -> str:
    return f"{'up' if value >= 0 else 'down'} {abs(value):.1f}%"


class Insights:
    def __init__(self, settings: Settings, db: Database, research: Research, market: MarketData, instruments: Instruments,
                 portfolio: Portfolio, ledger: Ledger) -> None:
        self.s, self.db, self.research, self.market = settings, db, research, market
        self.instruments, self.portfolio, self.ledger = instruments, portfolio, ledger

    # ---- indices and movers ---------------------------------------------------------------------

    async def indices(self) -> list[dict[str, Any]]:
        results = await asyncio.gather(*(self.research.index_quote(sym, name) for name, sym in INDICES), return_exceptions=True)
        return [r for r in results if isinstance(r, dict)]

    async def movers(self) -> dict[str, list[dict[str, Any]]]:
        import time as _t
        cached = getattr(self, "_movers", None)
        if cached and _t.monotonic() - cached[0] < 60:
            return cached[1]
        raw = await self.research.movers()

        async def resolve(row: dict) -> dict | None:
            try:
                q = await self.market.quote(f"{row['symbol']}.NS")
            except Exception:
                return None
            inst = self.instruments.ensure(q.info)
            return {"conid": inst.conid, "symbol": inst.ticker, "name": inst.name, "exchange": inst.exchange, "currency": inst.currency,
                    "last": str(q.last), "changePct": round(row["pct"], 2)}

        out = {}
        for key in ("gainers", "losers"):
            resolved = await asyncio.gather(*(resolve(r) for r in raw[key][:5]))
            out[key] = [r for r in resolved if r]
        self._movers = (_t.monotonic(), out)
        return out

    async def overview(self) -> dict[str, Any]:
        indices, movers = await asyncio.gather(self.indices(), self.movers(), return_exceptions=True)
        return {"indices": indices if isinstance(indices, list) else [],
                "gainers": movers["gainers"] if isinstance(movers, dict) else [],
                "losers": movers["losers"] if isinstance(movers, dict) else []}

    # ---- morning briefing -----------------------------------------------------------------------

    async def briefing(self, user_id: str, lang: str = "en") -> dict[str, Any]:
        today = datetime.now(_IST).date()
        indices, pnl, positions, ipos = await asyncio.gather(
            self.indices(), self.portfolio.pnl(user_id), self.portfolio.positions(user_id), self.research.ipos("IN"),
            return_exceptions=True)
        indices = indices if isinstance(indices, list) else []
        by_name = {i["name"]: i for i in indices}

        ipo_lines: list[str] = []
        ipo_facts: list[dict[str, str]] = []
        if isinstance(ipos, dict):
            from .market.research import _day
            for item in ipos.get("open_now", []):
                if item["type"] != "mainboard":
                    continue
                opens, closes = _day(item.get("opens")), _day(item.get("closes"))
                if opens == today:
                    ipo_facts.append({"name": item["name"], "event": "opens today", "price_band": item.get("price_band") or ""})
                elif closes == today:
                    ipo_facts.append({"name": item["name"], "event": "closes today", "price_band": item.get("price_band") or ""})
            for item in ipos.get("coming_soon", []):
                if item["type"] == "mainboard" and _day(item.get("opens")) == today:
                    ipo_facts.append({"name": item["name"], "event": "opens today", "price_band": item.get("price_band") or ""})
            for item in ipos.get("recently_listed", []):
                if item["type"] == "mainboard" and _day(item.get("listed_on")) == today:
                    ipo_facts.append({"name": item["name"], "event": "lists today", "price_band": item.get("price_band") or ""})

        daily = {i["currency"]: float(i["daily"]) for i in (pnl.get("items", []) if isinstance(pnl, dict) else [])}
        holding_count = len(positions) if isinstance(positions, list) else 0

        # display text for the notification (digits are fine on screen)
        hi = lang.startswith("hi")
        parts: list[str] = []
        for label in ("Nifty 50", "Sensex"):
            i = by_name.get(label)
            if i:
                parts.append((f"{label} {i['last']:,.0f} ({i['changePct']:+.1f}%)"))
        if holding_count and daily.get("INR"):
            parts.append((f"आपके होल्डिंग्स आज ₹{daily['INR']:+,.0f}" if hi else f"Your holdings today ₹{daily['INR']:+,.0f}"))
        if ipo_facts:
            parts.append(", ".join(f"{f['name']} {f['event']}" for f in ipo_facts[:2]))
        title = "सुबह की मार्केट ब्रीफिंग" if hi else "Your morning market briefing"
        return {
            "title": title, "text": " · ".join(parts) or ("बाज़ार की ताज़ा जानकारी देखें" if hi else "Tap to hear today's market wrap"),
            "indices": [{"name": i["name"], "last": i["last"], "changePct": i["changePct"]} for i in indices],
            "holdings": {"count": holding_count, "dailyInr": daily.get("INR"), "dailyUsd": daily.get("USD")},
            "ipos": ipo_facts, "date": today.isoformat(),
        }

    # ---- portfolio value over time --------------------------------------------------------------

    async def portfolio_history(self, user_id: str, currency: str, days: int = 90) -> dict[str, Any]:
        """Daily portfolio value rebuilt from the order history and daily closing prices."""
        currency = currency.upper()
        start_cash = self.s.start_cash_usd if currency == "USD" else self.s.start_cash_inr
        rows = self.db.query(
            "SELECT o.side, o.qty, o.avg_price, o.updated_at, o.instrument_id FROM orders o "
            "WHERE o.user_id = ? AND o.status = 'Filled' AND o.avg_price IS NOT NULL ORDER BY o.updated_at", (user_id,))
        fills = []
        for r in rows:
            inst = self.instruments.by_conid(int(r["instrument_id"]))
            if inst and inst.currency == currency:
                fills.append((datetime.fromisoformat(r["updated_at"]).astimezone(_IST).date(), r["side"], int(r["qty"]),
                              Decimal(r["avg_price"]), inst))
        today = datetime.now(_IST).date()
        if not fills:
            return {"currency": currency, "points": [], "first": float(start_cash), "last": float(start_cash), "changePct": 0.0}

        first_day = max(fills[0][0], today - timedelta(days=days))
        symbols = sorted({f[4].symbol for f in fills})
        rng = "1mo" if (today - first_day).days <= 28 else "6mo" if (today - first_day).days <= 175 else "1y"
        closes: dict[str, dict[date, float]] = {}
        results = await asyncio.gather(*(self.research.daily_closes(s, rng) for s in symbols), return_exceptions=True)
        for sym, res in zip(symbols, results):
            closes[sym] = {datetime.fromtimestamp(t, _IST).date(): c for t, c in res} if isinstance(res, list) else {}

        qty: dict[int, int] = {}
        cash = Decimal(start_cash)
        idx = 0
        points: list[list[float]] = []
        last_price: dict[str, float] = {}
        day = fills[0][0] - timedelta(days=1)
        while day <= today:
            while idx < len(fills) and fills[idx][0] <= day:
                _, side, q, price, inst = fills[idx]
                cash += (price * q) if side == "SELL" else -(price * q)
                qty[inst.conid] = qty.get(inst.conid, 0) + (q if side == "BUY" else -q)
                idx += 1
            if day.weekday() < 5 or day == today:
                value = float(cash)
                for conid, held in qty.items():
                    if not held:
                        continue
                    sym = self.instruments.by_conid(conid).symbol
                    if day in closes.get(sym, {}):
                        last_price[sym] = closes[sym][day]
                    if sym in last_price:
                        value += held * last_price[sym]
                    else:  # no close yet (fresh trade today): use the fill price
                        value += held * float(next(f[3] for f in fills if f[4].conid == conid))
                if day >= first_day - timedelta(days=1):
                    points.append([int(datetime(day.year, day.month, day.day, 15, 30, tzinfo=_IST).timestamp()), round(value, 2)])
            day += timedelta(days=1)
        first, last = points[0][1], points[-1][1]
        return {"currency": currency, "points": points, "first": first, "last": last,
                "changePct": round((last - first) / first * 100, 2) if first else 0.0}
