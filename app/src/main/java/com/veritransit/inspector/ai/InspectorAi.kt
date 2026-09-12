package com.veritransit.inspector.ai

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
        // Per-section self-assessments, normalised through
        // [normaliseSectionConfidence]: null means the model did not report
        // one (older replies, or a value too garbled to trust).
        @SerialName("header_confidence") val headerConfidence: Float? = null,
        @SerialName("route_confidence") val routeConfidence: Float? = null,
        @SerialName("items_confidence") val itemsConfidence: Float? = null,
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
          header_confidence - how sure you are of the bill number and
                              vehicle number, between 0 and 1
          route_confidence  - how sure you are of origin, destination
                              and distance, between 0 and 1
          items_confidence  - how sure you are of the goods rows,
                              between 0 and 1

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
        ).mapCatching { raw -> parseBillReading(raw) }

    /**
     * Decodes a raw model reply into a [BillReading], normalising the
     * per-section confidences on the way in. Internal so the JVM test suite
     * can exercise the exact parsing path the device run takes.
     */
    internal fun parseBillReading(raw: String): BillReading {
        val parsed = json.decodeFromString<BillReading>(extractJsonObject(raw))
        return parsed.copy(
            headerConfidence = normaliseSectionConfidence(parsed.headerConfidence),
            routeConfidence = normaliseSectionConfidence(parsed.routeConfidence),
            itemsConfidence = normaliseSectionConfidence(parsed.itemsConfidence),
        )
    }

    /**
     * Normalises one self-reported section confidence. The prompt asks for
     * 0..1; a quantised model sometimes answers in percent (rescaled) or
     * produces garbage. Garbage becomes null — *not reported* — because a
     * missing signal must never be turned into a false all-clear, and
     * inventing a low value would cry wolf on every reply.
     */
    internal fun normaliseSectionConfidence(raw: Float?): Float? = when {
        raw == null || raw < 0f || raw > 100f -> null
        raw <= 1f -> raw
        else -> (raw / 100f).coerceIn(0f, 1f)
    }

    /** Below this a section carries an amber verify-manually marker. */
    const val LOW_SECTION_CONFIDENCE = 0.5f

    /** The sections of [BillReading] whose reported confidence is low enough to flag. */
    internal fun lowConfidenceSections(reading: BillReading): List<String> = buildList {
        if ((reading.headerConfidence ?: 1f) < LOW_SECTION_CONFIDENCE) add("header")
        if ((reading.routeConfidence ?: 1f) < LOW_SECTION_CONFIDENCE) add("route")
        if ((reading.itemsConfidence ?: 1f) < LOW_SECTION_CONFIDENCE) add("items")
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
