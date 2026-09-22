"""Order safety and paper-money accounting (PRD TC-01..TC-15, adapted to the paper ledger)."""
from __future__ import annotations

from datetime import timedelta
from decimal import Decimal

import pytest

from app.trading import TradeError

pytestmark = pytest.mark.asyncio


async def buy(w, inst=None, qty=10, **kw):
    inst = inst or w.infy
    r = await w.trading.preview_order(w.user, "s1", inst.conid, "BUY", quantity=qty, **kw)
    assert not r.get("blocked"), r
    return r


async def test_new_user_gets_both_wallets(w):
    assert w.ledger.cash(w.user, "INR") == Decimal("1000000")
    assert w.ledger.cash(w.user, "USD") == Decimal("10000")


async def test_users_have_separate_wallets(w):
    other = w.ledger.create_user("other")
    await buy(w)
    order = await w.trading.confirm(w.user, w.trading.active_preview(w.user)["preview_id"])
    assert order.status == "Filled"
    assert w.ledger.cash(other, "INR") == Decimal("1000000")


async def test_preview_never_moves_money(w):
    await buy(w)
    assert w.ledger.cash(w.user, "INR") == Decimal("1000000")
    assert w.ledger.orders(w.user) == []


async def test_confirm_buy_deducts_cash_and_adds_position(w):
    r = await buy(w, qty=10)
    order = await w.trading.confirm(w.user, r["preview_id"])
    assert order.status == "Filled" and order.avg_price == Decimal("1500")
    assert w.ledger.cash(w.user, "INR") == Decimal("1000000") - Decimal("15000")
    pos = w.ledger.positions(w.user)
    assert len(pos) == 1 and pos[0].qty == 10 and pos[0].avg_cost == Decimal("1500")


async def test_sell_adds_cash_and_tracks_realized_pnl(w):
    r = await buy(w, qty=10)
    await w.trading.confirm(w.user, r["preview_id"])
    w.market.prices["INFY.NS"] = Decimal("1600")
    s = await w.trading.preview_order(w.user, "s1", w.infy.conid, "SELL", quantity=4)
    await w.trading.confirm(w.user, s["preview_id"])
    assert w.ledger.cash(w.user, "INR") == Decimal("1000000") - 15000 + 6400
    assert w.ledger.positions(w.user)[0].qty == 6
    assert w.ledger.realized_pnl(w.user, "INR") == Decimal("400.00")


async def test_average_cost_across_two_buys(w):
    await w.trading.confirm(w.user, (await buy(w, qty=10))["preview_id"])
    w.market.prices["INFY.NS"] = Decimal("1800")
    await w.trading.confirm(w.user, (await buy(w, qty=10))["preview_id"])
    assert w.ledger.positions(w.user)[0].avg_cost == Decimal("1650.0000")


async def test_us_stock_uses_usd_wallet(w):
    r = await w.trading.preview_order(w.user, "s1", w.aapl.conid, "BUY", quantity=2)
    await w.trading.confirm(w.user, r["preview_id"])
    assert w.ledger.cash(w.user, "USD") == Decimal("9600")
    assert w.ledger.cash(w.user, "INR") == Decimal("1000000")


async def test_sell_all_and_half_use_current_position(w):
    await w.trading.confirm(w.user, (await buy(w, qty=10))["preview_id"])
    half = await w.trading.preview_order(w.user, "s1", w.infy.conid, "SELL", sell_fraction="half")
    assert "5 Infosys" in half["summary_for_speech"]
    all_ = await w.trading.preview_order(w.user, "s1", w.infy.conid, "SELL", sell_fraction="all")
    assert "10 Infosys" in all_["summary_for_speech"]


async def test_sell_without_position_is_blocked(w):
    r = await w.trading.preview_order(w.user, "s1", w.infy.conid, "SELL", quantity=1)
    assert r["blocked"]


async def test_amount_order_rounds_down_to_whole_shares(w):
    r = await w.trading.preview_order(w.user, "s1", w.infy.conid, "BUY", amount=Decimal("20000"))
    assert "13 Infosys" in r["summary_for_speech"]  # 20000 / 1500 = 13.33


