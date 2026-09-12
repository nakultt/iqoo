package com.veritransit.inspector.ai

import com.veritransit.core.DocumentFact
import com.veritransit.core.DocumentKind
import com.veritransit.core.DocumentLine
import com.veritransit.core.DocumentTotals
import com.veritransit.core.PartyRef
import com.veritransit.inspector.data.CargoItem
import com.veritransit.inspector.data.InspectionRecord
import com.veritransit.inspector.data.ItemStatus
import com.veritransit.inspector.data.Manifest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The three inspection tasks the on-device model performs, each a single
 * stateless turn against [NpuEngine].
 *
 * Every task asks for JSON and parses it leniently: a 4-bit quantised model
 * will occasionally fence its output or trail a sentence after the closing
 * brace, and a malformed reply must degrade to "AI unavailable" rather than
 * crash an officer's inspection mid-shift.
 *
 * Token budgets are sized against [NpuEngine.CONTEXT_TOKENS], not the 4096 the
 * model supports off-device: the AI Hub bundle is compiled to a 2048 context,
 * and an evidence photo already spends ~256 of it.
 */
object InspectorAi {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    // ------------------------------------------------------- E-Way Bill OCR

    @Serializable
    data class BillItem(
        val name: String = "",
        val packaging: String = "",
        val quantity: Int = 0,
    )

    @Serializable
    data class BillReading(
        @SerialName("ewb_number") val ewb: String = "",
        @SerialName("vehicle_number") val vehicle: String = "",
        @SerialName("vehicle_model") val vehicleModel: String = "",
        val consignment: String = "",
        val origin: String = "",
        val destination: String = "",
        @SerialName("distance_km") val distanceKm: Int = 0,
        val items: List<BillItem> = emptyList(),
        val legible: Boolean = true,
    ) {
        val route: String
            get() = when {
                origin.isNotBlank() && destination.isNotBlank() -> "$origin → $destination"
                destination.isNotBlank() -> destination
                else -> ""
            }

        /** Enough was read to be worth showing the officer. */
        val usable: Boolean get() = ewb.isNotBlank() || vehicle.isNotBlank() || items.isNotEmpty()

        fun toManifest(): Manifest = Manifest(
            ewb = ewb.ifBlank { "—" },
            vehicle = vehicle.ifBlank { "—" },
            vehicleModel = vehicleModel,
            consignment = consignment.ifBlank { "Unclassified consignment" },
            route = route.ifBlank { "—" },
            distanceKm = distanceKm,
            items = items
                .filterNot { isSchemaEcho(it.name) }
                .map {
                    CargoItem(
                        name = it.name,
                        detail = it.packaging.ifBlank { "Declared line item" },
                        expected = it.quantity.coerceAtLeast(0),
                        found = it.quantity.coerceAtLeast(0),
                    )
                },
            ref = "#" + ewb.filter { it.isDigit() }.takeLast(4).ifBlank { "NPU" },
        )
    }

    private const val BILL_SYSTEM =
        "You are a document-reading assistant for a transit compliance officer. " +
            "You read Indian E-Way Bills and goods manifests from photographs and " +
            "return structured data. Transcribe only what is legibly printed in the " +
            "image. Never invent a number you cannot read — leave the field empty " +
            "instead. Reply with JSON only."

    /**
     * Deliberately describes the schema instead of showing a filled-in example.
     * An example row gets copied: a 4-bit model handed
     * `{"name": "line item", "quantity": 0}` will cheerfully return exactly
     * that as its answer.
     */
    private val BILL_PROMPT = """
        Read this E-Way Bill / consignment note.

        Reply with one JSON object with exactly these keys:
          ewb_number    - the E-Way Bill number, as printed
          vehicle_number- the vehicle registration number
          vehicle_model - the vehicle make/model
          consignment   - what the goods are, in a few words
          origin        - the place the goods are dispatched from
          destination   - the place the goods are going to
          distance_km   - the distance in kilometres, as a number
          items         - an array with one object per printed goods row,
                          each having the keys name, packaging, quantity
          legible       - true, or false if the document cannot be read

        Copy the values off the document. Use "" for any text field that is not
        printed, 0 for a missing number, and [] if no goods rows are printed.
        Never answer with a key name or with this description as a value.
        Return the JSON object and nothing else.
    """.trimIndent()

