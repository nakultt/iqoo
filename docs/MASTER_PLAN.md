# VeriTransit Platform — Master Integration Plan

**What VeriTransit is:** a **physical-to-financial trust layer** for cargo — not
just an inspection app. Every package carries a cryptographically signed label,
field devices verify each scan on the spot (the NPU does the visual work
locally), documents are reconciled against physical reality, and the result
drives the money: **verified shipments release payment, mismatched shipments
hold it.**

> **The pitch:** *"VeriTransit doesn't just verify whether goods are present.
> It verifies whether the physical shipment deserves the digital transaction
> associated with it."*

India is actively pushing risk-based logistics compliance and digital
processing, while documentation errors and verification delays remain everyday
problems. VeriTransit sits exactly in that gap: the four-way match between
**what was ordered (PO) → what was invoiced → what was declared (E-Way
Bill/manifest) → what is physically in the truck**, and a signed, tamper-evident
answer that finance systems can act on.

TL;DR of the design:

- **The QR is not the truth, the signature is.** Labels carry Ed25519-signed
  tokens issued by the backend; devices verify offline.
- **Swap detection is layered:** signature → shipment binding → QR↔barcode
  cross-check → duplicate detection → on-device AI visual checks → server-side
  reconciliation. No single point decides.
- **Local AI is the field differentiator.** All photo/visual judgment (tamper,
  contents, counts, document OCR) runs on the phone's NPU — docks and check
  posts with dead Wi-Fi still work fully.
- **The commerce layer turns verification into money.** Multi-document
  reconciliation, a shipment risk score, payment release/hold with maker-checker,
  digital proof of delivery, and an AI operations agent that runs the loop end
  to end with humans only where policy demands.
- **Offline-first, audit-chained.** Room is the device source of truth with an
  outbox sync; the backend's append-only hash chain means no one can quietly
  rewrite history.
- **Phased delivery:** foundations → device verification flow → backend MVP →
  admin web UI → commerce trust layer → AI agent + scale-out. A verification-only
  warehouse pilot lands at ~week 15; the full FinTech pilot at ~week 24.

---

## 1. Where the project stands today

Inventory of what exists in this repository and how the plan reuses it.

| Exists today | Where | Fate in this plan |
| --- | --- | --- |
| Compose M3 app, 7 screens, full inspection walkthrough | `app/src/main/java/.../ui/` | Kept; becomes **Field/Gate mode**; new **Warehouse mode** and **Delivery (PoD) mode** added beside it |
| Simulated QR viewfinder + manual E-Way Bill entry | `ui/screens/ScanScreen.kt`, `FlowState.kt` | Replaced by a real CameraX + barcode-scanner viewfinder; manual entry stays as fallback |
| In-memory `Repo` singleton with seed records | `data/Repo.kt` | Replaced by Room DB (seed data kept behind a "demo mode" flag) |
| Domain models (`Manifest`, `InspectionRecord`, `CargoItem`) | `data/Models.kt` | Kept and extended; already `kotlinx.serialization`-friendly → shared with backend, bot, and agent |
| On-device AI engine (Qwen3-VL-4B w4a16 on Hexagon NPU via GenieX 0.4.0) | `ai/NpuEngine.kt` | Kept as-is; new AI tasks for document OCR and package verification |
| Three AI tasks: E-Way Bill OCR, cargo reconciliation, note drafting | `ai/InspectorAi.kt` | Kept; `verifyPackage()` and `readDocument()` tasks added |
| CameraX deps already present (core/camera2/lifecycle/view) | `app/build.gradle.kts` | Reused — the analysis-stream plumbing for the scanner is mostly there |
| Telegram container-check bot (JVM, long-poll) | `telegram-bot/` | Becomes the **human interface to the AI operations agent**: queries, approvals, alerts |
| cDSP memory constraint (1007 on 3rd context binary) | README "Known constraint" | Carried into ops runbook: load retries + process restart |

Missing entirely (what this plan builds): persistence, real barcode scanning,
cryptography, networking/sync, a backend, an admin web UI, label issuance,
documents/reconciliation, payment release/hold, risk scoring, proof of
delivery, the AI agent, role-based access, and an audit trail.

---

## 2. How the app will be used — sender side & receiver side

The problem VeriTransit exists for: between pack and delivery, labels get
swapped or duplicated, packages go phantom, and paperwork drifts from the
boxes — and today that surfaces only at a payment dispute, weeks later. The
platform fixes it at the two moments humans actually touch the goods: **packing**
and **receiving**. One running example carries the whole plan:

> **Kumar Electronics** (supplier) ships **148 cartons** — 3 line items,
> **₹14.2L** — to **Zen Digital** (retailer); **SafeMove** transports.
> Payment terms: 70% on verified delivery, 30% net-30.

### 2.1 Sender side — the packer's day (Kumar Electronics)

1. **Pack.** Inner boxes first: each saleable box — an iPhone box, say —
   gets its own small signed label at the source line. Then the big box: the
   packer scans the 10 inner boxes as they go in and taps *Close master
   carton* — the backend signs a master label declaring exactly those 10
   children. Both levels now exist in the platform, linked:
   master → 10 units, each with code, contents, quantity, PO line.
2. **Paperwork.** The officer photographs PO ZD-4471, invoice KE-2291 and the
   E-Way Bill; the phone's NPU reads them into the system on the spot. The
   server immediately cross-checks ordered ↔ invoiced ↔ declared ↔ packed —
   before the truck leaves, not at a dispute.
3. **Load.** The loader scans every box into the truck — master boxes or
   inner labels, the platform treats either as loading its whole subtree.
   Each scan answers in under 1.5 s: ✅ VERIFIED / ⚠️ SUSPECT (reason on
   screen — swapped label, duplicate, wrong box) / ❌ REJECT. The screen
   counts live: *147/148 — box P-…07 missing, named*; the truck cannot be
   released while incomplete or flagged. Loading a master ticks its 10 inner
   boxes as *loaded — pending receiver verification*.
4. **Dispatch.** Everything syncs; the AI agent sends both companies' chats a
   dispatch summary, flags included. *(If the truck is stopped at a highway
   check post, the officer's app shows the risk badge and E-Way Bill — fast
   lane or full check.)*

### 2.2 Receiver side — the receiver's day (Zen Digital)

1. **Arrival.** The receiver opens shipment SHP-2026-090231 on the
   VeriTransit phone; the platform already knows all 148 cartons by code —
   nothing to type.
