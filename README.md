# VoiceTrade AI

A voice-first stock market and paper trading assistant, **Mira**. Talk to it in Hindi, English, or a
natural mix of both, about live prices, IPOs, company fundamentals, sector performance, top gainers and
losers, news, and your own portfolio, and place practice trades with a paper wallet, all by voice. Built
for the Agora Voice AI Hackathon on Agora's Conversational AI Engine.

## Why

Stock market apps in India are still English-heavy and chart-dense, built for people who are already
financially literate. Most first-time investors are comfortable talking about money out loud, in Hindi or
Hinglish, but no trading app lets them. VoiceTrade AI treats voice as the actual product: you ask a
question the way you'd ask a knowledgeable friend, and get a real, spoken, data-grounded answer back, no
menus, no charts to decode first.

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
                                                 LLM (OpenAI, agentic tool-calling)
```

The backend is the one thing Agora's cloud agent calls as its "LLM": every turn is a normal OpenAI-style
chat-completion request that streams back through Agora to the phone as speech, while the same backend
also serves the app's own screens (portfolio, IPOs, charts, alerts) over REST. Agora handles the live voice
connection and turn-taking; Sarvam is the speech vendor (hearing and speaking, tuned for Indian languages);
the LLM does all of the reasoning and decides which live tools to call, so nothing spoken is ever invented.

## What it does

- **Natural voice conversation** in Hindi, English, or Hinglish, decided fresh from what was just said, not
  fixed at onboarding — switch languages mid-conversation and Mira follows.
- **Live prices and fundamentals** for Indian (NSE/BSE) and US (NASDAQ/NYSE) stocks: price, day range,
  52-week range, P/E, growth, analyst view, and news, all from a real market-data call in that turn.
- **Sector overviews**: how IT, banking, auto, pharma, FMCG, metals, energy, real estate or financial
  services stocks are doing today, with the sector's own index move and its biggest names.
  Nifty 50 top gainers and losers, on request.
- **Paper trading**: every user gets a ₹10,00,000 and a $10,000 practice wallet. Orders are previewed, read
  back, and placed only after an explicit, code-level-verified confirmation, independent of the LLM, so a
  misheard "yes" or a prompt-injection attempt can never place a trade.
- **IPOs**: live/upcoming listings, price band, lot size, subscription numbers, and a timeline.
- **Price alerts** set by voice, delivered as a phone notification.
- **Watchlist, portfolio, and a daily market briefing**: index tiles, holdings, today's P&L.
- Pause the assistant mid-answer and pick it back up, and continue a past conversation from history.

## What makes it reliable, not just a demo

Built and hardened against real, adversarial voice usage, not a scripted happy path:

- Company name resolution correctly disambiguates genuinely different companies (like the several listed
  "Adani" or "Sigma" companies) while never getting stuck re-asking the same question when a name is
  actually unambiguous, even across confusing cases like NSE/BSE dual listings or a ticker that's also a
  leveraged ETF's name.
- The assistant is instructed, and verified, to never state whether a company is public, private, or
  delisted from memory alone; it always checks live data first, since corporate actions like renames,
  demergers, and delistings make stale training knowledge actively wrong.
- Every turn's reasoning goes through one consistent model rather than routing by detected language, after
  live testing showed a language-routed model gave inconsistent answers, including fabricated data, to the
  identical question.
- Filler pacing is tuned to sound natural without turning into a wall of "umm"s, and a correction or
  interruption ("no wait, I meant X") is recognized as a correction, not swept into a tool call as if it
  were a company name.

## Where it runs

The backend is a single always-on service (any host that runs a container works) with the database on
Postgres; this instance is deployed on [Fly.io](https://fly.io). See [`docs/DEPLOY.md`](docs/DEPLOY.md) for
a from-scratch guide to putting it on your own domain.

## Repository layout

| Path | What's there |
| --- | --- |
| `android/` | The Compose app |
| `backend/` | The FastAPI service |
| `docs/` | Deployment guide and the API contract the app expects |
| `docker-compose.yml`, `Caddyfile` | Self-hosting: Postgres + the backend + automatic HTTPS |

## Built with

Agora Conversational AI Engine · Sarvam (Indian-language speech) · OpenAI (agentic tool-calling) · FastAPI
· PostgreSQL · Kotlin · Jetpack Compose · Fly.io
