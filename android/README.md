# VoiceTrade AI: Android app

Talk to "Mira," a voice assistant for the stock market, and place **paper-trading** orders with a
read-back and an explicit confirmation for every order. Voice runs through Agora RTC and Agora
Conversational AI. The backend (FastAPI) is a separate component in [`../backend`](../backend); the exact
contract the app expects is in [docs/BACKEND_CONTRACT.md](docs/BACKEND_CONTRACT.md).

## Build

Requires JDK 17 and the Android SDK (compileSdk 35). `local.properties` must point at the SDK.

```bash
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # unit tests
./gradlew :app:assembleRelease      # minified with R8; sign it yourself for distribution
```

The backend URL is set at build time:

```bash
./gradlew :app:assembleRelease -PbackendUrl=https://your-backend-domain
```

Debug builds default to `http://10.0.2.2:8000` (an emulator's localhost) and allow cleartext for local
testing; release builds only allow HTTPS/WSS.

## Sign-in

Google sign-in, plus a guest option while the backend has it enabled (useful for local development and
testing without setting up OAuth).

## Architecture

MVVM with a small domain layer, one `:app` module organised by layer.

```
ui/        Compose screens + one ViewModel each (StateFlow<UiState>, UiEvent in, UiEffect out)
domain/    models, repository interfaces, single-purpose use cases
data/      Retrofit + OkHttp WebSocket, Agora RTC wrapper, Room, DataStore
core/      design system, localisation (Hindi/English), shared utilities
service/   foreground service (microphone) for a live voice session
```

Safety on the client mirrors the backend and never replaces it: `ConfirmOrderUseCase` only confirms the
latest live, unexpired preview; the Confirm and Cancel buttons are spaced apart; large orders can require
biometrics; a kill switch in Settings disables order actions. The PAPER badge is always visible.

## Screens

Home (index tiles, top movers, market briefing), a live voice session, stock detail with charts, IPO
listings and detail pages, price alerts, portfolio (holdings, value history, allocation), watchlist,
conversation history, and settings (language, voice, kill switch).

## Known gaps

- Unit tests use JUnit 4; Compose UI tests are not written yet.
- No certificate pinning configured (the backend host is a build-time constant here, but pin it for a
  production deployment via `res/xml/network_security_config.xml`).
- English strings plus a full Hindi translation (`values-hi/`); Hinglish speech is handled by the
  assistant, not the UI.
