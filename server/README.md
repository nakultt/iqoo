# VeriTransit backend

Ktor + Postgres implementation of [`docs/MASTER_PLAN.md`](../docs/MASTER_PLAN.md)
§6.2 — the verification core and the commerce trust layer.

```bash
./db/scripts/setup.sh                       # database first (once)
./gradlew :server:installDist
VT_OPERATOR_SECRET=veritransit-pilot ./server/build/install/server/bin/server
```

It reads `db/.env` for connection details and the signing key, so a checkout
that has built the database needs no further configuration. Real environment
variables always win.

| Variable | Default | Purpose |
| --- | --- | --- |
| `PORT` | `8080` | HTTP port |
| `VT_OPERATOR_SECRET` | `veritransit-pilot` | sign-in secret and device activation code |
| `VT_SESSION_SECRET` | random per boot | HMAC key for session tokens |
| `VT_CORS_HOSTS` | any | comma-separated allowed origins |

Open `http://localhost:8080/` for the admin UI once `admin-web` is built
(`cd admin-web && npm run build`) — Ktor serves it from the same origin, so the
browser needs no CORS grant.

## What it does

| Service | §6.2 responsibility |
| --- | --- |
| `ShipmentService` | shipments, packages, completeness report, device bootstrap |
| `LabelService` | Ed25519 label issuance, `close-master` hierarchy write, reprint/supersede |
| `ScanService` | idempotent ingest, cross-device duplicate detection, server re-verification |
| `DocumentService` | `DocumentFact` ingest from device NPU or web parser, dedupe by doc no |
| `FourWayMatcher` | PO-anchored ordered ↔ invoiced ↔ declared ↔ physical match |
| `FinanceService` | finance state machine, maker-checker, signed certificates |
| `RiskEngine` | rule-based scores with mandatory explaining factors |
| `PodService` | certificate assembly and signing |
| `AgentService` | the `gather → update → notify → act → report` loop, policy-bounded |
| `WebhookService` | HMAC-signed payer deliveries with retry |
| `AuditLog` | append-only chain, sealed by the database |

## Three things worth knowing before this goes anywhere real

**Authentication is pilot-grade.** The `users` table has no credential column,
so `POST /v1/auth/login` checks a single shared operator secret rather than
per-user passwords or SSO. Roles and maker-checker *are* enforced once identity
is established — a maker genuinely cannot approve their own release — but
anyone with the operator secret can sign in as anyone. This is deliberately not
hidden behind a JWT that would look stronger than it is. Real credentials are a
prerequisite for production, not a nice-to-have.

**No object storage yet.** `evidence_uri`, `source_uri` and `pdf_uri` hold
`s3://` keys pointing at a MinIO instance that does not exist. The shape is
right and the hashes are real; the bytes are not there. Certificates render as
text (`GET /v1/pod/{id}/render`) rather than PDF.

**The agent's LLM is not wired.** `AgentService` assembles its explanations
deterministically from engine output. That is the correct default — §6 says the
engines decide and the model only explains — but the hosted-LLM drafting layer
described in §5.5 is not implemented. Nothing downstream depends on it.

## Tests

```bash
./gradlew :core-models:test :server:test
```

`core-models` tests are pure (token round-trip, tamper cases, the check stack,
band thresholds). The server tests are integration tests against the real
database and **skip** rather than fail when none is reachable — the matcher's
correctness is mostly a question of whether its SQL counts the right boxes, and
a mocked database would only test the mock.
