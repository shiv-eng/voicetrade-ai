"""Keyless fallback 'model'. With no LLM_API_KEY the server still works: this turns simple English/Hinglish
commands into the *same* tool calls a real model would make, so the real market data, the paper ledger, the
confirmation guard and the risk rules all run. Only the language understanding is basic. A real LLM replaces it
automatically once LLM_API_KEY is set."""
from __future__ import annotations

import json
import re
from decimal import Decimal
from typing import Any, AsyncIterator

from ..market.aliases import resolve_alias
from .orchestrator import ChatEvent, ToolCall

_BUY = re.compile(r"\b(buy|kharid\w*|purchase)\b", re.I)
_SELL = re.compile(r"\b(sell|bech\w*)\b", re.I)
_YES = re.compile(r"\b(yes|yeah|yep|confirm\w*|go ahead|place it|haan|kar do|karo|do it|ok(ay)?)\b", re.I)
_NO = re.compile(r"\b(no|nahi\w*|cancel|stop|ruk\w*|don'?t)\b", re.I)
_GREETING = re.compile(r"^\s*(hi+|hello+|hey+|hii+|namaste|namaskar|good (morning|afternoon|evening)|yo|sup)\W*(mira|there)?\W*$", re.I)
_THANKS = re.compile(r"\b(thanks?|thank you|shukriya|dhanyavaad|ok thanks)\b", re.I)
_WHO = re.compile(r"\b(who are you|what can you do|help|kya kar sakti|what do you do)\b", re.I)
_STOCK_INTENT = re.compile(
    r"\b(price|quote|rate|bhav|how is|how\'?s|doing|trading|stock|share|shares|kaisa|kya haal|watchlist|worth|"
    r"value|chart|range|high|low|52|buy|sell|add|remove)\b", re.I)
_NUM = re.compile(r"(\d[\d,]*(?:\.\d+)?)")
_STOPWORDS = re.compile(
    r"\b(buy|sell|kharid\w*|bech\w*|purchase|shares?|stocks?|of|the|my|to|from|for|at|worth|rupees?|rs|dollars?|"
    r"add|remove|delete|watchlist|watch|list|price|quote|how|is|doing|what|whats|about|me|tell|show|please|a|an|in|"
    r"ka|ki|ke|kya|hai|haal|aaj|today|market|limit|all|half|my|and|on|nse|bse|"
    r"confirm\w*|yes|yeah|haan|ok|okay|now|it|place|go|ahead|do|kar|karo|sab|saare)\b",
    re.I,
)


def _last_user(messages: list[dict]) -> str:
    return next((m["content"] for m in reversed(messages) if m["role"] == "user"), "")


def _tool_results(messages: list[dict]) -> list[tuple[str, dict]]:
    """(tool name, parsed result) for tool calls made since the last user message."""
    idx = max(i for i, m in enumerate(messages) if m["role"] == "user")
    names: dict[str, str] = {}
    out: list[tuple[str, dict]] = []
    for m in messages[idx + 1:]:
        if m["role"] == "assistant":
            for tc in m.get("tool_calls", []):
                names[tc["id"]] = tc["function"]["name"]
        elif m["role"] == "tool":
            try:
                out.append((names.get(m["tool_call_id"], "?"), json.loads(m["content"])))
            except json.JSONDecodeError:
                pass
    return out


def _company(text: str) -> str:
    # "what's"/"Reliance's": the apostrophe breaks \b word matching, so the stopword regex sees "what" and a
    # stray "s" as two separate words and only removes the first, leaving a lone "s" stuck to the company name.
    text = re.sub(r"'s\b", "", text, flags=re.I)
    cleaned = _NUM.sub(" ", _STOPWORDS.sub(" ", text))
    return re.sub(r"\s+", " ", re.sub(r"[^\w&\s]", " ", cleaned)).strip()