    /**
     * Reads [imagePath] into a [BillReading].
     *
     * Header fields (bill number, vehicle, make/model) come back reliably. The
     * line-item table often does not: the bundle's encoder is fixed at 512x512
     * / 256 tokens, which puts small tabular print near the limit of what it
     * can resolve. A reading with no items is still useful — the officer
     * confirms the goods on the manifest step — so an empty list is a normal
     * result here, not an error.
     */
    suspend fun readEwayBill(imagePath: String): Result<BillReading> =
        NpuEngine.run(
            systemPrompt = BILL_SYSTEM,
            userPrompt = BILL_PROMPT,
            imagePaths = listOf(imagePath),
            maxTokens = 512,
            temperature = 0.1f,
            label = "Reading E-Way Bill",
        ).mapCatching { raw ->
            json.decodeFromString<BillReading>(extractJsonObject(raw))
        }

    // --------------------------------------------------- cargo reconciliation

    @Serializable
    data class CountedItem(
        val name: String = "",
        val found: Int = 0,
    )

    @Serializable
    data class UnlistedItem(
        val name: String = "",
        val count: Int = 1,
    )

    @Serializable
    data class Reconciliation(
        val items: List<CountedItem> = emptyList(),
        val unlisted: List<UnlistedItem> = emptyList(),
        val observation: String = "",
        val confidence: Float = 0f,
    )

    private const val CARGO_SYSTEM =
        "You are a cargo verification assistant for a transit compliance officer. " +
            "You look at a photograph of a loaded vehicle bay and count the goods " +
            "you can actually see, matching them against a declared manifest. " +
            "Count only what is visible in the photograph. If a declared item is " +
            "not visible, report 0 for it. Reply with JSON only."

    /** What the model made of one cargo-bay photograph. */
    data class CargoScan(
        val items: List<CargoItem>,
        val observation: String,
        val confidence: Float,
    )

    /**
     * Reconciles [imagePath] against [manifest]. Declared quantities are given
     * to the model as context but the returned counts are re-keyed against the
     * manifest here — a model that renames a line item cannot silently drop it.
     */
    suspend fun reconcileCargo(imagePath: String, manifest: Manifest): Result<CargoScan> {
        val declared = manifest.items.joinToString("\n") { "- ${it.name} (declared qty ${it.expected})" }
        val prompt = """
            The manifest for this vehicle declares:
            $declared

            Look at the photograph of the cargo bay and reply with one JSON
            object with exactly these keys:
              items       - an array with one object per declared line above,
                            each having the keys name and found. Use the
                            declared name verbatim and put the number of that
                            item you can see in found.
              unlisted    - an array of objects with the keys name and count,
                            one per kind of goods you can see that is not
                            declared above. Use [] if there are none.
              observation - one sentence describing what is in the photograph
              confidence  - how sure you are, between 0 and 1

            Count only what is visible. Never answer with a key name or with
            this description as a value. Return the JSON object and nothing else.
        """.trimIndent()

        return NpuEngine.run(
            systemPrompt = CARGO_SYSTEM,
            userPrompt = prompt,
            imagePaths = listOf(imagePath),
            maxTokens = 448,
            temperature = 0.1f,
            label = "Reconciling cargo",
        ).mapCatching { raw ->
            val parsed = json.decodeFromString<Reconciliation>(extractJsonObject(raw))
            val counted = parsed.items.associateBy { normalise(it.name) }
            val declaredLines = manifest.items.map { item ->
                val found = counted[normalise(item.name)]?.found?.coerceAtLeast(0) ?: 0
                item.copy(found = found.coerceAtMost(item.expected))
            }
            val extras = parsed.unlisted
                .filterNot { isSchemaEcho(it.name) }
                .map { CargoItem(it.name, "Not on manifest", 0, it.count.coerceAtLeast(1)) }
            CargoScan(
                items = declaredLines + extras,
                observation = parsed.observation.trim(),
                confidence = parsed.confidence.coerceIn(0f, 1f),
            )
        }
    }

