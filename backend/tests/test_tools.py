"""Tool-driven UI cards must not duplicate: a prefetch shortcut and the model's own tool call can both ask
for the same data in one turn, and a free-tier model can lose track and re-call a tool it already has the
answer for. The card should still only show once."""
from __future__ import annotations

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
