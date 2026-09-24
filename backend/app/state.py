"""Wires every service together once. Tests pass in a fake market and a scripted LLM."""
from __future__ import annotations

from dataclasses import dataclass

from .agora.client import AgoraClient
from .config import Settings
from .db import Database
from .events import Hub
from .instruments import Instruments
from .ledger import Ledger
import logging

from .llm.basic import BasicChat
from .llm.orchestrator import ChatClient, OpenAIChat, Orchestrator, RoutedChat
from .llm.tools import ToolBox
from .market.base import MarketData
from .market.research import Research
from .alerts import Alerts
from .devices import Devices
from .insights import Insights
from .portfolio import Portfolio
from .push import PushClient
from .risk import RiskEngine
from .sessions import SessionRegistry
from .trading import TradingService

_SARVAM_LLM_BASE_URL = "https://api.sarvam.ai/v1"


@dataclass
class Services:
    settings: Settings
    db: Database
    market: MarketData
    hub: Hub
    instruments: Instruments
    ledger: Ledger
    risk: RiskEngine
    trading: TradingService
    portfolio: Portfolio
    tools: ToolBox
    orchestrator: Orchestrator
    sessions: SessionRegistry
    agora: AgoraClient
    devices: Devices
    push: PushClient
    research: Research | None = None
    alerts: Alerts | None = None
    insights: Insights | None = None


def build_services(settings: Settings, market: MarketData, chat: ChatClient | None = None, db: Database | None = None) -> Services:
    db = db or Database(settings.database_url or settings.db_path)
    hub = Hub()
    instruments = Instruments(db, market)
    ledger = Ledger(db, settings)
    risk = RiskEngine(db, ledger, market, settings)
    trading = TradingService(db, ledger, instruments, market, risk, settings, hub)
    portfolio = Portfolio(db, ledger, instruments, market)
    research = Research()
    alerts = Alerts(db, instruments, market)
    insights = Insights(settings, db, research, market, instruments, portfolio, ledger)
    tools = ToolBox(db, instruments, market, ledger, trading, portfolio, hub, research, alerts, insights)
    if chat is None:
        if settings.llm_api_key:
            english: ChatClient = OpenAIChat(settings.llm_base_url, settings.llm_api_key, settings.llm_model,
                                              settings.llm_timeout_s, settings.llm_patience)
            # Sarvam's own model for Hindi/Hinglish turns (tuned for Indian languages); English keeps using
            # whichever model LLM_* points at. Reuses SARVAM_API_KEY — no separate credential to configure.
            if settings.sarvam_api_key:
                hindi: ChatClient = OpenAIChat(_SARVAM_LLM_BASE_URL, settings.sarvam_api_key, settings.sarvam_llm_model,
                                                settings.llm_timeout_s, 1.0)
                chat = RoutedChat(english, hindi)
            else:
                chat = english
        else:
            logging.getLogger("voicetrade").warning("LLM_API_KEY not set: using the basic keyless command parser.")
            chat = BasicChat()
    orchestrator = Orchestrator(chat, tools, trading, ledger, portfolio, settings)
    return Services(settings, db, market, hub, instruments, ledger, risk, trading, portfolio, tools, orchestrator,
                    SessionRegistry(db), AgoraClient(settings), Devices(db), PushClient(settings),
                    research, alerts, insights)
