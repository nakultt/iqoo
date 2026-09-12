# VeriTransit — Photo-Verified Scan Flow

**Implementation plan.** Extends [MASTER_PLAN.md](MASTER_PLAN.md) §4.3 (detection
layers), §5.1 (documents) and §6.1 (mobile UI). Section numbers here are `§A`–`§K`
so they never collide with the master plan's.

The one-line statement of intent:

> A QR scan alone must stop being a verdict. Scan → **photograph the goods** →
> pull what the database says that code contains → **prove the photo agrees with
> the record** → persist photo, reading and adjudication together.

Three things this covers beyond that spine:

* **§G** — bills are read against *generated* profiles with open field capture,
  because a delivery challan, an LR and a GST invoice do not share a schema.
* **§D.3** — "how many are in this photo" answered in a way that can be trusted,
  or honestly reported as unknown.
* **§J** — the QR scanner's own defects, **already fixed in the working tree**.

---

## §A. What exists today, and the five things wrong with it

### A.1 What works and stays

| Layer | Where | Verdict |
| --- | --- | --- |
| L1 Ed25519 signature | [LabelVerifier.kt](../app/src/main/java/com/veritransit/inspector/crypto/LabelVerifier.kt) | keep |
| L2/L3/L4 binding, QR↔barcode, duplicate | [LabelChecks](../core-models/src/main/kotlin/com/veritransit/core/LabelToken.kt) | keep |
| Demote-only AI fold-in | [VerificationEngine.applyAiFlags](../app/src/main/java/com/veritransit/inspector/scan/VerificationEngine.kt) | keep — this rule is load-bearing |
| Offline-first outbox for **scans** | [VeriTransitRepo.recordScan](../app/src/main/java/com/veritransit/inspector/data/VeriTransitRepo.kt) | keep |
| Master/inner checklist | [ReceiverScreens.kt](../app/src/main/java/com/veritransit/inspector/ui/receiver/ReceiverScreens.kt) | keep, re-sequence |

### A.2 Defect 1 — the photo check is optional and off to one side

`AiVisualCheckScreen` is reachable only by tapping an **AI** button beside an
inner row ([ReceiverScreens.kt:196](../app/src/main/java/com/veritransit/inspector/ui/receiver/ReceiverScreens.kt)).
An officer under time pressure never taps it. The scan therefore resolves on
cryptography alone — which proves *the label is genuine*, and says nothing at
all about *what is in the box*. Label-swap and substitution both pass.

**Fix (§C):** the photo becomes a **gate in the sequence**, not a side trip. A
package cannot reach `VERIFIED` without a `ProductCheck` row, unless the risk
band's friction rule explicitly says `photo_evidence = "none"` and a supervisor
owns that policy.

### A.3 Defect 2 — the VLM is asked the one question it is worst at

```kotlin
// InspectorAi.kt:359
This parcel's label declares: $declaredContents (quantity $declaredQty).
  contents_match - true if what you can see is consistent with the declaration above
```

This hands a 4-bit 4B model the answer and asks it to agree. Quantised models
under-refuse: given a declaration and a blurry carton, `contents_match: true` is
the overwhelmingly likely token regardless of what is in the frame. The one
field that would catch substitution is the one field that is anchored.

**Fix (§D):** split perception from adjudication.

1. **Blind observation** — the model is shown the photo and *not* told what is
   expected. It transcribes and describes: printed strings, brand, model, count,
   packaging, colour, seal state, damage.
2. **Deterministic match** — plain Kotlin compares that observation against the
   database expectation, field by field, with per-field rules and tolerances.
3. **Explanation** — a text-only turn drafts the officer-facing sentence, from
   the already-decided deltas.

The model does perception. Kotlin decides. The decision is then unit-testable,
reproducible, and auditable — none of which is true of a boolean off a 4-bit LLM.

### A.4 Defect 3 — bills are not durably saved

Four concrete bugs, in the order they bite:

1. **Evidence lives in the cache.** `EvidenceCamera.capture` writes to
   `context.cacheDir` ([Evidence.kt:71,91](../app/src/main/java/com/veritransit/inspector/ai/Evidence.kt)).
   Android evicts `cacheDir` under storage pressure with no notice. Every bill
   photo, cargo frame and package shot is one low-storage event away from gone,
   and `flow.billEvidence` ([ScanScreen.kt:253](../app/src/main/java/com/veritransit/inspector/ui/screens/ScanScreen.kt))
   is a path that can dangle. Nothing is hashed; `evidence_sha256` in the schema
   is never populated from this path.
2. **There is no document outbox.** The confirm handler
   ([SenderScreens.kt:271-288](../app/src/main/java/com/veritransit/inspector/ui/sender/SenderScreens.kt))
   writes the Room row, then makes a *best-effort* server call and, on failure,
   tells the user `"Saved on phone — syncs with the outbox."` It does not.
   `VeriTransitRepo.drainOutbox()` drains `scan_outbox` only. A bill captured on
   a dock with no Wi-Fi never reaches Postgres, so the four-way match runs
   against paperwork that does not exist.
3. **Blank document numbers collide destructively.** Local rows fall back to
   `"NOP-${System.currentTimeMillis() % 10000}"`, but the *server* call sends
   `p.docNo` — blank. Postgres has
   `CREATE UNIQUE INDEX documents_dedupe_key ON documents (kind, upper(doc_no))`
   ([V2:73](../db/migrations/V2__commerce_schema.sql)) and `attach` does
   `ON CONFLICT … DO UPDATE SET fact = EXCLUDED.fact`. So the second unreadable
   invoice **overwrites the first one's facts**. Silent data loss on exactly the
   rows finance acts on.
4. **`confirmed_by` is always null.** `DocumentService.attach` reads the
   `X-Confirmed-By` header ([Routes.kt](../server/src/main/kotlin/com/veritransit/server/routes/Routes.kt)),
   `ApiClient` never sends it. The §12 rail — *a document that moves money must
   name the human who confirmed its numbers* — is inert in production.

Additionally the **gate flow throws its bill away entirely**: `readEwayBill`
builds a `Manifest` for the screen and no `DocumentFact` is ever persisted
([ScanScreen.kt:254](../app/src/main/java/com/veritransit/inspector/ui/screens/ScanScreen.kt)).

