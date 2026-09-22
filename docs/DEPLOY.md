# Deploying VoiceTrade AI on your domain

Agora's cloud agent calls your backend as its LLM, so the backend needs a public **HTTPS** address. Two ways to get one.

## A. A small VPS (recommended)

Any Ubuntu server with 1 vCPU / 2 GB RAM works (Hetzner, DigitalOcean, AWS Lightsail, Oracle free tier).

1. **DNS:** at your domain registrar add an **A record** `api` pointing to the server's IP address
   (so `api.yourdomain.com` resolves to it). Wait a few minutes.
2. **Server setup**
   ```bash
   curl -fsSL https://get.docker.com | sh
   git clone <your repo> voicetrade && cd voicetrade
   cp backend/.env.example .env
   nano .env            # API_DOMAIN, PUBLIC_BASE_URL, POSTGRES_PASSWORD, JWT_SECRET, GOOGLE_CLIENT_IDS, Agora, LLM
   docker compose up -d --build
   ```
   Caddy fetches the HTTPS certificate automatically. Open ports 80 and 443 on the server firewall.
3. **Check:** `https://api.yourdomain.com/healthz` should show `{"ok":true,"agoraConfigured":true,"llmConfigured":true}`.
4. **Logs:** `docker compose logs -f backend`. **Update:** `git pull && docker compose up -d --build`.
   Data lives in the `pgdata` Docker volume (back it up with `docker compose exec db pg_dump -U voicetrade voicetrade > backup.sql`).

## B. Your own computer through a tunnel (for a demo)

Cloudflare Tunnel works with a domain whose DNS is on Cloudflare (free).
```bash
cloudflared tunnel login
cloudflared tunnel create voicetrade
cloudflared tunnel route dns voicetrade api.yourdomain.com
# run the backend (README in backend/), then:
cloudflared tunnel run --url http://localhost:8000 voicetrade
```
Set `PUBLIC_BASE_URL=https://api.yourdomain.com`. The laptop must stay on and awake during the demo and recording.

## What you must supply

| Item | Where to get it |
| --- | --- |
| Agora **App ID**, **App Certificate** | console.agora.io > your project (turn on the certificate) |
| Agora **Customer ID + Secret** | Console > RESTful API. Also enable **Conversational AI** on the project |
| **LLM key** | Gemini (free, aistudio.google.com), OpenAI or Groq. Set `LLM_BASE_URL`, `LLM_MODEL` |
| Google **Web client ID** | Google Cloud Console > APIs & Services > Credentials > OAuth client ID > *Web application* |
| Google **Android client** | Same page > *Android*: package `com.quietstack.voicetrade` and the SHA-1 of your signing key |

## Building the app for your domain

```bash
cd android
./gradlew :app:assembleRelease -PbackendUrl=https://api.yourdomain.com -PgoogleWebClientId=<web client id>
```
Debug builds talk to `http://localhost:8000` (run `adb reverse tcp:8000 tcp:8000` with the phone on USB) and show a
"Continue as guest" button when the backend has `ALLOW_GUEST_LOGIN=true`.
Release builds must be signed with your own key (the same key whose SHA-1 you registered in Google Cloud).
