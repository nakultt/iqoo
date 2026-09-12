package com.veritransit.core

import java.util.Base64

/**
 * The signed label payload of §4.2 — the QR's entire contents.
 *
 * ```
 * VT1|P=VT-P-8F3K2M9D|S=SHP-2026-090231|N=1|T=20667|SIG=<base64url Ed25519>
 * ```
 *
 * Deliberately not JSON: this is decoded on the scanning hot path (§7.1, under
 * 1.5 s to a verdict) and printed on a 100×150 mm label, so every byte of QR
 * payload costs module density and every allocation costs frame time.
 *
 * The QR is not the truth — the signature is. The token carries identity only;
 * contents, PO line and finance state live in the database and reach the device
 * through the shift bootstrap, which is why a master can be repacked without
 * reprinting a single inner label.
 */
data class LabelToken(
    val packageCode: String,
    val shipmentRef: String,
    /** Reprint counter: a superseded copy still verifies cryptographically, so
     *  binding checks — not the signature — are what reject an old label. */
    val copyNo: Int,
    /** Issue date as whole days since the Unix epoch; two bytes shorter than a date. */
    val issuedEpochDay: Long,
    val signatureB64: String,
) {
    /** Exactly the bytes the signature covers: everything left of `|SIG=`. */
    val signedBody: String
        get() = "$PREFIX|P=$packageCode|S=$shipmentRef|N=$copyNo|T=$issuedEpochDay"

    /** The full token as printed in the QR. */
    override fun toString(): String = "$signedBody|$SIG_FIELD=$signatureB64"

    fun signatureBytes(): ByteArray = decodeB64Url(signatureB64)

    companion object {
        const val PREFIX = "VT1"
        private const val SIG_FIELD = "SIG"

        /** Ed25519 signatures are 64 bytes → 86 base64url characters unpadded. */
        const val SIGNATURE_CHARS = 86

        /**
         * Builds the signed body for a package that has not been signed yet.
         * The caller signs these exact bytes; keeping the construction here is
         * what stops the issuer and the verifier drifting apart.
         */
        fun bodyToSign(packageCode: String, shipmentRef: String, copyNo: Int, issuedEpochDay: Long): String =
            "$PREFIX|P=$packageCode|S=$shipmentRef|N=$copyNo|T=$issuedEpochDay"

        /**
         * Parses a scanned token. Returns null rather than throwing: a camera
         * frame will routinely decode a sticker, a GS1 code or a shred of
         * another system's QR, and none of that is exceptional.
         */
        fun parse(raw: String): LabelToken? {
            val text = raw.trim()
            if (!text.startsWith("$PREFIX|")) return null

            var code: String? = null
            var ship: String? = null
            var copy: Int? = null
            var day: Long? = null
            var sig: String? = null

            for (field in text.split('|')) {
                when {
                    field == PREFIX -> Unit
                    field.startsWith("P=") -> code = field.substring(2)
                    field.startsWith("S=") -> ship = field.substring(2)
                    field.startsWith("N=") -> copy = field.substring(2).toIntOrNull()
                    field.startsWith("T=") -> day = field.substring(2).toLongOrNull()
                    field.startsWith("$SIG_FIELD=") -> sig = field.substring(SIG_FIELD.length + 1)
                    else -> return null          // unknown field: not our token
                }
            }

            if (code.isNullOrBlank() || ship.isNullOrBlank() || sig.isNullOrBlank()) return null
            if (copy == null || copy < 1 || day == null) return null
            if (sig.length != SIGNATURE_CHARS) return null
            // Reject early if the signature is not decodable — cheaper than a
            // failed verify, and it keeps the failure reason honest.
            runCatching { decodeB64Url(sig) }.getOrNull() ?: return null

            return LabelToken(code, ship, copy, day, sig)
        }

        internal fun decodeB64Url(s: String): ByteArray =
            Base64.getUrlDecoder().decode(s.padTo4())

        internal fun encodeB64Url(b: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(b)

        private fun String.padTo4(): String =
            if (length % 4 == 0) this else this + "=".repeat(4 - length % 4)
    }
}

/**
 * The deterministic half of the check stack (§4.3 layers 1–3). Kept here so the
 * device, the backend ingest path and the tests all run identical logic — the
 * plan's "the device decides in real time; the backend decides authoritatively"
 * only holds if both are deciding the same way.
 *
 * Layer 4 (duplicates) needs session or cross-device state and layer 5 (visual)
 * needs the NPU, so both are supplied by the caller rather than computed here.
 */
object LabelChecks {

    /**
     * @param verified      result of the Ed25519 check over [LabelToken.signedBody]
     * @param expectedShipment the shipment the operator is currently working
     * @param barcodeCode   the Code128 read from the same label, if one was decoded
     * @param known         the package as the device knows it from bootstrap, if any
     * @param duplicate     true when this code was already seen this session or on another device
     */
    fun evaluate(
        token: LabelToken,
        verified: Boolean,
        expectedShipment: String?,
        barcodeCode: String? = null,
        known: PackageRecord? = null,
        duplicate: Boolean = false,
    ): Pair<ScanResult, List<ReasonCode>> {
        val reasons = mutableListOf<ReasonCode>()

        // L1 — a forged label is refused outright and nothing else matters.
        if (!verified) return ScanResult.REJECTED to listOf(ReasonCode.SIGNATURE_INVALID)

        // L2 — binding: right shipment, current copy, actually on the list.
        if (expectedShipment != null && token.shipmentRef != expectedShipment) {
            reasons += ReasonCode.WRONG_SHIPMENT
        }
        if (known == null) {
            reasons += ReasonCode.NOT_IN_MANIFEST
        } else if (token.copyNo < known.copyNo) {
            reasons += ReasonCode.REPRINT_SUPERSEDED
        }

        // L3 — the two codes on one label must agree, or the label moved.
        if (barcodeCode != null && barcodeCode != token.packageCode) {
            reasons += ReasonCode.QR_BARCODE_MISMATCH
        }

        // L4 — one label, one box.
        if (duplicate) reasons += ReasonCode.DUPLICATE_LABEL

        return if (reasons.isEmpty()) ScanResult.VERIFIED to emptyList()
        else ScanResult.SUSPECT_REVIEW to reasons
    }
}