2. **Unload & scan.** Each master box is scanned off the truck and ticked
   against the dispatch. Anything missing, extra, or wearing a suspect label
   is named immediately — with the sender's evidence photos attached.
3. **Open & verify inside.** The app shows what each master declares —
   *"10 iPhone boxes"* — and the receiver opens it and verifies all 10:
   scanning each inner label as it comes out, or one guided photo the NPU
   counts. The master closes as verified only when all 10 resolve. Only 9
   inside → `INNER_SHORTAGE`, photo evidence attached, payment held for that
   line's value. Verification depth follows risk: LOW-band shipments may
   accept the master scan alone; HIGH-band always opens and counts.
4. **Visual check.** A photo of the goods; the NPU confirms contents match
   the declaration and seals look intact. Doubtful → SUSPECT with the reason.
5. **Sign off.** The receiver signs; GPS + timestamp + photos + scans (per
   master and per inner box) are sealed into a **tamper-evident PoD
   certificate** — the proof for disputes, returns, and insurance.
6. **Get paid — or held.** The four-way match decides the money: green →
   finance releases 70% (maker-checker) via a signed Release Certificate to
   Zen's ERP. Off → exact delta **held** — *in the example, invoice says 60
   units, PO says 55, physical counts 60 → ₹87,400 held* — both parties
   notified, credit note re-runs the match, release completes.

**Why two sides is enough:** the packer's labels and scans make every carton
provable at origin; the receiver's scans and PoD make delivery provable at
destination; everything in between — gate stops, transit alerts, finance
release — hangs off that same record. One shipment, one truth; the manager
watches it all from Telegram.

Use cases the platform directly serves:

| # | Use case | What VeriTransit does |
| --- | --- | --- |
| UC-1 | B2B dispatch with payment-on-delivery | Four-way match (PO/invoice/EWB/physical) gates the payment release signal |
| UC-2 | Marketplace FC inbound (vendor management) | ASN vs physical vs vendor invoice → discrepancy evidence for vendor scoring/chargebacks |
| UC-3 | Risk-based check-post clearance | Risk badge → fast lane vs full check; signed field verdicts |
| UC-4 | Disputes, returns (RTO), insurance claims | Tamper-evident PoD certificate bundle: photos + GPS + timestamps + hashes |
| UC-5 | Finance/ops oversight without logging in | Telegram: "status SHP-90231", "why was it held?", approve release in-chat |

---

## 3. Verification in plain language

Read this before the technical sections. Every check is the app asking one
simple question.

**When a worker scans one box:**

1. **"Is this label real, or printed at home?"** — When the company makes a
   label, its server stamps it with an invisible signature: think hologram
   strip on a banknote. Every phone knows what the real stamp looks like and
   rejects fakes instantly, even offline.
2. **"Is this box supposed to be on THIS truck?"** — The label names its
   shipment, like a boarding pass names its flight.
3. **"Do the two codes on the label agree?"** — Every label carries both a QR
   and a striped barcode with the *same* box number. If someone peeled one off
   and stuck another on, they disagree — ticket vs passport mismatch.
4. **"Have I seen this exact label before?"** — The phone remembers every
   number it has seen this shift. Same number on two boxes — or on two phones —
   means one is a copy. Like a nightclub hand-stamp: one per person.
5. **"Does the box actually look like what the label claims?"** — The worker
   photographs it; the AI in the phone checks: contents match the declaration?
   tape opened and re-stuck? label torn or peeled? It even reads the
   human-readable printed number and compares it to the scanned code.

**When the truck is closed:**

6. **"Is every box on the list actually here?"** — The manifest is a class
   register: 143 names ticked off as scanned. 142 ticks → the app names the
   missing one. A box scanned but not on the list is an extra that slipped in.
   The truck cannot be marked dispatched until every line is accounted for.
7. **"Did everything that left actually arrive?"** — Same register at the
   destination; anything absent becomes a report with photo evidence attached.

**In the background:**

8. **"Central double-check"** — On sync, the server re-checks everything
   *across all phones*: two workers scanning the same box number is invisible
   to one phone, obvious to the server.
9. **"Can anyone secretly erase a mistake later?"** — No. Every scan and
   decision goes into a diary where each page is sealed to the previous one;
   rip out a page and the seals stop matching. Nobody quietly deletes last
   Tuesday's flagged scan.

**The scam walk-through.** Box A = towels (₹500), Box B = phones (₹80,000).
The fraudster wants to ship A as B: *photocopies B's label* → Check 4 (same
number twice). *Peels B's label onto A* → Check 5 (photo shows towels under a
"phones" label) and B itself surfaces as an extra/unlabeled box in Check 6.
*Just removes B* → Check 6 (142 of 143, named). *Prints his own label* →
Check 1 (no real stamp). *Edits history later* → Check 9 (broken seal). And
with the commerce layer: *the invoice says 100 units but 75 are in the truck* →
document reconciliation (§6.1) catches the gap and the payment is held for the
difference.

---

## 4. The trust architecture (technical)

### 4.1 Package identity

Every physical package gets exactly one platform identity at pack time:

```
package_code   VT-P-8F3K2M9D        (short, human-readable, unique)
shipment_ref   SHP-2026-090231      (which dispatch it belongs to)
kind/parent    UNIT → MASTER → PALLET  (parent_code links an inner box to its master)
copy_no        1                    (increments on reprint; old copy invalid)
contents       2 × "Smartphone cartons 5″"   (declared description)
status         CREATED → PRINTED → SCANNED/LOADED → RECEIVED | MISSING | FLAGGED
```

### 4.2 Signed label payload (the QR content)

Compact, offline-verifiable, deliberately not JSON:

```
VT1|P=VT-P-8F3K2M9D|S=SHP-2026-090231|N=1|T=20667|SIG=<base64url Ed25519, 86 chars>
```

- Signed by the backend's Ed25519 key; devices hold the **public** key pinned
  in app config (rotatable via the backend). Verification needs no network.
- Code128 barcode carries `VT-P-8F3K2M9D` alone — fallback for damaged QR and
  one half of the swap cross-check.
- GS1 note: if a pilot partner requires GS1, the QR can carry a GS1 Digital
  Link URI with the signature in a companion DataMatrix; the scanner layer
  parses both. (Open decision §15.)

### 4.3 The detection layers

