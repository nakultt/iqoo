package com.veritransit.inspector.ai

import android.content.Context
import com.veritransit.core.ReasonCode
import com.veritransit.core.ScanResult
import com.veritransit.core.VoiceAlertText
import com.veritransit.inspector.data.DeviceSettings

/**
 * Decides what the phone says and when — the policy around [KokoroVoice].
 *
 * Rules, in priority order:
 * 1. Master switch off ([DeviceSettings.voiceAlerts]) → total silence.
 * 2. VERIFIED → speaks only if the officer opted into pass announcements.
 * 3. SUSPECT / REJECTED → always spoken (that is the point of the feature).
 * 4. Gate results → spoken only when [DeviceSettings.gateAnnouncements] is on,
 *    and only when something is actually wrong (clean results stay silent).
 *    The neural voice speaks the counted variant (codes can't be
 *    phonemized from free text); system TTS keeps the full item names.
 */
object VoiceAnnouncer {

    fun announceScan(
        context: Context,
        result: ScanResult,
        packageCode: String?,
        reasons: List<ReasonCode>,
    ) {
        val settings = DeviceSettings(context)
        if (!settings.voiceAlerts) return
        KokoroVoice.speakScan(context, result, packageCode, reasons, settings.announcePasses)
    }

    fun announceGate(
        context: Context,
        ewb: String,
        flaggedLines: List<String>,
        unlistedCount: Int,
    ) {
        val settings = DeviceSettings(context)
        if (!settings.voiceAlerts || !settings.gateAnnouncements) return
        if (flaggedLines.isEmpty() && unlistedCount == 0) return
        if (KokoroVoice.neuralReady(context)) {
            KokoroVoice.speakGateCounted(
                context, ewb.filter { it.isDigit() }, flaggedLines.size, unlistedCount,
            )
        } else {
            val text = VoiceAlertText.forGate(ewb, flaggedLines, unlistedCount) ?: return
            KokoroVoice.speakSystem(text)
        }
    }

    /**
     * Which non-pass verdicts also become a Telegram voice note: rejections
     * always; suspect reviews except a bare re-scan duplicate (holding the
     * phone still over one carton must not page the supervisor).
     */
    fun wantsVoiceNote(result: ScanResult, reasons: List<ReasonCode>): Boolean =
        result == ScanResult.REJECTED ||
            (result == ScanResult.SUSPECT_REVIEW &&
                reasons.any { it != ReasonCode.DUPLICATE_LABEL })
}
