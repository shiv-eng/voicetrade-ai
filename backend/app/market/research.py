"""Everything beyond a live price: price history for charts, company fundamentals, news, and IPO calendars.

All free public sources, no keys: Yahoo Finance (chart, news, quoteSummary), NSE India (IPO lists) and Nasdaq (US IPO
calendar). Results are cached for a few minutes so a chatty user does not hammer them."""
from __future__ import annotations

import asyncio
import logging
import re
import time
from datetime import date, datetime, timedelta, timezone
from typing import Any

import httpx
import xml.etree.ElementTree as ET
from email.utils import parsedate_to_datetime

log = logging.getLogger(__name__)

_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
_YAHOO = "https://query1.finance.yahoo.com"

# period -> (yahoo range, yahoo interval)
PERIODS: dict[str, tuple[str, str]] = {
    "1d": ("1d", "5m"), "1w": ("5d", "15m"), "1m": ("1mo", "1d"),
    "6m": ("6mo", "1d"), "1y": ("1y", "1d"), "5y": ("5y", "1wk"),
}
MAX_POINTS = 100


class ResearchError(Exception):
    pass

_DATE_RANGE = re.compile(r"(\d{1,2}-[A-Za-z]{3}-\d{4})\s*to\s*(\d{1,2}-[A-Za-z]{3}-\d{4})")
_PRICES = re.compile(r"(\d[\d,]*(?:\.\d+)?)")
_LOT = re.compile(r"(\d[\d,]*)")
_EMAIL = re.compile(r"[\w.+-]+@[\w-]+\.[\w.-]+")
_IST = timezone(timedelta(hours=5, minutes=30))


def _add_business_days(d: date, n: int) -> date:
    """Weekends skipped; exchange holidays are not known, so callers label the result 'expected'."""
    while n > 0:
        d += timedelta(days=1)
        if d.weekday() < 5:
            n -= 1
    return d


def _ipo_status(opens: date | None, closes: date | None, today: date) -> str:
    if opens and today < opens:
        return "Upcoming"
    if closes and today <= closes:
        return "Live"
    return "Closed"


def _num(text: Any) -> float | None:
    try:
        return float(str(text).replace(",", ""))
    except (TypeError, ValueError):
        return None


def parse_issue_info(items: list[dict]) -> dict[str, str]:
    return {str(i["title"]).strip(): str(i.get("value") or "").strip().strip('"') for i in items if i.get("title")}


