# VoiceTrade AI

A voice assistant for the stock market, "Mira." Talk to it in Hindi or English about live prices, IPOs,
company fundamentals, news and your portfolio, and place practice trades with a paper wallet. Built on
Agora Conversational AI for the voice pipeline.

Two parts:

- **[`android/`](android)** — the phone app (Kotlin, Jetpack Compose).
- **[`backend/`](backend)** — the server (FastAPI) that the app and the Agora voice agent both talk to:
  real market data, the paper-trading ledger, price alerts, and the LLM that drives the conversation.

Each has its own README with setup and run instructions.

## How it fits together

```
Phone (Compose UI)  <-- REST + WebSocket -->  Backend (FastAPI)  <-- Postgres
        |                                           ^
        v                                           |
   Agora RTC audio  <---------------------->  Agora Conversational AI
                                                      |
                                          speech-to-text / text-to-speech (Sarvam)
                                                      |
                                                 LLM (OpenAI-compatible)
```

The backend is the one thing Agora's cloud agent calls as its "LLM": every turn is a normal OpenAI-style
chat-completion request that streams back through Agora to the phone as speech, while the same backend
also serves the app's own screens (portfolio, IPOs, charts, alerts) over REST.

## What it does

- **Voice conversation** in Hindi or English, chosen once at onboarding — prices, portfolio, news and
  plain-language explanations, with numbers spoken naturally instead of read as digits.
- **Paper trading**: every user gets a ₹10,00,000 and a $10,000 practice wallet. Orders are previewed,
  read back, and placed only after an explicit confirmation.
- **IPOs**: live/upcoming listings, price band, lot size, subscription numbers and a timeline.
- **Price alerts** set by voice, delivered as a phone notification.
- **Market overview**: index tiles, top movers, a daily briefing, and a portfolio value chart.
- Pause the assistant mid-answer and pick it back up, and continue a past conversation from history.

## Where it runs

The backend is a single always-on service (any host that runs a container works) with the database on
Postgres. See [`docs/DEPLOY.md`](docs/DEPLOY.md) for a from-scratch guide to putting it on your own
domain.

## Repository layout

| Path | What's there |
| --- | --- |
| `android/` | The Compose app |
| `backend/` | The FastAPI service |
| `docs/` | Deployment guide and the API contract the app expects |
| `docker-compose.yml`, `Caddyfile` | Self-hosting: Postgres + the backend + automatic HTTPS |
