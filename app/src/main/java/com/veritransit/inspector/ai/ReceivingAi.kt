package com.veritransit.inspector.ai

import com.veritransit.inspector.data.ItemStatus
import com.veritransit.inspector.data.PackingItem
import com.veritransit.inspector.data.PackingList
import com.veritransit.inspector.data.ReceivingRecord
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The three receiving tasks the AI performs, each a single stateless turn
 * against [LlmGateway] — the resident NPU model when there is one, the
 * GLM-5.3-Flash cloud fallback otherwise.
 *
 * Every task asks for JSON and parses it leniently: a 4-bit quantised model
 * will occasionally fence its output or trail a sentence after the closing
 * brace, and a malformed reply must degrade to "AI unavailable" rather than
 * crash a receiver's shift mid-delivery.
 *
 * Token budgets are sized against [NpuEngine.effectiveContextTokens], not the
 * 4096 the model supports off-device: the AI Hub bundle is compiled to a 2048
 * context, and a dock photo already spends ~256 of it. The cloud leg has a far
 * larger window, so there the caller's reply budget is just a floor —
 * reasoning headroom is added on top of it.
 */
object ReceivingAi {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    // ------------------------------------------------- packing-list OCR

    @Serializable
    data class ListItem(
        val sku: String = "",
        val name: String = "",
        val packaging: String = "",
        val quantity: Int = 0,
    )

    @Serializable
    data class PackingListReading(
        @SerialName("purchase_order") val purchaseOrderId: String = "",
        @SerialName("packing_list") val packingListId: String = "",
        val supplier: String = "",
        val goods: String = "",
        val dock: String = "",
        val carrier: String = "",
        val items: List<ListItem> = emptyList(),
        val legible: Boolean = true,
        // Per-section self-assessments, normalised through
        // [normaliseSectionConfidence]: null means the model did not report
        // one (older replies, or a value too garbled to trust).
        @SerialName("header_confidence") val headerConfidence: Float? = null,
        @SerialName("supplier_confidence") val supplierConfidence: Float? = null,
        @SerialName("items_confidence") val itemsConfidence: Float? = null,
    ) {
        /** Enough was read to be worth showing the receiver. */
        val usable: Boolean
            get() = purchaseOrderId.isNotBlank() || packingListId.isNotBlank() || items.isNotEmpty()

        fun toPackingList(): PackingList = PackingList(
            purchaseOrderId = purchaseOrderId.ifBlank { "—" },
            packingListId = packingListId.ifBlank { "—" },
            supplier = supplier.ifBlank { "Unnamed supplier" },
            goods = goods.ifBlank { "Unclassified goods" },
            dock = dock.ifBlank { "—" },
            carrier = carrier.ifBlank { "—" },
            items = items
                .filterNot { isSchemaEcho(it.name) }
                .map {
                    PackingItem(
                        sku = it.sku.ifBlank { "—" },
                        name = it.name,
                        detail = it.packaging.ifBlank { "Declared line" },
                        expected = it.quantity.coerceAtLeast(0),
                        received = it.quantity.coerceAtLeast(0),
                    )
                },
        )
    }

    private const val LIST_SYSTEM =
        "You are a document-reading assistant for a goods-receiving clerk at a " +
            "warehouse. You read supplier packing lists and carton labels from " +
            "photographs and return structured data. Transcribe only what is " +
            "legibly printed in the image. Never invent a number you cannot read " +
            "— leave the field empty instead. Reply with JSON only."

    /**
     * Deliberately describes the schema instead of showing a filled-in example.
     * An example row gets copied: a 4-bit model handed
     * `{"sku": "SKU-000", "quantity": 0}` will cheerfully return exactly that
     * as its answer.
     */
    private val LIST_PROMPT = """
        Read this supplier packing list.

        Reply with one JSON object with exactly these keys:
          purchase_order - the purchase order number, as printed
          packing_list   - the packing list reference, as printed
          supplier       - the name of the company that supplied the goods
          goods          - what the goods are, in a few words
          dock           - the receiving dock or warehouse named on the list,
                           or "" if none is printed
          carrier        - the transport company, or "" if none is printed
          items          - an array with one object per printed goods row,
                           each having the keys sku, name, packaging, quantity
          legible        - true, or false if the document cannot be read
          header_confidence   - how sure you are of the purchase order and
                                packing list numbers, between 0 and 1
          supplier_confidence - how sure you are of the supplier name and the
                                goods description, between 0 and 1
          items_confidence    - how sure you are of the goods rows,
                                between 0 and 1

        Copy the values off the document. Use "" for any text field that is not
        printed, 0 for a missing number, and [] if no goods rows are printed.
        Never answer with a key name or with this description as a value.
        Return the JSON object and nothing else.
    """.trimIndent()

