# VoiceTrade AI backend

FastAPI service behind the Android app and the Agora Conversational AI agent. Real market data (Yahoo
Finance, NSE/BSE and US), a per-user **paper ledger** (each user gets ₹10,00,000 and $10,000; buys and
sells move that cash and their holdings at the real live price), price alerts, IPO data, and an LLM tool
loop that drives the voice conversation.

Nothing here touches a real broker: prices are real, trades are simulated.

## Run

```bash
python -m venv .venv
./.venv/Scripts/pip install -r requirements.txt     # Windows; use .venv/bin/pip on macOS/Linux
cp .env.example .env                                # then edit
./.venv/Scripts/python -m uvicorn app.main:get_app --factory --host 0.0.0.0 --port 8000
```

Without Agora credentials, sessions are text-only (no voice). Without an LLM key configured, most tools
still work through the REST API, but the conversational endpoint has nothing to drive it.

For real voice you need: an Agora App ID, certificate and REST customer id/secret (with Conversational AI
enabled), a Sarvam API key (speech-to-text and text-to-speech), an OpenAI-compatible LLM key, and a public
HTTPS `PUBLIC_BASE_URL` so Agora's cloud can reach `/v1/chat/completions` (for local testing, tunnel with
something like `ngrok http 8000`).

## Tests

```bash
./.venv/Scripts/python -m pytest -q
```

Covers the ledger (cash and holdings accounting, per-user wallets, limit orders, reserved cash), the
safety rules around order confirmation (preview never trades, confirm once, expiry, price-drift and value
caps, kill switch), price alerts, the Agora token signature and agent config, the streaming chat endpoint,
and pause/resume.

## Layout

| Path | Purpose |
| --- | --- |
| `app/market/` | Yahoo Finance client, IPO and news data, spoken-name alias table |
| `app/ledger.py` | wallets, positions, orders; one DB transaction per fill |
| `app/trading.py`, `app/risk.py`, `app/guard.py` | preview/confirm, limits, confirmation lexicon |
| `app/llm/` | system prompt, tools, streaming orchestrator, prefetch shortcuts |
| `app/agora/` | RTC token builder, agent join/leave/speak/interrupt |
| `app/speech.py` | numbers spoken the way people say them, in Hindi and English |
| `app/alerts.py`, `app/insights.py` | price alerts, market overview and the daily briefing |
| `app/main.py` | REST, WebSocket, and the OpenAI-style endpoint Agora calls |

The JSON the app expects is in [`../android/docs/BACKEND_CONTRACT.md`](../android/docs/BACKEND_CONTRACT.md).

## Notes on the Agora setup

- `POST .../agents/{id}/speak` and `.../interrupt` are used (best effort) to voice typed messages,
  tap-confirmed order results, and to pause/replay an answer.
- Speech-to-text and text-to-speech go through Sarvam (`SARVAM_API_KEY`); the voice is fixed to one
  female speaker so replies always sound the same.
- The reply language (Hindi or English) is decided per session from what the user picked at onboarding,
  not auto-detected turn by turn.
