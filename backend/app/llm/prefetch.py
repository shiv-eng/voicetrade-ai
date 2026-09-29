"""Latency: every LLM round trip costs ~1.5 s, and a price question used to take three (search, quote, answer).
For plain read-only questions we can tell from the words which tools will be needed, so the server runs them first
(in parallel) and hands the results to the model with the question. The model then answers in one round.
Only read-only tools are ever prefetched. Anything that changes money still goes through the model + confirmation."""
from __future__ import annotations

import re

from ..market.aliases import resolve_sector
from .basic import _BUY, _NO, _SELL, _STOCK_INTENT, _THANKS, _WHO, _YES, _company

_SHORT_FALLBACK_MAX_WORDS = 5

_MUTATING = re.compile(r"\b(buy|sell|kharid\w*|bech\w*|purchase|add|remove|delete|cancel|confirm\w*|place)\b", re.I)


def plan(text: str, has_preview: bool) -> list[tuple[str, dict]]:
    low = text.lower()
    if re.search(r"\d", text):
        return []  # a number means a sum or a trade: let the model decide what to look up
    if _MUTATING.search(low) or _BUY.search(low) or _SELL.search(low):
        return []
    if re.search(r"\b(chart|graph|ipo|ipos|news|headlines?|listing|listings|lot|lots|apply|allot|allotment|alert|alerts|calculate)\b|चार्ट|ग्राफ|आईपीओ|खबर|समाचार|न्यूज़", low):
        return []  # these have their own tools; the model picks the right one
    if has_preview and (_YES.search(low) or _NO.search(low)):
        return []
    out: list[tuple[str, dict]] = []
    hi_cash = re.search(r"कैश|बैलेंस|बैलेन्स|वॉलेट|पैसे|पैसा|बचे", low)
    hi_holdings = re.search(r"पोर्टफोलियो|होल्डिंग|शेयर हैं|स्टॉक हैं", low)
    if (re.search(r"\b(portfolio|holdings?|positions?|own)\b", low) or hi_holdings) \
            and not re.search(r"cash|balance", low) and not hi_cash:
        out.append(("get_positions", {}))
    if re.search(r"\b(cash|balance|wallet|buying power|money|paisa)\b", low) or hi_cash:
        out.append(("get_account_summary", {}))
    if re.search(r"\b(pnl|p&l|profit|loss|up today|down today)\b", low) or re.search(r"मुनाफ़?ा|नुकसान|फायदा|प्रॉफिट|लॉस", low):
        out.append(("get_pnl", {}))
    if re.search(r"\b(open orders?|my orders?|pending)\b", low):
        out.append(("get_orders", {"status": "open"}))
    if "watchlist" in low or "वॉचलिस्ट" in low or "वाचलिस्ट" in low:
        out.append(("get_watchlist", {}))
    if re.search(r"\b(brief me|briefing|morning update|market wrap|market update|how'?s the market|market today)\b", low) \
            or re.search(r"ब्रीफिंग|मार्केट.*(कैसा|अपडेट)", low):
        out.append(("get_market_briefing", {}))
    if re.search(r"\b(gainers?|losers?|top movers?|biggest (gain|los)\w*|movers|up the most|down the most)\b", low):
        out.append(("get_top_movers", {}))
    # A short message with none of the intent words above ("hey Figma", a bare correction, a one-word reply to
    # "which company?") is still very likely a company reference in this app — nearly nothing else is that
    # short. Force a real lookup for it too, rather than leaving the model free to answer a company question
    # from its own (frequently stale or wrong) memory just because the phrasing didn't match a keyword.
    short_fallback = (
        len(text.split()) <= _SHORT_FALLBACK_MAX_WORDS
        and not _THANKS.search(low) and not _WHO.search(low) and not _YES.search(low) and not _NO.search(low)
    )
    if not out and (_STOCK_INTENT.search(low) or short_fallback):
        # A sector word ("IT", "solar", "banking") must never reach the company-name extractor: "IT" alone
        # collides with the stopword "it" (the pronoun) and gets stripped, and the leftover text still gets
        # searched as if it were a company name — Yahoo's fuzzy search then hands back some unrelated stock.
        sector = resolve_sector(text)
        if sector:
            out.append(("get_sector_overview", {"sector": text}))
        else:
            company = _company(text)
            if company and 2 <= len(company) <= 40:
                price_only = re.search(r"\b(price|quote|rate|bhav|worth|value|trading at)\b|भाव|कीमत|प्राइस|रेट", low)
                out.append(("get_quote" if price_only else "get_company_overview", {"company": company}))
    return out[:3]