    // ------------------------------------------------------ verdict drafting

    private const val NOTE_SYSTEM =
        "You draft the remarks field of a statutory cargo inspection record for " +
            "an Indian transit compliance officer. Write one or two plain factual " +
            "sentences in the third person, in the register of an official field " +
            "note. State only what the reconciliation data shows. No preamble, no " +
            "bullet points, no markdown."

    /** Free-text officer note for the result screen — the NLP-only path. */
    suspend fun draftNote(
        record: InspectionRecord,
        onToken: (String) -> Unit = {},
    ): Result<String> {
        val lines = record.items.joinToString("\n") { item ->
            when (item.status) {
                ItemStatus.MATCHED -> "- ${item.name}: declared ${item.expected}, found ${item.found} (matches)"
                ItemStatus.SHORTAGE -> "- ${item.name}: declared ${item.expected}, found ${item.found} (short by ${item.expected - item.found})"
                ItemStatus.UNLISTED -> "- ${item.name}: not declared, ${item.found} observed"
            }
        }
        val prompt = """
            Inspection ${record.id} at a highway check post.
            E-Way Bill ${record.ewb}, vehicle ${record.vehicle}, route ${record.route}.
            Consignment: ${record.cargo}.

            Reconciliation result:
            $lines

            Write the officer's remarks for this record.
        """.trimIndent()

        return NpuEngine.run(
            systemPrompt = NOTE_SYSTEM,
            userPrompt = prompt,
            maxTokens = 192,
            temperature = 0.35f,
            label = "Drafting remarks",
            onToken = onToken,
        ).map { it.trim().removeSurrounding("\"") }
    }

    // ------------------------------------------------------------- parsing