**Fix (§F).**

### A.5 Defect 4 — the DB has nothing worth cross-checking against

`packageByCode` returns `contents` (free text) and `qty`. There is no brand, no
model, no unit of measure, no HSN on the device (`PackageEntity` drops the `hsn`
that `PackageRecord` carries), no declared markings, no unit value. A match
engine can only compare what the record holds, so the record has to hold more.

**Fix (§B.2, §H.1).**

### A.6 Defect 5 — the QR scanner itself is unreliable *(fixed, see §J)*

None of the above matters if the code never decodes. Seven distinct faults were
found in the scanning path; all are diagnosed and fixed in §J.

---

## §B. The corrected sequence

### B.1 The hot path, end to end

```
 ┌ deterministic, offline, < 1.5 s ─────────────────────────┐
 │ decode → L1 sig → L2 binding → L3 QR↔barcode → L4 dup    │  ScanResult A
 └──────────────────────────────────────────────────────────┘
                          │  verdict + haptic + voice  (unchanged)
                          ▼
 ┌ photo gate (new, mandatory unless policy waives) ────────┐
 │ "Photograph the goods for VT-P-8F3K2M9D"                 │
 │   shot 1: the label face      shot 2: the contents       │
 └──────────────────────────────────────────────────────────┘
                          │
                          ▼
 ┌ expectation lookup (Room, offline) ──────────────────────┐
 │ PackageExpectation: contents, sku, hsn, brand, model,    │
 │ uom, qty, unit value, declared marks, PO line,           │
 │ referenceObservation (what the sender's camera saw)      │
 └──────────────────────────────────────────────────────────┘
                          │
                          ▼
 ┌ VLM, blind (no expectation in the prompt) ───────────────┐
 │ ProductObservation: printed_code, brand, model, text[],  │
 │ item_kind, visible_count, packaging, colour, seal,       │
 │ damage, confidence                                       │
 └──────────────────────────────────────────────────────────┘
                          │
                          ▼
 ┌ ProductMatcher — plain Kotlin, deterministic ────────────┐
 │ per-attribute verdicts → MatchOutcome                    │
 │   MATCH · WEAK_MATCH · MISMATCH · INDETERMINATE          │
 └──────────────────────────────────────────────────────────┘
                          │
                          ▼
      applyAiFlags(demote-only) → final verdict → screen + voice
                          │
                          ▼
 ┌ persist, local-first ────────────────────────────────────┐
 │ evidence file (filesDir) + sha256 → EvidenceEntity       │
 │ ProductCheckEntity (observation + outcome + deltas)      │
 │ scan_outbox row carries evidenceUri + sha + aiFlags      │
 └──────────────────────────────────────────────────────────┘
```

### B.2 `MatchOutcome` → verdict mapping

| Outcome | Meaning | Effect on the scan verdict |
| --- | --- | --- |
| `MATCH` | every required attribute agreed | verdict stands (never promoted) |
| `WEAK_MATCH` | agreed on ≥1 strong attribute, others indeterminate | stands, banner: "partial visual confirmation" |
| `INDETERMINATE` | photo unusable / model unsure / NPU absent | stands, flagged `PHOTO_INCONCLUSIVE`, retake offered |
| `MISMATCH` | any required attribute contradicted | `VERIFIED` → `SUSPECT_REVIEW`, reason codes attached |

`MISMATCH` never produces `REJECTED` on its own and never clears an existing
`REJECTED`. The existing one-directional rule in `applyAiFlags` is the only place
this is enforced — keep it that way.

---

## §C. The photo gate

New composable `ProductProofScreen` (`ui/verify/ProductProofScreen.kt`),
entered automatically from the scan result, not from a button.

* **Two shots, one flow.** Shot 1 `Fit.CONTAIN` on the label face (OCR of the
  human-readable code and printed marks). Shot 2 `Fit.COVER` on the contents.
  Each is its own VLM turn — the bundle's encoder is fixed at 512×512 / ~256
  tokens and two images in one turn halves the room left for the answer
  (`CONTEXT_TOKENS = 2048`).
* **Skip is a policy decision, not a user decision.** The button reads
  *"Skip — supervisor override"*, is disabled unless the band's
  `FrictionRule.photoEvidence` allows it, writes a `PHOTO_SKIPPED` audit row and
  records the officer name.
* **Spot-check sampling.** For `photo_evidence = "spot"`, the app picks the
  sampled inners deterministically from `sha256(shipmentRef + packageCode)` so
  the officer cannot shop for an easy carton, and the sample is reproducible in
  audit.
* **Degrades honestly.** No NPU → outcome is `INDETERMINATE`, the screen says
  *"Photo stored as evidence; on-device AI not loaded, so no visual
  adjudication"*, and the photo is still saved and hashed. Evidence capture must
  never depend on the model being resident.

---

## §D. The VLM task catalogue

All tasks live in `InspectorAi`, all use `NpuEngine.run`, all budget against
`CONTEXT_TOKENS = 2048` with ~256 spent per image.

| # | Task | Role | Image | maxTokens | New? |
| --- | --- | --- | --- | --- | --- |
| 1 | `observeProduct` — blind description + OCR | sender, receiver, gate | ✓ | 384 | **new** |
| 2 | `observeLabelFace` — printed code, marks, seal | receiver, gate | ✓ | 256 | **new** |
| 3 | `countUnits` — how many units are in the photo | sender, receiver, gate | ✓ | 224 | **new**, see §D.3 |
| 4 | `readVehiclePlate` — registration off the truck | gate | ✓ | 96 | **new** |
| 5 | `explainMismatch` — officer sentence from deltas | all | ✗ | 160 | **new** |
| 6 | `readDocument` — PO / invoice / EWB → `DocumentFact` | sender, gate | ✓ | 768 | exists, hardened (§F.4) |
| 7 | `readEwayBill` → manifest | gate | ✓ | 512 | exists, now persisted (§F.5) |
| 8 | `reconcileCargo` — bay photo vs manifest | gate | ✓ | 448 | exists, re-based on §D.1 |
| 9 | `draftNote` — statutory remarks | gate | ✗ | 192 | exists |
| — | ~~`verifyPackage`~~ | — | — | — | **deleted**, replaced by 1+2 |