def build_ipo_detail(symbol: str, series: str, raw: dict[str, Any], today: date) -> dict[str, Any]:
    info = parse_issue_info((raw.get("issueInfo") or {}).get("dataList") or [])
    get = lambda *keys: next((info[k] for k in keys if info.get(k)), None)  # noqa: E731

    m = _DATE_RANGE.search(info.get("Issue Period", ""))
    opens, closes = (_day(m.group(1)), _day(m.group(2))) if m else (None, None)
    prices = [float(x.replace(",", "")) for x in _PRICES.findall((get("Price Range") or "").replace("Rs.", "").replace("Rs", ""))]
    low, high = (min(prices), max(prices)) if prices else (None, None)
    lot_text = get("Bid Lot", "Lot Size", "Minimum Order Quantity") or ""
    lot = int(_LOT.search(lot_text).group(1).replace(",", "")) if _LOT.search(lot_text) else None
    status = _ipo_status(opens, closes, today)

    timeline = []
    if opens:
        timeline.append({"label": "Opens", "date": opens.isoformat(), "done": today >= opens})
    if closes:
        timeline.append({"label": "Closes", "date": closes.isoformat(), "done": today > closes})
        timeline.append({"label": "Allotment", "date": _add_business_days(closes, 1).isoformat(), "done": False, "expected": True})
        timeline.append({"label": "Listing", "date": _add_business_days(closes, 3).isoformat(), "done": False, "expected": True})
    for t in timeline:
        if t.get("expected"):
            t["done"] = date.fromisoformat(t["date"]) < today

    rows = raw.get("bidDetails") or []
    names = {"1": "QIB", "2": "NII", "2.1": "bNII (above ₹10 lakh)", "2.2": "sNII (₹2 to 10 lakh)", "3": "Retail", "4": "Employees"}
    categories = []
    for r in rows:
        label = names.get(str(r.get("srNo")))
        times = _num(r.get("noOfTime"))
        if label and times is not None:
            categories.append({"name": label, "times": round(times, 2), "offered": _num(r.get("noOfSharesOffered")),
                               "bid": _num(r.get("noOfsharesBid"))})
    if not categories:  # some issues (mostly SME) report through the 'active category' table instead
        for r in (raw.get("activeCat") or {}).get("dataList") or []:
            label = names.get(str(r.get("srNo")))
            times = _num(r.get("noOfTotalMeant"))
            if label and times is not None:
                categories.append({"name": label, "times": round(times, 2), "offered": _num(r.get("noOfShareOffered")),
                                   "bid": _num(r.get("noOfSharesBid"))})
    categories = [c for c in categories if c["times"] > 0 or (c.get("offered") or 0) > 0]
    overall = _num((raw.get("demandGraphALL") or raw.get("demandGraph") or {}).get("noOfTimesIssueSubscribed"))

    facts: list[tuple[str, str | None]] = [
        ("Issue type", get("Issue Type")), ("Face value", get("Face Value")),
        ("Retail limit", get("Maximum Subscription Amount for Retail Investor")),
        ("Bidding hours", get("IPO Market Timings")), ("Lead manager", get("Book Running Lead Managers")),
        ("Registrar", get("Name of the Registrar")), ("Sponsor bank", get("Sponsor Bank")),
    ]
    contact = _EMAIL.search(info.get("Contact person name number and Email id", ""))
    if contact:
        facts.append(("Registrar email", contact.group(0)))
    links = []
    for label, key in (("Red Herring Prospectus", "Red Herring Prospectus"), ("Basis of issue price", "Ratios / Basis of Issue Price"),
                       ("Anchor allocation", "Anchor Allocation Report")):
        url = info.get(key, "")
        if url.startswith("http"):
            links.append({"label": label, "url": url})
    size_text = get("Issue Size") or ""
    return {
        "kind": "ipo_detail", "symbol": symbol, "series": series, "status": status,
        "type": "SME" if series.upper() == "SME" else "mainboard",
        "name": next((k for k in info if k != "Symbol" and not info[k]), None) or symbol,
        "price_low": low, "price_high": high, "lot_size": lot,
        "min_investment": round(lot * high) if lot and high else None,
        "issue_size": (size_text[:240] + ("…" if len(size_text) > 240 else "")) or None,
        "opens": opens.isoformat() if opens else None, "closes": closes.isoformat() if closes else None,
        "timeline": timeline, "subscription": {"overall": overall, "categories": categories},
        "facts": [{"label": k, "value": v} for k, v in facts if v], "links": links,
    }


def _f(value: Any) -> float | None:
    try:
        return None if value is None else float(value)
    except (TypeError, ValueError):
        return None


def _day(text: str | None) -> date | None:
    for fmt in ("%d-%b-%Y", "%m/%d/%Y"):
        try:
            return datetime.strptime((text or "").strip(), fmt).date()
        except ValueError:
            continue
    return None


