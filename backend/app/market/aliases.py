"""Spoken names to provider symbols. Speech recognition mangles company names, so common ones are pinned
here (including how they tend to come out of Hindi/Hinglish ASR); anything else goes to search."""
from __future__ import annotations

import re

ALIASES: dict[str, str] = {
    # India (NSE)
    "reliance": "RELIANCE.NS", "reliance industries": "RELIANCE.NS", "ril": "RELIANCE.NS",
    "infosys": "INFY.NS", "infy": "INFY.NS", "infosis": "INFY.NS", "इन्फोसिस": "INFY.NS",
    "tcs": "TCS.NS", "tata consultancy": "TCS.NS", "tata consultancy services": "TCS.NS", "t c s": "TCS.NS",
    "hdfc bank": "HDFCBANK.NS", "hdfc": "HDFCBANK.NS", "hdfcbank": "HDFCBANK.NS",
    "icici bank": "ICICIBANK.NS", "icici": "ICICIBANK.NS",
    "sbi": "SBIN.NS", "state bank": "SBIN.NS", "state bank of india": "SBIN.NS",
    "axis bank": "AXISBANK.NS", "axis": "AXISBANK.NS",
    "kotak": "KOTAKBANK.NS", "kotak mahindra": "KOTAKBANK.NS", "kotak bank": "KOTAKBANK.NS",
    "bharti airtel": "BHARTIARTL.NS", "airtel": "BHARTIARTL.NS",
    "itc": "ITC.NS", "i t c": "ITC.NS",
    "wipro": "WIPRO.NS", "hcl tech": "HCLTECH.NS", "hcl": "HCLTECH.NS", "tech mahindra": "TECHM.NS",
    "larsen": "LT.NS", "larsen and toubro": "LT.NS", "l and t": "LT.NS", "l&t": "LT.NS",
    # "tata motors" is deliberately not pinned: the company demerged (27 Sep 2026) into two separately listed
    # entities (Tata Motors Passenger Vehicles, and a new Tata Motors Limited for commercial vehicles), and the
    # old TATAMOTORS.NS ticker is now delisted. Left unpinned, a plain "Tata Motors" falls through to live
    # search, which correctly asks the user which one they mean instead of guessing.
    "tata steel": "TATASTEEL.NS", "tata power": "TATAPOWER.NS",
    "maruti": "MARUTI.NS", "maruti suzuki": "MARUTI.NS",
    "mahindra": "M&M.NS", "mahindra and mahindra": "M&M.NS", "m&m": "M&M.NS",
    "bajaj finance": "BAJFINANCE.NS", "bajaj finserv": "BAJAJFINSV.NS",
    "asian paints": "ASIANPAINT.NS", "hindustan unilever": "HINDUNILVR.NS", "hul": "HINDUNILVR.NS",
    "sun pharma": "SUNPHARMA.NS", "titan": "TITAN.NS", "adani enterprises": "ADANIENT.NS", "adani ports": "ADANIPORTS.NS",
    "ntpc": "NTPC.NS", "ongc": "ONGC.NS", "coal india": "COALINDIA.NS", "power grid": "POWERGRID.NS",
    # Zomato renamed itself to Eternal Limited; the old ZOMATO.NS ticker is gone, but people still say "Zomato".
    "zomato": "ETERNAL.NS", "eternal": "ETERNAL.NS", "paytm": "PAYTM.NS", "nykaa": "NYKAA.NS",
    "mrf": "MRF.NS", "yes bank": "YESBANK.NS", "vodafone idea": "IDEA.NS", "jio financial": "JIOFIN.NS",
    # US
    "apple": "AAPL", "aapl": "AAPL", "microsoft": "MSFT", "msft": "MSFT",
    "google": "GOOGL", "alphabet": "GOOGL", "amazon": "AMZN", "meta": "META", "facebook": "META",
    "tesla": "TSLA", "nvidia": "NVDA", "netflix": "NFLX", "amd": "AMD", "intel": "INTC",
    "disney": "DIS", "coca cola": "KO", "pepsi": "PEP", "walmart": "WMT", "nike": "NKE",
    "boeing": "BA", "visa": "V", "mastercard": "MA", "paypal": "PYPL", "uber": "UBER", "spotify": "SPOT",
}

_FILLER = re.compile(r"\b(shares?|stocks?|ka|ki|ke|of|the|ltd|limited|inc|corp|corporation|company)\b", re.I)


def normalise(text: str) -> str:
    return re.sub(r"\s+", " ", _FILLER.sub(" ", text.lower().replace(".", " "))).strip()


def resolve_alias(text: str) -> str | None:
    """Return a provider symbol if the spoken text is a known name, else None."""
    key = normalise(text)
    if key in ALIASES:
        return ALIASES[key]
    raw = text.lower().strip()
    return ALIASES.get(raw)