### D.1 `observeProduct` — the prompt rule

The prompt must contain **no** expected value. Not the SKU, not the contents
string, not the quantity. The moment an expectation enters the prompt, the
answer is anchored and the check is theatre.

```
Look at this photograph of goods and report only what you can see.

Reply with one JSON object with exactly these keys:
  printed_code    - any code printed in human-readable characters, as printed, or ""
  brand           - the brand name printed or moulded on the goods, or ""
  model           - the model name or number, or ""
  visible_text    - an array of every other legible printed string
  item_kind       - what kind of goods these are, in two or three words
  visible_count   - how many individual items you can count, or 0 if you cannot
  packaging       - carton, shrink-wrap, crate, sack, loose, or ""
  colour          - the dominant colour of the goods, or ""
  seal_state      - intact, opened, resealed, or unknown
  damage          - a short phrase, or "" if none is visible
  legible         - false if the photograph is too blurred or dark to judge
  confidence      - your confidence from 0.0 to 1.0

Report only what is visible in this photograph. Do not guess a brand or a
number you cannot read. Never answer with a key name or with this description
as a value. Return the JSON object and nothing else.
```

Reuse `extractJsonObject` and the `SCHEMA_ECHOES` guard — both already earn
their keep and both apply unchanged.

### D.2 Self-consistency on the numbers that matter

Finance-critical and count-critical reads get a **second independent pass**:

* `countUnits` runs twice at `temperature = 0.1`. Agreement → use it.
  Disagreement → `INDETERMINATE`, never the average. A wrong count that looks
  confident is worse than no count.
* `readDocument` totals: after the first read, a second pass at `Fit.COVER`
  (a different framing of the same file) re-reads the totals and the document
  number only, with a 96-token budget. Disagreement forces the confirm screen
  into **manual entry** for that field rather than pre-filling it.

This costs one extra short turn and removes the single largest source of silent
error in the current pipeline.

### D.3 "How many are in this photo?" — counting, done honestly

Counting is the request with the widest gap between how easy it sounds and how
badly a 4-bit 4B VLM does it. Asked "how many boxes?", the model returns a
confident round number — often 10, often the number it just saw in the prompt.
Three mechanisms keep the answer trustworthy.

**1. Ask for the decomposition, not just the total.**

```
  count           - how many individual items you can count, or 0 if you cannot
  count_method    - one of: counted_individually, rows_and_columns,
                    estimated_from_pattern, cannot_count
  rows            - if stacked in a grid, how many rows, else 0
  columns         - if stacked in a grid, how many per row, else 0
  partially_hidden- true if any item is cut off by the frame edge or occluded
  count_confidence- 0.0 to 1.0
```

Kotlin then cross-checks: when `count_method = rows_and_columns`, `rows × columns`
must equal `count`, or the reading is discarded. Two independent derivations of
the same number from one model turn, checked deterministically — this catches
more errors than raising the token budget ever does.

**2. Tier by how countable the scene actually is.**

| Visible items | Behaviour |
| --- | --- |
| 0 | `cannot_count` → `INDETERMINATE`, prompt a closer re-shoot |
| 1–12 | exact count expected; used as a required match attribute |
| 13–40 | grid decomposition required, else the count is advisory only |
| > 40 or `partially_hidden` | report a **band** ("more than 40"), never a figure; require manual entry to make it count against the declaration |

A count that cannot be trusted is reported as unknown. It is never quietly
rounded into a number that a shortage claim might later rest on.

**3. Never let the count clear a shortage.**

The same one-directional rule as everything else: `observed > declared` raises
`COUNT_MISMATCH` (unlisted goods), `observed < declared` raises `COUNT_MISMATCH`
(shortage), `observed == declared` confirms nothing on its own — it simply
leaves the deterministic verdict standing. The model is never the reason a
carton passes.

**Where the count surfaces.** It is a first-class line on the proof screen, not
a footnote:

```
   Counted in photo   9
   Declared on label  10
   ────────────────────────
   SHORT BY 1 · COUNT_MISMATCH        [ Retake ]  [ Escalate ]
```

and it is spoken by `VoiceAnnouncer` — *"nine counted, ten declared, short by
one"* — so the officer hears it without lowering the phone.

## §E. `ProductMatcher` — deterministic adjudication

New file `app/src/main/java/com/veritransit/inspector/verify/ProductMatcher.kt`,
pure Kotlin, no Android imports, fully unit-tested.

```kotlin
enum class AttributeVerdict { AGREE, DISAGREE, UNKNOWN }

data class AttributeCheck(
    val attribute: String,       // "printed_code", "brand", "count", …
    val expected: String,
    val observed: String,
    val verdict: AttributeVerdict,
    val required: Boolean,       // a DISAGREE here forces MISMATCH
    val detail: String,
)

data class MatchReport(
    val outcome: MatchOutcome,
    val checks: List<AttributeCheck>,
    val score: Float,            // agreed-weight / decidable-weight
) { fun reasonCodes(): List<ReasonCode> }
```

### E.1 Per-attribute rules

| Attribute | Rule | Required | Notes |
| --- | --- | --- | --- |
| `printed_code` | confusable-folded edit distance ≤ 1 over the last 8 chars | ✓ | see §E.2 |
| `brand` | case/space-folded token containment, either direction | ✓ when expectation has one | "Kalyani Electronics" vs "KALYANI" agrees |
| `model` / `sku` | folded containment of the SKU's alphanumeric core | ✗ | SKUs are rarely printed on the box |
| `item_kind` | token overlap with `contents` ≥ 0.5 after stop-word strip | ✓ | "smartphone carton" vs "Smartphone cartons 5in" |
| `visible_count` | `observed == expected`, or `observed == 0` → UNKNOWN | ✓ only on open-carton shots | never on a sealed box |
| `colour` | equality when both present | ✗ | weak signal, informational |
| `seal_state` | `opened`/`resealed` → DISAGREE | ✓ | maps to `VISUAL_TAMPER` |
| `damage` | non-empty → DISAGREE | ✗ | maps to a new `VISIBLE_DAMAGE` |

### E.2 Confusable folding, and why the current check is wrong

