# Handoff: VoiceTrade AI — Visual Redesign

## Overview
A full visual redesign of all screens in the VoiceTrade AI Android app (Kotlin/Jetpack Compose, repo `shiv-eng/voicetrade-ai`). Same information architecture, navigation, and app colors as the current app — new typography, layout, card treatments, and an animated voice-orb. Goal: make the app feel more polished and professional without changing what it does.

## About the Design Files
The files in this bundle (`VoiceTrade Redesign.dc.html`, `VoiceTrade Current.dc.html`) are **HTML design references** — static/interactive mockups built to show intended look, layout and behavior. They are not production code. The task is to **recreate these designs inside the existing Android/Compose codebase**, using its existing Theme.kt, designsystem package (Ui.kt, Widgets.kt, Components.kt, MicOrb.kt) and screen composables — not to port HTML/CSS into the app.

`VoiceTrade Redesign.dc.html` is a canvas of 12 mobile-frame mockups (390×844, iPhone-ish viewport used purely as a design frame — the target is Android Compose, not iOS). `VoiceTrade Current.dc.html` recreates 2 of today's screens (Home, Voice session) faithfully from the current repo, for side-by-side comparison. Open either directly in a browser to view.

## Fidelity
**High-fidelity.** Exact hex colors, spacing, type sizes and component structure are given below and should be matched as closely as Compose allows. Treat pixel values as dp (1px ≈ 1dp at baseline density).

## Screens / Views

All screens share: background `#0B1220`, card surface `#0F1727` with `1px solid rgba(124,138,161,.14)` border and `24px` corner radius, primary accent `#2DD4BF` (teal), secondary `#818CF8`/`#4F46E5` (indigo), gain `#4ADE80`, loss `#F87171`, paper-badge `background:#3B2A07 / color:#FCD34D`. Font: Manrope (400/500/600/700/800). Body text `#E8EDF5`, secondary text `#A9B4C6`, tertiary `#7C8AA1`. Icons: Material Symbols Rounded (filled variant for active/tinted icons).

1. **Welcome / language choice** — full-bleed teal radial glow top-left over `#0B1220`. Centered animated idle orb (170px). Headline "Talk to the market." (36px/800). Two language pickers: Hindi filled teal pill (60px tall, `18px` radius, glow shadow), English outlined pill.
2. **Sign in** — indigo radial glow. Icon badge (72px, teal/indigo gradient tint, `verified_user` icon), headline "Create your practice account" (30px/800), 3-row benefit list (wallet amount, order read-back, biometric+kill switch) in a card, white Google button, legal disclaimer footer.
3. **Home** — avatar + greeting + 3 icon buttons (notifications w/ amber dot, history, settings) top bar. Voice hero card (teal-tinted card, animated idle orb 120px, "Talk to Mira", 3 suggestion chips). Practice-balance card: dark teal gradient (`#0F766E`→`#134E4A`), INR/USD segmented pill, big balance figure, day-change chip, cash/invested split row. Markets section: 2×2 index tile grid with sparkline SVGs, green/red change. IPO entry row (amber rocket icon). Top movers: gainers/losers segmented tabs (interactive), stock rows with colored monogram avatars. Holdings list. Floating bottom nav with raised teal mic FAB center.
4. **Voice session (listening)** — top bar: back, "Mira" + PAPER badge, live pill (blinking green dot). Large animated orb (state-driven, 280px zone) + state label. Suggestion chips. Floating pill-shaped control bar: mic / pause / keyboard / red end-call.
5. **Order confirmation** — same session shell; orb in "awaiting" ring state (compact, 84px) next to "Waiting for your confirmation" (amber). Chat bubble (user) + Mira line. Order preview card: amber border + glow, BUY/PAPER chips, countdown ring + seconds, "10 × Infosys" headline, estimated value/fees rows, Cancel (outline) / Confirm (filled teal, far apart) buttons.
6. **Portfolio** — INR/USD segmented pill, big total value (42px/800), day change chip. 2×2 stat grid (Cash/Invested/Unrealised/Realised). Account-value line chart card (SVG, gain-green fill+stroke). Allocation card: stacked bar + legend dots + percentages. Holdings summary strip (Invested/Current/P&L) + holdings list.
7. **Orders** — Open/Filled segmented tabs (counts inline). Order cards: status chip (Working=blue, Partial=blue w/ progress bar, Filled=green), side-colored headline, Cancel-order outline button. Voice-cancel hint box (dashed border).
8. **Watchlist** — search bar (inactive look), live-polling indicator (blinking dot + "6 of 20"), FAB-style add button top-right (filled teal square-ish), stock rows with inline sparkline + price + change + kebab menu.
9. **Stock detail** — header with avatar/name/exchange + open/closed pill, big price + change. Period segmented tabs (1D…5Y, "1M" active). Line chart card w/ scrub dashed marker + low/high row. 52-week range bar. 3×2 fundamentals grid (Market cap, P/E, Div yield, EPS, Volume, Sector). News list. Sticky bottom action bar: Watch/Alert/Ask-Mira row + Sell (outline red) / Buy (filled teal) row.
10. **IPOs** — India/US segmented pill top-right. Sectioned list: Open now (progress bar "Subscribed 4.2×"), Coming soon, Just listed (with return %). Source disclaimer footer.
11. **History** — date-grouped list ("TODAY", "YESTERDAY"), entries with mic-orb icon (voice sessions, teal) or translate icon (non-English), title, timestamp+duration, "N order" chip where relevant. Voice-resume hint box.
12. **Settings** — profile card (avatar, name, masked email, PAPER badge). Grouped sections (VOICE, SAFETY AND LIMITS, APPEARANCE, DATA) as bordered cards with dividers: language/voice/speed segmented + slider controls, kill-switch/biometric toggles (custom pill switches, teal=on), risk-limit rows (value chips), theme segmented (Dark active), large-text toggle, delete-history/sign-out rows. Version footer.

