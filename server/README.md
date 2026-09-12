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
| `VT_WEBHOOK_SECRETS` | none | payer webhook HMAC secrets as `name=secret` pairs, e.g. `"zen-erp=s3cret,metro-erp=s3cret2"`. The database stores only their digests (`webhooks.secret_hash`); a webhook with no configured secret has its deliveries withheld — never sent unsigned |

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

## Who may call what

Everything under `/v1` requires a principal — a device API key (`X-API-Key`,
issued by `POST /v1/devices/activate` with the operator secret) or a session
token (`Authorization: Bearer`, from `POST /v1/auth/login`) — enforced by the
Ktor `authenticate("vt")` block around the route tree. Only `/health`,
`/v1/auth/login` and `/v1/devices/activate` are public, and the live WebSocket
`/v1/live` takes the session token as a `?token=` query parameter because
browsers cannot send headers on a handshake. A new route added inside the
authenticated block is protected by default.

On top of authentication, mutations carry an explicit role gate (§6.2 roles):

| Route | Roles allowed |
| --- | --- |
| `POST /v1/shipments`, `…/status` | ADMIN, SUPERVISOR |
| `POST …/labels:batch`, `/packages:close-master`, `/labels/{code}:reprint` | ADMIN, PACKER |
| `POST /v1/scans:batch` | ADMIN, SUPERVISOR, OFFICER (devices are OFFICER) |
| `POST …/documents`, `POST /v1/shipments/{ref}/pod` | ADMIN, SUPERVISOR (+ OFFICER for PoD) |
| `POST …/reconcile` | ADMIN, SUPERVISOR, OFFICER |
| `POST /v1/finance/{ref}:release` | ADMIN, FINANCE_MAKER |
| `POST /v1/finance/{ref}:approve` | ADMIN, FINANCE_CHECKER |
| `POST /v1/finance/{ref}:hold`, `:resolve` | ADMIN, SUPERVISOR |
| `POST /v1/agent/run/{ref}`, `/discrepancies/{id}/resolve`, `/alerts/voice/{id}/delivered` | ADMIN, SUPERVISOR |
| `GET /v1/audit`, `/v1/audit/verify` | ADMIN, SUPERVISOR |
| `GET /v1/webhooks/deliveries` | ADMIN |
| finance reads (`/finance/queue`, `…/finance`, `…/payments`) | ADMIN, SUPERVISOR, FINANCE_MAKER, FINANCE_CHECKER |

Reads that drive the floor (shipments, packages, scans, risk, discrepancies,
agent explanations) are open to any authenticated caller. The Telegram bot is
just another API caller: issue its `veritransit.api.token` to a user whose role
covers the commands it should have.

## Three things worth knowing before this goes anywhere real

**Authentication is pilot-grade.** The `users` table has no credential column,
so `POST /v1/auth/login` checks a single shared operator secret rather than
per-user passwords or SSO. Authorization *is* enforced once identity is
established — the role matrix above, maker-checker (a maker genuinely cannot
approve their own release), and the audit chain — but anyone with the operator
secret can sign in as anyone. This is deliberately not hidden behind a JWT that
would look stronger than it is. Real credentials are a prerequisite for
production, not a nice-to-have.

**Single tenant by design, for now.** The schema carries `tenant_id` for the
production shape, but this deployment has exactly one tenant and no
principal-scoped queries — every insert resolves the pilot tenant explicitly.
Multi-tenancy means threading `tenant_id` through the principal and every
query; it is not claimed until that exists.

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