class Research:
    def __init__(self) -> None:
        headers = {"User-Agent": _UA, "Accept": "application/json, text/plain, */*", "Accept-Language": "en-US,en;q=0.9"}
        self._yahoo = httpx.AsyncClient(headers=headers, timeout=10.0, follow_redirects=True)
        self._nse = httpx.AsyncClient(headers=headers, timeout=15.0, follow_redirects=True)
        self._nasdaq = httpx.AsyncClient(
            headers={**headers, "Origin": "https://www.nasdaq.com", "Referer": "https://www.nasdaq.com/"},
            timeout=15.0, follow_redirects=True,
        )
        self._cache: dict[str, tuple[float, Any]] = {}
        self._crumb: str | None = None
        self._nse_ready = 0.0

    async def aclose(self) -> None:
        for c in (self._yahoo, self._nse, self._nasdaq):
            await c.aclose()

    def _cached(self, key: str, ttl: float) -> Any | None:
        hit = self._cache.get(key)
        return hit[1] if hit and time.monotonic() - hit[0] < ttl else None

    def _store(self, key: str, value: Any) -> Any:
        self._cache[key] = (time.monotonic(), value)
        return value

    # ---- price history (charts) ------------------------------------------------------------------

    async def history(self, symbol: str, period: str = "1m") -> dict[str, Any]:
        period = period if period in PERIODS else "1m"
        key = f"hist:{symbol}:{period}"
        if (hit := self._cached(key, 60)) is not None:
            return hit
        rng, interval = PERIODS[period]
        try:
            resp = await self._yahoo.get(f"{_YAHOO}/v8/finance/chart/{symbol}", params={"range": rng, "interval": interval})
            resp.raise_for_status()
            result = resp.json()["chart"]["result"][0]
        except Exception as e:
            raise ResearchError(f"No price history for {symbol} ({e})") from e
        stamps = result.get("timestamp") or []
        closes = (result.get("indicators", {}).get("quote") or [{}])[0].get("close") or []
        pts = [(int(t), float(c)) for t, c in zip(stamps, closes) if c is not None]
        if len(pts) < 2:
            raise ResearchError(f"Not enough price history for {symbol}")
        if len(pts) > MAX_POINTS:  # thin evenly, always keeping the last point
            step = len(pts) / MAX_POINTS
            pts = [pts[int(i * step)] for i in range(MAX_POINTS - 1)] + [pts[-1]]
        meta = result.get("meta", {})
        base = _f(meta.get("chartPreviousClose")) if period == "1d" else pts[0][1]
        base = base or pts[0][1]
        last = pts[-1][1]
        values = [p[1] for p in pts]
        return self._store(key, {
            "period": period, "points": [[t, round(c, 4)] for t, c in pts], "first": round(base, 4), "last": round(last, 4),
            "high": round(max(values), 4), "low": round(min(values), 4),
            "change_pct": round((last - base) / base * 100, 2) if base else 0.0,
            "currency": meta.get("currency"),
        })

    # ---- indices, movers and unthinned daily closes ------------------------------------------------

    async def index_quote(self, symbol: str, name: str) -> dict[str, Any]:
        key = f"idx:{symbol}"
        if (hit := self._cached(key, 60)) is not None:
            return hit
        try:
            resp = await self._yahoo.get(f"{_YAHOO}/v8/finance/chart/{symbol}", params={"range": "1d", "interval": "15m"})
            resp.raise_for_status()
            result = resp.json()["chart"]["result"][0]
        except Exception as e:
            raise ResearchError(f"No data for {name} ({e})") from e
        meta = result.get("meta", {})
        last = _f(meta.get("regularMarketPrice"))
        prev = _f(meta.get("chartPreviousClose")) or _f(meta.get("previousClose"))
        closes = [c for c in ((result.get("indicators", {}).get("quote") or [{}])[0].get("close") or []) if c is not None]
        if last is None or not prev:
            raise ResearchError(f"No data for {name}")
        return self._store(key, {"name": name, "symbol": symbol, "last": round(last, 2), "changePct": round((last - prev) / prev * 100, 2),
                                 "spark": [round(c, 2) for c in closes[-40:]]})

    async def movers(self) -> dict[str, list[dict[str, Any]]]:
        """Top Nifty 50 gainers and losers right now (NSE)."""
        key = "movers"
        if (hit := self._cached(key, 120)) is not None:
            return hit

        async def fetch(which: str) -> list[dict[str, Any]]:
            data = await self._nse_get(f"/api/live-analysis-variations?index={which}")
            rows = (data.get("NIFTY") or {}).get("data") or []
            return [{"symbol": r["symbol"], "pct": float(r.get("perChange") or 0)} for r in rows if r.get("symbol")]

        gainers, losers = await asyncio.gather(fetch("gainers"), fetch("loosers"))
        return self._store(key, {"gainers": sorted(gainers, key=lambda r: -r["pct"]), "losers": sorted(losers, key=lambda r: r["pct"])})

    async def daily_closes(self, symbol: str, rng: str = "6mo") -> list[tuple[int, float]]:
        key = f"dc:{symbol}:{rng}"
        if (hit := self._cached(key, 600)) is not None:
            return hit
        try:
            resp = await self._yahoo.get(f"{_YAHOO}/v8/finance/chart/{symbol}", params={"range": rng, "interval": "1d"})
            resp.raise_for_status()
            result = resp.json()["chart"]["result"][0]
        except Exception as e:
            raise ResearchError(f"No price history for {symbol} ({e})") from e
        closes = (result.get("indicators", {}).get("quote") or [{}])[0].get("close") or []
        return self._store(key, [(int(t), float(c)) for t, c in zip(result.get("timestamp") or [], closes) if c is not None])


    # ---- fundamentals ----------------------------------------------------------------------------

    async def _crumb_token(self) -> str:
        if self._crumb:
            return self._crumb
        await self._yahoo.get("https://fc.yahoo.com")  # sets the session cookie the crumb is tied to
        resp = await self._yahoo.get(f"{_YAHOO}/v1/test/getcrumb")
        resp.raise_for_status()
        self._crumb = resp.text.strip()
        return self._crumb

    async def overview(self, symbol: str) -> dict[str, Any]:
        key = f"ov:{symbol}"
        if (hit := self._cached(key, 1800)) is not None:
            return hit
        modules = "summaryDetail,defaultKeyStatistics,financialData,assetProfile,price"
        data: dict[str, Any] | None = None
        for attempt in range(2):
            try:
                resp = await self._yahoo.get(
                    f"{_YAHOO}/v10/finance/quoteSummary/{symbol}", params={"modules": modules, "crumb": await self._crumb_token()})
                if resp.status_code in (401, 403):
                    self._crumb = None
                    continue
                resp.raise_for_status()
                data = resp.json()["quoteSummary"]["result"][0]
                break
            except Exception as e:
                if attempt == 1:
                    raise ResearchError(f"No company data for {symbol} ({e})") from e
        if data is None:
            raise ResearchError(f"No company data for {symbol}")
        raw = lambda block, k: _f((data.get(block, {}).get(k) or {}).get("raw"))  # noqa: E731
        fd, ap = data.get("financialData", {}), data.get("assetProfile", {})
        out = {
            "sector": ap.get("sector"), "industry": ap.get("industry"),
            "summary": (ap.get("longBusinessSummary") or "")[:280] or None,
            "market_cap": raw("summaryDetail", "marketCap"), "pe": raw("summaryDetail", "trailingPE"),
            "forward_pe": raw("summaryDetail", "forwardPE"), "eps": raw("defaultKeyStatistics", "trailingEps"),
            "dividend_yield_pct": (raw("summaryDetail", "dividendYield") or 0) * 100 or None,
            "beta": raw("summaryDetail", "beta"),
            "profit_margin_pct": (raw("financialData", "profitMargins") or 0) * 100 or None,
            "revenue_growth_pct": (raw("financialData", "revenueGrowth") or 0) * 100 or None,
            "return_on_equity_pct": (raw("financialData", "returnOnEquity") or 0) * 100 or None,
            "debt_to_equity": raw("financialData", "debtToEquity"),
            "analyst_view": fd.get("recommendationKey") if fd.get("recommendationKey") not in (None, "none") else None,
            "analyst_target": raw("financialData", "targetMeanPrice"),
            "analyst_count": raw("financialData", "numberOfAnalystOpinions"),
        }
        return self._store(key, out)

    # ---- news ------------------------------------------------------------------------------------

    async def news(self, query: str, limit: int = 5) -> list[dict[str, Any]]:
        """Recent headlines from Google News (free RSS, India edition); Yahoo's feed only as a fallback."""
        key = f"news:{query.lower()}:{limit}"
        if (hit := self._cached(key, 300)) is not None:
            return hit
        items: list[dict[str, Any]] = []
        try:
            resp = await self._yahoo.get(
                "https://news.google.com/rss/search",
                params={"q": f"{query} when:7d", "hl": "en-IN", "gl": "IN", "ceid": "IN:en"})
            resp.raise_for_status()
            now = datetime.now(timezone.utc)
            for item in ET.fromstring(resp.content).iter("item"):
                title = (item.findtext("title") or "").strip()
                source = (item.findtext("source") or "").strip()
                if source and title.endswith(" - " + source):
                    title = title[: -len(source) - 3]
                try:
                    published = parsedate_to_datetime(item.findtext("pubDate") or "")
                    age_h = max(0.0, (now - published).total_seconds() / 3600)
                except (TypeError, ValueError):
                    age_h = 0.0
                items.append({
                    "title": title, "publisher": source,
                    "age": f"{int(age_h)} hours ago" if age_h < 48 else f"{int(age_h // 24)} days ago",
                    "url": (item.findtext("link") or "").strip(),
                })
                if len(items) >= limit:
                    break
        except Exception as e:
            log.warning("Google News failed for %r: %s", query, e)
        if not items:
            try:
                resp = await self._yahoo.get(f"{_YAHOO}/v1/finance/search", params={"q": query, "newsCount": limit, "quotesCount": 0})
                now_ts = datetime.now(timezone.utc).timestamp()
                for n in resp.json().get("news", [])[:limit]:
                    age_h = max(0, (now_ts - float(n.get("providerPublishTime") or now_ts)) / 3600)
                    items.append({"title": n.get("title", ""), "publisher": n.get("publisher", ""),
                                  "age": f"{int(age_h)} hours ago" if age_h < 48 else f"{int(age_h // 24)} days ago", "url": n.get("link", "")})
            except Exception as e:
                raise ResearchError(f"News is unavailable right now ({e})") from e
        return self._store(key, items)

    # ---- IPOs ------------------------------------------------------------------------------------

    async def _nse_get(self, path: str) -> Any:
        """NSE only answers callers that already hold its session cookies, so visit the site first and refresh often."""
        if time.monotonic() - self._nse_ready > 240:
            await self._nse.get("https://www.nseindia.com/")
            await self._nse.get("https://www.nseindia.com/market-data/all-upcoming-issues-ipo")
            self._nse_ready = time.monotonic()
        resp = await self._nse.get(
            f"https://www.nseindia.com{path}", headers={"Referer": "https://www.nseindia.com/market-data/all-upcoming-issues-ipo"})
        if resp.status_code in (401, 403):
            self._nse_ready = 0.0
            raise ResearchError("NSE refused the request")
        resp.raise_for_status()
        return resp.json()

    async def ipos(self, market: str = "IN") -> dict[str, Any]:
        market = "US" if market.upper() == "US" else "IN"
        key = f"ipo:{market}"
        if (hit := self._cached(key, 600)) is not None:
            return hit
        try:
            return self._store(key, await (self._ipos_us() if market == "US" else self._ipos_in()))
        except ResearchError:
            raise
        except Exception as e:
            raise ResearchError(f"IPO data is unavailable right now ({e})") from e


    async def ipo_detail(self, symbol: str, series: str = "EQ", name: str | None = None) -> dict[str, Any]:
        key = f"ipod:{symbol}:{series}"
        if (hit := self._cached(key, 300)) is not None:
            return {**hit, "name": name or hit["name"]}
        try:
            raw = await self._nse_get(f"/api/ipo-detail?symbol={symbol}&series={series}")
        except ResearchError:
            raise
        except Exception as e:
            raise ResearchError(f"No IPO details for {symbol} ({e})") from e
        today = datetime.now(_IST).date()
        detail = build_ipo_detail(symbol, series, raw, today)
        self._store(key, detail)
        return {**detail, "name": name or detail["name"]}

    async def find_ipo(self, query: str) -> tuple[str, str, str] | None:
        """(symbol, series, name) of the Indian IPO that best matches what the user said."""
        data = await self.ipos("IN")
        words = [w for w in re.findall(r"[a-z0-9]+", query.lower()) if w not in {"ipo", "limited", "ltd", "the", "of", "india"}]
        best: tuple[int, dict] | None = None
        for key in ("open_now", "coming_soon", "closed_awaiting_listing", "recently_listed"):
            for item in data.get(key, []):
                name = (item.get("name") or "").lower()
                score = sum(1 for w in words if w in name) + (5 if item.get("symbol", "").lower() in query.lower().split() else 0)
                if words and score and (best is None or score > best[0]):
                    best = (score, item)
        if not best:
            return None
        item = best[1]
        return item["symbol"], "SME" if item["type"] == "SME" else "EQ", item["name"]

    async def _brief(self, sem: asyncio.Semaphore, symbol: str, series: str) -> dict[str, Any] | None:
        async with sem:
            try:
                return await self.ipo_detail(symbol, series)
            except Exception:
                return None

    async def _enrich(self, items: list[dict[str, Any]]) -> None:
        """Lot size, minimum investment and overall subscription for the IPOs on the list (best effort, parallel)."""
        sem = asyncio.Semaphore(4)
        details = await asyncio.gather(*(self._brief(sem, i["symbol"], "SME" if i["type"] == "SME" else "EQ") for i in items))
        for item, d in zip(items, details):
            if d:
                item.update(lot_size=d["lot_size"], min_investment=d["min_investment"], price_low=d["price_low"],
                            price_high=d["price_high"], overall_times=d["subscription"]["overall"])

    async def _ipos_in(self) -> dict[str, Any]:
        current, upcoming, past = await asyncio.gather(
            self._nse_get("/api/ipo-current-issue"), self._nse_get("/api/all-upcoming-issues?category=ipo"),
            self._nse_get("/api/public-past-issues"),
        )
        today = datetime.now(timezone(timedelta(hours=5, minutes=30))).date()

        def kind(series: str | None) -> str:
            return "SME" if (series or "").upper() == "SME" else "mainboard"

        open_now = [{
            "name": i.get("companyName"), "type": kind(i.get("series")), "opens": i.get("issueStartDate"),
            "closes": i.get("issueEndDate"), "price_band": i.get("issuePrice"), "symbol": i.get("symbol"),
            "subscribed_times": round(float(i["noOfTime"]), 2) if i.get("noOfTime") not in (None, "") else None,
        } for i in current]
        open_names = {i["name"] for i in open_now}
        coming = [{
            "name": i.get("companyName"), "type": kind(i.get("series")), "opens": i.get("issueStartDate"),
            "closes": i.get("issueEndDate"), "price_band": i.get("issuePrice"), "symbol": i.get("symbol"),
        } for i in upcoming if i.get("companyName") not in open_names and i.get("status") != "Active"]
        recent = []
        for p in past:
            listed, closed = _day(p.get("listingDate")), _day(p.get("ipoEndDate"))
            if listed and today - timedelta(days=14) <= listed <= today:
                recent.append({"name": p.get("company"), "type": kind(p.get("securityType")), "listed_on": p.get("listingDate"),
                               "price_band": p.get("priceRange"), "symbol": p.get("symbol")})
        closed_wait = [{
            "name": p.get("company"), "type": kind(p.get("securityType")), "closed_on": p.get("ipoEndDate"),
            "price_band": p.get("priceRange"), "symbol": p.get("symbol"),
        } for p in past if (c := _day(p.get("ipoEndDate"))) and today - timedelta(days=7) <= c < today and (p.get("listingDate") or "-") == "-"]
        by_type = lambda rows: sorted(rows, key=lambda r: r["type"] != "mainboard")  # noqa: E731
        open_now, coming = by_type(open_now)[:8], by_type(coming)[:6]
        try:
            await asyncio.wait_for(self._enrich(open_now + coming), timeout=8.0)  # lot sizes are nice to have, not worth a long wait
        except asyncio.TimeoutError:
            log.warning("IPO detail enrichment timed out; showing the list without lot sizes")
        return {"market": "IN", "as_of": today.isoformat(), "open_now": open_now, "coming_soon": coming,
                "closed_awaiting_listing": by_type(closed_wait)[:6], "recently_listed": by_type(recent)[:8]}

    async def _ipos_us(self) -> dict[str, Any]:
        today = date.today()
        months = {today.strftime("%Y-%m")}
        if today.day > 15:
            months.add((today.replace(day=28) + timedelta(days=5)).strftime("%Y-%m"))
        upcoming: list[dict] = []
        priced: list[dict] = []
        for m in sorted(months):
            resp = await self._nasdaq.get("https://api.nasdaq.com/api/ipo/calendar", params={"date": m})
            resp.raise_for_status()
            data = resp.json().get("data") or {}
            for r in ((data.get("upcoming") or {}).get("upcomingTable") or {}).get("rows") or []:
                upcoming.append({"name": r.get("companyName"), "symbol": r.get("proposedTickerSymbol"), "exchange": r.get("proposedExchange"),
                                 "expected": r.get("expectedPriceDate"), "price": r.get("proposedSharePrice") or None,
                                 "raise": r.get("dollarValueOfSharesOffered") or None})
            for r in (data.get("priced") or {}).get("rows") or []:
                d = _day(r.get("pricedDate"))
                if d and d >= today - timedelta(days=14):
                    priced.append({"name": r.get("companyName"), "symbol": r.get("proposedTickerSymbol"), "exchange": r.get("proposedExchange"),
                                   "priced_on": r.get("pricedDate"), "price": r.get("proposedSharePrice"), "raise": r.get("dollarValueOfSharesOffered")})
        return {"market": "US", "as_of": today.isoformat(), "coming_soon": upcoming[:8], "recently_priced": priced[:8]}
