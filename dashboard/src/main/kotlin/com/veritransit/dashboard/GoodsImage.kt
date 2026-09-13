package com.veritransit.dashboard

/**
 * The goods family a receipt's own words point at, resolved to one frozen
 * reference photograph.
 *
 * These are illustrative pictures — what this class of goods looks like — not
 * photographs of the actual consignment; the dock's own evidence frames carry
 * that. The bytes are committed assets (see `report-images/ATTRIBUTION.md`),
 * loaded from the classpath and never from the network, so a report embedding
 * one stays deterministic.
 */
enum class GoodsImage(val label: String) {
    ELECTRONICS("Electronics"),
    FABRIC("Fabric"),
    HARDWARE("Hardware & fasteners"),
    FOOD("Packaged foodstuffs"),
    WAREHOUSE("General cargo");

    val resourcePath: String get() = "/report-images/${name.lowercase()}.jpg"

    /** The frozen JPEG bytes, or null if the asset is missing from the build. */
    fun bytes(): ByteArray? =
        GoodsImage::class.java.getResourceAsStream(resourcePath)?.use { it.readBytes() }

    companion object {

        /** Longest keyword first, so "packaged foodstuffs" beats "food". */
        private val KEYWORDS: List<Pair<String, GoodsImage>> = listOf(
            "packaged foodstuffs" to FOOD,
            "consumer electronics" to ELECTRONICS,
            "smartphone" to ELECTRONICS,
            "electronics" to ELECTRONICS,
            "electronic" to ELECTRONICS,
            "tablet" to ELECTRONICS,
            "monitor" to ELECTRONICS,
            "laptop" to ELECTRONICS,
            "computer" to ELECTRONICS,
            "appliance" to ELECTRONICS,
            "circuit" to ELECTRONICS,
            "semiconductor" to ELECTRONICS,
            "textile" to FABRIC,
            "fabric" to FABRIC,
            "weaving" to FABRIC,
            "apparel" to FABRIC,
            "garment" to FABRIC,
            "clothing" to FABRIC,
            "yarn" to FABRIC,
            "loom" to FABRIC,
            "dyeing" to FABRIC,
            "foodstuffs" to FOOD,
            "cereal" to FOOD,
            "grain" to FOOD,
            "grocery" to FOOD,
            "food" to FOOD,
            "fastener" to HARDWARE,
            "hardware" to HARDWARE,
            "bolts" to HARDWARE,
            "screws" to HARDWARE,
            "steel" to HARDWARE,
            "plate" to HARDWARE,
            "pipe" to HARDWARE,
            "valve" to HARDWARE,
            "spare" to HARDWARE,
        )

        /** The bucket a receipt's supplier and goods text point at; general cargo when neither. */
        fun of(supplier: String, goods: String): GoodsImage {
            val t = "${supplier.lowercase()} ${goods.lowercase()}"
            return KEYWORDS.firstOrNull { (kw, _) -> kw in t }?.second ?: WAREHOUSE
        }

        fun of(record: ReceivingRecord): GoodsImage = of(record.supplier, record.goods)
    }
}
