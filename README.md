# VeriTransit — Cargo Inspector

Field inspection companion for transit & logistics compliance officers. Scan an E-Way
Bill, verify the manifest, reconcile cargo against declarations, and record a signed
statutory verdict — on-device first, with a cloud fallback when the NPU model is
unavailable.

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

## On-device AI (Snapdragon NPU)

Vision and language run locally on the Hexagon NPU via the **Qualcomm GenieX
SDK** (`com.qualcomm.qti:geniex-android`), using the pre-compiled
**Qwen3-VL-4B-Instruct** bundle Qualcomm publishes on AI Hub.

| | |
| --- | --- |
| Model | `qualcomm/Qwen3-VL-4B-Instruct`, `w4a16` |
| Runtime | GenieX → QAIRT (`qairt` plugin), compute unit `npu` |
| Chipset bundle | `qualcomm-snapdragon-8-elite-gen5` (SM8850) |
| Context | 2048 tokens (compiled into the bundle; not adjustable at runtime) |
| Download | ~4.4 GB, once, into the app's private files dir |

Weights are **not** in the APK. On first use, `NpuEngine` asks the SDK's model
manager which chipset this handset is, finds the model in the AI Hub catalog,
and pulls the matching bundle. Everything after that runs with the radio off.

Three tasks use it (`InspectorAi`):

| Step | Task |
| --- | --- |
| Load E-Way Bill | Reads the printed bill from a photo into a structured manifest |
| Cargo Scan | Counts visible cargo against the declared manifest |
| Verification Result | Drafts the officer's statutory remarks (text-only) |

Every screen degrades to the scripted demo path when the model is absent, so
the app is fully usable without the download. Manage it under
**Settings → On-device AI**.

### Cloud fallback (OpenRouter)

When the NPU model is not resident, or a generation fails, AI tasks are served
by **`z-ai/glm-5.3-flash`** through OpenRouter — that one model, no routing.
The fallback is automatic and per-call; `LlmGateway` picks the backend and
screens say which one answered. Privacy posture changes on that path: the
prompt and any attached photo leave the handset for the fallback turn, which
the Chat screen states in place.

Reasoning cannot be disabled on GLM-5.3-Flash, so the client excludes
reasoning from the streamed content, caps it at 512 tokens, and adds the same
headroom to the completion budget.

The OpenRouter API key is read from the gitignored `local.properties`
(`openrouter.api.key=sk-or-…`) and injected as a `BuildConfig` field, so it
ends up inside the APK but never inside source control (GitHub push
protection rejects commits carrying keys). Without a key the cloud leg is
disabled and the app behaves as before: on-device model or demo path.

Know the exposure: anything compiled into an APK is extractable
(`apkanalyzer`/`strings`), so whoever holds an APK holds the key. Keep the
distribution list short, set a spend limit on the OpenRouter account, and
rotate by changing `local.properties` and rebuilding.

### Running the on-device tests

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class com.veritransit.inspector.ai.NpuInferenceTest \
  com.veritransit.inspector.test/androidx.test.runner.AndroidJUnitRunner
```

> [!WARNING]
> Do **not** use `./gradlew :app:connectedDebugAndroidTest`. It uninstalls the
> APK when the run finishes, which deletes the app's data dir — and with it the
> 4.4 GB model bundle, forcing a full re-download. `am instrument` leaves the
> install alone.

The suite skips itself (rather than failing) when the model is not resident, so
it is safe to run on a machine without a Snapdragon device attached.

### Known constraint

Creating the four weight-shared HTP context binaries asks the cDSP for a large
block up front. When another client on the phone is already holding cDSP
memory, the third context comes back `QNN_COMMON_ERROR_RESOURCE_UNAVAILABLE`
(1007) and the load fails. `NpuEngine.load()` retries with backoff; if it still
fails, restarting the app (which drops the previous process's DSP session)
clears it.

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

- **Offline after setup** — in-memory records; the network is used once, to pull
  the model bundle.
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
`ANDROID_HOME`), NDK 27.3.13750724. `minSdk` is 31 and the only packaged ABI is
`arm64-v8a` — both are GenieX requirements.

## Project layout

```
app/src/main/java/com/veritransit/inspector/
├── MainActivity.kt
├── ai/              # GenieX engine, inspection prompts, camera + image prep
├── data/            # models, seed repository, consignment presets
└── ui/
    ├── theme/       # color tokens, typography (variable fonts), theme
    ├── components/  # chips, buttons, cards, evidence canvas, flow scaffolding
    └── screens/     # Home, Scan, Manifest, CargoScan, Result, Records, Settings
telegram-bot/           # Telegram container-check companion service (JVM)
└── src/…/bot/          # long-poll client, container-check engine, routing
```

*Demo data only — not affiliated with any real transport authority.*