# Spoken sector name -> (Yahoo index symbol, spoken display name). NSE's own sectoral indices — real, tradable
# benchmarks, not a guess — cover the sectors people actually ask about ("how are IT stocks doing"). Multi-word
# keys are more specific and must be tried first, so "psu bank" is not swallowed by the plain "bank" entry.
SECTOR_INDEX: dict[str, tuple[str, str]] = {
    "it": ("^CNXIT", "Nifty IT"), "information technology": ("^CNXIT", "Nifty IT"), "tech": ("^CNXIT", "Nifty IT"),
    "technology": ("^CNXIT", "Nifty IT"),
    "psu bank": ("^CNXPSUBANK", "Nifty PSU Bank"), "psu banks": ("^CNXPSUBANK", "Nifty PSU Bank"),
    "public sector bank": ("^CNXPSUBANK", "Nifty PSU Bank"), "public sector banks": ("^CNXPSUBANK", "Nifty PSU Bank"),
    "government bank": ("^CNXPSUBANK", "Nifty PSU Bank"), "government banks": ("^CNXPSUBANK", "Nifty PSU Bank"),
    "bank": ("^NSEBANK", "Nifty Bank"), "banking": ("^NSEBANK", "Nifty Bank"), "banks": ("^NSEBANK", "Nifty Bank"),
    "auto": ("^CNXAUTO", "Nifty Auto"), "automobile": ("^CNXAUTO", "Nifty Auto"), "automobiles": ("^CNXAUTO", "Nifty Auto"),
    "cars": ("^CNXAUTO", "Nifty Auto"),
    "pharma": ("^CNXPHARMA", "Nifty Pharma"), "pharmaceutical": ("^CNXPHARMA", "Nifty Pharma"),
    "pharmaceuticals": ("^CNXPHARMA", "Nifty Pharma"), "healthcare": ("^CNXPHARMA", "Nifty Pharma"),
    "fmcg": ("^CNXFMCG", "Nifty FMCG"), "consumer goods": ("^CNXFMCG", "Nifty FMCG"),
    "metal": ("^CNXMETAL", "Nifty Metal"), "metals": ("^CNXMETAL", "Nifty Metal"), "steel": ("^CNXMETAL", "Nifty Metal"),
    "mining": ("^CNXMETAL", "Nifty Metal"),
    "renewable energy": ("^CNXENERGY", "Nifty Energy"), "green energy": ("^CNXENERGY", "Nifty Energy"),
    "energy": ("^CNXENERGY", "Nifty Energy"), "power": ("^CNXENERGY", "Nifty Energy"),
    "oil and gas": ("^CNXENERGY", "Nifty Energy"), "renewables": ("^CNXENERGY", "Nifty Energy"),
    "solar": ("^CNXENERGY", "Nifty Energy"),
    "real estate": ("^CNXREALTY", "Nifty Realty"), "realty": ("^CNXREALTY", "Nifty Realty"),
    "financial services": ("^CNXFIN", "Nifty Financial Services"), "finance": ("^CNXFIN", "Nifty Financial Services"),
    "nbfc": ("^CNXFIN", "Nifty Financial Services"),
}

# A handful of the biggest, most-recognised names in each sector, so Mira can cite specific movers alongside
# the index. Every symbol here has been checked live against Yahoo before being added.
SECTOR_STOCKS: dict[str, list[str]] = {
    "^CNXIT": ["TCS.NS", "INFY.NS", "HCLTECH.NS"],
    "^NSEBANK": ["HDFCBANK.NS", "ICICIBANK.NS", "SBIN.NS"],
    "^CNXPSUBANK": ["SBIN.NS", "PNB.NS", "BANKBARODA.NS", "CANBK.NS"],
    "^CNXAUTO": ["MARUTI.NS", "M&M.NS"],  # Tata Motors dropped: demerged 27 Sep 2026, old ticker now delisted
    "^CNXPHARMA": ["SUNPHARMA.NS", "DRREDDY.NS", "CIPLA.NS"],
    "^CNXFMCG": ["HINDUNILVR.NS", "ITC.NS"],
    "^CNXMETAL": ["TATASTEEL.NS", "JSWSTEEL.NS", "HINDALCO.NS"],
    "^CNXENERGY": ["NTPC.NS", "ONGC.NS", "ADANIGREEN.NS"],
    "^CNXREALTY": ["DLF.NS", "GODREJPROP.NS"],
    "^CNXFIN": ["BAJFINANCE.NS", "HDFCLIFE.NS"],
}


_SECTOR_COLLECTIVE = re.compile(r"\b(stocks?|shares?|companies|sector|sectors|index|indices)\b", re.I)


def resolve_sector(text: str) -> tuple[str, str, list[str]] | None:
    """(index symbol, spoken name, representative stocks) for a sector mentioned in the text, or None.
    Longer keys (more words) are tried first so a specific one isn't shadowed by a shorter, looser one."""
    words = set(re.findall(r"[a-z&]+", text.lower()))
    has_marker = bool(_SECTOR_COLLECTIVE.search(text))
    for key, (symbol, display) in sorted(SECTOR_INDEX.items(), key=lambda kv: -len(kv[0].split())):
        key_words = key.split()
        if not all(w in words for w in key_words):
            continue
        # A bare single word like "bank", "power", "finance", "steel" or "solar" is also, very often, part of
        # a specific company's actual name (HDFC Bank, Tata Power, Bajaj Finance, Tata Steel, Vikram Solar) —
        # only treat it as the whole sector when the user also said something collective ("stocks", "sector",
        # "companies"...) or used its plural ("banks", "metals"). A longer, distinctive phrase ("psu bank",
        # "real estate") never collides with a company's own name, so it needs no such extra evidence.
        risky = len(key_words) == 1 and not key.endswith("s")
        if risky and not has_marker:
            continue
        return symbol, display, SECTOR_STOCKS.get(symbol, [])
    return None