```kotlin
// InspectorAi.kt:331 — today
return !read.contains(scanned.takeLast(8).uppercase())
```

Substring containment means a single OCR slip on any of those 8 characters
reports a code mismatch, and `SenderViewModel`'s alphabet is
`"0123456789ABCDEFGHJKMNPQRSTVWXYZ"` — Crockford-style, already excluding
`I L O U`. The remaining realistic confusions are `0↔D`, `1↔7`, `5↔S`, `8↔B`,
`2↔Z`. Fold those to a canonical character on both sides, then allow edit
distance ≤ 1. A genuinely swapped label differs in far more than one character;
a camera at an angle differs in one.

New reason codes in `core-models`:
`PRINTED_CODE_MISMATCH`, `BRAND_MISMATCH`, `COUNT_MISMATCH`, `VISIBLE_DAMAGE`,
`PHOTO_MISSING`, `PHOTO_INCONCLUSIVE`, `REFERENCE_DIVERGENCE`.
Each carries its `message` string, as the existing ones do — a verdict is never
just a colour.

### E.3 Reference divergence — the origin photo as ground truth

Comparing a photo to a *text* description is the hardest thing a small VLM does.
Comparing two **structured observations** produced by the same prompt at two
moments is easy, and it is deterministic.

So the sender captures a `ProductObservation` when the carton is packed (§H.1).
It is stored on the package row and travels in the bootstrap. At receipt the
receiver's observation is diffed against it:

* brand/model/item_kind/colour differ → `REFERENCE_DIVERGENCE`, high severity
  (the box was packed with one thing and arrived holding another)
* seal state moved `intact` → `opened`/`resealed` → `VISUAL_TAMPER`
* count dropped → `COUNT_MISMATCH` with the delta named

This is the single highest-accuracy check in the whole plan, and it needs no
additional model capability — only that the same prompt runs at both ends.

---

## §F. Making documents and evidence durable

### F.1 `EvidenceStore`

New `app/src/main/java/com/veritransit/inspector/data/EvidenceStore.kt`.

* Files move from `cacheDir` to
  `filesDir/evidence/<shipmentRef>/<yyyyMMdd>/<kind>-<ts>-<sha12>.jpg`.
* `capture()` returns `EvidenceRef(file, sha256, bytes, capturedAt)`; the sha is
  computed once, on the IO dispatcher, before the file is handed anywhere.
* New Room table `evidence` — `sha256` PK, `path`, `kind`
  (`DOC | PRODUCT | LABEL | CARGO | POD`), `shipmentRef`, `packageCode`,
  `capturedAt`, `uploadState`, `remoteUri`.
* Retention: a `PruneWorker` deletes only files whose `uploadState = UPLOADED`
  **and** are older than 30 days. Nothing unsynced is ever pruned — the same
  rule that already protects `scan_outbox`.
* `Evidence.kt` keeps `cacheDir` for the intermediate `evidence_raw_*.jpg` only,
  and deletes it as it already does.

### F.2 The document outbox

New Room table `document_outbox` mirroring `scan_outbox`'s contract:

```kotlin
@Entity(tableName = "document_outbox")
data class DocumentOutboxEntity(
    @PrimaryKey val clientDocId: String,   // UUID — the server's idempotency key
    val shipmentRef: String,
    val kind: String,
    val docNo: String?,                    // null when unreadable — see F.3
    val docDate: String?,
    val factJson: String,
    val confidence: Double?,
    val readBy: String,
    val confirmedBy: String?,              // the officer who pressed Confirm
    val evidenceSha256: String?,
    val syncState: String = "PENDING",
    val attempts: Int = 0,
    val lastError: String? = null,
)
```

`VeriTransitRepo.drainOutbox()` grows a second phase: scans, then documents,
then evidence uploads. `SyncWorker` is unchanged — it already calls
`drainOutbox()` and retries on throw.

New API: `POST /v1/documents:batch` taking `client_doc_id` per item and
returning per-item acks, exactly like `/v1/scans:batch`. Idempotent by
`client_doc_id`, which becomes a new `UNIQUE` column on `documents`.

### F.3 Fixing the destructive dedupe

Migration `V6__document_identity.sql`:

```sql
ALTER TABLE documents ADD COLUMN client_doc_id uuid UNIQUE;
ALTER TABLE documents ALTER COLUMN doc_no DROP NOT NULL;

DROP INDEX documents_dedupe_key;
-- Two documents with no readable number are two documents, not one.
CREATE UNIQUE INDEX documents_dedupe_key
    ON documents (kind, upper(doc_no))
    WHERE doc_no IS NOT NULL AND doc_no <> '';
```

`DocumentService.attach` changes in three ways:

1. `ON CONFLICT (client_doc_id) DO UPDATE` replaces the `doc_no`-keyed upsert,
   so a retry updates *its own* row and can never touch another document's.
2. A blank `docNo` is stored as `NULL`, never as a synthesised placeholder. The
   admin UI shows it as *"number unread — needs manual entry"* and the four-way
   match reports `DOC_MISSING` rather than matching against a fake number.
3. A *different* document arriving with a doc number already attached to another
   shipment still raises `DuplicateDocument` — that path is correct and stays.

### F.4 Confirmation actually recorded

* `ApiClient.attachDocument` gains a `confirmedBy: String?` parameter and sends
  `X-Confirmed-By`.
* `DocumentFactEntity` gains `sourceSha256`, `confirmedBy`, `capturedAt`.
* The confirm button is disabled until the officer has either accepted or
  re-typed every finance-critical field (`docNo`, `taxableValue`, and each line
  `qty`/`rate`) — with any field the §D.2 second pass disagreed on forced to
  manual entry.

### F.5 The gate's E-Way Bill stops evaporating

`ScanScreen`'s bill read currently produces a `Manifest` and nothing else. It
gains the same persistence path as the sender's:

`BillReading` → `DocumentFact(kind = EWB)` → `document_fact` row +
`document_outbox` row + `EvidenceRef` for the photograph, linked by sha256.

This is what makes the gate inspection defensible after the fact: today the
record says an officer read a bill, with no bill.

### F.6 Room migration

