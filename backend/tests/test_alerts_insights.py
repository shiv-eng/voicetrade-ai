from __future__ import annotations

from datetime import datetime, timedelta, timezone
from decimal import Decimal

import pytest

from app.alerts import AlertError, Alerts
from app.insights import Insights

pytestmark = pytest.mark.asyncio
IST = timezone(timedelta(hours=5, minutes=30))


async def test_alert_fires_once_when_price_crosses_and_waits_for_the_phone(w):
    alerts = Alerts(w.db, w.instruments, w.market)
    alerts.add(w.user, w.infy, "above", Decimal("1600"))
    assert await alerts.check_all() == []                       # 1500 is not above 1600
    w.market.prices["INFY.NS"] = Decimal("1610")
    fired = await alerts.check_all()
    assert len(fired) == 1 and fired[0]["symbol"] == "INFY" and fired[0]["triggerPrice"] == "1610"
    assert await alerts.check_all() == []                       # never fires twice
    assert [a["id"] for a in alerts.unseen(w.user)] == [fired[0]["id"]]
    alerts.ack(w.user, [fired[0]["id"]])
    assert alerts.unseen(w.user) == []


async def test_below_alert_and_cancel(w):
    alerts = Alerts(w.db, w.instruments, w.market)
    alert_id = alerts.add(w.user, w.tcs, "below", Decimal("3900"))
    assert alerts.cancel(w.user, alert_id)
    w.market.prices["TCS.NS"] = Decimal("3800")
    assert await alerts.check_all() == []                       # cancelled alerts stay quiet


async def test_alert_rules(w):
    alerts = Alerts(w.db, w.instruments, w.market)
    with pytest.raises(AlertError):
        alerts.add(w.user, w.infy, "sideways", Decimal("10"))
    with pytest.raises(AlertError):
        alerts.add(w.user, w.infy, "above", Decimal("0"))
    first = alerts.add(w.user, w.infy, "above", Decimal("2000"))
    assert alerts.add(w.user, w.infy, "above", Decimal("2000")) == first   # same alert is not stored twice


async def test_portfolio_history_is_rebuilt_from_fills_and_daily_closes(w):
    today = datetime.now(IST).date()
    day1 = today - timedelta(days=3)
    while day1.weekday() >= 5:
        day1 -= timedelta(days=1)
    w.db.execute(
        "INSERT INTO orders (user_id, instrument_id, side, qty, type, status, avg_price, created_at, updated_at) "
        "VALUES (?, ?, 'BUY', 10, 'MKT', 'Filled', '1500', ?, ?)",
        (w.user, w.infy.conid, day1.isoformat() + "T05:00:00+00:00", day1.isoformat() + "T05:00:00+00:00"))

    class Research:
        async def daily_closes(self, symbol, rng):
            days = [day1 + timedelta(days=i) for i in range((today - day1).days + 1)]
            return [(int(datetime(d.year, d.month, d.day, 10, tzinfo=IST).timestamp()), 1500.0 + 10 * i) for i, d in enumerate(days) if d.weekday() < 5]

    insights = Insights(w.settings, w.db, Research(), w.market, w.instruments, w.portfolio, w.ledger)
    h = await insights.portfolio_history(w.user, "INR", 30)
    assert h["points"][0][1] == float(w.settings.start_cash_inr)          # the day before the first trade: just cash
    assert h["points"][-1][1] > h["points"][0][1]                          # the share went up, so the account did too
    assert h["changePct"] > 0
    empty = await insights.portfolio_history(w.user, "USD", 30)
    assert empty["points"] == []
