# VeriTransit database

PostgreSQL system of record for the VeriTransit platform, implementing
[`docs/MASTER_PLAN.md`](../docs/MASTER_PLAN.md) §8.1 in full — the verification
core *and* the commerce layer — so later phases add code, not migrations
(§9 Phase 2).

It runs on this laptop and is reachable from other devices on the LAN and over
Tailscale.

---

## Connecting from another device

Credentials are in `db/.env` (not in version control — `cat db/.env`).

| Role | Use | Privileges |
| --- | --- | --- |
| `veritransit_app` | the Ktor backend, the Telegram bot | read/write everything except rewriting `audit_log` |
| `veritransit_ro` | dashboards, analytics, a psql from your phone or desktop | read-only |

Three addresses reach the same database:

| Address | When to use it | Stability |
| --- | --- | --- |
| `Nakuls-MacBook-Pro.local` | same Wi-Fi (mDNS) | **preferred on LAN** — survives a DHCP lease change |
| `172.26.252.224` | same Wi-Fi, literal IP | changes if the router reassigns |
| `nakuls-macbook-pro.tail97d9c7.ts.net` | anywhere, via Tailscale | stable; works off the LAN |

```bash
psql "postgresql://veritransit_ro:PASSWORD@Nakuls-MacBook-Pro.local:5432/veritransit"
```

JDBC, for the Android app and the Ktor server:

```
jdbc:postgresql://Nakuls-MacBook-Pro.local:5432/veritransit
```

**Android note:** an emulator reaches the host at `10.0.2.2`, not `localhost`.
A physical handset on the dock Wi-Fi uses the `.local` name or the LAN IP.

### What is deliberately *not* open