`VeriTransitDatabase` goes `version = 1` → `2` with a **real**
`Migration(1, 2)` — `fallbackToDestructiveMigration` stays forbidden, for the
reason the file already documents. New tables: `evidence`, `document_outbox`,
`product_check`. Altered: `package_record` (+`hsn`, `brand`, `model`, `uom`,
`unitValue`, `declaredMarks`, `referenceObsJson`), `document_fact`
(+`sourceSha256`, `confirmedBy`, `capturedAt`).

---

## §G. Dynamic documents — bills are not a fixed schema

### G.1 The problem with the current reader

`readDocument` asks every document for the same eleven keys
([InspectorAi.kt:458](../app/src/main/java/com/veritransit/inspector/ai/InspectorAi.kt)):
`doc_no, date, seller_name, seller_gstin, buyer_name, buyer_gstin, lines,
taxable_value, legible, confidence` — and every line for the same six:
`description, sku, hsn, qty, rate, amount`.

Real paperwork does not cooperate. A GST tax invoice carries an IRN, a place of
supply and a reverse-charge flag. A delivery challan carries no value at all. A
lorry receipt carries an LR number, a vehicle number and freight terms and no
GSTIN. A *kachha* bill is handwritten with two columns. An e-invoice has a QR
that is worth more than the whole OCR pass.

The fixed schema means two failures, both silent:

* **Fields that exist are dropped.** An IRN or a vehicle number printed on the
  page is thrown away because no key asked for it.
* **Fields that do not exist are invented.** A 4-bit model asked for
  `taxable_value` on a delivery challan that has no value column will supply
  one. That number then flows into `DocumentService.orderValue()` and sets
  finance terms.

The second is the dangerous one.

### G.2 Classify, then extract against a profile

Two turns instead of one.

**Pass 1 — `classifyDocument` (96 tokens, cheap).**

```
  doc_type          - tax_invoice, proforma, purchase_order, delivery_challan,
                      eway_bill, lorry_receipt, packing_list, receipt, unknown
  has_line_table    - true if goods are listed in a table
  has_amounts       - true if money columns are printed
  has_tax_section   - true if CGST/SGST/IGST appear
  printed           - true if machine-printed, false if handwritten
  language          - the script the document is printed in
  has_qr            - true if a QR or barcode is printed on the page
```

**Pass 2 — an extraction prompt *generated* from a `DocumentProfile`.**

```kotlin
data class DocField(
    val key: String,
    val prompt: String,          // the line that goes into the schema block
    val type: FieldType,         // TEXT | NUMBER | DATE | GSTIN | CODE
    val required: Boolean,
    val financeCritical: Boolean, // gates manual confirmation (§F.4)
)

data class DocumentProfile(
    val id: String,
    val matches: (Classification) -> Boolean,
    val fields: List<DocField>,
    val wantsLineTable: Boolean,
)
```

Profiles live in `core-models/…/DocProfiles.kt` as data, so adding support for a
new document is a table entry, not a new prompt and a new parser. The prompt
text is assembled from the profile's fields at call time.

Crucially: **a profile never asks for a field its classification says is not
there.** A delivery challan's profile has no `taxable_value`, so the model is
never given the opportunity to invent one.

### G.3 Open capture — the part that makes it dynamic

Every profile, always, additionally asks:

```
  extra_fields - an array of objects with the keys label and value, one per
                 other printed label-and-value pair on the document that you
                 have not already reported above. Copy both exactly as printed.
                 Use [] if there are none.
```

This is the dynamic half. An IRN, an acknowledgement number, a transporter ID,
a place of supply, a vehicle number, a driver's name, a PO reference, payment
terms — whatever this particular bill happens to carry lands in `extra_fields`
rather than being discarded because no one anticipated it.

Kotlin then **promotes** extras whose label fuzzy-matches a canonical key
(`irn`, `ack_no`, `eway_bill_no`, `vehicle_no`, `lr_no`, `po_no`,
`place_of_supply`, `transporter`, `payment_terms`) into typed slots, using the
same folding as §E.2. Everything unpromoted is still stored verbatim.

`DocumentFact` gains:

```kotlin
val profileId: String,
val extras: Map<String, String> = emptyMap(),   // promoted, canonical keys
val unmapped: List<LabelledValue> = emptyList(),// kept verbatim, nothing lost
```

Postgres needs no migration for this — `documents.fact` is already `jsonb` and
the `fact -> 'lines'` CHECK still holds.

### G.4 Tables with columns nobody predicted

Instead of demanding six fixed line keys, ask for the table **as printed**:

```
  table_header - an array of the column headings, exactly as printed,
                 left to right
  table_rows   - an array of arrays; one inner array per goods row, with one
                 cell per heading, in the same order. Copy cells exactly.
```

Kotlin maps headings to canonical fields with a synonym table:

| Canonical | Headings seen in the wild |
| --- | --- |
| `qty` | Qty, Quantity, Nos, Pcs, No. of Pkgs, Units |
| `rate` | Rate, Price, Unit Price, Rate/Unit, MRP |
| `amount` | Amount, Value, Total, Taxable Value, Net |
| `description` | Description, Particulars, Goods, Item, Description of Goods |
| `hsn` | HSN, HSN/SAC, SAC, Tariff |

An unmapped column is kept on the line as an extra rather than dropped. A
missing column simply produces a line without that field — which the matcher
already handles, because §5.1's four-way match reports `DOC_MISSING` rather than
matching against nothing.

This one change is why the reader stops being brittle: the model transcribes a
grid, which it is good at, and Kotlin interprets the grid, which it is good at.

### G.5 Arithmetic as a free accuracy check

No model call, no extra latency, and it catches the most common OCR failure —
a transposed or dropped digit:

* per line, `qty × rate ≈ amount` within 1%
* `Σ amount ≈ taxable_value` within 1%
* tax lines, when the profile has them, `taxable_value × rate ≈ tax_amount`

A line that fails arithmetic is marked low-trust and **forced to manual entry**
before it can drive money. A document whose totals fail is flagged
`needs_review` and cannot set finance terms. Between this and §D.2's second
pass, an OCR slip on a rupee figure has to survive two independent checks to
reach `finance_terms`.

### G.6 Provenance on every field