| # | Layer | Catches | Runs where | Offline? |
| --- | --- | --- | --- | --- |
| 1 | Signature check | forged / fabricated labels | device (Ed25519 verify) | ✅ |
| 2 | Binding checks | wrong shipment, expired reprint, wrong copy no. | device (local manifest cache) | ✅ |
| 3 | QR ↔ barcode cross-check | label physically swapped onto another carton | device | ✅ |
| 4 | Duplicate detection | one label copied onto N cartons | device session-set + server cross-device history | ⚠️ partial |
| 5 | AI visual verification | contents vs declaration, re-taped seal, torn label, miscount, inner-box counts (§4.4) | device NPU (photo evidence) | ✅ |
| 6 | Shipment reconciliation | missing / extra / unlisted packages | backend + device load screen | sync-time |
| 7 | Document reconciliation | invoice vs PO vs manifest vs physical: qty, product, value, party | backend (+ device doc capture) | sync-time |

Per-scan verdicts: `VERIFIED | SUSPECT_REVIEW | REJECTED` with reason codes
(`SIGNATURE_INVALID`, `WRONG_SHIPMENT`, `QR_BARCODE_MISMATCH`,
`DUPLICATE_LABEL`, `REPRINT_SUPERSEDED`, `VISUAL_TAMPER`, `NOT_IN_MANIFEST`,
plus the nested-packaging codes of §4.4). Per shipment: `COMPLETE |
INCOMPLETE | FLAGGED` and, separately, finance state (§6.2). The AI can
*raise* a flag but never *clear* a package alone — a pass requires the
deterministic layers to agree.

### 4.4 Nested packaging — master boxes and inner boxes

Real shipments are nested: a big box holds 10 iPhone boxes; a pallet holds
many big boxes. VeriTransit models this as a package hierarchy.

- **Kinds:** `UNIT` (inner saleable box) → `MASTER` (big box) → `PALLET`
  (optional). Every labeled box gets its own identity; a `parent_code` links
  inner to master. The QR stays identity + signature only (§4.2) — the
  hierarchy lives in the database, so a master can be re-packed without
  reprinting inner labels.
- **Labeling:** inner boxes get small individual labels where feasible; the
  master label declares its children ("10 × iPhone box"). Unlabeled
  supplier-preprinted inners are fine too — the master declares them and the
  receiver verifies by count.
- **Verification depth follows risk** (§5.3): LOW band → master scan alone;
  MEDIUM/HIGH (or any master above a value threshold) → open and verify every
  inner box by scan or NPU count. A master closes as VERIFIED only when all
  inners resolve.
- **New reason codes:** `INNER_SHORTAGE` (declared 10, verified 9),
  `INNER_MISMATCH` (an inner box's parent binding disagrees — it belongs to
  another master), `INNER_UNLISTED` (an extra box inside a sealed master —
  the classic short-ship concealment).
- **Completeness is two-level:** masters for the shipment, inners per master;
  the PoD certificate records both levels, so *"10/10 verified inside"* is
  part of the delivery proof.
- **Inner scans are first-class scan events:** opening a master and scanning
  each inner label runs the same check stack (signature, binding, duplicate,
  AI) as any scan, syncs through the same outbox, and the server re-verifies
  the parent binding on ingest — the backend logic, not the phone alone,
  closes a master as verified.

### 4.5 How data gets in — the population chain (sender side)

Nothing is typed twice; every data item has one moment and one surface where
it is created, and the QR itself carries almost none of it (identity +
signature only, §4.2). The details live in the database and are pushed to the
scanning phones at shift bootstrap.

| Data | Who enters it | When | How |
| --- | --- | --- | --- |
| Parties (GSTIN, PAN) | Admin | Onboarding | Web UI form (later: ERP sync) |
| PO lines (SKU/HSN, qty, rate) | Buyer's team | Ordering | Web UI / CSV import (pilot) → ERP/EDI push (Phase 5) |
| Shipment ("shipping 60 of the 500 units on PO line 2") | Dispatcher | Pre-pack | Web UI, picking from the PO |
| **Package rows + signed labels** | **Packer** | **At pack time** | **Pack station (below)** |
| Invoice, E-Way Bill, LR | Supplier / transporter | Dispatch | PDF upload, or photo read by NPU + confirm |
| Packing list | Nobody — generated | Automatic | Derived from the package registry |
| Risk score, finance state | Engines | Continuous | Automatic |

The pack-station flow is the linchpin — it is where boxes become data:

```
Packer selects shipment SHP-2026-090231
  → registers each inner box (contents, qty, PO line) as it is sealed
  → scans the 10 inner codes into a master box, taps CLOSE MASTER
  → backend: inserts UNIT rows + MASTER row (parent link), signs every
    label token, writes everything to the DB, returns printable labels
  → labels print in the same motion; packer affixes and continues
```

A bulk variant for uniform goods: "300 units ÷ 10 per master = 30 masters"
auto-generates all rows and prints a numbered batch. From Phase 5 the WMS
does this natively and calls the API at carton-close.

Devices bootstrap before the shift (§8.3): today's shipments + packages +
document facts + public key + risk bands, cached for offline scanning — so a
dock scan resolves everything (contents, PO line, finance state) with no
network.

---

## 5. The five commerce capabilities

These sit on top of the verification core and turn it into a trust layer.

### 5.1 Multi-document reconciliation

**What it does:** matches *all* the paper against the physical scan set, not
just the manifest.

- **Document types:** Tax Invoice, Purchase Order, E-Way Bill / Lorry Receipt
  (consignment note), Packing List / Manifest, Delivery Challan.
- **Capture paths:**
  - *Device:* photograph the document → new NPU task `readDocument(kind)`
    (same pattern as today's `readEwayBill`: strict system prompt, JSON-only
    reply, schema-echo guard, confirmation screen before commit).
  - *Web:* upload PDF/image on the admin UI → server parser (deterministic PDF
    text extraction first; LLM assist optional — finance-critical numbers are
    always human-confirmed on screen).
- **Common fact model** (`DocumentFact`): `kind, doc_no, date, seller, buyer,
  ship_to, lines[{description, hsn/sku, qty, unit, rate, amount}], totals,
  tax`. Whatever the paper looks like, the engine matches facts, not formats.
- **Matching engine** (server, deterministic): the **PO is the anchor**; line
  items match by HSN/SKU with fuzzy-description fallback. Produces a match
  matrix and mismatch codes: `QTY_MISMATCH`, `VALUE_MISMATCH`,
  `PRODUCT_MISMATCH`, `PARTY_MISMATCH`, `DOC_MISSING`, `DOC_DUPLICATE`.
  Tolerances are configurable per commodity (default: qty exact, value ±2%,
  party matched by GSTIN — never by name).
