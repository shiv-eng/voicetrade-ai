from __future__ import annotations

import os
import sys
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import app.llm.orchestrator as _orchestrator  # noqa: E402

_orchestrator.ACK_AFTER_S = 60.0  # the spoken "one moment" is timing based; tests that need it set it themselves

# Tests must never use real credentials from a developer's .env (load_dotenv does not override values already set).
for _name in ("AGORA_APP_ID", "AGORA_APP_CERTIFICATE", "AGORA_CUSTOMER_ID", "AGORA_CUSTOMER_SECRET", "LLM_API_KEY",
              "SARVAM_API_KEY", "GOOGLE_CLIENT_IDS", "DATABASE_URL", "AGORA_ASR_JSON", "AGORA_TTS_JSON"):
    os.environ[_name] = ""

from app.config import Settings  # noqa: E402
from app.db import Database  # noqa: E402
from app.events import Hub  # noqa: E402
from app.instruments import Instruments  # noqa: E402
from app.ledger import Ledger  # noqa: E402
from app.market.base import MarketDataError, RawQuote, SymbolInfo  # noqa: E402
from app.portfolio import Portfolio  # noqa: E402
from app.risk import RiskEngine  # noqa: E402
from app.trading import TradingService  # noqa: E402

INFY = SymbolInfo("INFY.NS", "Infosys Limited", "NSE", "INR")
TCS = SymbolInfo("TCS.NS", "Tata Consultancy Services Limited", "NSE", "INR")
AAPL = SymbolInfo("AAPL", "Apple Inc.", "NASDAQ", "USD")


class FakeMarket:
    """Deterministic prices; tests move them to simulate the real market."""

    def __init__(self) -> None:
        self.prices = {"INFY.NS": Decimal("1500"), "TCS.NS": Decimal("4000"), "AAPL": Decimal("200")}
        self.infos = {i.symbol: i for i in (INFY, TCS, AAPL)}
        self.open = True
        self.fx = Decimal("80")
        self.down = False

    async def search(self, query: str, limit: int = 6):
        q = query.lower()
        return [i for i in self.infos.values() if q in i.name.lower() or q in i.symbol.lower()][:limit]

    async def quote(self, symbol: str) -> RawQuote:
        if self.down:
            raise MarketDataError("down")
        if symbol not in self.prices:
            raise MarketDataError("symbol not found")
        p = self.prices[symbol]
        return RawQuote(self.infos[symbol], p, p - Decimal("10"), p + 5, p - 5, p * 2, p / 2, 1000, self.open,
                        datetime.now(timezone.utc))

    async def usd_inr(self) -> Decimal:
        return self.fx


def fresh_db() -> Database:
    """SQLite in memory by default; a real PostgreSQL when TEST_DATABASE_URL is set (tables emptied per test)."""
    url = os.environ.get("TEST_DATABASE_URL", ":memory:")
    db = Database(url)
    if db.is_pg:
        db.execute(
            "TRUNCATE users, wallets, instruments, positions, orders, previews, watchlist, sessions, audit_log, alerts, "
            "device_tokens RESTART IDENTITY"
        )
    return db


class World:
    def __init__(self) -> None:
        self.settings = Settings(db_path=":memory:", jwt_secret="s" * 32)
        self.db = fresh_db()
        self.market = FakeMarket()
        self.hub = Hub()
        self.instruments = Instruments(self.db, self.market)
        self.ledger = Ledger(self.db, self.settings)
        self.risk = RiskEngine(self.db, self.ledger, self.market, self.settings)
        self.trading = TradingService(self.db, self.ledger, self.instruments, self.market, self.risk, self.settings, self.hub)
        self.portfolio = Portfolio(self.db, self.ledger, self.instruments, self.market)
        self.user = self.ledger.create_user("test")
        self.hub.register_session(self.user, "s1")
        self.infy = self.instruments.ensure(INFY)
        self.tcs = self.instruments.ensure(TCS)
        self.aapl = self.instruments.ensure(AAPL)


@pytest.fixture
def w() -> World:
    return World()