Each extracted field carries `{value, source, confidence}` where `source` is
`vlm | manual | derived | promoted`. The confirm screen shows the source, the
audit row records it, and only `manual` or arithmetic-`derived` fields may set
`order_value`. This is what §12's "a document that moves money names the human
who confirmed it" actually looks like in the data.

### G.7 Unknown documents still get saved

`doc_type = unknown` falls to a generic profile: header key/values, the table if
there is one, totals if they are printed, everything else into `extras`. The
document is stored, hashed, outboxed and flagged `needs_review`. Nothing is ever
refused — an unreadable bill still needs to exist in the record.

---

## §H. The three flows

### H.1 Sender — the packer at origin

> Goal: the database's expectation is created *with a photograph behind it*, so
> everything downstream has something real to compare against.

1. **Shipment** — pick or create. Unchanged.
2. **Paperwork first.** Photograph PO / invoice / EWB → `readDocument` →
   second-pass number check (§D.2) → confirm screen → `document_fact` +
   `document_outbox` + evidence, all local-first. *Changed: this now genuinely
   persists offline.*
3. **Line items from the paperwork.** The confirmed `DocumentFact` lines
   pre-fill contents / SKU / HSN / qty / unit value for label issue, instead of
   the packer retyping them. Fewer keystrokes, and the expectation is now
   traceable to a document.
4. **Issue labels.** 1 master + N inners, server-signed or DRAFT offline.
   Unchanged.
5. **Pack proof (new).** Before the carton is sealed, photograph the open
   carton → `observeProduct` + `countUnits` → matched against the declared line.
   * count < declared → **block the close-master**, with the shortage named
   * brand/kind disagrees → warn, require packer acknowledgement
   * the observation is stored as the package's **reference observation**
6. **Seal and close master** over the inner codes actually scanned in.
   Unchanged, except step 5 must have passed or been overridden.
7. **Master face shot** — one `observeLabelFace` on the sealed carton, stored as
   the reference for the receiver's seal comparison.

Net effect: short-packing is caught at origin, where it is cheap, instead of at
receipt, where it is a dispute.

### H.2 Receiver — the unboxing

1. **Shipment** — pick. Unchanged.
2. **Scan MASTER QR** → deterministic verdict (unchanged, still < 1.5 s).
3. **Photo gate on the master (new, mandatory).** Label face + carton →
   `observeLabelFace` → seal state vs the sender's reference → `MatchReport`.
   A master whose seal moved `intact → resealed` is announced by voice before
   the officer opens it, and the whole inner session is marked
   `opened_before_receipt` for the claim file.
4. **Guided inner scan** — the existing "7 of 10" checklist, unchanged.
5. **Per-inner photo gate**, depth set by the risk band's `FrictionRule`:
   * `open_and_verify` → every inner
   * `master_scan_only` → the deterministic sample (§C)
   Each inner: `observeProduct` → expectation lookup → `ProductMatcher` →
   demote-only fold-in → `product_check` row + evidence.
6. **Master close** — `closeMaster` as today, plus: any inner with
   `MISMATCH` blocks completion until a supervisor resolves it.
7. **PoD** — signature + receiver name + the sha256 of every photo taken in
   steps 3 and 5 as `evidenceHashes`. Currently `submitPod` sends
   `mapOf("inners" to …)`; it starts sending real hashes, which is what §5.4 of
   the master plan asks for and what makes the certificate worth anything.

### H.3 Gate officer — roadside / check post

Kept, because it is a different job: no shipment context, no bootstrap of that
consignment, often no network.

1. **Scan** — if the vehicle carries a VeriTransit QR, run the full §B.1 path
   against the bootstrap cache. If not, fall through to 2.
2. **Photograph the E-Way Bill** → `readEwayBill` → manifest → **persisted as an
   EWB document + evidence** (§F.5). *Changed.*
3. **Photograph the number plate (new)** → `readVehiclePlate` → folded
   comparison against the bill's vehicle field. A mismatch here is the classic
   bill-swap and today nothing checks it.
4. **Photograph the cargo bay** → `reconcileCargo` against the manifest
   (existing), now writing its frame to the evidence store rather than the cache.
5. **Verdict** → `draftNote` → officer confirms → record committed, with every
   photograph hashed and attached.

### H.4 Who can waive what

| Waiver | Who | Recorded as |
| --- | --- | --- |
| Skip product photo | officer, only if band allows | `PHOTO_SKIPPED` audit row + officer name |
| Override `MISMATCH` | supervisor | `VISUAL_OVERRIDE` audit row + typed reason |
| Override pack-proof shortage | supervisor | `PACK_PROOF_OVERRIDE` + reason |
| Dispatch incomplete | supervisor | `DISPATCH_OVERRIDE` (exists today) |

---

## §I. Work packages

Each is independently shippable and independently testable. WP1 is a
prerequisite for everything else; WP2–WP5 can run in parallel after it.

### WP1 — Evidence & document durability *(fixes §A.4; no UI change)*

| Files | Change |
| --- | --- |
| `data/EvidenceStore.kt` | **new** — filesDir paths, sha256, `EvidenceRef` |
| `ai/Evidence.kt` | `capture()` returns `EvidenceRef`; raw stays in cache |
| `data/local/Entities.kt`, `Dao.kt` | `evidence`, `document_outbox`, `product_check` |
| `data/local/VeriTransitDatabase.kt` | version 2 + real `Migration(1,2)` |
| `data/VeriTransitRepo.kt` | `drainOutbox()` phases; `saveDocument()` writes Room + outbox together |
| `net/ApiClient.kt` | `pushDocuments()`, `X-Confirmed-By`, `uploadEvidence()` |
| `ui/sender/SenderScreens.kt` | confirm handler goes through `saveDocument()` |
| `db/migrations/V6__document_identity.sql` | **new** — `client_doc_id`, nullable `doc_no`, partial index |
| `server/…/DocumentService.kt` | conflict on `client_doc_id`; null doc numbers |
| `server/…/Routes.kt` | `POST /v1/documents:batch`, `POST /v1/evidence` |

**Acceptance:** capture a bill in airplane mode, force-stop the app, clear the
app's cache, re-open, reconnect → the bill photo is still on disk, its Room row
still present, and the server has it after one sync. Capture two unreadable
invoices → two rows on the server, neither overwriting the other.

### WP2 — Blind observation + deterministic matching *(fixes §A.3)*