    /**
     * Reads [imagePath] into a [PackingListReading].
     *
     * Header fields (PO number, packing list reference, supplier) come back
     * reliably. The line-item table often does not: the bundle's encoder is
     * fixed at 512x512 / 256 tokens, which puts small tabular print near the
     * limit of what it can resolve. A reading with no items is still useful —
     * the receiver confirms the lines on the packing-list step — so an empty
     * list is a normal result here, not an error.
     */
    suspend fun readPackingList(imagePath: String): Result<PackingListReading> =
        LlmGateway.run(
            systemPrompt = LIST_SYSTEM,
            userPrompt = LIST_PROMPT,
            imagePaths = listOf(imagePath),
            maxTokens = 512,
            temperature = 0.1f,
            label = "Reading packing list",
        ).mapCatching { raw -> parsePackingListReading(raw) }

    /**
     * Decodes a raw model reply into a [PackingListReading], normalising the
     * per-section confidences on the way in. Internal so the JVM test suite can
     * exercise the exact parsing path the device run takes.
     */
    internal fun parsePackingListReading(raw: String): PackingListReading {
        val parsed = json.decodeFromString<PackingListReading>(extractJsonObject(raw))
        return parsed.copy(
            headerConfidence = normaliseSectionConfidence(parsed.headerConfidence),
            supplierConfidence = normaliseSectionConfidence(parsed.supplierConfidence),
            itemsConfidence = normaliseSectionConfidence(parsed.itemsConfidence),
        )
    }

    /**
     * Normalises one self-reported section confidence. The prompt asks for
     * 0..1; a quantised model sometimes answers in percent (rescaled) or
     * produces garbage. Garbage becomes null — *not reported* — because a
     * missing signal must never be turned into a false all-clear, and inventing
     * a low value would cry wolf on every reply.
     */
    internal fun normaliseSectionConfidence(raw: Float?): Float? = when {
        raw == null || raw < 0f || raw > 100f -> null
        raw <= 1f -> raw
        else -> (raw / 100f).coerceIn(0f, 1f)
    }

    /** Below this a section carries an amber verify-manually marker. */
    const val LOW_SECTION_CONFIDENCE = 0.5f

    /** The sections of [PackingListReading] whose reported confidence is low enough to flag. */
    internal fun lowConfidenceSections(reading: PackingListReading): List<String> = buildList {
        if ((reading.headerConfidence ?: 1f) < LOW_SECTION_CONFIDENCE) add("header")
        if ((reading.supplierConfidence ?: 1f) < LOW_SECTION_CONFIDENCE) add("supplier")
        if ((reading.itemsConfidence ?: 1f) < LOW_SECTION_CONFIDENCE) add("items")
    }

    // --------------------------------------------------- dock counting

    @Serializable
    data class CountedItem(
        val sku: String = "",
        val name: String = "",
        val received: Int = 0,
        val damaged: Int = 0,
    )

    @Serializable
    data class UnlistedItem(
        val name: String = "",
        val count: Int = 1,
    )

    @Serializable
    data class Count(
        val items: List<CountedItem> = emptyList(),
        val unlisted: List<UnlistedItem> = emptyList(),
        val observation: String = "",
        val confidence: Float = 0f,
    )

    private const val DOCK_SYSTEM =
        "You are a goods-receiving assistant at a warehouse dock. You look at a " +
            "photograph of a delivered consignment and count the goods you can " +
            "actually see, matching them against a supplier packing list. Count " +
            "only what is visible in the photograph. If a packed item is not " +
            "visible, report 0 for it. Reply with JSON only."

    /** What the model made of one dock photograph. */
    data class DockCount(
        val items: List<PackingItem>,
        val observation: String,
        val confidence: Float,
    )