Only the `veritransit` database, only those two roles, only from private
address space (`172.26.252.0/22` and Tailscale's `100.64.0.0/10`), always with
`scram-sha-256`. The superuser and the other databases on this host stay
loopback-only — verified, not assumed:

```
$ psql -h 172.26.252.224 -U veritransit_app -d locus
FATAL: no pg_hba.conf entry for host "172.26.252.224", user "veritransit_app", database "locus"
```

There is no TLS on the wire yet and the macOS firewall is off, so this is a
trusted-network setup: fine for the dock and the tailnet, not for a hostile
network. TLS is a config change (`ssl = on` + a cert), not a migration.

---

## Layout

```
db/
├── migrations/
│   ├── V1__core_schema.sql          tenants, sites, users, devices, shipments,
│   │                                packages (§4.4 hierarchy), labels, scans,
│   │                                discrepancies, audit_log
│   ├── V2__commerce_schema.sql      parties, documents, reconciliation_runs,
│   │                                finance, risk, PoD, policies, agent, webhooks
│   ├── V3__audit_chain_and_views.sql  hash-chain triggers + the 6 read models
│   └── V4__grants.sql               least-privilege grants for the two roles
├── scripts/
│   ├── setup.sh                     rebuild everything from nothing
│   ├── generate_seed.py             demo data + real Ed25519 label signing
│   ├── verify_labels.py             offline signature check, as a device does it
│   └── healthcheck.sql              14 assertions; all must say PASS
├── keys/                            Ed25519 signing keypair (gitignored)
├── seed/S1__demo_data.sql           generated (gitignored — rebuild, don't edit)
└── .env                             credentials (gitignored)
```

Migration files are Flyway-named, so the `server/` module of Phase 2 can adopt
them as-is.

---

## What is in the data

The §2 running example, plus the history that makes risk and finance scores
mean something rather than being decoration.

| Shipment | State | Value | Finance | Why it exists |
| --- | --- | --- | --- | --- |
| `SHP-2026-090187` | RECEIVED | ₹1.60L | **RELEASED** ₹1,12,000 | the clean path: four-way MATCHED, PoD issued, maker-checker release, webhook delivered 200 |
| `SHP-2026-090231` | FLAGGED | **₹14.20L** | **HELD ₹1,04,880** | **the hero** — 148 cartons, 1,480 inner boxes, Kumar Electronics → Zen Digital |
| `SHP-2026-090244` | LOADING | ₹2.37L | AWAITING | mid-load right now (62 of 90 cartons); invoice not yet attached → `DOC_MISSING` |
| `SHP-2026-090198` | DISPATCHED | ₹4.90L | HELD ₹44,500 | the fraud case: HIGH risk (74), duplicate label, QR↔barcode swap, over-invoicing |
| `SHP-2026-090255` | OPEN | ₹0.67L | AWAITING | just created, labels issued, nothing packed — the pack-station starting state |

Totals: **5 shipments, 3,547 packages** (377 cartons + 3,170 inner boxes),
**3,547 signed labels, 2,567 scan events, 21 documents, 47 audit entries**.

### The hero shipment, end to end

`SHP-2026-090231` carries both failures the plan describes, and the money
follows from them exactly:

```
PO ZD-4471 line 2      55 × Smartphone A15 @ ₹17,480
Invoice KE-2291 line 2 60 ×                              → +5 units  = ₹87,400
E-Way Bill 381005472913 agrees with the invoice (raised from it)
Physically verified    59 (one sealed master declared 10, held 9)
                                                          → −1 unit  = ₹17,480
                                                            HELD       ₹1,04,880
```

Two reconciliation runs are stored, not one: the dispatch-time run (physical 60,
holds ₹87,400) and the receipt-time run (physical 59, holds ₹1,04,880). That is
what `reconciliation_runs` is for — the hold grew when the receiver opened the
box, and the history shows it.

The short carton is real data, not a flag on a summary row: one `MASTER`
package with `qty = 10`, nine `UNIT` children marked `RECEIVED` and one marked
`MISSING`, a `SUSPECT_REVIEW` scan carrying `INNER_SHORTAGE` with
`tape_resealed: true`, and an open HIGH-severity discrepancy.

### Ask it the questions the plan asks

```sql
-- the admin dashboard and the Telegram `status` command, one row per shipment
SELECT * FROM v_shipment_dashboard ORDER BY shipment_ref;

-- "why was it held?"
SELECT mismatch_code, line_no, sku, ordered_qty, invoiced_qty, physical_qty, delta_value
  FROM v_four_way_match WHERE shipment_ref = 'SHP-2026-090231';

-- "which carton was short?"
SELECT master_code, contents, declared_inners, verified_inners
  FROM v_master_boxes WHERE NOT fully_verified;

-- the discrepancy queue, worst first
SELECT * FROM v_open_discrepancies;

-- what a phone caches at shift bootstrap (§8.3)
SELECT * FROM v_device_bootstrap WHERE shipment_ref = 'SHP-2026-090231' LIMIT 10;
```

---

## The two things that are actually enforced

**Labels are really signed.** Every label row holds a genuine Ed25519 signature
over the §4.2 token, made with the key in `db/keys/`. A device verifies them
offline with the public key in the `signing_keys` table and nothing else:

```bash
db/.venv/bin/python db/scripts/verify_labels.py
# verified 3547/3547 live labels   signature+binding OK
# tamper check: forged package code rejected
```

**History cannot be rewritten.** `audit_log` seals each entry to the previous
one with SHA-256 in a `BEFORE INSERT` trigger; `UPDATE` and `DELETE` raise; and
the application role is not granted those privileges in the first place.
`audit_verify_chain()` walks the chain and names the first broken link — it
catches an edit even when the trigger is bypassed by the table owner.

```sql
SELECT * FROM audit_verify_chain();   -- ok | checked | first_bad_seq | detail
```

The schema also refuses states the plan forbids: a `UNIT` cannot be a parent
carton, a risk score cannot exist without its explaining factors, a score must
agree with its band's thresholds, a policy cannot be active without a human
approver, and released + held value cannot exceed the order value.

---

## Rebuilding

```bash
./db/scripts/setup.sh                 # drop, recreate, migrate, seed, verify
./db/scripts/setup.sh --schema        # schema only, no demo data
./db/scripts/setup.sh --keep-creds    # reuse the passwords already in db/.env
psql -d veritransit -f db/scripts/healthcheck.sql
```

The generator is deterministic — same package codes and quantities every run.
Signatures change only if the keypair is regenerated (delete `db/keys/` to force
a new key ceremony).

`db/seed/S1__demo_data.sql` is a build artefact; edit `generate_seed.py`.

---

## Operational notes

- Postgres starts at login via `brew services` (`postgresql@18`). After a
  reboot, log in once and it comes back on its own. `brew services restart
  postgresql@18` if it does not.
- Config lives in `/opt/homebrew/var/postgresql@18/`; the originals are backed
  up beside them as `*.conf.bak.20260912141736`.
- No MinIO yet. `evidence_uri` / `source_uri` / `pdf_uri` hold `s3://` keys that
  point at object storage that does not exist — the shape is right, the bytes
  are not there. Stand MinIO up when the backend starts writing real photos.
- Backups: nothing is scheduled. `pg_dump veritransit | gzip > backup.sql.gz`
  is enough for the pilot; the audit chain and evidence are the crown jewels
  (§10).