| Files | Change |
| --- | --- |
| `core-models/…/Inspection.kt` | `ProductObservation`, `MatchOutcome`, `AttributeCheck`, new `ReasonCode`s |
| `ai/InspectorAi.kt` | `observeProduct`, `observeLabelFace`, `countUnits`, `explainMismatch`; delete `verifyPackage` |
| `verify/ProductMatcher.kt` | **new** — folding, per-attribute rules, scoring |
| `verify/CodeFolding.kt` | **new** — confusable folding + bounded edit distance |
| `core-models/src/test/…` | `ProductMatcherTest`, `CodeFoldingTest`, `CountTierTest` |

**Acceptance:** unit tests only — no device needed. A substituted brand, a
one-character OCR slip, a short count and an unreadable photo each produce the
documented outcome. This is the package that makes the AI *correct* rather than
merely present.

### WP3 — The photo gate in the receiver flow *(fixes §A.2)*

| Files | Change |
| --- | --- |
| `ui/verify/ProductProofScreen.kt` | **new** — two-shot capture, live match report |
| `ui/AppRoot.kt` | `Page.AiCheck` → `Page.ProductProof`, entered from the scan result |
| `ui/warehouse/WarehouseViewModel.kt` | `requireProof()`, sampling, `MatchReport` in state |
| `ui/receiver/ReceiverScreens.kt` | checklist rows show per-inner match state; AI button removed |
| `ai/VoiceAnnouncer.kt` | announce `MISMATCH` and seal-state changes |

**Acceptance:** an inner cannot be ticked complete without a `product_check` row
or a recorded waiver; a `MISMATCH` demotes `VERIFIED` to `SUSPECT` and speaks
the reason.

### WP4 — Sender pack-proof and the richer expectation *(fixes §A.5)*

| Files | Change |
| --- | --- |
| `data/local/Entities.kt` | `PackageEntity` + hsn/brand/model/uom/unitValue/declaredMarks/referenceObsJson |
| `core-models/…/Domain.kt`, `Api.kt` | same fields on `PackageRecord`; observations in bootstrap |
| `db/migrations/V7__package_expectation.sql` | columns + `package_observations` table |
| `server/…/LabelService.kt`, `ShipmentService.kt` | accept and serve the fields |
| `ui/sender/SenderScreens.kt`, `SenderViewModel.kt` | pack-proof step, document-derived line pre-fill |
| `server/…/Routes.kt` | `POST /v1/packages/{code}/observations` |

**Acceptance:** pack 9 units into a carton declaring 10 → close-master is
blocked and names the shortage. At receipt, a carton whose reference says
"Kalyani smartphone, sealed" and whose photo shows a resealed box of something
else raises `REFERENCE_DIVERGENCE` before the inners are even scanned.

### WP5 — Gate hardening

| Files | Change |
| --- | --- |
| `ui/screens/ScanScreen.kt` | **real `BarcodeAnalyzer`** replacing the scripted viewfinder (§J.4); bill persisted as EWB document + evidence |
| `ui/screens/PlateCheckStep.kt` | **new** — plate photo, folded comparison |
| `ai/InspectorAi.kt` | `readVehiclePlate` |
| `ui/screens/CargoScanScreen.kt` | cargo frame → evidence store |
| `ui/screens/ResultScreen.kt` | evidence thumbnails + hashes on the record |

**Acceptance:** a completed gate inspection has, on disk and on the server, the
bill photo, the plate photo, the bay photo, all hashed, plus the structured
readings each produced.

### WP6 — Dynamic document profiles *(§G)*

| Files | Change |
| --- | --- |
| `core-models/…/DocProfiles.kt` | **new** — `DocField`, `DocumentProfile`, the profile table, header synonyms |
| `core-models/…/Documents.kt` | `DocumentFact` + `profileId`, `extras`, `unmapped`; per-field provenance |
| `ai/InspectorAi.kt` | `classifyDocument`; `readDocument` builds its prompt from a profile; table-as-printed |
| `verify/DocumentNormaliser.kt` | **new** — header→canonical mapping, extras promotion, arithmetic validation |
| `ui/sender/SenderScreens.kt` | confirm screen renders profile fields + extras, forces manual entry on failed arithmetic |
| `server/…/DocumentService.kt` | `orderValue()` ignores documents flagged `needs_review` |
| `core-models/src/test/…` | `DocumentNormaliserTest` — a challan with no amounts, an LR, a 4-column handwritten bill, an unknown type |

**Acceptance:** a delivery challan with no value column produces no
`taxable_value` and does not set finance terms; an invoice printing an IRN and a
vehicle number keeps both; a bill whose line arithmetic does not add up cannot
be confirmed without manual entry.

### Sequencing

```
WP1 ──┬── WP2 ──┬── WP3
      │         ├── WP5
      │         └── WP6
      └── WP4 ───────┘

WP0 (QR scanner, §J) — done, independent of all of the above
```

WP1 first and alone: it is the one package that fixes data loss already
happening today.

---

## §J. The QR scanner — diagnosis and fix *(implemented)*

The scanner had seven faults. All are fixed in the working tree; the module
compiles (`:app:compileDebugKotlin`), and the behavioural items still need a
handset to confirm (§J.3).

### J.1 What was wrong

| # | Fault | Symptom on the dock |
| --- | --- | --- |
| J‑1 | `ImageAnalysis` ran at CameraX's **640×480 default** | A signed token is ~140 bytes → a version‑7+ QR at 45+ modules. At 640×480 it decodes only when the label fills the frame and is perfectly sharp. *"Sometimes it just doesn't scan."* |
| J‑2 | `handledThisSession` was a **permanent** `Set` | Any code scanned once was ignored for the rest of the session. Re-scanning a carton did nothing, for ever. `forgetSession()` — the escape hatch — had **zero call sites**. |
| J‑3 | Emitted on the **first** symbology to decode | QR and Code128 almost never resolve in the same 640×480 frame, so §4.3 **layer 3 (`QR_BARCODE_MISMATCH`) never actually ran** — the check that catches a label peeled onto another carton was dead. |
| J‑4 | `barcodes.firstOrNull { … }` | With four cartons in frame, the verdict could belong to a box the officer never aimed at. |
| J‑5 | `BarcodeAnalyzer.close()` never called; use cases never unbound | ML Kit leak; camera stayed hot after navigating away; on single-session devices the next `EvidenceCamera` bind — *the AI photo check* — failed. |
| J‑6 | No tap-to-focus, no torch | Continuous AF hunts on a flat carton. A QR soft by two pixels per module will not decode; a laminated label glares under side lighting. |
| J‑7 | No feedback when nothing decodes | A live camera decoding nothing is indistinguishable from a broken app. |