    /**
     * Counts [imagePath] against [packingList]. Packed quantities are given to
     * the model as context but the returned counts are re-keyed against the
     * packing list here — a model that renames a line cannot silently drop it.
     */
    suspend fun countDelivery(imagePath: String, packingList: PackingList): Result<DockCount> {
        val packed = packingList.items.joinToString("\n") {
            "- ${it.sku} ${it.name} (packed qty ${it.expected})"
        }
        val prompt = """
            The supplier's packing list for this delivery declares:
            $packed

            Look at the photograph of the delivered goods and reply with one
            JSON object with exactly these keys:
              items       - an array with one object per declared line above,
                            each having the keys sku, name, received and
                            damaged. Use the declared sku and name verbatim;
                            put the number of that item you can see in
                            received, and the number of them that are visibly
                            crushed, torn or otherwise not fit to book in
                            damaged.
              unlisted    - an array of objects with the keys name and count,
                            one per kind of goods you can see that is not
                            declared above. Use [] if there are none.
              observation - one sentence describing what is in the photograph
              confidence  - how sure you are, between 0 and 1

            Count only what is visible. Never answer with a key name or with
            this description as a value. Return the JSON object and nothing else.
        """.trimIndent()

        return LlmGateway.run(
            systemPrompt = DOCK_SYSTEM,
            userPrompt = prompt,
            imagePaths = listOf(imagePath),
            maxTokens = 448,
            temperature = 0.1f,
            label = "Counting delivery",
        ).mapCatching { raw ->
            val parsed = json.decodeFromString<Count>(extractJsonObject(raw))
            applyCount(parsed, packingList)
        }
    }

    /**
     * Re-keys the model's counts against the packing list. Declared lines come
     * back with whatever count the model reported — including counts **above**
     * the packed quantity, because an over-delivery is a discrepancy the
     * receiver needs on the record, not something to clamp into a clean match.
     */
    internal fun applyCount(parsed: Count, packingList: PackingList): DockCount {
        // Match on SKU first — it is the stable key a supplier cannot rename —
        // and fall back to the description for lists whose label carries no SKU.
        val bySku = parsed.items.filter { it.sku.isNotBlank() }.associateBy { normalise(it.sku) }
        val byName = parsed.items.associateBy { normalise(it.name) }
        val declaredLines = packingList.items.map { item ->
            val counted = bySku[normalise(item.sku)] ?: byName[normalise(item.name)]
            item.copy(
                received = counted?.received?.coerceAtLeast(0) ?: 0,
                damaged = counted?.damaged?.coerceAtLeast(0) ?: 0,
            )
        }
        val extras = parsed.unlisted
            .filterNot { isSchemaEcho(it.name) }
            .map { PackingItem("", it.name, "Not on packing list", 0, it.count.coerceAtLeast(1)) }
        return DockCount(
            items = declaredLines + extras,
            observation = parsed.observation.trim(),
            confidence = normaliseConfidence(parsed.confidence),
        )
    }

    /**
     * The prompt asks for 0..1, but a quantised model sometimes answers in
     * percent; rescale that instead of clamping it up to a manufactured 100%.
     */
    internal fun normaliseConfidence(raw: Float): Float {
        val c = raw.coerceAtLeast(0f)
        return if (c <= 1f) c else (c / 100f).coerceIn(0f, 1f)
    }

    // ------------------------------------------------------ note drafting

    private const val NOTE_SYSTEM =
        "You draft the remarks field of a goods-received note for a warehouse " +
            "receiving clerk. Write one or two plain factual sentences in the " +
            "third person, in the register of an internal warehouse note. State " +
            "only what the count data shows. Do not mention tax, customs, duty " +
            "or any legal or regulatory authority. No preamble, no bullet " +
            "points, no markdown."

    /** Free-text receiving note for the result screen — the NLP-only path. */
    suspend fun draftNote(
        record: ReceivingRecord,
        onToken: (String) -> Unit = {},
        onLegSwitch: () -> Unit = {},
    ): Result<String> {
        val lines = record.items.joinToString("\n") { item ->
            val damaged = if (item.damaged > 0) ", ${item.damaged} damaged" else ""
            when (item.status) {
                ItemStatus.MATCHED -> "- ${item.name}: packed ${item.expected}, received ${item.received} (matches)"
                ItemStatus.SHORT -> "- ${item.name}: packed ${item.expected}, received ${item.received} (short by ${item.expected - item.received})"
                ItemStatus.OVER -> "- ${item.name}: packed ${item.expected}, received ${item.received} (over by ${item.received - item.expected})"
                ItemStatus.UNLISTED -> "- ${item.name}: not on the packing list, ${item.received} received"
                ItemStatus.DAMAGED -> "- ${item.name}: packed ${item.expected}, received ${item.received}$damaged (damaged)"
            }
        }
        val prompt = """
            Goods-received note ${record.id} at ${record.dock}.
            Purchase order ${record.purchaseOrderId}, packing list ${record.packingListId},
            supplier ${record.supplier}, carrier ${record.carrier}.
            Goods: ${record.goods}.

            Count result:
            $lines

            Write the receiving remarks for this note.
        """.trimIndent()

        return LlmGateway.run(
            systemPrompt = NOTE_SYSTEM,
            userPrompt = prompt,
            maxTokens = 192,
            temperature = 0.35f,
            label = "Drafting note",
            onToken = onToken,
            onLegSwitch = onLegSwitch,
        ).map { it.trim().removeSurrounding("\"") }
    }

