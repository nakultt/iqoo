# VeriTransit Container-Check Bot (Telegram)

Companion service to the **VeriTransit Cargo Inspector** app. Anyone who messages
the bot — with a question or a receipt — gets the matching container checked
against the inspection vault shared with the app (same consignments, verdicts,
and discrepancy reports).

The bot runs long-polling against the Telegram Bot API: no webhook, no public
endpoint, no database. It uses only the JDK HTTP stack + kotlinx-serialization.

## What it answers

| You send | The bot does |
| --- | --- |
| `EWB-7819-2044` | Full container check: manifest reconciliation, discrepancies, verdict, recommended statutory action |
| `TN 38 BX 4491` | Checks every inspection of that vehicle (tolerates `tn38bx4491`) |
| `VT-2024-8841` | Checks by inspection record id |
| "how did the cereal shipment do?" | Keyword match on cargo / item names → container check |
| "any flagged consignments?" | List of consignments held for review |
| "shift stats" | Station summary: inspections, cleared, flagged, pending, flag rate |
| Receipt photo with an EWB caption | Matches the receipt to the consignment and runs the container check |
| Text/CSV/JSON receipt file | Downloads the file, scans it for an EWB / vehicle / item references, runs the check |

Commands: `/start` `/help` `/check <ref>` `/recent` `/flagged` `/stats`.

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
├── ContainerCheck.kt  # reference resolution (EWB/vehicle/id/keyword/receipt) + reports
├── TelegramClient.kt  # Bot API long-polling, sendMessage, document download
├── BotConfig.kt       # token resolution (env / property / gitignored file)
├── BotData.kt         # seed vault mirroring the app's Repo (demo data)
└── Models.kt          # domain model shared with the app's data layer
```

*Demo data only — not affiliated with any real transport authority.*
