# VeriTransit — Goods Receiving

Goods-receiving companion for B2B warehouse teams. Load a supplier's packing list,
count what actually arrived at the dock, and file a goods-received note — on-device
first, with a cloud fallback when the NPU model is unavailable.

A supplier packs against a purchase order and a packing list; a warehouse receives
the delivery and books what actually arrived (matched / short / over / unlisted /
damaged). The output is a goods-received note and a stock movement.

> [!IMPORTANT]
> **This is not a GST, customs or legal verification tool.** It has no GSTN or any
> other tax-authority integration, issues no statutory document and states no legal
> position. It does not verify E-Way Bills and is not a compliance or enforcement
> instrument. Records are demo data held in memory.

Built with **Kotlin + Jetpack Compose (Material 3)**, following the *Warehouse
Operational Precision* design language: warm alabaster canvas, deep transit
burgundy, restrained diagnostic accents, Hanken Grotesk for narrative type and
JetBrains Mono for machine-verified values.

## Screens

| Screen | Purpose |
| --- | --- |
| Home (Receiving) | Shift stats, recent receipts, quick actions |
| Load Packing List (Step 1) | Carton-label scan + manual PO / packing-list entry with validation |
| Packing List (Step 2) | Supplier summary, packed SKU lines, pre-count checks |
| Receive / Count (Step 3) | SKU-by-SKU packed-vs-received count with live progress |
| Receipt Result | Discrepancy report, evidence photo, receiving action & filed stamp |
| Receipts | Searchable/filterable goods-received log with flagged-delivery card |
| Settings | Warehouse & receiver identity, sound & haptic feedback, app info |

## Consignment documents (reference)

Which papers an electronics or fabric consignment carries — legally required
versus commercially customary, the clocks they start, and how far each rule's
wording has been checked — is encoded as data in
[`data/documents/`](app/src/main/java/com/veritransit/inspector/data/documents/)
and written out in [docs/CONSIGNMENT_DOCUMENTS.md](docs/CONSIGNMENT_DOCUMENTS.md)
(first cut of #27, Tamil Nadu scope). It is reference data for the receiving
team, within the non-goal above: the app does not verify these documents or
state a legal position.

> [!IMPORTANT]
> **QR ingestion yields headers only — never line items.** The carton-label scan
> (`QrLabel`) extracts a PO and a packing-list reference and nothing else; even a
> signed e-invoice QR carries only header fields (GSTINs, document number and
> date, total value, the *count* of lines, the main HSN, the IRN). Packed lines
> always come from a document read or manual entry, confirmed on the Packing
> List step.

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

Four tasks use it (`ReceivingAi`):

| Step | Task |
| --- | --- |
| Load Packing List | Reads the supplier packing list or carton label from a photo into structured PO / packing-list / SKU lines |
| Receive / Count | Counts the goods visible on the dock against the packing list, per SKU |
| Receipt Result | Drafts the receiver's factual note (text-only) |
| Chat | General assistant for the receiving walkthrough |

The app stays fully usable without the download: carton labels and item codes
are decoded on the handset by ML Kit, counts can be corrected by hand, and only
the photo-reading steps wait on a model. Manage it under
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

## Telegram receiving bot

[telegram-bot/](telegram-bot/) is a companion JVM service that shares the
receiving log: message the bot a purchase order, packing-list reference, SKU or
a receipt (photo caption / text file) and it replies with the receipt report —
packed vs received per SKU, discrepancies, receipt outcome and the recommended
warehouse action.

```bash
VERITRANSIT_BOT_TOKEN=<token> ./gradlew :telegram-bot:run
```

See [telegram-bot/README.md](telegram-bot/README.md) for the full command set
and token handling (token is read from the environment or a gitignored file —
never committed).

## Back-office dashboard

[dashboard/](dashboard/) is a JDK-only web service for the office side: the
receipts board, per-receipt deterministic PDF reports, a per-receipt
**paperwork page** (the consignment document registry resolved live — legally
required vs. commercially customary, deadlines, and the insurance-and-claim
documents when the dock found goods short or damaged), and `POST /api/records`
for the app's handoff. An accepted delivery shows **OK TO PAY**; a flagged one
holds the delivery and the payment.

```bash
./gradlew :dashboard:run     # http://localhost:8080
```

## Highlights

- **Offline after setup** — in-memory records; the network is used once, to pull
  the model bundle.
- **Motion-first UX** — staggered list entrances, animated counters, scanning line,
  spring checkbox/radio states, filed stamp overlay, animated tab & screen transitions.
- **Lightweight** — R8-minified release APK ≈ 1.4 MB; every visual (evidence canvases,
  viewfinder scene, launcher glyph) is drawn in code or vectors — no binary image assets.
- Repeated counts cycle through receiving scenarios (clean, short, over, unlisted,
  damaged), so the walkthrough stays representative.

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
├── ai/              # GenieX engine, receiving prompts, camera + image prep
├── data/            # models, seed repository, packing-list presets
└── ui/
    ├── theme/       # color tokens, typography (variable fonts), theme
    ├── components/  # chips, buttons, cards, evidence canvas, flow scaffolding
    └── screens/     # Home, Scan, PackingList, DockCount, ReceiptResult, Receipts, Settings
telegram-bot/           # Telegram receiving companion service (JVM)
└── src/…/bot/          # long-poll client, receiving-check engine, routing
dashboard/              # back-office receipts board + deterministic PDF reports (JVM)
```

*Demo data only — not affiliated with any real transport authority.*