    // --------------------------------------- box photo-vs-label check

    @Serializable
    private data class VerdictReply(
        val matches: Boolean = false,
        val observation: String = "",
        val confidence: Float = 0f,
    )

    /** What the AI made of one box photo against the box's own QR declaration. */
    data class BoxVerdict(
        val matches: Boolean,
        val observation: String,
        val confidence: Float,
    )

    private const val VERIFY_SYSTEM =
        "You are a goods-receiving assistant at a warehouse dock. You look at " +
            "a photograph of goods and decide whether what you can see matches " +
            "the box label's declaration, which the clerk reads out to you. " +
            "Judge only what is visible in the photograph: the KIND of goods " +
            "and HOW MANY of them. Packaging words in the declaration (box, " +
            "carton, crate, loose) describe how the goods travel, not what " +
            "they look like — loose goods photographed out of their box still " +
            "match. A photograph of a screen showing the goods still shows " +
            "the goods. Reply with JSON only."

    /**
     * Plain words for what [box] declares, for the verification prompt — and
     * for screens to quote beside the verdict. [names] maps SKU to product
     * name; [supplier] names who packed it.
     */
    fun describeBox(
        box: com.veritransit.inspector.data.BoxLabel,
        names: Map<String, String>,
        supplier: String,
    ): String = when (box) {
        is com.veritransit.inspector.data.BoxLabel.Inner ->
            "little box ${box.id} (box ${box.seq} of ${box.of} inside big box " +
                "${box.masterId}), packed by $supplier, declares " +
                box.lines.joinToString(", ") { line ->
                    "${line.qty} × ${names[line.sku] ?: line.sku} (${line.sku})"
                }
        is com.veritransit.inspector.data.BoxLabel.Master ->
            "big outer box ${box.id}, packed by $supplier, declares " +
                "${box.boxCount} ${if (box.boxCount == 1) "little box" else "little boxes"} " +
                "inside it with ${box.units} ${if (box.units == 1) "item" else "items"} altogether"
    }

    /**
     * Checks [imagePath] against [declaration] (see [describeBox]). Returns
     * whether the visible goods match, one sentence saying what was seen, and
     * the model's own confidence. A malformed reply is a failure, never a
     * pass: no verdict must read as a match.
     */
    suspend fun verifyBoxPhoto(imagePath: String, declaration: String): Result<BoxVerdict> =
        LlmGateway.run(
            systemPrompt = VERIFY_SYSTEM,
            userPrompt = """
                The box label declares: $declaration.

                Look at the photograph and reply with one JSON object with
                exactly these keys:
                  matches     - true if the photograph shows the declared kind
                                of goods in the declared number. Count every
                                item of that kind you can see, even partly
                                hidden ones. A different variety or colour of
                                the same fruit still matches as long as the
                                kind and the number are right. When in doubt,
                                answer false.
                  observation - one sentence describing what is in the
                                photograph, in plain words, naming the kind
                                and the number you counted
                  confidence  - how sure you are, between 0 and 1

                Judge only what is visible. Never answer with a key name or
                with this description as a value. Return the JSON object and
                nothing else.
            """.trimIndent(),
            imagePaths = listOf(imagePath),
            maxTokens = 192,
            temperature = 0.1f,
            label = "Checking box photo",
        ).mapCatching(::parseBoxVerdict)

    /**
     * Decodes a raw model reply into a [BoxVerdict]. Internal so the JVM test
     * suite can exercise the exact parsing path the device run takes.
     */
    internal fun parseBoxVerdict(raw: String): BoxVerdict {
        val parsed = json.decodeFromString<VerdictReply>(extractJsonObject(raw))
        return BoxVerdict(
            matches = parsed.matches,
            observation = parsed.observation.trim(),
            confidence = normaliseConfidence(parsed.confidence),
        )
    }

    // ------------------------------------------------------------- parsing

    private fun normalise(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    /**
     * Words that only appear in the schema description, never on a real packing
     * list. A quantised model sometimes answers with the instructions instead
     * of the contents, and an echoed placeholder must not reach a receiving
     * record as a goods line.
     */
    private val SCHEMA_ECHOES = listOf(
        "line item",
        "not on the packing list",
        "item visible",
        "exact name from the packing list",
        "declared line above",
        "one object per",
    )

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
