"""Tool-driven UI cards must not duplicate: a prefetch shortcut and the model's own tool call can both ask
for the same data in one turn, and a free-tier model can lose track and re-call a tool it already has the
answer for. The card should still only show once."""
from __future__ import annotations

import json

import pytest
from conftest import INFY
from test_api import call, make, pair, say, start_voice_session, turn, user_of

pytestmark = pytest.mark.asyncio


def card_events(svc, sid, kind=None):
    cards = [e["data"] for e in svc.hub._buffer[sid] if e["type"] == "card"]
    return [c for c in cards if kind is None or c["card"]["kind"] == kind]


async def test_calling_get_positions_twice_in_one_turn_only_shows_the_card_once():
    """The exact bug reported: asking 'show me my portfolio' rendered the positions card twice."""
    app, svc, client, chat, market = make([])
    h = await pair(client)
    user = user_of(svc, h)
    infy = svc.instruments.ensure(INFY)
    s, secret = start_voice_session(svc, user)

    chat.rounds = [[call("preview_order", conid=infy.conid, side="BUY", quantity=1)], say("Should I place it?")]
    await turn(client, secret, "buy 1 infosys")
    pid = svc.trading.active_preview(user)["preview_id"]
    chat.rounds = [[call("confirm_order", preview_id=pid)], say("Done.")]
    await turn(client, secret, "confirm")

    chat.rounds = [[call("get_positions")], [call("get_positions")], say("You have one position.")]
    await turn(client, secret, "show me my portfolio")

    assert len(card_events(svc, s.id, "positions")) == 1


async def test_calling_get_account_summary_twice_in_one_turn_only_shows_the_card_once():
    app, svc, client, chat, market = make([])
    h = await pair(client)
    s, secret = start_voice_session(svc, user_of(svc, h))

    chat.rounds = [[call("get_account_summary")], [call("get_account_summary")], say("Here's your account.")]
    await turn(client, secret, "what's my balance")

    assert len(card_events(svc, s.id, "account")) == 1


async def test_a_different_card_kind_in_the_same_turn_still_shows_both() -> None:
    """The dedup key includes the kind, so it never hides a genuinely different card."""
    app, svc, client, chat, market = make([])
    h = await pair(client)
    infy = svc.instruments.ensure(INFY)
    s, secret = start_voice_session(svc, user_of(svc, h))

    chat.rounds = [[call("get_quote", conid=infy.conid)], [call("get_account_summary")], say("Here you go.")]
    await turn(client, secret, "how's infosys doing and what's my balance")

    assert len(card_events(svc, s.id, "quote")) == 1
    assert len(card_events(svc, s.id, "account")) == 1


async def test_calling_the_same_read_only_tool_twice_in_one_turn_only_does_the_work_once(w):
    """The duplicate-card bug above is really a duplicate-work bug: a repeated call within one turn used to
    hit the database/market data again too, not just re-show a card. ToolContext.cache answers the repeat
    without re-running the handler at all — cheaper than the card dedup, and fixes it at the source."""
    from app.llm.tools import ToolBox, ToolContext

    tools = ToolBox(w.db, w.instruments, w.market, w.ledger, w.trading, w.portfolio, w.hub)
    ctx = ToolContext(w.user, "s1", "what's my balance")

    first = await tools.call("get_account_summary", "{}", ctx)
    second = await tools.call("get_account_summary", "{}", ctx)

    assert first == second
    rows = w.db.query("SELECT COUNT(*) AS n FROM audit_log WHERE tool = 'get_account_summary'")
    assert int(rows[0]["n"]) == 1  # the second call was served from cache, never reached the handler


async def test_a_new_turn_gets_a_fresh_cache_not_stale_data(w):
    from app.llm.tools import ToolBox, ToolContext

    tools = ToolBox(w.db, w.instruments, w.market, w.ledger, w.trading, w.portfolio, w.hub)
    await tools.call("get_account_summary", "{}", ToolContext(w.user, "s1", "what's my balance"))
    await tools.call("get_account_summary", "{}", ToolContext(w.user, "s1", "and now?"))  # a fresh ctx = a new turn

    rows = w.db.query("SELECT COUNT(*) AS n FROM audit_log WHERE tool = 'get_account_summary'")
    assert int(rows[0]["n"]) == 2


async def test_a_mutating_tool_is_never_cached_even_with_identical_args(w):
    """Placing the same order twice must place two orders, not silently answer the second from cache."""
    from app.llm.tools import ToolBox, ToolContext

    tools = ToolBox(w.db, w.instruments, w.market, w.ledger, w.trading, w.portfolio, w.hub)
    ctx = ToolContext(w.user, "s1", "buy 1 infosys")
    args = json.dumps({"conid": w.infy.conid, "side": "BUY", "quantity": 1})

    await tools.call("preview_order", args, ctx)
    await tools.call("preview_order", args, ctx)

    rows = w.db.query("SELECT COUNT(*) AS n FROM audit_log WHERE tool = 'preview_order'")
    assert int(rows[0]["n"]) == 2  # both ran for real