async def test_quantity_and_amount_together_is_ambiguous(w):
    r = await w.trading.preview_order(w.user, "s1", w.infy.conid, "BUY", quantity=10, amount=Decimal("5000"))
    assert r["blocked"] and r["code"] == "AMBIGUOUS_QUANTITY"


async def test_value_cap_blocks_with_no_preview(w):
    r = await w.trading.preview_order(w.user, "s1", w.tcs.conid, "BUY", quantity=20)  # 80,000 > 50,000
    assert r["blocked"] and r["code"] == "RISK_BLOCKED"
    assert w.trading.active_preview(w.user) is None


async def test_quantity_cap_blocks(w):
    w.risk.update_limits(w.user, Decimal("500000"), 5, 20)
    r = await w.trading.preview_order(w.user, "s1", w.infy.conid, "BUY", quantity=6)
    assert r["blocked"]


async def test_cannot_spend_more_than_wallet(w):
    w.risk.update_limits(w.user, Decimal("500000"), 5000, 20)
    w.ledger.db.execute("UPDATE wallets SET cash = '1000' WHERE user_id = ? AND currency = 'INR'", (w.user,))
    r = await w.trading.preview_order(w.user, "s1", w.infy.conid, "BUY", quantity=5)
    assert r["blocked"] and "afford" in r["reason"]


async def test_kill_switch_blocks_preview_and_confirm(w):
    r = await buy(w)
    w.risk.set_kill_switch(w.user, True)
    with pytest.raises(TradeError) as e:
        await w.trading.confirm(w.user, r["preview_id"])
    assert e.value.code == "KILL_SWITCH"
    blocked = await w.trading.preview_order(w.user, "s1", w.infy.conid, "BUY", quantity=1)
    assert blocked["blocked"] and blocked["code"] == "KILL_SWITCH"
    assert w.ledger.cash(w.user, "INR") == Decimal("1000000")


async def test_confirm_twice_places_one_order(w):
    r = await buy(w)
    await w.trading.confirm(w.user, r["preview_id"])
    with pytest.raises(TradeError) as e:
        await w.trading.confirm(w.user, r["preview_id"])
    assert e.value.code == "PREVIEW_EXPIRED"
    assert len(w.ledger.orders(w.user)) == 1
    assert w.ledger.cash(w.user, "INR") == Decimal("985000")


async def test_new_preview_replaces_old_one(w):
    first = await buy(w, qty=10)
    second = await buy(w, qty=20)
    with pytest.raises(TradeError):
        await w.trading.confirm(w.user, first["preview_id"])
    order = await w.trading.confirm(w.user, second["preview_id"])
    assert order.qty == 20


async def test_expired_preview_cannot_be_confirmed(w):
    r = await buy(w)
    w.trading.clock = lambda: w.ledger.clock() + timedelta(seconds=61)
    with pytest.raises(TradeError) as e:
        await w.trading.confirm(w.user, r["preview_id"])
    assert e.value.code == "PREVIEW_EXPIRED"
    assert w.ledger.orders(w.user) == []


async def test_price_drift_over_two_percent_rejects_confirm(w):
    r = await buy(w)
    w.market.prices["INFY.NS"] = Decimal("1535")  # +2.33%
    with pytest.raises(TradeError) as e:
        await w.trading.confirm(w.user, r["preview_id"])
    assert e.value.code == "PRICE_DRIFT"
    assert w.ledger.orders(w.user) == []


async def test_small_price_move_is_fine_and_fills_at_live_price(w):
    r = await buy(w)
    w.market.prices["INFY.NS"] = Decimal("1510")
    order = await w.trading.confirm(w.user, r["preview_id"])
    assert order.avg_price == Decimal("1510")


async def test_someone_elses_preview_cannot_be_confirmed(w):
    r = await buy(w)
    other = w.ledger.create_user("mallory")
    with pytest.raises(TradeError):
        await w.trading.confirm(other, r["preview_id"])


