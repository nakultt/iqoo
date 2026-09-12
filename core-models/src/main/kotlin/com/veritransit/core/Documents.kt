package com.veritransit.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * §5.1 — the common fact model. Whatever the paper looks like, the matching
 * engine sees facts, not formats: an NPU reading of a photographed invoice and
 * a parsed PDF land in the same shape, so the matcher never learns about layouts.
 */

@Serializable
enum class DocumentKind { PO, INVOICE, EWB, LR, PACKING_LIST, CHALLAN }

@Serializable
enum class DocumentReader {
    @SerialName("device_npu") DEVICE_NPU,
    @SerialName("web_parser") WEB_PARSER,
    @SerialName("manual") MANUAL,
}

@Serializable
data class PartyRef(
    val name: String = "",
    /** §5.1: parties match by GSTIN, never by name — names differ across documents. */
    val gstin: String? = null,
)

@Serializable
data class DocumentLine(
    @SerialName("line_no") val lineNo: Int,
    val description: String = "",
    val sku: String? = null,
    val hsn: String? = null,
    val qty: Double = 0.0,
    val unit: String = "NOS",
    val rate: Double = 0.0,
    val amount: Double = 0.0,
)

@Serializable
data class DocumentTotals(
    @SerialName("taxable_value") val taxableValue: Double = 0.0,
    val igst: Double = 0.0,
    @SerialName("grand_total") val grandTotal: Double = 0.0,
)

@Serializable
data class DocumentFact(
    val kind: DocumentKind,
    @SerialName("doc_no") val docNo: String,
    val date: String? = null,
    val seller: PartyRef = PartyRef(),
    val buyer: PartyRef = PartyRef(),
    @SerialName("ship_to") val shipTo: String? = null,
    val lines: List<DocumentLine> = emptyList(),
    val totals: DocumentTotals = DocumentTotals(),
    val vehicle: String? = null,
)

// ------------------------------------------------------- four-way match

/** §5.1 mismatch vocabulary. These codes drive the money, so they are closed. */
@Serializable
enum class MismatchCode {
    QTY_MISMATCH, VALUE_MISMATCH, PRODUCT_MISMATCH, PARTY_MISMATCH,
    DOC_MISSING, DOC_DUPLICATE, INNER_SHORTAGE,
}

/** Which two of the four corners disagreed. */
@Serializable
enum class MatchPair { PO_VS_INVOICE, INVOICE_VS_PHYSICAL, INVOICE_VS_EWB, PARTY }

@Serializable
data class MatchLine(
    @SerialName("line_no") val lineNo: Int,
    val sku: String? = null,
    val description: String = "",
    val hsn: String? = null,
    val rate: Double = 0.0,
    @SerialName("ordered_qty") val orderedQty: Double? = null,
    @SerialName("invoiced_qty") val invoicedQty: Double? = null,
    @SerialName("declared_qty") val declaredQty: Double? = null,
    @SerialName("physical_qty") val physicalQty: Double? = null,
)

@Serializable
data class Mismatch(
    val code: MismatchCode,
    val pair: MatchPair,
    @SerialName("line_no") val lineNo: Int? = null,
    val sku: String? = null,
    @SerialName("ordered_qty") val orderedQty: Double? = null,
    @SerialName("invoiced_qty") val invoicedQty: Double? = null,
    @SerialName("declared_qty") val declaredQty: Double? = null,
    @SerialName("physical_qty") val physicalQty: Double? = null,
    @SerialName("delta_qty") val deltaQty: Double? = null,
    @SerialName("delta_value") val deltaValue: Double = 0.0,
    val detail: String = "",
)

@Serializable
data class Tolerances(
    /** Default per §5.1: quantity exact, value ±2%, party by GSTIN. */
    val qty: String = "exact",
    @SerialName("value_pct") val valuePct: Double = 2.0,
    val party: String = "gstin",
)

@Serializable
data class MatchMatrix(
    val anchor: String = "PO",
    val tolerances: Tolerances = Tolerances(),
    val lines: List<MatchLine> = emptyList(),
    @SerialName("four_way") val fourWay: Map<String, Double> = emptyMap(),
)

@Serializable
enum class MatchStatus { MATCHED, MISMATCHED, PARTIAL }

@Serializable
data class ReconciliationRun(
    val shipmentRef: String,
    val matrix: MatchMatrix,
    val mismatches: List<Mismatch> = emptyList(),
    val status: MatchStatus,
    /** The exact value the run says must be withheld — never a rounded estimate. */
    @SerialName("held_value") val heldValue: Double = 0.0,
    @SerialName("run_by") val runBy: String = "system",
    @SerialName("created_at") val createdAt: String? = null,
)
