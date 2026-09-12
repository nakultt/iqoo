# VeriTransit — Cargo Inspector

Field inspection companion for transit & logistics compliance officers. Scan an E-Way
Bill, verify the manifest, reconcile cargo against declarations, and record a signed
statutory verdict — all on-device.

Built with **Kotlin + Jetpack Compose (Material 3)**, following the *Field Operational
Precision* design language: warm alabaster canvas, deep transit burgundy, restrained
diagnostic accents, Hanken Grotesk for narrative type and JetBrains Mono for
machine-verified values.

## Screens

| Screen | Purpose |
| --- | --- |
| Home (Field Portal) | Shift stats, recent inspections, quick actions |
| Load E-Way Bill (Step 1) | Simulated QR viewfinder + manual entry with validation |
| Manifest Details (Step 2) | Consignment summary, declared items, pre-scan checks |
| Cargo Scan (Step 3) | Item-by-item reconciliation with live progress |
| Verification Result | Discrepancy report, evidence frame, officer verdict & signed stamp |
| Records | Searchable/filterable audit vault with flagged-consignment audit card |
| Settings | Inspector profile, sound & haptic feedback, app info |

## Telegram container-check bot

[telegram-bot/](telegram-bot/) is a companion JVM service that shares the
inspector's vault: message the bot an E-Way Bill, vehicle number, or a receipt
(photo caption / text file) and it replies with the container check — manifest
reconciliation, discrepancies, verdict and recommended statutory action.

```bash
VERITRANSIT_BOT_TOKEN=<token> ./gradlew :telegram-bot:run
```

See [telegram-bot/README.md](telegram-bot/README.md) for the full command set
and token handling (token is read from the environment or a gitignored file —
never committed).

## Highlights

- **Fully offline demo** — in-memory data, zero permissions, no network access.
- **Motion-first UX** — staggered list entrances, animated counters, scanning line,
  spring checkbox/radio states, verdict stamp overlay, animated tab & screen transitions.
- **Lightweight** — R8-minified release APK ≈ 1.4 MB; every visual (evidence canvases,
  viewfinder scene, launcher glyph) is drawn in code or vectors — no binary image assets.
- Repeated inspections cycle through scenarios (clean pass, shortage, unlisted cargo),
  so the walkthrough stays representative.

## Build

```bash
./gradlew :app:assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`
(signing keystore: `keystore/veritransit.keystore`, demo credentials in
`app/build.gradle.kts`).

Requirements: JDK 17+, Android SDK 36 (`sdk.dir` via `local.properties` or
`ANDROID_HOME`).

## Project layout

```
app/src/main/java/com/veritransit/inspector/
├── MainActivity.kt
├── data/            # models, seed repository, consignment presets
└── ui/
    ├── theme/       # color tokens, typography (variable fonts), theme
    ├── components/  # chips, buttons, cards, evidence canvas, flow scaffolding
    └── screens/     # Home, Scan, Manifest, CargoScan, Result, Records, Settings
telegram-bot/           # Telegram container-check companion service (JVM)
└── src/…/bot/          # long-poll client, container-check engine, routing
```

*Demo data only — not affiliated with any real transport authority.*
