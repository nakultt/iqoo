package com.veritransit.inspector.scan

import com.veritransit.core.LabelChecks
import com.veritransit.core.LabelToken
import com.veritransit.core.PackageKind
import com.veritransit.core.PackageRecord
import com.veritransit.core.PackageStatus
import com.veritransit.core.ReasonCode
import com.veritransit.core.ScanKind
import com.veritransit.core.ScanResult
import com.veritransit.inspector.crypto.LabelVerifier
import com.veritransit.inspector.data.local.PackageDao
import com.veritransit.inspector.data.local.PackageEntity
import com.veritransit.inspector.data.local.ScanOutboxDao

/**
 * §7.1 — the scanning hot path, offline, under 1.5 s to a verdict.
 *
 * ```
 * decode ─ L1 signature ─ L2 binding ─ L3 QR↔barcode ─ L4 duplicate ─ verdict
 *                                                      L5 photo/NPU, async
 * ```
 *
 * Two properties are load-bearing:
 *
 *  * **The deterministic layers run first and complete without a network.** The
 *    officer gets a verdict and a haptic before any photo is taken.
 *  * **The AI can only add a flag.** [applyAiFlags] can turn VERIFIED into
 *    SUSPECT_REVIEW; nothing it returns can turn a SUSPECT into a pass. A 4-bit
 *    model misread should cost a supervisor visit, never a wrongful release.
 */
class VerificationEngine(
    private val verifier: LabelVerifier,
    private val packages: PackageDao,
    private val outbox: ScanOutboxDao,
) {

    data class Verdict(
        val result: ScanResult,
        val reasons: List<ReasonCode>,
        val token: LabelToken?,
        val known: PackageEntity?,
        /** Set when the scan opens a master that declares inner boxes (§4.4). */
        val declaredInners: Int = 0,
        val raw: String? = null,
    ) {
        val headline: String
            get() = when (result) {
                ScanResult.VERIFIED -> "VERIFIED"
                ScanResult.SUSPECT_REVIEW -> "SUSPECT"
                ScanResult.REJECTED -> "REJECT"
            }
        /** The officer must be told *why*, not just shown a colour (§12). */
        val explanation: String
            get() = reasons.joinToString(" · ") { it.message }
    }

    suspend fun evaluate(
        qr: String?,
        barcode: String?,
        expectedShipment: String?,
        kind: ScanKind,
    ): Verdict {
        val raw = qr ?: barcode

        // A code that is not one of ours is not a failure of verification — it is
        // a sticker, a courier label, or another system's QR.
        val token = qr?.let { LabelToken.parse(it) }
            ?: return Verdict(
                ScanResult.REJECTED,
                listOf(ReasonCode.SIGNATURE_INVALID),
                null, null, raw = raw,
            )

        val verified = verifier.verify(token)
        val known = packages.byCode(token.packageCode)

        // L4 — this shift's session set. The server widens this to all devices on
        // sync (§3 check 8); the phone can only see itself.
        val duplicate = outbox.timesSeen(token.packageCode, kind.name) > 0

        val (result, reasons) = LabelChecks.evaluate(
            token = token,
            verified = verified,
            expectedShipment = expectedShipment,
            barcodeCode = barcode,
            known = known?.toCore(),
            duplicate = duplicate,
        )

        val declaredInners = if (known != null && known.kind != PackageKind.UNIT.name) known.qty else 0
        return Verdict(result, reasons, token, known, declaredInners, raw)
    }

    /**
     * Folds the NPU's visual findings into an existing verdict. Deliberately
     * one-directional: flags can demote a pass, never promote a failure.
     */
    fun applyAiFlags(verdict: Verdict, flags: Map<String, Boolean>): Verdict {
        val added = buildList {
            if (flags["tamper"] == true || flags["reseal"] == true) add(ReasonCode.VISUAL_TAMPER)
            if (flags["contents_mismatch"] == true) add(ReasonCode.CONTENTS_MISMATCH)
        }
        if (added.isEmpty()) return verdict

        return verdict.copy(
            // Already REJECTED stays REJECTED; VERIFIED becomes SUSPECT_REVIEW.
            result = if (verdict.result == ScanResult.REJECTED) ScanResult.REJECTED
            else ScanResult.SUSPECT_REVIEW,
            reasons = (verdict.reasons + added).distinct(),
        )
    }

    /**
     * §4.4 — closing a master. It resolves only when every declared inner is
     * accounted for; a shortage names the value at risk rather than just failing.
     */
    suspend fun closeMaster(masterCode: String, scannedInners: Set<String>): MasterOutcome {
        val master = packages.byCode(masterCode) ?: return MasterOutcome(0, 0, emptyList(), emptyList())
        val children = packages.childrenOf(masterCode)

        val declared = if (children.isNotEmpty()) children.size else master.qty
        val expectedCodes = children.map { it.packageCode }.toSet()

        val missing = expectedCodes - scannedInners
        // A box inside a sealed master that the master never declared is the
        // classic short-ship concealment (§4.4 INNER_UNLISTED).
        val unlisted = scannedInners - expectedCodes

        return MasterOutcome(declared, scannedInners.size, missing.toList(), unlisted.toList())
    }

    data class MasterOutcome(
        val declared: Int,
        val verified: Int,
        val missing: List<String>,
        val unlisted: List<String>,
    ) {
        val complete: Boolean get() = missing.isEmpty() && unlisted.isEmpty() && verified >= declared
        val reasons: List<ReasonCode>
            get() = buildList {
                if (missing.isNotEmpty()) add(ReasonCode.INNER_SHORTAGE)
                if (unlisted.isNotEmpty()) add(ReasonCode.INNER_UNLISTED)
            }
    }
}

fun PackageEntity.toCore() = PackageRecord(
    packageCode = packageCode,
    shipmentRef = shipmentRef,
    kind = runCatching { PackageKind.valueOf(kind) }.getOrDefault(PackageKind.UNIT),
    parentCode = parentCode,
    contents = contents,
    sku = sku,
    qty = qty,
    poLineNo = poLineNo,
    status = runCatching { PackageStatus.valueOf(localStatus ?: status) }
        .getOrDefault(PackageStatus.CREATED),
    labelPayload = labelPayload,
    copyNo = copyNo,
)