async def test_limit_order_waits_then_fills_when_price_reached(w):
    r = await buy(w, qty=10, order_type="LMT", limit_price=Decimal("1400"))
    order = await w.trading.confirm(w.user, r["preview_id"])
    assert order.status == "Working"
    assert w.ledger.cash(w.user, "INR") == Decimal("1000000")          # cash not yet spent...
    assert w.ledger.buying_power(w.user, "INR") == Decimal("986000")   # ...but reserved
    assert await w.trading.fill_due_limit_orders() == []
    w.market.prices["INFY.NS"] = Decimal("1395")
    filled = await w.trading.fill_due_limit_orders()
    assert len(filled) == 1 and filled[0][0].status == "Filled"
    assert w.ledger.cash(w.user, "INR") == Decimal("1000000") - Decimal("13950")


async def test_marketable_limit_fills_immediately_at_better_price(w):
    r = await buy(w, qty=10, order_type="LMT", limit_price=Decimal("1600"))
    order = await w.trading.confirm(w.user, r["preview_id"])
    assert order.status == "Filled" and order.avg_price == Decimal("1500")


async def test_cancel_working_order_releases_reserved_cash(w):
    r = await buy(w, qty=10, order_type="LMT", limit_price=Decimal("1400"))
    order = await w.trading.confirm(w.user, r["preview_id"])
    p = await w.trading.preview_cancel(w.user, "s1", order.order_id)
    cancelled = await w.trading.confirm(w.user, p["preview_id"])
    assert cancelled.status == "Cancelled"
    assert w.ledger.buying_power(w.user, "INR") == Decimal("1000000")


async def test_market_closed_fills_at_last_price_with_warning_by_default(w):
    w.market.open = False
    r = await buy(w)
    assert any("closed" in x for x in r["warnings"])
    order = await w.trading.confirm(w.user, r["preview_id"])
    assert order.status == "Filled" and "closed" in (order.note or "")


async def test_market_closed_blocks_when_configured(w):
    from dataclasses import replace
    w.settings = replace(w.settings, fill_when_market_closed=False)
    w.trading.settings = w.settings
    w.market.open = False
    r = await w.trading.preview_order(w.user, "s1", w.infy.conid, "BUY", quantity=1)
    assert r["blocked"] and r["code"] == "MARKET_CLOSED"


async def test_market_data_outage_blocks_orders(w):
    w.market.down = True
    r = await w.trading.preview_order(w.user, "s1", w.infy.conid, "BUY", quantity=1)
    assert r["blocked"] and r["code"] == "MARKET_DATA_UNAVAILABLE"


async def test_daily_order_limit(w):
    w.risk.update_limits(w.user, Decimal("500000"), 500, 2)
    for _ in range(2):
        await w.trading.confirm(w.user, (await buy(w, qty=1))["preview_id"])
    r = await w.trading.preview_order(w.user, "s1", w.infy.conid, "BUY", quantity=1)
    assert r["blocked"] and "limit" in r["reason"]


async def test_risk_limits_cannot_exceed_server_maximum(w):
    from app.risk import RiskBlock
    with pytest.raises(RiskBlock):
        w.risk.update_limits(w.user, Decimal("99999999"), 10, 10)


async def test_account_reflects_live_prices_and_both_wallets(w):
    await w.trading.confirm(w.user, (await buy(w, qty=10))["preview_id"])
    w.market.prices["INFY.NS"] = Decimal("1600")
    acc = await w.portfolio.account(w.user)
    inr = next(x for x in acc["wallets"] if x["currency"] == "INR")
    D = Decimal
    assert D(inr["cash"]) == D("985000") and D(inr["positionsValue"]) == D("16000") and D(inr["netLiquidation"]) == D("1001000")
    assert D(next(x for x in acc["wallets"] if x["currency"] == "USD")["cash"]) == D("10000")


async def test_watchlist_add_remove_limit(w):
    assert w.portfolio.watchlist_add(w.user, w.infy)
    assert w.portfolio.watchlist_add(w.user, w.infy)  # idempotent
    assert len(w.portfolio.watchlist_instruments(w.user)) == 1
    w.portfolio.watchlist_remove(w.user, w.infy.conid)
    assert w.portfolio.watchlist_instruments(w.user) == []
