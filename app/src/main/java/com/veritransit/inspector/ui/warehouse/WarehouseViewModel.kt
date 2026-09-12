package com.veritransit.inspector.ui.warehouse

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.veritransit.core.ScanKind
import com.veritransit.core.ScanResult
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
 * The scan path is deliberately synchronous to a verdict: [onCodes] evaluates
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
        // A fresh shipment may have fresh keys (e.g. a device-local key pinned by
        // a quick-ship that happened after the engine was built) — rebuild.
        engine = null
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
     * The hot path. Called from the camera analyser on every decoded frame, so
     * it must be cheap and must not re-record a code it has already answered.
     */
    fun onCodes(qr: String?, barcode: String?, kind: ScanKind) {
        val key = qr ?: barcode ?: return
        if (!handledThisSession.add(key)) return

        viewModelScope.launch {
            val e = engine ?: repo.engine().also { engine = it }
            val verdict = e.evaluate(qr, barcode, _selected.value, kind)
            _verdict.value = verdict

            // Recorded whatever the verdict: a rejection is evidence too, and the
            // discrepancy queue is built from exactly these events.
            verdict.token?.let { token ->
                repo.recordScan(
                    packageCode = token.packageCode,
                    shipmentRef = _selected.value,
                    kind = kind,
                    result = verdict.result,
                    reasons = verdict.reasons,
                )
            }
            refreshCounts()
        }
    }

    fun clearVerdict() { _verdict.value = null }

    /**
     * Single-phone demo path: feeds the next unscanned carton's signed label
     * through the exact same verdict pipeline the camera would. It exists so a
     * sender and receiver can share one handset with the QRs on screen.
     */
    fun simulateScan(kind: ScanKind) {
        val ref = _selected.value ?: return
        viewModelScope.launch {
            val next = repo.notYetScanned(ref).firstOrNull() ?: return@launch
            val payload = next.labelPayload ?: return@launch
            onCodes(payload, null, kind)
        }
    }

    /** Lets the officer re-scan a carton they deliberately want to re-check. */
    fun forgetSession() = handledThisSession.clear()

    fun sync() {
        viewModelScope.launch {
            _syncing.value = true
            runCatching { repo.drainOutbox() }
            runCatching { repo.refreshBootstrap() }
            engine = null   // bootstrap may have rotated keys
            _syncing.value = false
            refreshCounts()
        }
    }

    fun verdictTone(result: ScanResult) = result
}
