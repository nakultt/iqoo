package com.veritransit.server.db

import com.veritransit.core.*
import kotlinx.serialization.json.Json
import java.sql.ResultSet

/**
 * Row → domain mappings, in one place.
 *
 * Every route that returns a shipment returns *this* shape, so the admin UI,
 * the bot and the device cannot end up looking at three subtly different
 * pictures of the same record.
 */
object Rows {

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun shipment(rs: ResultSet) = Shipment(
        ref = rs.str("ref"),
        vehicle = rs.strOrNull("vehicle"),
        origin = rs.strOrNull("origin"),
        destination = rs.strOrNull("destination"),
        expectedCount = rs.int("expected_count"),
        status = ShipmentStatus.valueOf(rs.str("status")),
        supplier = rs.strOrNull("supplier"),
        buyer = rs.strOrNull("buyer"),
        dispatchedAt = rs.isoOrNull("dispatched_at"),
        receivedAt = rs.isoOrNull("received_at"),
    )

    fun packageRecord(rs: ResultSet) = PackageRecord(
        packageCode = rs.str("package_code"),
        shipmentRef = rs.str("shipment_ref"),
        kind = PackageKind.valueOf(rs.str("kind")),
        parentCode = rs.strOrNull("parent_code"),
        contents = rs.strOrNull("contents"),
        sku = rs.strOrNull("sku"),
        hsn = rs.strOrNull("hsn"),
        qty = rs.int("qty"),
        poLineNo = rs.intOrNull("po_line_no"),
        status = PackageStatus.valueOf(rs.str("package_status")),
        labelPayload = rs.strOrNull("label_payload"),
        copyNo = rs.getObject("copy_no")?.let { rs.getInt("copy_no") } ?: 1,
    )

    fun discrepancy(rs: ResultSet) = Discrepancy(
        id = rs.str("id"),
        shipmentRef = rs.str("shipment_ref"),
        kind = rs.str("kind"),
        packageCode = rs.strOrNull("package_code"),
        severity = rs.str("severity"),
        detail = runCatching {
            json.decodeFromString<Map<String, String>>(rs.str("detail"))
        }.getOrElse { flattenJson(rs.str("detail")) },
        detectedAt = rs.iso("detected_at"),
        resolvedAt = rs.isoOrNull("resolved_at"),
        resolution = rs.strOrNull("resolution"),
    )

    fun riskScore(rs: ResultSet) = RiskScore(
        subjectKind = rs.str("subject_kind"),
        subjectId = rs.str("subject_id"),
        score = rs.int("score"),
        band = RiskBand.valueOf(rs.str("band")),
        factors = runCatching {
            json.decodeFromString<List<RiskFactor>>(rs.str("factors"))
        }.getOrDefault(emptyList()),
        computedAt = rs.isoOrNull("computed_at"),
    )

    fun financeState(rs: ResultSet) = FinanceState(
        shipmentRef = rs.str("shipment_ref"),
        currency = rs.str("currency"),
        orderValue = rs.dbl("order_value"),
        status = FinanceStatus.valueOf(rs.str("status")),
        releasedValue = rs.dbl("released_value"),
        heldValue = rs.dbl("held_value"),
        terms = flattenJson(rs.str("terms")),
    )

    fun document(rs: ResultSet) = ShipmentDocument(
        id = rs.strOrNull("id"),
        shipmentRef = rs.str("shipment_ref"),
        kind = DocumentKind.valueOf(rs.str("kind")),
        docNo = rs.str("doc_no"),
        docDate = rs.strOrNull("doc_date"),
        fact = json.decodeFromString(rs.str("fact")),
        sourceUri = rs.strOrNull("source_uri"),
        readBy = when (rs.str("read_by")) {
            "device_npu" -> DocumentReader.DEVICE_NPU
            "web_parser" -> DocumentReader.WEB_PARSER
            else -> DocumentReader.MANUAL
        },
        confidence = rs.dblOrNull("confidence"),
    )

    /**
     * JSONB values in this schema are free-form by design (a `detail` blob is
     * whatever the detector had to say). The API exposes them as string maps so
     * clients need no per-field schema; nested structures are rendered rather
     * than dropped.
     */
    fun flattenJson(raw: String): Map<String, String> = runCatching {
        json.parseToJsonElement(raw).let { el ->
            (el as? kotlinx.serialization.json.JsonObject)?.mapValues { (_, v) ->
                (v as? kotlinx.serialization.json.JsonPrimitive)?.content ?: v.toString()
            } ?: emptyMap()
        }
    }.getOrDefault(emptyMap())
}
