package com.veritransit.inspector.ui.warehouse

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.veritransit.core.LabelToken
import com.veritransit.core.ScanKind
import com.veritransit.core.ScanResult
import com.veritransit.inspector.ai.KokoroVoice
import com.veritransit.inspector.ai.VoiceAnnouncer
import com.veritransit.inspector.data.DeviceSettings
import com.veritransit.inspector.data.VeriTransitRepo
import com.veritransit.inspector.data.local.PackageEntity
import com.veritransit.inspector.data.local.ShipmentEntity
import com.veritransit.inspector.scan.VerificationEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * State for the warehouse mode (§6.1).
 *
 * The scan path is deliberately synchronous to a verdict: [onFrame] evaluates
 * and records without awaiting anything remote, because §7.1 budgets under 1.5 s
 * from frame to haptic and a dock has no network to wait on anyway.
 */
class WarehouseViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = VeriTransitRepo.get(app)

    val shipments: StateFlow<List<ShipmentEntity>> =
        repo.shipments().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val outboxDepth: StateFlow<Int> =
        repo.outboxDepth.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _selected = MutableStateFlow<String?>(null)
    val selected: StateFlow<String?> = _selected.asStateFlow()

    private val _verdict = MutableStateFlow<VerificationEngine.Verdict?>(null)
    val verdict: StateFlow<VerificationEngine.Verdict?> = _verdict.asStateFlow()

    private val _accounted = MutableStateFlow(0 to 0)
    val accounted: StateFlow<Pair<Int, Int>> = _accounted.asStateFlow()

    private val _missing = MutableStateFlow<List<PackageEntity>>(emptyList())
    val missing: StateFlow<List<PackageEntity>> = _missing.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private var engine: VerificationEngine? = null

    /** Codes already handled this session, so holding the phone still over one
     *  carton does not record the same scan forty times a second. */
    private val handledThisSession = mutableSetOf<String>()

    fun select(ref: String) {
        _selected.value = ref
        handledThisSession.clear()
        _verdict.value = null
        refreshCounts()
    }

    fun refreshCounts() {
        val ref = _selected.value ?: return
        viewModelScope.launch {
            val shipment = repo.shipment(ref) ?: return@launch
            val pending = repo.notYetScanned(ref)
            _missing.value = pending
            _accounted.value = (shipment.expectedCount - pending.size) to shipment.expectedCount
        }
    }

    /**
     * The hot path. Called from the camera analyser on every decoded frame —
     * with **every** code the frame contained, so one glance at a pallet
     * verifies all its labels at once (§7.1). It must not re-record a code it
     * has already answered; the shown verdict and the voice belong to the
     * worst result in the frame.
     */
    fun onFrame(codes: List<String>, kind: ScanKind) {
        val fresh = codes.mapNotNull { raw -> LabelToken.parse(raw)?.packageCode }
            .filter { handledThisSession.add(it) }
        if (fresh.isEmpty()) return

        viewModelScope.launch {
            val e = engine ?: repo.engine().also { engine = it }
            val verdicts = e.evaluateFrame(codes, _selected.value, kind)
                .filter { it.token != null && it.token.packageCode in fresh }
            if (verdicts.isEmpty()) return@launch

            val worst = verdicts.maxBy { it.result.ordinal }
            _verdict.value = worst

            // Recorded whatever the verdict: a rejection is evidence too, and the
            // discrepancy queue is built from exactly these events.
            verdicts.forEach { verdict ->
                repo.recordScan(
                    packageCode = verdict.token!!.packageCode,
                    shipmentRef = _selected.value,
                    kind = kind,
                    result = verdict.result,
                    reasons = verdict.reasons,
                )
            }
            refreshCounts()

            // Spoken the moment anything is wrong: the officer hears the reason
            // without lowering the phone, and the supervisor gets a voice note.
            // One voice per frame — the worst — never a chorus.
            VoiceAnnouncer.announceScan(
                getApplication(), worst.result,
                worst.token?.packageCode, worst.reasons,
            )
            if (VoiceAnnouncer.wantsVoiceNote(worst.result, worst.reasons) &&
                DeviceSettings(getApplication()).telegramVoice
            ) {
                queueVoiceNote(worst)
            }
        }
    }

    /**
     * Records the spoken alert to a WAV and queues it for the supervisor
     * Telegram chat. Offline-first: the file lives on the phone regardless;
     * the server upload is best-effort and the share sheet always works.
     */
    private fun queueVoiceNote(verdict: VerificationEngine.Verdict) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val caption = com.veritransit.core.VoiceAlertText.telegramCaption(
                _selected.value, verdict.token?.packageCode, verdict.result, verdict.reasons,
            )
            // Neural Kokoro audio when weights are on disk, system TTS
            // otherwise — either way this is the exact audio the dock heard.
            val wav = KokoroVoice.synthesizeVoiceNote(
                app, verdict.result, verdict.token?.packageCode, verdict.reasons,
            )
            if (wav != null) {
                val queued = repo.uploadVoiceAlert(
                    _selected.value, verdict.token?.packageCode,
                    verdict.result, verdict.reasons, wav,
                )
                _voiceAlert.value = VoiceAlert(wav, caption, queued)
            } else {
                // No voice engine at all: the caption alone still pages the
                // supervisor as text via the discrepancy queue on next sync.
                _voiceAlert.value = null
            }
        }
    }

    fun clearVerdict() { _verdict.value = null }

    /** Lets the officer re-scan a carton they deliberately want to re-check. */
    fun forgetSession() = handledThisSession.clear()

    // ------------------------------------------------- receiver inner session
    // Scanning a MASTER opens a guided checklist: the master declares N inners
    // and the receiver must scan all N back (the sender's count is the contract).
    // VLM photo checks fold in via applyAiFlags — they can only demote, never clear.

    private val _masterCode = MutableStateFlow<String?>(null)
    val masterCode: StateFlow<String?> = _masterCode.asStateFlow()

    private val _masterChildren = MutableStateFlow<List<PackageEntity>>(emptyList())
    val masterChildren: StateFlow<List<PackageEntity>> = _masterChildren.asStateFlow()

    private val _innerScanned = MutableStateFlow<Set<String>>(emptySet())
    val innerScanned: StateFlow<Set<String>> = _innerScanned.asStateFlow()

    private val _aiNote = MutableStateFlow<String?>(null)
    val aiNote: StateFlow<String?> = _aiNote.asStateFlow()

    /** A tamper voice note waiting for the officer (share to Telegram / queued). */
    data class VoiceAlert(
        val file: java.io.File,
        val caption: String,
        val queued: Boolean,
    )

    private val _voiceAlert = MutableStateFlow<VoiceAlert?>(null)
    val voiceAlert: StateFlow<VoiceAlert?> = _voiceAlert.asStateFlow()

    fun clearVoiceAlert() { _voiceAlert.value = null }

    fun engineForAi(): VerificationEngine? = engine

    /** Folds VLM visual flags into the current verdict (one-directional). */
    fun applyAiFlags(flags: Map<String, Boolean>, observation: String = "") {
        val e = engine ?: return
        val cur = _verdict.value ?: return
        _verdict.value = e.applyAiFlags(cur, flags)
        _aiNote.value = observation
        viewModelScope.launch {
            cur.token?.let { token ->
                repo.recordScan(
                    packageCode = token.packageCode,
                    shipmentRef = _selected.value,
                    kind = com.veritransit.core.ScanKind.RECEIVE,
                    result = _verdict.value?.result ?: cur.result,
                    reasons = _verdict.value?.reasons ?: cur.reasons,
                    aiFlags = flags,
                )
            }
        }
    }

    /** Called when a MASTER verdict lands — loads its declared inners. */
    fun startMasterSession(master: String) {
        _masterCode.value = master
        _innerScanned.value = emptySet()
        _aiNote.value = null
        viewModelScope.launch {
            _masterChildren.value = repo.masterChildren(master)
        }
    }

    fun onInnerCode(code: String): Boolean {
        val norm = code.trim().uppercase()
        if (norm.isEmpty() || _innerScanned.value.contains(norm)) return false
        _innerScanned.value = _innerScanned.value + norm
        viewModelScope.launch {
            val e = engine ?: repo.engine().also { engine = it }
            val known = repo.packageByCode(norm)
            val qr = known?.labelPayload ?: norm
            val verdict = e.evaluate(qr, norm, _selected.value, ScanKind.RECEIVE)
            repo.recordScan(norm, _selected.value, ScanKind.RECEIVE, verdict.result, verdict.reasons)
        }
        return true
    }

    fun clearMasterSession() {
        _masterCode.value = null
        _masterChildren.value = emptyList()
        _innerScanned.value = emptySet()
        _aiNote.value = null
    }

    fun sync() {
        viewModelScope.launch {
            _syncing.value = true
            runCatching { repo.drainOutbox() }
            runCatching { repo.refreshBootstrap() }
            _syncing.value = false
            refreshCounts()
        }
    }

    fun verdictTone(result: ScanResult) = result
}
