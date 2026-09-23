"""Runtime configuration, read from environment variables (or a .env file)."""
from __future__ import annotations

import json
import os
import secrets
from dataclasses import dataclass, field
from decimal import Decimal

from dotenv import load_dotenv

load_dotenv()


def _env(name: str, default: str = "") -> str:
    return os.environ.get(name, default).strip()


def _json_env(name: str) -> dict | None:
    raw = _env(name)
    return json.loads(raw) if raw else None


@dataclass(frozen=True)
class Settings:
    # Public HTTPS address Agora can reach (ngrok / VPS). Needed for the Agora agent's LLM url.
    public_base_url: str = field(default_factory=lambda: _env("PUBLIC_BASE_URL", "http://localhost:8000").rstrip("/"))
    # PostgreSQL in real use: postgresql://user:password@host:5432/dbname. Falls back to a local SQLite file.
    database_url: str = field(default_factory=lambda: _env("DATABASE_URL"))
    db_path: str = field(default_factory=lambda: _env("DB_PATH", "voicetrade.db"))
    jwt_secret: str = field(default_factory=lambda: _env("JWT_SECRET") or secrets.token_hex(32))
    # Google Sign-In: OAuth *web* client id(s) the app requests ID tokens for (comma separated).
    google_client_ids: list[str] = field(default_factory=lambda: [c.strip() for c in _env("GOOGLE_CLIENT_IDS").split(",") if c.strip()])
    # Firebase service account JSON (Project Settings > Service Accounts > Generate new private key), for
    # sending price-alert pushes via FCM's HTTP v1 API. None = pushes are skipped; the 15-minute poll still works.
    firebase_service_account: dict | None = field(default_factory=lambda: _json_env("FIREBASE_SERVICE_ACCOUNT_JSON"))
    # Development only: lets a debug app sign in without a Google account. Keep false in production.
    allow_guest_login: bool = field(default_factory=lambda: _env("ALLOW_GUEST_LOGIN", "false").lower() == "true")
    max_registrations_per_hour: int = field(default_factory=lambda: int(_env("MAX_REGISTRATIONS_PER_HOUR", "30")))
    # Set true when behind a reverse proxy (Caddy/nginx) so the client IP is read from X-Forwarded-For.
    trust_proxy: bool = field(default_factory=lambda: _env("TRUST_PROXY", "false").lower() == "true")

    # Agora
    agora_app_id: str = field(default_factory=lambda: _env("AGORA_APP_ID"))
    agora_app_certificate: str = field(default_factory=lambda: _env("AGORA_APP_CERTIFICATE"))
    agora_customer_id: str = field(default_factory=lambda: _env("AGORA_CUSTOMER_ID"))
    agora_customer_secret: str = field(default_factory=lambda: _env("AGORA_CUSTOMER_SECRET"))
    agora_agent_uid: int = 1001
    agora_idle_timeout: int = 60
    # Overrides for the Agora agent's asr/tts blocks, as JSON. Defaults use Agora-managed vendors (no extra keys).
    agora_asr_json: dict | None = field(default_factory=lambda: _json_env("AGORA_ASR_JSON"))
    agora_tts_json: dict | None = field(default_factory=lambda: _json_env("AGORA_TTS_JSON"))
    # Sarvam (Indian speech vendor): Hindi + Indian English, both hearing and speaking. Preferred when its key is set.
    sarvam_api_key: str = field(default_factory=lambda: _env("SARVAM_API_KEY"))
    sarvam_speaker_female: str = field(default_factory=lambda: _env("SARVAM_SPEAKER_FEMALE", "simran"))
    sarvam_speaker_male: str = field(default_factory=lambda: _env("SARVAM_SPEAKER_MALE", "shubh"))
    sarvam_pace: float = field(default_factory=lambda: float(_env("SARVAM_PACE", "1.15")))
    sarvam_tts_model: str = field(default_factory=lambda: _env("SARVAM_TTS_MODEL", "bulbul:v3"))
    # Agora turn detection override, as JSON. Default: end the user's turn after a short pause (lower = snappier).
    agora_turn_detection_json: dict | None = field(default_factory=lambda: _json_env("AGORA_TURN_DETECTION_JSON"))
    # How long the user must be speaking before Mira stops talking. Short noises and coughs — or a thumb
    # brushing the mic while scrolling the transcript — should not cut her off, so this sits well above a
    # brief incidental sound and only fires on speech that keeps going.
    interrupt_ms: int = field(default_factory=lambda: int(_env("INTERRUPT_MS", "1200")))
    end_of_speech_ms: int = field(default_factory=lambda: int(_env("END_OF_SPEECH_MS", "400")))
    asr_language: str = field(default_factory=lambda: _env("ASR_LANGUAGE", "multi"))
    tts_voice_female: str = field(default_factory=lambda: _env("TTS_VOICE_FEMALE", "coral"))
    tts_voice_male: str = field(default_factory=lambda: _env("TTS_VOICE_MALE", "onyx"))

    # LLM (any OpenAI-compatible endpoint: OpenAI, Gemini's OpenAI shim, Groq, OpenRouter...)
    llm_base_url: str = field(default_factory=lambda: _env("LLM_BASE_URL", "https://api.openai.com/v1"))
    llm_api_key: str = field(default_factory=lambda: _env("LLM_API_KEY"))
    llm_model: str = field(default_factory=lambda: _env("LLM_MODEL", "gpt-4.1-mini"))
    llm_timeout_s: float = 20.0
    llm_patience: float = field(default_factory=lambda: float(_env("LLM_PATIENCE", "1")))  # >1 for slow free tiers

    # Paper money
    start_cash_inr: Decimal = Decimal(_env("START_CASH_INR", "1000000"))
    start_cash_usd: Decimal = Decimal(_env("START_CASH_USD", "10000"))

    # Trading rules
    preview_ttl_s: int = 60
    price_drift_pct: Decimal = Decimal("2")
    # Paper convenience: fill at the last traded price even when the exchange is closed.
    fill_when_market_closed: bool = field(default_factory=lambda: _env("FILL_WHEN_MARKET_CLOSED", "true").lower() != "false")
    default_max_order_value_inr: Decimal = Decimal("50000")
    default_max_qty: int = 500
    default_max_orders_per_day: int = 20
    limit_poll_interval_s: float = 15.0

    @property
    def agora_configured(self) -> bool:
        return bool(self.agora_app_id and self.agora_customer_id and self.agora_customer_secret)


def load_settings() -> Settings:
    return Settings()
