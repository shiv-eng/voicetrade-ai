"""The keyless fallback drives the same tools, guard and ledger through the full HTTP voice endpoint."""
from __future__ import annotations

import json
from decimal import Decimal

import httpx
import pytest

from app.config import Settings
from app.llm.basic import BasicChat
from app.main import create_app
from conftest import AAPL, INFY, TCS, FakeMarket, fresh_db

pytestmark = pytest.mark.asyncio


async def setup():
    market = FakeMarket()
    app = create_app(Settings(db_path=":memory:", allow_guest_login=True, jwt_secret="s" * 32), market, BasicChat(), background=False, db=fresh_db())
    svc = app.state.svc
    client = httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://t")
    token = (await client.post("/auth/guest", json={})).json()["token"]
    import jwt
    user = jwt.decode(token, svc.settings.jwt_secret, algorithms=["HS256"])["sub"]
    s, secret = svc.sessions.create(user, "en", "female", 1.0)
    svc.hub.register_session(user, s.id)
    return svc, client, user, secret


async def say(client, secret, text):
    r = await client.post("/v1/chat/completions", params={"s": secret}, json={"messages": [{"role": "user", "content": text}]})
    lines = [l[6:] for l in r.text.splitlines() if l.startswith("data: ")]
    return "".join(json.loads(l)["choices"][0]["delta"].get("content", "") for l in lines[:-1])


async def test_buy_flow_needs_a_separate_yes_and_moves_cash():
    svc, client, user, secret = await setup()
    svc.instruments.ensure(INFY)
    reply = await say(client, secret, "Buy 10 shares of Infosys")
    assert "market buy of ten" in reply and "Should I place it" in reply
    assert svc.ledger.cash(user, "INR") == Decimal("1000000")               # preview only
    reply = await say(client, secret, "yes, confirm")
    assert "Done. Bought ten" in reply
    assert svc.ledger.cash(user, "INR") == Decimal("985000")
    assert (await say(client, secret, "what do I own")).startswith("You hold one stock: ten Infosys")


async def test_buy_and_confirm_in_one_sentence_only_previews():
    svc, client, user, secret = await setup()
    svc.instruments.ensure(INFY)
    reply = await say(client, secret, "buy 10 infosys confirm")
    assert "Should I place it" in reply and svc.ledger.orders(user) == []


async def test_no_at_the_preview_places_nothing():
    svc, client, user, secret = await setup()
    svc.instruments.ensure(INFY)
    await say(client, secret, "buy 5 infosys")
    assert "nothing was placed" in await say(client, secret, "no cancel that")
    assert svc.ledger.orders(user) == []


async def test_sell_all_and_balance_questions():
    svc, client, user, secret = await setup()
    svc.instruments.ensure(INFY)
    await say(client, secret, "buy 10 infosys")
    await say(client, secret, "yes")
    await say(client, secret, "sell all my infosys")
    assert "Done. Sold ten" in await say(client, secret, "yes")
    assert "cash" in await say(client, secret, "how much cash do I have")


async def test_watchlist_by_voice_and_quote():
    svc, client, user, secret = await setup()
    svc.instruments.ensure(INFY)
    svc.instruments.ensure(AAPL)
    assert "empty" in await say(client, secret, "show my watchlist")
    assert "added" in await say(client, secret, "add infosys to my watchlist")
    assert "Infosys" in await say(client, secret, "what's on my watchlist")
    assert "one thousand five hundred rupees" in await say(client, secret, "how is infosys doing")


async def test_over_cap_order_is_explained_not_previewed():
    svc, client, user, secret = await setup()
    svc.instruments.ensure(TCS)
    reply = await say(client, secret, "buy 100 tcs")
    assert "per-order limit" in reply and svc.trading.active_preview(user) is None


async def test_smalltalk_is_not_treated_as_a_stock():
    svc, client, user, secret = await setup()
    assert "I'm Mira" in await say(client, secret, "hello")
    assert "I'm Mira" in await say(client, secret, "Hi Mira")
    assert "Anytime" in await say(client, secret, "thanks")
    assert "didn't catch" in await say(client, secret, "purple monkey dishwasher")
    assert svc.db.one("SELECT COUNT(*) AS n FROM instruments")["n"] == 0    # nothing looked up
