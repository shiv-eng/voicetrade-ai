"""FCM push for price alerts: registering a device token, and the alert poller sending to every one of a
user's devices, falling back to the periodic poll when nothing is registered or the push fails."""
from __future__ import annotations

from decimal import Decimal

import pytest

from app.alerts import Alerts
from app.devices import Devices
from app.main import _push_alert

pytestmark = pytest.mark.asyncio


class FakePush:
    def __init__(self) -> None:
        self.sent: list[tuple[str, str, str]] = []

    async def send(self, token, title, body, data=None) -> bool:
        self.sent.append((token, title, body))
        return True


def test_registering_the_same_token_twice_does_not_duplicate(w):
    devices = Devices(w.db)
    devices.register(w.user, "tok-1")
    devices.register(w.user, "tok-1")
    assert devices.tokens_for(w.user) == ["tok-1"]


async def test_a_fired_alert_pushes_to_every_registered_device_and_marks_it_notified(w):
    alerts = Alerts(w.db, w.instruments, w.market)
    alerts.add(w.user, w.infy, "above", Decimal("1600"))
    w.market.prices["INFY.NS"] = Decimal("1610")
    fired = (await alerts.check_all())[0]

    w.devices = Devices(w.db)
    w.devices.register(w.user, "tok-1")
    w.devices.register(w.user, "tok-2")
    w.push = FakePush()
    w.alerts = alerts

    await _push_alert(w, fired, "INR")

    assert len(w.push.sent) == 2
    assert {token for token, _, _ in w.push.sent} == {"tok-1", "tok-2"}
    assert alerts.unseen(w.user) == []  # delivered by push, so the poll won't notify again


async def test_a_fired_alert_with_no_registered_device_is_left_for_the_poll(w):
    alerts = Alerts(w.db, w.instruments, w.market)
    alerts.add(w.user, w.infy, "above", Decimal("1600"))
    w.market.prices["INFY.NS"] = Decimal("1610")
    fired = (await alerts.check_all())[0]

    w.devices = Devices(w.db)
    w.push = FakePush()
    w.alerts = alerts

    await _push_alert(w, fired, "INR")

    assert w.push.sent == []
    assert [a["id"] for a in alerts.unseen(w.user)] == [fired["id"]]