- **The four-way match** — the product's core claim:

  ```
  ordered (PO)  ↔  invoiced  ↔  declared (EWB/manifest)  ↔  physically scanned
  ```

  Example output for SHP-90231: *"Invoice KE-2291 line 2: invoiced 60, PO 55
  (+5, ₹87,400), physical 60 → PO-vs-invoice qty mismatch; payment held for
  delta."* Every mismatch lands in the discrepancy queue with the source
  documents and photos linked.

### 5.2 Payment release / hold

**What it does:** converts the verification outcome into a finance action.

- Shipment gains a finance object: order value, payment terms
  (e.g. 70% on verified delivery / 30% net-30), and a state:

  ```
  AWAITING_VERIFICATION → VERIFIED → RELEASE_PENDING → RELEASED
                        ↘ HELD (mismatch) → RESOLVED → RELEASE_PENDING
  ```

- **VeriTransit does not move money.** It emits a signed **Release
  Certificate** or **Hold Notice** (audit-chained JSON event + PDF) that the
  payer's system acts on via adapters: generic webhook first; Tally / ZOHO
  Books / SAP / Razorpay payouts in Phase 5. This keeps VeriTransit a trust
  layer, not an NBFC — no custody of funds, no licensing trap.
- **Maker-checker:** a finance officer requests release; a second role
  approves (web UI or Telegram with explicit confirmation). The release event
  carries the hash of the verification evidence it is based on.
- **Partial release:** if 2 of 3 line items reconcile, release the verified
  value and hold only the disputed delta (configurable policy).
- The agent (§5.5) can execute release/hold **only inside pre-authorized
  policies** (§5.3 rules); anything outside policy waits for a human.

### 5.3 Fraud & risk scoring

**What it does:** turns accumulated verification history into a decision about
how much to trust this shipment, supplier, or receiver — and how much friction
to apply.

- **Score 0–100 per shipment**, plus rolling profiles per party (supplier /
  receiver / transporter) and per route.
- **Inputs (v1, rule-based and explainable):** historical mismatch rate and
  mismatch *type* mix per party, frequency of doc-vs-physical deltas,
  duplicate-label events, value anomalies (declared vs invoice), route/time
  deviations, officer-override frequency, device irregularities (GPS jumps,
  off-hours scans).
- **Bands drive friction** — the risk-based operations story:
  - `LOW` (≤ 30): fast lane — spot AI checks, no supervisor sign-off.
  - `MEDIUM` (31–60): photo evidence mandatory on every scan.
  - `HIGH` (≥ 61): 100% item scan, supervisor co-sign to dispatch, **payment
    auto-hold until resolved**.
- **Explainability is mandatory:** anywhere a score appears, its top-3
  contributing factors appear with it ("HIGH because: 4 value mismatches in 6
  shipments; 2 duplicate-label events; route deviation 11 Feb"). An
  unexplainable score will not be trusted by operations — or by a dispute
  officer.
- v1 ships rules; ML replaces/refines rules only once real history exists
  (Phase 5+). Rules first is a feature, not a compromise: they're auditable.

### 5.4 Digital proof of delivery (PoD)

**What it does:** produces a tamper-evident delivery record that survives a
dispute.

- **Capture (device, works offline):** scans of the delivered packages +
  AI-verified photos + GPS + timestamp + device/officer identity + receiver
  signature or OTP, all hashed **on the device before upload** (provenance
  even over an untrusted network).
- **Assembly (backend):** a **PoD Certificate** — hash-chained, Ed25519-signed
  by the server, containing the evidence hashes and the four-way match result
  at delivery time. Rendered as a PDF for humans; an API for systems.
- **Consumers:** dispute/chargeback packs, returns (RTO runs the flow in
  reverse), insurance claims (evidence bundle export: certificate + photos +
  scan trail in one ZIP), and the payment-release trigger in UC-1.
- Most primitives already exist in the plan's earlier phases (evidence photo
  pipeline, crypto module, audit chain) — PoD is their assembly and packaging,
  which is why it is cheap to add and valuable early.

### 5.5 AI Operations Agent

**What it does:** runs the operational loop so humans handle exceptions, not
chores.

```
trigger (scan ingested | reconciliation done | discrepancy raised |
         shipment completed | payment action due)
   → gather & verify (evidence, four-way match result, risk band)
   → update DB (shipment/finance/risk state)
   → notify (supplier, receiver, supervisor — Telegram/WhatsApp/email)
   → act (release/hold webhook — only within policy; else request human approval)
   → report (PDF/Telegram summary with factor breakdown)
```

- **Runs server-side** (needs the full picture and the network). Field AI
  stays on the NPU; the agent's reasoning uses a hosted LLM in the pilot, with
  an on-prem model option for data-sensitive tenants later. The *decisions*
  (match/no-match, release/hold, score) are deterministic engine outputs — the
  LLM explains, drafts, and routes; it does not decide.
