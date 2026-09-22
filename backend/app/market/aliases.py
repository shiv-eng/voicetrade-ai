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
    "tata motors": "TATAMOTORS.NS", "tata steel": "TATASTEEL.NS", "tata power": "TATAPOWER.NS",
    "maruti": "MARUTI.NS", "maruti suzuki": "MARUTI.NS",
    "mahindra": "M&M.NS", "mahindra and mahindra": "M&M.NS", "m&m": "M&M.NS",
    "bajaj finance": "BAJFINANCE.NS", "bajaj finserv": "BAJAJFINSV.NS",
    "asian paints": "ASIANPAINT.NS", "hindustan unilever": "HINDUNILVR.NS", "hul": "HINDUNILVR.NS",
    "sun pharma": "SUNPHARMA.NS", "titan": "TITAN.NS", "adani enterprises": "ADANIENT.NS", "adani ports": "ADANIPORTS.NS",
    "ntpc": "NTPC.NS", "ongc": "ONGC.NS", "coal india": "COALINDIA.NS", "power grid": "POWERGRID.NS",
    "zomato": "ZOMATO.NS", "eternal": "ETERNAL.NS", "paytm": "PAYTM.NS", "nykaa": "NYKAA.NS",
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