### J.2 What changed

**[BarcodeAnalyzer.kt](../app/src/main/java/com/veritransit/inspector/scan/BarcodeAnalyzer.kt)**

* *Centre-preference* (J‑4): among candidates, the one whose bounding box is
  nearest the frame centre wins — the code under the officer's reticle. Boxes
  are measured in the **rotated** frame, since that is the space ML Kit returns
  them in.
* *Pairing window* (J‑3): a lone symbology is held for `PAIR_WINDOW_MS = 350`
  so its partner can arrive from a later frame, then emitted paired. A
  different label entering the frame abandons the half-read one rather than
  merging two cartons. 350 ms is several frames at 30 fps and far inside the
  §7.1 1.5 s budget — layer 3 now genuinely runs.
* `close()` is now reachable and called.

**[WarehouseViewModel.kt](../app/src/main/java/com/veritransit/inspector/ui/warehouse/WarehouseViewModel.kt)**

* *Debounce, not duplicate detection* (J‑2): the permanent `Set` becomes
  `lastSeenAt: Map<String, Long>` with `RESCAN_DEBOUNCE_MS = 2500`. Holding
  still over one carton still records one scan; a deliberate re-scan answers.
  `clearVerdict()` releases the dismissed code immediately.
  **The conceptual fix:** debounce and duplicate detection were conflated.
  Genuine duplicates are layer 4's job and are already found from the outbox
  and re-decided server-side across all devices.

**[WarehouseScreens.kt](../app/src/main/java/com/veritransit/inspector/ui/warehouse/WarehouseScreens.kt)**

* *Resolution* (J‑1): `ResolutionSelector` targeting **1280×720** with
  `FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER`. This is the single highest-impact
  line in the fix.
* *Teardown* (J‑5): `DisposableEffect` now does `clearAnalyzer()`,
  `analyzer.close()`, `provider.unbindAll()`, then shuts the executor down.
* *Tap-to-focus and torch* (J‑6): touch listener → `startFocusAndMetering` with
  a 3 s auto-cancel; a **Lamp** button beside **Done**.
* *Stale-closure guard*: `rememberUpdatedState` — `AndroidView`'s `factory` runs
  once, so the analyser previously held the first lambda for ever and would
  report against a stale `ScanKind`.
* *Silence is explained* (J‑7): after `NO_DECODE_HINT_MS = 4000` with no decode,
  an overlay says *fill the frame, hold steady, tap to focus, try the lamp*.

### J.3 Still to verify on a handset

Compilation is confirmed; these need real hardware and real labels:

1. Decode rate at 300/500/800 mm on a printed 100×150 mm label, before vs after.
2. Re-scanning the same carton answers every time (J‑2).
3. `QR_BARCODE_MISMATCH` fires on a label whose Code128 was swapped (J‑3) —
   previously unobservable.
4. Scan → navigate to the AI photo check → the camera binds first time (J‑5).
5. Time from frame to verdict stays under the §7.1 1.5 s budget with the
   350 ms pairing window included.

### J.4 Not yet done — the gate tab's scanner is still simulated

`ScanScreen` (the **Gate** tab) has **no `BarcodeAnalyzer` at all**. Its
viewfinder is a scripted `delay(3600); flow.detected = true`
([ScanScreen.kt](../app/src/main/java/com/veritransit/inspector/ui/screens/ScanScreen.kt)),
with the live path reading the *bill* via the VLM rather than decoding a QR. If
"the QR scanner doesn't work" was said about the Gate tab, this is why: there is
nothing there to work.

Wiring the real analyser into the gate flow is **WP5**, since it also wants the
plate check and evidence persistence landing at the same time. Until then the
Gate tab should say *"demo viewfinder"* on screen rather than implying it is
scanning — a one-line honesty fix worth doing immediately.

---

## §K. Risks

| Risk | Mitigation |
| --- | --- |
| Photo gate slows a dock to a crawl | Gate is per-*master* by default; inners sampled by band. Measure: added seconds per carton, target < 8 s including capture. |
| Officers photograph the floor to get past the gate | `legible = false` and a `MATCH` score of 0 both produce `INDETERMINATE`, which does not satisfy the gate — it must be retaken or waived on the record. |
| 4-bit model hallucinates a brand | Blind extraction + required-attribute rules mean a hallucinated brand produces `DISAGREE` → `SUSPECT`, i.e. a supervisor visit, never a wrongful release. Direction is preserved by `applyAiFlags`. |
| Evidence storage fills the device | Prune at 30 days, uploaded-only; surface used bytes in Settings; JPEG at 512 px is ~60 KB, so ~1 000 photos ≈ 60 MB. |
| `REFERENCE_DIVERGENCE` false-positives on lighting | Colour is a non-required attribute; divergence requires brand / model / item_kind, which are OCR- and semantics-driven, not exposure-driven. |
| Migration 1→2 on devices holding unsynced scans | Real `Migration`, never destructive; migration test with a populated v1 DB is part of WP1's acceptance. |
| 1280×720 analysis raises per-frame cost | `STRATEGY_KEEP_ONLY_LATEST` already drops backlog; measure frame-to-verdict on the oldest supported handset and fall back to 960×540 if the §7.1 budget is missed. |
| Counting is asked of a scene it cannot count | Tiering (§D.3) reports a band or `INDETERMINATE` rather than a figure; only 1–12 items produce a required match attribute. |
| A new bill layout has no profile | The generic profile always applies, `extra_fields` keeps everything printed, and the document is stored flagged `needs_review` — never refused, never silently mis-parsed. |
| Profile picks the wrong document type | Classification is advisory: a profile can only *remove* fields from the ask. The generic fallback plus `extras` means a misclassification loses structure, not data. |
