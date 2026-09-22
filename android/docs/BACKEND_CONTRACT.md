# Backend contract (Android app <-> backend)

This is what the app sends and parses, and what `backend/` implements. JSON is camelCase. Money is a JSON
number or string (parsed exactly as a decimal, never as a float). Timestamps are ISO-8601 UTC. Every call except
`/auth/*` and `/healthz` carries `Authorization: Bearer <device token>`.

There is no broker: prices are real (Yahoo Finance), trades are simulated against a per-user paper ledger.
Every account has two wallets, INR (Rs 10,00,000) and USD ($10,000).

## Auth

| Method | Path | Body | Response |
| --- | --- | --- | --- |
| POST | `/auth/google` | `{idToken}` (Google ID token from the app) | `{token, newAccount, profile:{userId, email, name, picture}}` |
| POST | `/auth/guest` | `{}` | same. Only when the server has `ALLOW_GUEST_LOGIN=true` (development) |
| GET | `/me` | | `profile` |

The server verifies the ID token against Google's public keys and requires `aud` = the server's
`GOOGLE_CLIENT_IDS`. Accounts are keyed by the Google account, so signing in on a new phone returns the same
wallets, holdings and watchlist. A 401 anywhere signs the app out.

## REST

| Method | Path | Body | Response |
| --- | --- | --- | --- |
| GET | `/broker/status` | | `{authenticated:true, accountId, paper:true}` |
| POST | `/sessions` | `{language:"en"\|"hinglish", voice:"female"\|"male", speechRate}` | `{sessionId, agora:{appId, channel, token, uid}, wsUrl, paper}`. `agora.appId == ""` means the server has no Agora voice: the app uses on-device speech instead |
| DELETE | `/sessions/{id}` | | 2xx |
| POST | `/sessions/{id}/text` | `{text}` | 2xx; the reply arrives as `transcript` events (and is spoken by the agent when Agora is on) |
| POST | `/sessions/{id}/token` | | `{token}` (Agora token renewal) |
| POST | `/previews/{id}/confirm` | | `{orderId, status}` |
| POST | `/previews/{id}/reject` | | 2xx |
| GET | `/account` | | `{accountId, isPaper, wallets:[{currency, cash, buyingPower, positionsValue, netLiquidation}]}` |
| GET | `/positions` | | `[{instrument, quantity, avgCost, marketPrice, marketValue, unrealizedPnl, dayChange}]` |
| GET | `/pnl` | | `{items:[{currency, daily, unrealized, realized}]}` |
| GET | `/orders?status=open\|filled` | | `[Order]` |
| POST | `/orders/{id}/cancel-preview` | | `OrderPreview` (kind CANCEL) |
| GET | `/quote?conid=` | | `Quote` (adds `week52High`, `week52Low`, `marketOpen`) |
| GET | `/search?q=` | | `[Instrument]` (NSE/BSE and NASDAQ/NYSE stocks) |
| GET | `/watchlist` | | `[{instrument, quote?}]` |
| PUT / DELETE | `/watchlist/{conid}` | | 2xx (max 20) |
| POST | `/watchlist/{conid}/move?up=` | | 2xx |
| GET / PUT | `/settings/risk` | `{maxOrderValue, maxQty, maxOrdersPerDay}` | same + `killSwitch`, `serverMax` |
| PUT | `/settings/kill-switch` | `{on}` | `{on}` |
| POST | `/v1/chat/completions?s=<secret>` | OpenAI chat request | OpenAI SSE stream. **Called by Agora only** |

Errors are `{code, message}`: `PREVIEW_EXPIRED` (409), `PRICE_DRIFT` (409), `KILL_SWITCH` (423),
`RISK_BLOCKED`, `MARKET_DATA_UNAVAILABLE` (503), `GOOGLE_AUTH_FAILED` (401), `UNAUTHORIZED` (401), `AGENT_FAILED` (502).

Shapes: `Money {amount, currency}`; `Instrument {conid, symbol, name, exchange, currency}`;
`OrderPreview {previewId, instrument, side, quantity, type:"MKT"|"LMT", limitPrice?, estimatedValue, warnings, expiresAt, kind}`;
`Order {orderId, instrument, side, quantity, type, limitPrice?, status:{state, filled?, avgPrice?, reason?}, updatedAt}`.

## WebSocket `wsUrl` (server -> app)

Envelope `{type, sessionId, seq, ts, data}`; on reconnect the app sends `?lastSeq=` and the server replays the gap.

| type | data |
| --- | --- |
| `transcript` | `{role:"user"\|"agent", text, isFinal, messageId}` |
| `agent_state` | `{state:"listening"\|"thinking"\|"speaking"\|"awaiting_confirmation"}` |
| `card` | `{messageId, card}` with `card.kind` = `quote`, `positions` (`positions`, `totals[]`), `account` (`summary.wallets[]`), `preview`, `status`, `disambiguation`, `error` |
| `preview_created` / `preview_closed` | `{preview}` / `{previewId, reason}` |
| `order_update` | `{order}` |
| `watchlist` | `{action, instrument}` |
| `error` | `{code, message, retryable}` |

## Voice paths

1. **Agora (production):** the app joins `agora.channel` with `agora.token`; the Agora agent (ASR + TTS) calls
   `/v1/chat/completions` on the backend; the backend runs the LLM with tools and streams the spoken text back.
2. **On-device (fallback when `agora.appId` is empty):** the phone's speech recognizer sends what you say to
   `/sessions/{id}/text`, and the phone's text-to-speech reads the agent's `transcript` reply aloud. Same tools,
   same ledger, same confirmation rules.