def _call(name: str, **args: Any) -> ChatEvent:
    return ChatEvent(tool_calls=[ToolCall(f"call_{name}", name, json.dumps(args))])


def _say(text: str) -> ChatEvent:
    return ChatEvent(text=text)


class BasicChat:
    async def stream(self, messages: list[dict], tools: list[dict]) -> AsyncIterator[ChatEvent]:
        for ev in self._next(messages):
            yield ev

    def _next(self, messages: list[dict]) -> list[ChatEvent]:
        text = _last_user(messages)
        low = text.lower()
        results = _tool_results(messages)
        system = messages[0]["content"]
        m_preview = re.search(r"'preview_id': '(p_\w+)'", system)
        buy, sell = bool(_BUY.search(low)), bool(_SELL.search(low))

        if results:
            return self._after_tools(low, results, buy, sell)

        # 1. answering a live preview
        if m_preview and not buy and not sell:
            if _NO.search(low):
                return [_call("discard_preview", preview_id=m_preview.group(1))]
            if _YES.search(low):
                return [_call("confirm_order", preview_id=m_preview.group(1))]
        # 2. read-only questions
        if re.search(r"\b(portfolio|holdings?|positions?|own|have)\b", low) and not re.search(r"cash|balance", low):
            return [_call("get_positions")]
        if re.search(r"\b(cash|balance|wallet|buying power|money|paisa)\b", low):
            return [_call("get_account_summary")]
        if re.search(r"\b(pnl|p&l|profit|loss|up today|down today)\b", low):
            return [_call("get_pnl")]
        if re.search(r"\b(open orders?|my orders?|pending|orders?)\b", low):
            return [_call("get_orders", status="open")]
        if re.search(r"market (open|status)|is the market", low):
            return [_call("get_market_status", exchange="NASDAQ" if re.search(r"nasdaq|nyse|us\b", low) else "NSE")]
        if "watchlist" in low and not re.search(r"\b(add|remove|delete)\b", low):
            return [_call("get_watchlist")]
        if re.search(r"what (is|are)|explain|kya hota|matlab", low) and not _company(text):
            return [_say(self._explain(low))]
        # 3. small talk, never a stock lookup
        if _GREETING.match(text):
            return [_say("Hi, I'm Mira. Ask me about a stock, your portfolio, your cash or your watchlist, or say buy 10 Infosys.")]
        if _THANKS.search(low):
            return [_say("Anytime. Anything else you'd like to check?")]
        if _WHO.search(low):
            return [_say("I can look up live prices, read your portfolio and cash, manage your watchlist, and place practice trades after you confirm.")]
        # 4. a company: only if the message actually asks about a stock (or is exactly a known name)
        company = _company(text)
        if company and (buy or sell or _STOCK_INTENT.search(low) or resolve_alias(company)):
            return [_call("search_instrument", query=company)]
        return [_say("I didn't catch that as a stock question. Try: how is Reliance doing, what do I own, or buy 10 Infosys.")]

    def _after_tools(self, low: str, results: list[tuple[str, dict]], buy: bool, sell: bool) -> list[ChatEvent]:
        name, res = results[-1]
        if "error" in res:
            return [_say(res.get("message") or "Sorry, I couldn't do that.")]
        if name == "search_instrument":
            found = res.get("results", [])
            if not found:
                return [_say("I couldn't find that stock. Could you say the company name again?")]
            if res.get("ambiguous"):
                names = ", ".join(f["name"] for f in found[:3])
                return [_say(f"Which one do you mean: {names}?")]
            conid = found[0]["conid"]
            if buy or sell:
                args: dict[str, Any] = {"conid": conid, "side": "BUY" if buy else "SELL"}
                limit = re.search(r"\b(?:at|limit)\s*(?:rs\.?|₹|\$)?\s*(\d[\d,]*(?:\.\d+)?)", low)
                without_limit = low.replace(limit.group(0), " ") if limit else low
                nums = [Decimal(n.replace(",", "")) for n in _NUM.findall(without_limit)]
                if re.search(r"\b(all|saare|sab)\b", low) and sell:
                    args["sell_fraction"] = "all"
                elif re.search(r"\bhalf\b|aadha", low) and sell:
                    args["sell_fraction"] = "half"
                elif re.search(r"worth|rupees|rs\b|dollars", low) and nums:
                    args["amount"] = float(nums[0])
                elif nums:
                    args["quantity"] = int(nums[0])
                else:
                    return [_say(f"How many shares of {found[0]['name']}?")]
                if limit:
                    args["order_type"] = "LMT"
                    args["limit_price"] = float(limit.group(1).replace(",", ""))
                return [_call("preview_order", **args)]
            if "watchlist" in low:
                return [_call("update_watchlist", action="remove" if re.search(r"remove|delete", low) else "add", conid=conid)]
            return [_call("get_quote", conid=conid)]
        if name == "preview_order":
            if res.get("blocked"):
                return [_say(res["reason"])]
            return [_say(res["summary_for_speech"] + " " + " ".join(res.get("warnings") or []))]
        if name == "confirm_order":
            return [_say(res.get("summary_for_speech") or "Done.")]
        if name == "discard_preview":
            return [_say("Okay, nothing was placed.")]
        if name == "get_quote":
            extra = f" Day range {res['day_range']}." if res.get("day_range") else ""
            closed = " The market is closed, so that's the last price." if not res.get("market_open") else ""
            return [_say(f"{res['name']} is at {res['spoken_price']}, {res['spoken_move']} today.{extra}{closed}")]
        if name == "get_positions":
            if not res.get("top_positions"):
                return [_say("You don't hold any stocks yet.")]
            parts = [f"{p['quantity']} {p['name']}" for p in res["top_positions"][:3]]
            pnl = ", ".join(res.get("total_profit_or_loss", {}).values())
            return [_say(f"You hold {res['count']} stock{'s' if res['count'] != 1 else ''}: {', '.join(parts)}. Overall {pnl}.")]
        if name == "get_account_summary":
            ws = res["wallets"]
            return [_say(" ".join(f"In your {w['total_value'].split()[-1]} wallet you have {w['cash']} in cash." for w in ws))]
        if name == "get_pnl":
            return [_say(" ".join(f"Today you're at {i['today']}, unrealised {i['unrealised']}." for i in res["per_currency"] if i["today"] != "0 rupees" and i["today"] != "0 dollars") or "No movement in your holdings today.")]
        if name == "get_orders":
            orders = res.get("orders", [])
            return [_say("You have no open orders." if not orders else f"You have {len(orders)} open order{'s' if len(orders) != 1 else ''}, the latest is a {orders[0]['side'].lower()} of {orders[0]['quantity']} {orders[0]['name']}.")]
        if name == "get_watchlist":
            items = res.get("items", [])
            if not items:
                return [_say("Your watchlist is empty. Say add Infosys to my watchlist.")]
            return [_say("On your watchlist: " + "; ".join(f"{i['name']} at {i['price']}" for i in items[:5]) + ".")]
        if name == "update_watchlist":
            return [_say(f"Done. {res.get('name')} {res.get('action')} on your watchlist.")]
        if name == "get_market_status":
            return [_say(f"The {res['exchange']} market is {'open' if res['open'] else 'closed'} right now.")]
        return [_say("Done.")]

    @staticmethod
    def _explain(low: str) -> str:
        if "limit" in low:
            return "A limit order buys or sells only at your price or better. It may not fill if the market never gets there."
        if "stop" in low:
            return "A stop-loss sells automatically if the price falls to a level you choose. It isn't supported here yet."
        if "share" in low or "stock" in low:
            return "A share is a small piece of ownership in a company. If the company does well, the price can rise, and if not, it can fall."
        return "I can explain basics like shares and limit orders. What would you like to know?"