    private fun normalise(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Words that only appear in the schema description, never on a real
     * consignment. A quantised model sometimes answers with the instructions
     * instead of the contents, and an echoed placeholder must not reach an
     * officer's record as a cargo line.
     */
    private val SCHEMA_ECHOES = listOf(
        "line item",
        "not on the manifest",
        "item visible",
        "exact name from the manifest",
        "pack/crate",
        "declared line above",
        "one object per",
    )


    // ------------------------------------------------------- package verification

    /**
     * §4.3 layer 5 — what the model made of one package photograph.
     *
     * Every field is a *flag*, never a pass: [VerificationEngine.applyAiFlags]
     * can only demote a verdict on the strength of these. A 4-bit model misread
     * must be able to cost a supervisor visit and never a wrongful release
     * (§12), so there is deliberately no "looks fine" field that could clear a
     * package the deterministic layers doubted.
     */
    @Serializable
    data class PackageCheck(
        @SerialName("printed_code") val printedCode: String = "",
        @SerialName("contents_match") val contentsMatch: Boolean = true,
        @SerialName("seal_intact") val sealIntact: Boolean = true,
        @SerialName("label_damaged") val labelDamaged: Boolean = false,
        @SerialName("tape_resealed") val tapeResealed: Boolean = false,
        @SerialName("visible_count") val visibleCount: Int = 0,
        val observation: String = "",
        val confidence: Float = 0f,
    ) {
        /** The flag map the engine folds in. Only concerns appear here. */
        fun flags(): Map<String, Boolean> = buildMap {
            if (!contentsMatch) put("contents_mismatch", true)
            if (!sealIntact || tapeResealed) put("tamper", true)
            if (tapeResealed) put("reseal", true)
            if (labelDamaged) put("label_damaged", true)
        }

        /**
         * §3 check 5 — the third read of the code. The human-readable number is
         * printed beside the QR, so a model that can read it gives an
         * independent check on a label whose codes were swapped.
         */
        fun printedCodeDisagrees(scanned: String): Boolean {
            val read = printedCode.replace(" ", "").uppercase()
            if (read.length < 6) return false          // too little to judge
            return !read.contains(scanned.takeLast(8).uppercase())
        }
    }

    private const val PACKAGE_SYSTEM =
        "You are a package verification assistant for a warehouse officer. You " +
            "look at a photograph of one parcel and report only what you can " +
            "actually see: the printed code, whether the visible contents match " +
            "what the label declares, and whether the box or its seal has been " +
            "opened and re-taped. Report what is visible. Reply with JSON only."

    /**
     * Checks [imagePath] against what the label declares.
     *
     * The declared text is supplied as context rather than asked for, so the
     * model is judging agreement rather than inventing a description — a
     * quantised model asked "what is in this box?" will confabulate a plausible
     * answer, while one asked "does this match X?" will more often say no.
     */
    suspend fun verifyPackage(
        imagePath: String,
        declaredContents: String,
        declaredQty: Int,
    ): Result<PackageCheck> {
        val prompt = """
            This parcel's label declares: $declaredContents (quantity $declaredQty).

            Look at the photograph and reply with one JSON object with exactly
            these keys:
              printed_code   - the human-readable package code printed on the
                               label, as printed, or "" if you cannot read it
              contents_match - true if what you can see is consistent with the
                               declaration above, false if it plainly is not
              seal_intact    - true if the tape and seals look undisturbed
              label_damaged  - true if the label is torn, peeled or over-stuck
              tape_resealed  - true if the box appears opened and re-taped
              visible_count  - how many individual items you can count, or 0
              observation    - one short sentence on anything notable
              confidence     - your confidence from 0.0 to 1.0

            Judge only what is visible in the photograph. If you cannot tell,
            leave the boolean at its safe default and say so in observation.
            Never answer with a key name or with this description as a value.
            Return the JSON object and nothing else.
        """.trimIndent()

        return NpuEngine.run(
            systemPrompt = PACKAGE_SYSTEM,
            userPrompt = prompt,
            imagePaths = listOf(imagePath),
            maxTokens = 384,
            temperature = 0.1f,
            label = "Verifying package",
        ).mapCatching { raw ->
            json.decodeFromString<PackageCheck>(extractJsonObject(raw))
        }
    }

    // ------------------------------------------------------- document reading

    /**
     * §5.1 — a document read into the common fact model.
     *
     * The same shape comes back whatever the paper is, because the matching
     * engine matches facts, not formats. Finance-critical numbers still pass
     * through a confirmation screen before they are committed: the engine will
     * hold money on these figures, and a misread digit must not do that silently.
     */
    @Serializable
    data class DocumentReading(
        @SerialName("doc_no") val docNo: String = "",
        val date: String = "",
        @SerialName("seller_name") val sellerName: String = "",
        @SerialName("seller_gstin") val sellerGstin: String = "",
        @SerialName("buyer_name") val buyerName: String = "",
        @SerialName("buyer_gstin") val buyerGstin: String = "",
        val lines: List<DocumentLineReading> = emptyList(),
        @SerialName("taxable_value") val taxableValue: Double = 0.0,
        val legible: Boolean = true,
        val confidence: Float = 0f,
    ) {
        val usable: Boolean get() = docNo.isNotBlank() || lines.isNotEmpty()

        fun toFact(kind: DocumentKind): DocumentFact = DocumentFact(
            kind = kind,
            docNo = docNo,
            date = date.ifBlank { null },
            seller = PartyRef(sellerName, sellerGstin.ifBlank { null }),
            buyer = PartyRef(buyerName, buyerGstin.ifBlank { null }),
            lines = lines.mapIndexed { i, l ->
                DocumentLine(
                    lineNo = i + 1, description = l.description, sku = l.sku.ifBlank { null },
                    hsn = l.hsn.ifBlank { null }, qty = l.qty, rate = l.rate,
                    amount = if (l.amount > 0) l.amount else l.qty * l.rate,
                )
            },
            totals = DocumentTotals(taxableValue = taxableValue),
        )
    }

    @Serializable
    data class DocumentLineReading(
        val description: String = "",
        val sku: String = "",
        val hsn: String = "",
        val qty: Double = 0.0,
        val rate: Double = 0.0,
        val amount: Double = 0.0,
    )

    private const val DOCUMENT_SYSTEM =
        "You are a document reading assistant for a logistics compliance officer. " +
            "You read invoices, purchase orders and consignment notes and copy " +
            "the printed values exactly. You never estimate a number you cannot " +
            "read. Reply with JSON only."

    /**
     * Reads [imagePath] as a [kind] document.
     *
     * As with the E-Way Bill task, header fields read reliably and dense line
     * tables often do not — the bundle's encoder is fixed at 512x512. An empty
     * line list is a normal result: the four-way match will report DOC_MISSING
     * rather than silently matching against nothing.
     */
    suspend fun readDocument(imagePath: String, kind: DocumentKind): Result<DocumentReading> {
        val what = when (kind) {
            DocumentKind.PO -> "purchase order"
            DocumentKind.INVOICE -> "tax invoice"
            DocumentKind.EWB -> "e-way bill"
            DocumentKind.LR -> "lorry receipt / consignment note"
            DocumentKind.PACKING_LIST -> "packing list"
            DocumentKind.CHALLAN -> "delivery challan"
        }
        val prompt = """
            Read this $what.

            Reply with one JSON object with exactly these keys:
              doc_no        - the document number, as printed
              date          - the document date as printed
              seller_name   - the supplier/seller name
              seller_gstin  - the seller's GSTIN, as printed
              buyer_name    - the buyer/consignee name
              buyer_gstin   - the buyer's GSTIN, as printed
              lines         - an array with one object per printed goods row,
                              each having the keys description, sku, hsn, qty,
                              rate, amount
              taxable_value - the taxable value total, as a number
              legible       - true, or false if the document cannot be read
              confidence    - your confidence from 0.0 to 1.0

            Copy the values off the document. Use "" for any text field that is
            not printed, 0 for a missing number, and [] if no goods rows are
            printed. Never answer with a key name or with this description as a
            value. Return the JSON object and nothing else.
        """.trimIndent()

        return NpuEngine.run(
            systemPrompt = DOCUMENT_SYSTEM,
            userPrompt = prompt,
            imagePaths = listOf(imagePath),
            maxTokens = 768,
            temperature = 0.1f,
            label = "Reading $what",
        ).mapCatching { raw ->
            json.decodeFromString<DocumentReading>(extractJsonObject(raw))
        }
    }

    private fun isSchemaEcho(name: String): Boolean {
        val lower = name.lowercase().trim()
        return lower.isEmpty() || SCHEMA_ECHOES.any { lower.contains(it) }
    }

    /**
     * Pulls the first balanced JSON object out of a reply. Quantised models
     * fence their output, prefix it with "Here is the JSON:", or trail a
     * sentence after the closing brace often enough that strict parsing of the
     * whole string fails on otherwise perfectly good answers.
     */
    internal fun extractJsonObject(raw: String): String {
        val start = raw.indexOf('{')
        if (start < 0) throw IllegalArgumentException("No JSON object in model reply")
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until raw.length) {
            val c = raw[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return raw.substring(start, i + 1)
                }
            }
        }
        throw IllegalArgumentException("Unterminated JSON object in model reply")
    }
}