- **Guardrails (non-negotiable):** the agent *proposes, policy disposes*.
  Release/hold executes only inside a finance-approved policy (e.g. "auto-
  release when risk ≤ 30 AND zero mismatches AND value < ₹2L"). Every agent
  action is audit-chained with its reasoning summary; supervisor notifications
  are receipts, not approvals, unless explicitly configured as such.
- **Telegram is the natural interface** (the existing bot's new role):
  query anything ("status SHP-90231", "why held?", "score for Kumar
  Electronics"), receive alerts with factor breakdowns, and approve/hold
  in-chat with explicit confirmation — maker-checker enforced server-side, so
  a compromised chat is still bounded by policy.

---

## 6. System architecture

```
                ┌────────────────────────────────────────────────────┐
                │                   ADMIN WEB UI                     │
                │  React + Vite + TS (served by Ktor static)         │
                │  shipments · commerce/payments · documents · risk  │
                │  PoD certificates · live feed · label studio ·     │
                │  discrepancy queue · devices · audit · policies    │
                └──────────────┬─────────────────────────┬───────────┘
                               │ HTTPS/JSON + JWT        │ webhooks
┌──────────────────────┐   ┌───┴───────────────────────────┴──────────┐
│ ANDROID APP (core)   │   │              BACKEND (Ktor, JVM)         │
│ VeriTransit          │   │  verification core (labels, scans,       │
│ ├ Field/Gate mode    │──▶│  reconciliation, audit chain, Ed25519)   │
│ ├ Warehouse mode     │   │  ─────────────────────────────────────   │
│ ├ Delivery (PoD)     │   │  documents & four-way match engine       │
│ └ NPU AI tasks       │   │  finance state machine + certificates    │
│  (OCR, verify, count)│   │  risk engine (rules v1)                  │
└──────────┬───────────┘   │  ┌────────────────────────────────────┐  │
           │               │  │ AI OPERATIONS AGENT                │  │
           │  HTTPS/JSON   │  │ orchestrator + hosted LLM (explain,│  │
           ▼               │  │ draft, route) — never decides      │  │
┌──────────────────────┐   │  └────────────────────────────────────┘  │
│ TELEGRAM BOT = agent │   └──────┬──────────────┬───────────┬──────-┘
│ chat surface: query, │          ▼              ▼           ▼
│ alerts, approvals    │   ┌──────────┐   ┌──────────┐  ┌────────────────┐
└──────────────────────┘   │ Postgres │   │ MinIO/S3 │  │ INTEGRATIONS   │
                           └──────────┘   └──────────┘  │ payer ERP/bank │
                                                        │ webhook · Tally│
                                                        │ ZOHO · WMS/EDI │
                                                        └────────────────┘
```

Deliberate choices:

- **Kotlin end-to-end.** Ktor backend; `data/Models.kt` types move to a shared
  `core-models` module consumed by app, backend, bot, and agent. One
  serialization story, no DTO drift.
- **Postgres** as the system of record; **MinIO** (S3 API) for evidence and
  document files. Pilot runs on one `docker-compose` host; S3/KMS are config
  changes, not migrations.
- **The device decides in real time; the backend decides authoritatively.**
  Scan verdicts are computed on-device (offline-capable) and re-confirmed on
  ingest; conflicts raise discrepancies, never silently overwrite.
- **Deterministic engines decide, LLMs explain.** Matching, scoring, finance
  state, and audit are rule-based code. The hosted LLM appears only in the
  agent layer: summarizing, drafting notifications, routing — and it executes
  money actions only inside policy.
- **AI never gates alone.** NPU visual signals raise flags; passes require the
  deterministic layers to agree. A 4-bit model misread can cost a supervisor
  visit, never a wrongful release.

### 6.1 Android app changes (mobile UI specification)

| Area | Change |
| --- | --- |
| Persistence | New `data/local/` — Room entities for `Shipment`, `PackageRecord`, `ScanEvent` (outbox), `DocumentFact`, `PodDraft`, plus persisted `InspectionRecord`. `Repo` becomes a Room-backed repository; seed data → demo-mode flag. |
| Scanning | New `scan/` package: CameraX analysis → **ML Kit Barcode Scanning** (on-device, no network; QR + Code128 + DataMatrix in one frame). Simulated path stays behind `Settings → Demo mode`. |
| Crypto | New `crypto/` — Tink Ed25519 verify + record/PoD hashing. (Java Ed25519 is API 33+; minSdk 31 → Tink.) |
| Networking/sync | `net/` + `sync/`: Ktor client, WorkManager outbox, idempotent by client UUID; shift bootstrap pulls shipments, packages, docs, pubkey, risk bands. |
| AI | Fourth and fifth NPU tasks: `verifyPackage(photo, declared)` (printed-code read, tamper flags, contents consistency) and `readDocument(kind)` (invoice/PO/EWB/LR → `DocumentFact`, confirm-before-commit). Inner-box counts at open-and-verify reuse the existing cargo-reconciliation task rather than a new model. Same discipline: JSON-only, schema-echo guard, 2048-token budget. |

**Mobile screens** (all modes; existing design language — verdict colors,
haptics, motion — reused throughout):

| Mode | Screen | Purpose / key elements |
| --- | --- | --- |
| Warehouse | `ShipmentListScreen` | Today's dispatches; completeness ring; **risk badge** (LOW/MED/HIGH); **finance chip** (HELD/READY/RELEASED) |
| Warehouse | `PackageScanScreen` | Rapid-scan hot path; big verdict card + haptic; reason chips; doc-match hint when the shipment has pending mismatches; **master-box mode** — scanning a MASTER opens its inner-box checklist (tick/scan each of the 10, live count, close when all resolve) |
| Warehouse | `LoadReconciliationScreen` | Scanned vs expected; missing/extra lists; dispatch gate banner: "Payment HELD until resolved" |
| Warehouse | `DocumentCaptureScreen` *(new)* | Photograph invoice/PO/EWB; NPU read → editable confirm sheet; attach to shipment |
| Warehouse | `ShipmentDocsScreen` *(new)* | Docs attached, four-way match matrix summary, mismatch chips with jump-to-evidence |
| Delivery | `PodCaptureScreen` *(new)* | Scan off the truck; guided photos; receiver signature/OTP; GPS+timestamp capture; "certificate queued" |
| Delivery | `PodReadyScreen` *(new)* | Certificate summary with share/export (PDF/ZIP) |
| Field/Gate | existing screens | Unchanged flow; risk badge + platform package list check added |

### 6.2 Backend (new `server/` Gradle module)

Ktor (Netty); docker-compose: `server`, `postgres`, `minio`. Verification-core
services from the original plan — `auth`, `shipments`, `labels` (signing + PDF; **close-master** writes the UNIT/MASTER hierarchy
and signs the master label over its declared children), `scans` (idempotent
ingest, cross-device dedup), `reconciliation`,
`audit` (hash chain), `media` — plus the commerce services:

| Service | Responsibility |
| --- | --- |
| `documents` | Ingest `DocumentFact` (device or web upload + parser), store source files, dedupe by doc_no |
| `fourway` | PO-anchored matching engine; tolerances per commodity; mismatch codes; match matrix persisted per run |
| `finance` | Terms + state machine (AWAITING → VERIFIED/HELD → RELEASED); maker-checker; **Release Certificate / Hold Notice** (signed event + PDF); partial release |
| `risk` | Rules engine v1: per-shipment and per-party scores, bands, factor lists; policy config for friction levels |
| `pod` | Assemble certificates from delivery events; sign; render PDF; claims bundle (ZIP) export |
| `agent` | Event bus consumer → orchestrator pipeline (gather → update → notify → act → report); policy enforcement; action log |
| `integrations` | Webhook registry (HMAC-signed payloads, retries), ERP/payment adapters (Phase 5) |

### 6.3 Admin web UI (new `admin-web/`)

React + Vite + TypeScript, TanStack Query, served by Ktor as static assets.

| Page | Purpose / key elements |
| --- | --- |
| **Shipments** | The "are all packages there?" dashboard: completeness ring, missing/extra lists, swap queues, **risk badge + finance state columns**, timeline, export |
| **Commerce / Payments** *(new)* | Release-ready queue, held queue with reasons and deltas, maker-checker actions, partial-release tool, certificate PDFs |
| **Documents & Match** *(new)* | Per-shipment workspace: PO/Invoice/EWB/Packing-list side-by-side, four-way match matrix, mismatch chips → resolve workflow (credit note, recount, approve-as-is with note) |
| **Risk console** *(new)* | Shipment score list + party leaderboard/watchlist, factor drilldown, band-policy editor (what friction each band applies) |
| **PoD certificates** *(new)* | Viewer, PDF/ZIP export, dispute-bundle builder |
| Live ops | Cross-device scan feed, WebSocket push, filters |
| Label studio | Bulk issue, reprint (supersedes copy), printable PDF sheets |
| Pack station | The packer's page: select shipment, register inner boxes, scan children into a master, *Close master* → signed labels print; every DB write lands **before** paper does |
| Discrepancy queue | Flagged events → resolve/escalate; decisions land in the audit chain |
| Devices & people | Activate/retire devices, officers, roles |
| Policies & integrations *(new)* | Agent policies (auto-release rules), webhook endpoints, adapter config, API keys |
| Audit | Hash-chain viewer with tamper indicator; agent action log |

### 6.4 Telegram bot → agent surface

`ContainerCheck`'s resolve-and-report engine is kept; `BotData` (in-memory
vault) is replaced by the backend REST client, and the bot becomes the agent's
chat surface: status/completeness/payment queries, held-shipment explanations
("SHP-90231 is held ₹87,400 — invoice line 2 vs PO"), alert pushes with factor
breakdowns, and in-chat maker-checker approvals (explicit confirmation step,
policy-bounded server-side).

---

## 7. End-to-end flows

### 7.1 Single-package scan (hot path — < 1.5 s to verdict)

```
Camera frame → ML Kit decode (QR + barcode, same frame)
 ├─ L1 Ed25519 verify → fail ⇒ REJECT
 ├─ L2 shipment/copy binding → fail ⇒ SUSPECT
 ├─ L3 barcode == QR package code → fail ⇒ LABEL_SWAP
 ├─ L4 duplicate (session set; server history on sync) → flag
 ├─ L5 photo → NPU verifyPackage (async, can complete after beep)
 └─ verdict card + haptic + Room(scan_event, outbox)
        → online: POST /v1/scans (server re-verifies, dedups) → merge
```

### 7.2 Documents → money (the commerce flow)

```
Officer photographs invoice/PO/EWB ──▶ NPU readDocument ──▶ confirm sheet
        │ (offline queue)                        │
        ▼                                        ▼
POST /documents ──▶ fourway engine: PO ↔ invoice ↔ EWB/manifest ↔ scans
        │
        ├─ all green + risk ≤ policy ──▶ VERIFIED ──▶ maker requests, checker
        │        approves ──▶ signed Release Certificate ──▶ payer webhook
        │
        └─ mismatch ──▶ HELD (delta value) ──▶ agent notifies both parties
                 ──▶ resolution (credit note / recount) ──▶ re-run ──▶ release
```

### 7.3 Load reconciliation (release gate) — unchanged from the original plan

Scanned-set vs expected-set on device and backend; dispatch gated on
`COMPLETE` + no unresolved flags; overrides are audit-chained.

### 7.4 Proof of delivery

```
Delivery scan-off → guided photos (NPU verified) → GPS + timestamp + signature
   → device hashes evidence → outbox → backend assembles & signs
   PoD Certificate (PDF + API) → feeds payment release (UC-1), claims (UC-4)
```

### 7.5 Agent loop

Event → gather (evidence, match result, risk band) → update state → notify
(Telegram/email/webhook) → act **within policy** (release/hold webhook) or
request human approval → report with factor breakdown → every step
audit-chained.

---

## 8. Data model

### 8.1 Postgres (system of record)

```sql
-- verification core
tenants(id, name)
sites(id, tenant_id, name, kind)                      -- warehouse | gate | delivery
users(id, tenant_id, name, role, tg_handle)           -- admin|supervisor|officer|packer|finance_maker|finance_checker
devices(id, tenant_id, site_id, label, api_key_hash, activated_at, retired_at)

shipments(id, tenant_id, ref, vehicle, origin_site, dest_site,
          expected_count, status,                     -- OPEN|LOADING|DISPATCHED|RECEIVED|FLAGGED
          created_by, created_at, dispatched_at, received_at)
packages(id, shipment_id, package_code UNIQUE, kind, -- UNIT|MASTER|PALLET (§4.4)
          parent_code,                                -- NULL at top level; links inner → master
          contents, qty, weight_g,
          status,                                     -- CREATED|PRINTED|LOADED|RECEIVED|MISSING|FLAGGED
          created_at)
labels(id, package_id, copy_no, payload, signature, issued_at, superseded_at)

scan_events(id UUID, client_event_id UUID UNIQUE, package_code, shipment_ref,
            device_id, officer_id, kind,             -- LOAD|RECEIVE|FIELD|POD
            result, reasons JSONB, ai_flags JSONB, evidence_uri,
            lat, lng, client_ts, server_ts, source)
discrepancies(id, shipment_id, kind, package_code, detected_at,
              resolved_by, resolution, note)
audit_log(seq, at, actor, action, subject, payload JSONB, prev_hash, hash)

-- commerce layer
parties(id, tenant_id, kind,                          -- supplier|buyer|transporter
        name, gstin, pan, meta JSONB)
documents(id, shipment_id, kind,                      -- PO|INVOICE|EWB|LR|PACKING_LIST|CHALLAN
          doc_no, doc_date, party_from, party_to,
          fact JSONB,                                 -- DocumentFact
          source_uri, read_by,                        -- 'device_npu' | 'web_parser'
          confidence, uploaded_by, uploaded_at)
reconciliation_runs(id, shipment_id, matrix JSONB, mismatches JSONB,
                    status, run_by, created_at)
finance_terms(id, shipment_id UNIQUE, order_value, terms JSONB,
              status,                                 -- AWAITING|VERIFIED|HELD|RELEASE_PENDING|RELEASED
              released_value, held_value)
payment_events(id, shipment_id, kind,                 -- HOLD|RELEASE_REQUEST|RELEASE_APPROVED|CERTIFICATE_ISSUED|WEBHOOK_DELIVERED
               actor, policy_id, payload JSONB, audit_seq, created_at)
risk_scores(id, subject_kind,                         -- shipment|party|route
            subject_id, score, band, factors JSONB, computed_at)
pod_certificates(id, shipment_id, events JSONB, evidence_hashes JSONB,
                 signature, issued_at, pdf_uri, bundle_uri)
agent_actions(id, trigger_event, steps JSONB, outcome,
              reasoning_summary, policy_id, audit_seq, created_at)
policies(id, tenant_id, kind,                         -- agent_auto_release | friction_band | tolerance
         rules JSONB, active, approved_by)
```

### 8.2 Room (device mirror + outbox)

`shipment`, `package`, `document_fact`, `pod_draft`, `scan_event(outbox,
client_event_id UNIQUE)`, `inspection_record`, `key_config`. Server is
authoritative for shipment/package/document/finance rows; the device is
authoritative for its own scan/PoD events until acked.

### 8.3 API sketch

| Method/Path | Who | Purpose |
| --- | --- | --- |
| `POST /v1/devices/activate` | device | one-time code → API key |
| `GET /v1/sync/bootstrap?site=` | device | shipments + packages + docs + pubkey + risk bands (cached offline) |
| `POST /v1/shipments` | admin | create dispatch |
| `POST /v1/shipments/{ref}/labels:batch` | admin/packer | issue n signed labels (+ PDF) |
| `POST /v1/packages:close-master` | packer | children codes in → UNIT + MASTER rows written, hierarchy linked, master label signed (DB updated before print) |
| `POST /v1/labels/{code}:reprint` | admin | new copy_no, supersede old |
| `POST /v1/scans:batch` | device | idempotent ingest, server verdicts back |
| `GET /v1/shipments/{ref}/report` | all | completeness + discrepancies |
| `POST /v1/shipments/{ref}/documents` | device/web | attach `DocumentFact` (+ source file) |
| `POST /v1/shipments/{ref}/reconcile` | web/agent | run four-way match |
| `GET /v1/shipments/{ref}/finance` | all | terms, state, held/released value |
| `POST /v1/finance/{ref}:release` / `:hold` | finance maker/checker | state transitions (role-enforced) |
| `GET /v1/risk/shipments/{ref}` · `/v1/risk/parties/{id}` | all | score + factors |
| `POST /v1/shipments/{ref}/pod` | device | submit delivery evidence bundle |
| `GET /v1/pod/{id}.pdf` · `/bundle` | all | certificate / claims ZIP |
| `POST /v1/webhooks` | admin | register payer endpoint (HMAC-signed, retried) |
| `POST /v1/policies` | admin | agent auto-release rules, friction bands, tolerances |
| `GET /v1/agent/actions?shipment=` | admin | agent step log |
| `GET /v1/audit?from=&to=` | admin | hash-chain dump |

---

## 9. Phased roadmap

Two engineers (one Android, one backend/full-stack) plus part-time design;
calendar time includes noted overlaps. Every phase ends runnable on a real
device.

### Phase 0 — Foundations (weeks 1–3)
*No visible feature change; everything depends on this.*

- Room layer replacing in-memory `Repo` (seed data → demo mode).
- `core-models` Gradle module; move `data/Models.kt` types into it.
- Crypto module: Tink Ed25519 verify + the token format from §4.2, unit-tested.
- Real scanner: CameraX + ML Kit viewfinder behind a Settings flag; manual
  entry stays.
- CI: assemble + unit tests on every push.

**Accept:** persistence across process death; demo toggle works; tampered token
fails verification in tests; real QR scan resolves the existing flow.

### Phase 1 — Device verification flow (weeks 3–8; overlaps Phase 0 tail)
*The warehouse scanner works standalone, offline.*

- `verifyPackage()` NPU task + evidence photo pipeline.
- PackageScan / LoadReconciliation / ShipmentList screens (local Room only).
- Layers 1–4 locally (session duplicate set, QR↔barcode cross-check, binding).
- Officer/device identity (Settings → activation). PoD capture primitives
  (GPS/timestamp/signature capture) land here inside the evidence pipeline.

**Accept:** planted label-swap flagged in < 2 s offline with reason on screen;
100-scan session survives app kill/restart with zero lost events.

### Phase 2 — Backend MVP (weeks 4–9; parallel with Phase 1)

- Ktor + Postgres + MinIO via docker-compose; Flyway migrations. Schema ships
  **including** the commerce tables (parties/documents/finance/risk/pod/
  agent/policies) and the package hierarchy (`kind`, `parent_code`) so later
  phases add code, not migrations.
- Services: auth, shipments, labels (signing + PDF + the close-master
  hierarchy write), scans (idempotent, cross-device dedup), reconciliation,
  audit (hash chain), media, documents (upload/parse).
- App sync: bootstrap + WorkManager outbox; server-verdict merge; conflict →
  discrepancy. Load test: 4 devices × 500 scans/session.

**Accept:** two devices scanning the same package both see the duplicate flag
after sync; Wi-Fi killed mid-session loses nothing; server report matches the
device's local reconciliation.

### Phase 3 — Admin web UI + bot rewiring (weeks 9–13)

- Core pages: Shipments (completeness), Live ops, Label studio, **Pack
  station** (sender-side QR generation + DB writes), Discrepancy queue,
  Devices & people, Audit. Commerce dashboards (Payments, Documents, Risk,
  PoD) land as **read-only skeletons** over the same data.
- WebSocket live feed; Telegram bot → REST client; alerts on `FLAGGED`.

**Accept:** an operator issues 150 labels, prints them, watches scans land
live, sees "1 missing" on the completeness dashboard, exports the report. A
packer closes a 10-unit master on the pack-station page and the receiver
device's checklist shows all 10 children.

### Phase 4 — Commerce trust layer (weeks 13–19)  ★ the FinTech phase

- **Four-way match engine** (`fourway`): fact model, PO-anchored matching,
  tolerances, mismatch codes; `readDocument()` NPU task + DocumentCapture /
  ShipmentDocs screens; web document workspace with resolve workflow.
- **Finance state machine:** terms, HELD/VERIFIED, maker-checker, partial
  release, signed Release Certificate / Hold Notice (event + PDF), generic
  payer webhook (HMAC-signed, retried).
- **Risk engine v1:** rule-based scores + bands + factor lists; friction
  policies; risk badges on mobile and web.
- **PoD certificates:** PodCapture/PodReady screens, server assembly + signing,
  PDF + claims-bundle export.
- Risk-based verification depth switches on (§4.4): HIGH-band and
  value-threshold masters require open-and-verify; LOW may pass on master
  scan alone.
- Fraud drill extended: planted invoice mismatches, party anomalies, and a
  sealed master declaring 10 with only 9 inside (`INNER_SHORTAGE`).

**Accept:** the UC-1 scenario runs end-to-end on real data — four-way mismatch
holds exactly the disputed delta; release requires two roles; the payer webhook
receives a verifiable signed certificate; a PoD bundle stands up to an
adversarial "prove this was delivered" review.

### Phase 5 — AI Operations Agent + scale-out (weeks 19–25, then ongoing)

- **Agent:** event-bus orchestrator (gather → update → notify → act → report),
  hosted-LLM reasoning with deterministic decisions, policy guardrails, action
  log, Telegram command surface (query, explain, approve-in-chat).
- **Integrations:** Tally / ZOHO Books / SAP adapters, Razorpay-style payouts,
  WMS/EDI ASN push-and-pull for marketplace FCs.
- Risk ML refinement over accumulated history; optional on-prem model for the
  agent; multi-tenant hardening; KMS-held signing key; managed Postgres/S3.

**Accept:** a finance manager runs a full day from Telegram — holds explained
with factors, one release approved in-chat (checker enforced), zero agent
actions outside policy in the audit log.

**Milestones:** verification-only warehouse pilot at ~week 15 (end of Phase 3
+1), full FinTech pilot at ~week 24.

---

## 10. What must exist vs what must be built

**Must exist (procurement / environment):**

- Pilot devices: any Android 12+ (minSdk 31) for the deterministic path; at
  least one Snapdragon 8-Elite-class handset for the NPU path (~4.4 GB model
  download per device).
- Thermal-transfer label printer (Zebra ZD-class) + QR-grade stock (100×150 mm
  template).
- Dock Wi-Fi survey — or accept offline-first operation and sync at shift end.
- One VM/host for docker-compose (server + Postgres + MinIO), TLS, backup
  policy — evidence photos, documents, and the audit chain are the crown
  jewels.
- Signing key ceremony: Ed25519 keypair, public key into app config, private
  key into a vault (env-file to start, KMS in Phase 5).
- **Commerce prerequisites (new):** the payer's webhook endpoint or ERP access
  (Tally/ZOHO) for Release Certificates; party master data (GSTIN/PAN) for
  suppliers/buyers/transporters; a hosted-LLM API budget for the agent; legal
  review of the certificate wording (VeriTransit certifies facts; liability
  language matters).
- A pilot partner (dock + finance team willing to act on holds) + sample
  PO/invoice/EWB data; MDM profile for shared devices.

**Must be built (by phase):**

| Deliverable | Phase |
| --- | --- |
| Room persistence + core-models + real scanner + crypto | 0 |
| Package verify AI task + warehouse screens + identity | 1 |
| Ktor backend, full schema, label signing, sync, document upload | 2 |
| Admin web UI core (incl. pack-station page) + bot → REST | 3 |
| Four-way match, finance state machine, risk v1, PoD certificates | 4 |
| AI agent + policies + Telegram surface, ERP/WMS adapters | 5 |

---

## 11. Testing strategy

- **Unit:** token sign/verify round-trip and tamper cases; reconciliation set
  algebra; four-way matcher property tests (tolerance edges, duplicate doc
  nos, currency rounding); finance state machine transitions (including
  illegal ones); outbox idempotency; `extractJsonObject` edge cases.
- **Agent guardrail tests:** the agent cannot release outside policy, cannot
  approve its own release, and every action lands in the audit chain.
- **Instrumented:** scanner decode on real frames (QR + Code128 in one frame);
  NPU `verifyPackage`/`readDocument` on-device (follow the existing
  `am instrument` pattern; keep the README warning about
  `connectedDebugAndroidTest` deleting the model bundle).
- **Integration (backend):** ingest dedup, cross-device duplicate, webhook
  HMAC + retry, audit hash-chain tamper detection.
- **Red-team drill (Phase 4 gate):** planted swaps, photocopies, wrong-
  shipment, forged QR, photo replay, **inflated invoice vs PO, quantity
  shortfall vs physical, a sealed master declaring 10 with only 9 inside
  (`INNER_SHORTAGE`), an inner box from another master (`INNER_MISMATCH`)** —
  each must land in the discrepancy queue with the right reason code and (for
  commerce) the right hold amount.

## 12. Risks & mitigations

| Risk | Mitigation |
| --- | --- |
| 4-bit VLM misreads a package photo or dense invoice print | AI can flag, never clear; finance-critical numbers are human-confirmed on screen before commit; deterministic layers gate passes |
| Financial liability for a wrong release certificate | Certificates state verified facts + evidence hashes, not guarantees; maker-checker required; legal wording review before pilot |
| Agent autonomy over money | Policy-bounded execution, maker-checker always available, full action log, deterministic decisions only |
| cDSP 1007 load failure (known constraint) | retry/backoff + documented restart; runbook in Phase 3; one vision session at a time |
| Label printers drift / unreadable codes | Code128 fallback + AI printed-code OCR as third read; reprint supersedes copies |
| Officers treat SUSPECT as PASS under time pressure | verdict UI demands a reason tap; overrides audit-chained and surfaced in admin |
| Offline device holds stale shipment/finance cache | bootstrap refresh on dock Wi-Fi; stale > 24 h forces re-sync before reconciliation/release is authoritative |
| Signing key compromise | rotation path from day one; pubkey pinned via config; KMS in Phase 5 |
| Document fraud (edited PDFs/invoices) | facts always reconciled against physical scans and PO anchor; mismatch codes fire regardless of how "clean" one document looks |

## 13. Open decisions (flag early; none block Phase 0)

1. **GS1 vs internal token** in the QR — depends on the pilot partner's
   scanner ecosystem.
2. **First integration target** for Release Certificates — generic webhook
   (fastest) vs Tally/ZOHO (most credible for Indian mid-market pilots).
3. **Photo/document retention** — evidence ages out (30 days proposed);
   PoD certificates must persist longer for disputes/insurance; legal review.
4. **Packer UX at issuance** — dedicated pack-station page vs reprint-capable
   handhelds only.
5. **Agent notification channels** — Telegram-only for the pilot vs adding
   WhatsApp Business API (extra approval chain, better supplier reach).
6. **Weight as a verification signal** (BLE scale, post-pilot) — strong
   anti-swap evidence, adds hardware.