## Interactions & Behavior
- **Gainers/Losers tabs (Home)**: tapping switches the Top Movers list content and pill highlight — implemented as component state (`showGainers`/`showLosers` in the mockup's logic class).
- **Mic orb states**: idle (breathing pulse), listening (expanding pulse rings synced to mic level), thinking (spinning conic gradient arc), speaking (concentric outward ripple waves), awaiting (countdown ring, sweeps from full to empty over the confirm window), error (static red glow). These map 1:1 to `OrbState` in `MicOrb.kt` — implement as Compose `Canvas` + `Brush.sweepGradient`/`animateFloat`, same approach as the current app, just re-themed.
- **Order confirm/cancel**: Confirm and Cancel buttons must stay visually far apart (existing app has this as an explicit anti-mis-tap requirement — preserve it).
- **Bottom nav**: floating rounded bar, not edge-to-edge; center mic button is raised (FAB-style, overlaps the bar edge) and always navigates into a voice session.
- **Watchlist**: live polling indicator dot blinks while polling (every 5s, existing behavior — only cosmetic status treatment is new).
- No new screens, flows, or IA changes — this is a visual pass over the existing navigation graph (`AppNavHost.kt`: Splash → Onboarding → Home ⇄ tabs, Session as an overlay-style destination, Stock/IPO/History/Settings as detail pushes).

## State Management
No new state introduced. Reuse existing ViewModels/state classes 1:1 (`HomeViewModel`, `PortfolioViewModel`, `OrdersViewModel`, `WatchlistViewModel`, `VoiceSessionViewModel`, `StockDetailViewModel`, `IpoViewModel`, `OnboardingViewModel`). The only new "state" is purely presentational: which segmented-tab option is selected (Gainers/Losers, INR/USD, chart period) — already exists in the current code as `PortfolioSort`/local `remember` state and should stay that way.

## Design Tokens

**Colors**
- Background: `#0B1220`
- Card surface: `#0F1727`, alt surface `#131C30`, elevated `#1B2538`
- Border: `rgba(124,138,161,.14)` (cards), `rgba(124,138,161,.18–.22)` (chrome)
- Primary (teal): `#2DD4BF`, dark variant `#0F766E`, deep `#042F2E` / `#134E4A`
- Secondary (indigo): `#818CF8` / `#4F46E5` / `#312E81`
- Gain: `#4ADE80` (fg), `rgba(74,222,128,.12–.14)` (bg tint)
- Loss: `#F87171` (fg), `rgba(248,113,113,.12–.14)` (bg tint)
- Awaiting/amber: `#FBBF24`, paper badge bg `#3B2A07` / fg `#FCD34D`
- Orb states: listening `#60A5FA`, thinking `#A78BFA`, speaking `#4ADE80`, awaiting `#FBBF24`, idle `#2DD4BF`, error `#F87171`
- Text: primary `#E8EDF5`, secondary `#A9B4C6`, tertiary `#7C8AA1`

**Typography** — Manrope, weights 400/500/600/700/800
- Display figures (portfolio total): 42px/800, `-0.035em` letter-spacing
- Screen title: 28px/800
- Card headline: 20–21px/800
- Body: 15px/600–700
- Caption/meta: 12–13px/600
- Section label (all-caps): 11px/800, `.1em` letter-spacing
- All monetary/percentage figures use tabular numerals (`font-variant-numeric: tabular-nums`)

**Radius**: cards 24px, tiles 20–22px, pills/buttons 999px (full), small chips 8–14px
**Shadow**: cards mostly flat (border-only); floating bottom nav and primary CTA get a soft colored glow shadow (`0 10–20px 24–40px -8px <accent>`)

## Assets
- Icons: Google **Material Symbols Rounded** (variable font, filled=1 for active/tinted states) — loaded via Google Fonts in the mockup; use the equivalent Compose Material Icons (already used in the app) with matching glyphs (`mic`, `graphic_eq`, `home`, `account_balance_wallet`, `list_alt`, `visibility`, `notifications`, `history`, `settings`, `arrow_back`, `refresh`, `sort`, `add`, `search`, `more_vert`, `chevron_right`, `fingerprint`, `power_settings_new`, `verified_user`, `rocket_launch`, `play_circle`, `translate`, `delete`, `logout`).
- Sparklines/charts: inline SVG polylines/gradients in the mockup — implement with Compose `Canvas` (the app already has `PriceChart`/`Sparkline`/`RangeBar` composables to restyle, not rebuild).
- No bitmap image assets used; avatars are colored monogram initials (existing `SymbolAvatar`/`PersonAvatar` pattern, re-themed palette).

## Files
- `VoiceTrade Redesign.dc.html` — all 12 redesigned screens, single scrollable canvas, self-contained (loads Google Fonts via CDN link).
- `VoiceTrade Current.dc.html` — 2 screens recreated from the current app for before/after comparison.
- `github.md` — source repo association (`shiv-eng/voicetrade-ai`, branch `master`) and a screen→repo-file map showing exactly which Kotlin files each mockup screen was grounded in. Use this map to find the composable to edit for each screen.
