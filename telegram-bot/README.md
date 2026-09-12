# VeriTransit Receiving Bot (Telegram)

Companion service to the **VeriTransit Receiving** app. Anyone who messages the
bot — with a question or a receipt — gets the matching delivery checked against
the receiving log shared with the app (same deliveries, receipt outcomes, and
discrepancy reports).

A supplier packs against a purchase order and a packing list; a warehouse
receives the delivery and books what actually arrived. The bot is a
goods-receiving helper for B2B trade — it is **not** a GST, customs or legal
verification tool, holds no statutory authority and integrates with no tax
system.

The bot runs long-polling against the Telegram Bot API: no webhook, no public
endpoint, no database. It uses only the JDK HTTP stack + kotlinx-serialization.

## What it answers

| You send | The bot does |
| --- | --- |
| `PO-2025-4471` | Full receipt report: packed vs received per SKU, discrepancies, receipt outcome, recommended action |
| `PL-2025-4471-A` | Checks the delivery that packing list belongs to (tolerates `pl 2025 4471 a`) |
| `ELC-2710` | Checks every delivery carrying that SKU |
| `GRN-2025-8841` | Checks by GRN id |
| "how did the cereal delivery do?" | Keyword match on goods / supplier / item names → receipt report |
| "any flagged deliveries?" | List of deliveries held for review |
| "shift stats" | Warehouse summary: receipts, accepted, flagged, pending, flag rate |
| Receipt photo with a PO caption | Matches the receipt to the delivery and runs the receiving check |
| Text/CSV/JSON receipt file | Downloads the file, scans it for PO / packing-list / SKU references, runs the check |

Commands: `/start` `/help` `/check <ref>` `/recent` `/flagged` `/stats`.

## Receipt outcomes

`OK` · `SHORT` · `OVER` · `MISMATCH` (unlisted or damaged goods) · `PENDING`
(still being counted). The bot recommends a plain warehouse action for each:
**Accept Delivery / Flag for Review / Request Recount / Not Received**.

## Run it

```bash
# 1. Provide the token (choose one; never commit it)
export VERITRANSIT_BOT_TOKEN=<token>        # env var
# …or put it in gitignored telegram-bot/secrets.properties:
#   telegram.bot.token=<token>

# 2. Start long-polling
./gradlew :telegram-bot:run

# Diagnostics only — verifies the token + connectivity without replying:
./gradlew :telegram-bot:run --args="--poll-once"
```

Requires JDK 17+ (the module targets JVM 17 bytecode).

## Token hygiene

The token is read from (in priority order):

1. system property `telegram.bot.token`
2. env `VERITRANSIT_BOT_TOKEN` / `TELEGRAM_BOT_TOKEN`
3. `telegram-bot/secrets.properties` — **gitignored**, so it never lands in the repo

If you have ever pasted the token in plain text (chat, ticket, screenshot),
rotate it with [@BotFather](https://t.me/BotFather) (`/revoke`).

## Layout

```
telegram-bot/src/main/kotlin/com/veritransit/inspector/bot/
├── BotMain.kt         # entrypoint, long-poll loop, message routing & replies
├── ReceivingCheck.kt  # reference resolution (PO/PL/SKU/GRN/keyword/receipt) + reports
├── TelegramClient.kt  # Bot API long-polling, sendMessage, document download
├── BotConfig.kt       # token resolution (env / property / gitignored file)
├── BotData.kt         # seed log mirroring the app's Repo (demo data)
└── Models.kt          # domain model shared with the app's data layer
```

*Demo data only — not affiliated with any real transport authority.*
