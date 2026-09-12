package com.veritransit.inspector.ui.sender

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.veritransit.core.CloseMasterRequest
import com.veritransit.core.CreateShipmentRequest
import com.veritransit.core.IssueLabelsRequest
import com.veritransit.core.IssuedLabel
import com.veritransit.core.LabelLine
import com.veritransit.core.PackageKind
import com.veritransit.inspector.data.DeviceSettings
import com.veritransit.inspector.data.VeriTransitRepo
import com.veritransit.inspector.data.local.PackageEntity
import com.veritransit.inspector.net.ApiClient
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.security.SecureRandom

/**
 * Sender (pack-station) state — the phone replaces the web pack-station page.
 *
 * Flow: pick/create shipment → describe the carton (contents, N inners) →
 * issue labels (server-signed QRs, or DRAFT QRs offline) → close master over
 * the inner codes actually packed → photograph paperwork (VLM reads it).
 *
 * Offline rule: a DRAFT label scans but never verifies — its signature is a
 * placeholder and the UI says so. The rows still land in Room first, so no
 * physical box exists that the phone does not know about.
 */
class SenderViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = VeriTransitRepo.get(app)
    private val settings = DeviceSettings(app)
    private val random = SecureRandom()
    private val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    val shipments = repo.shipments()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var shipmentRef by mutableStateOf("")
    var supplier by mutableStateOf("")
    var buyer by mutableStateOf("")
    var contents by mutableStateOf("Smartphone cartons 5in")
    var sku by mutableStateOf("KE-SP-A15")
    var masters by mutableStateOf("1")
    var unitsPerMaster by mutableStateOf("10")
    var labelInners by mutableStateOf(true)

    var childCodesInput by mutableStateOf("")
    var issued by mutableStateOf<List<IssuedLabel>>(emptyList())
    var draft by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var status by mutableStateOf<String?>(null)

    private fun api(): ApiClient? {
        val base = settings.serverUrl ?: return null
        return ApiClient(base, settings.apiKey, settings.officerName)
    }

    private fun newCode(): String =
        "VT-P-" + (1..8).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")

    fun childCodes(): List<String> =
        childCodesInput.split("[\\s,]+".toRegex()).map { it.trim().uppercase() }.filter { it.isNotBlank() }

    fun useShipment(ref: String) {
        shipmentRef = ref
    }

    fun createShipment() {
        val ref = shipmentRef.trim().ifBlank { "SHP-${System.currentTimeMillis() % 100000}" }
        viewModelScope.launch {
            busy = true
            status = null
            val client = api()
            if (client != null) {
                try {
                    val s = client.createShipment(
                        CreateShipmentRequest(ref = ref, supplier = supplier.ifBlank { null }, buyer = buyer.ifBlank { null })
                    )
                    shipmentRef = s.ref
                    status = "Shipment ${s.ref} created on server."
                } catch (t: Throwable) {
                    repo.cacheLocalShipment(ref, supplier.ifBlank { null }, buyer.ifBlank { null }, 0)
                    shipmentRef = ref
                    status = "Offline — shipment $ref drafted on this phone. It syncs later."
                } finally {
                    client.close()
                }
            } else {
                viewModelScope.launch {
                    repo.cacheLocalShipment(ref, supplier.ifBlank { null }, buyer.ifBlank { null }, 0)
                }
                shipmentRef = ref
                status = "No server configured — shipment $ref drafted on this phone."
            }
            busy = false
        }
    }

    /**
     * Issues 1 master + N inner QRs per master (labelInners) — the exact
     * inner count the receiver will later be asked to scan back.
     */
    fun issueLabels() {
        val ref = shipmentRef.trim()
        if (ref.isEmpty()) {
            status = "Pick or create a shipment first."
            return
        }
        val m = masters.toIntOrNull()?.coerceIn(1, 30) ?: 1
        val n = unitsPerMaster.toIntOrNull()?.coerceIn(1, 50) ?: 1
        viewModelScope.launch {
            busy = true
            status = null
            val client = api()
            if (client != null) {
                try {
                    val res = client.issueLabels(
                        ref,
                        IssueLabelsRequest(
                            listOf(
                                LabelLine(
                                    poLineNo = 1, sku = sku, contents = contents,
                                    hsn = null, masters = m, unitsPerMaster = n, labelInners = labelInners,
                                )
                            )
                        )
                    )
                    issued = res.labels
                    draft = false
                    repo.cacheIssuedLabels(ref, res.labels)
                    // Pre-fill the close-master box with the first master's inners
                    // so the packer sees the expected shape immediately.
                    val firstMaster = res.labels.firstOrNull { it.kind == PackageKind.MASTER }
                    if (firstMaster != null) {
                        val inners = res.labels.filter { it.parentCode == firstMaster.packageCode }
                        if (inners.isNotEmpty()) childCodesInput = inners.joinToString(" ") { it.packageCode }
                    }
                    status = "${res.issued} signed labels issued — QRs below are scannable now."
                } catch (t: Throwable) {
                    issueDraft(ref, m, n)
                } finally {
                    client.close()
                }
            } else {
                issueDraft(ref, m, n)
            }
            busy = false
        }
    }

    private suspend fun issueDraft(ref: String, m: Int, n: Int) {
        val rows = mutableListOf<PackageEntity>()
        val out = mutableListOf<IssuedLabel>()
        repeat(m) {
            val masterCode = newCode()
            // Placeholder signature: 86 chars so the payload keeps the VT1 shape
            // and stays scannable; verification still fails until signed (by design).
            val masterPayload = "VT1|P=$masterCode|S=$ref|N=1|T=0|SIG=${"0".repeat(86)}"
            rows += PackageEntity(masterCode, ref, PackageKind.MASTER.name, null, "$n × $contents", sku, n, 1, "PRINTED", masterPayload, 1, null)
            out += IssuedLabel(masterCode, PackageKind.MASTER, null, masterPayload, "$n × $contents", n)
            if (labelInners) {
                repeat(n) {
                    val unitCode = newCode()
                    val unitPayload = "VT1|P=$unitCode|S=$ref|N=1|T=0|SIG=${"0".repeat(86)}"
                    rows += PackageEntity(unitCode, ref, PackageKind.UNIT.name, masterCode, contents, sku, 1, 1, "PRINTED", unitPayload, 1, null)
                    out += IssuedLabel(unitCode, PackageKind.UNIT, masterCode, unitPayload, contents, 1)
                }
            }
        }
        repo.cacheLocalShipment(ref, supplier.ifBlank { null }, buyer.ifBlank { null }, m)
        repo.cacheLocalPackages(ref, rows)
        issued = out
        draft = true
        val firstMaster = out.firstOrNull { it.kind == PackageKind.MASTER }
        if (firstMaster != null) {
            childCodesInput = out.filter { it.parentCode == firstMaster.packageCode }.joinToString(" ") { it.packageCode }
        }
        status = "Offline — ${out.size} DRAFT QRs minted on this phone. They verify after sync + server signing."
    }

    /** Closes a master over the inner codes actually in the carton. */
    fun closeMaster() {
        val ref = shipmentRef.trim()
        val children = childCodes()
        if (ref.isEmpty() || children.isEmpty()) {
            status = "Enter the inner codes scanned into the carton first."
            return
        }
        viewModelScope.launch {
            busy = true
            val client = api()
            if (client != null) {
                try {
                    val label = client.closeMaster(CloseMasterRequest(ref, children, "$children.size × $contents", 1))
                    issued = listOf(label) + issued
                    repo.cacheIssuedLabels(ref, listOf(label))
                    childCodesInput = ""
                    status = "Master ${label.packageCode} closed over ${children.size} inners — its QR declares exactly what went in."
                } catch (t: Throwable) {
                    status = "Close-master failed: ${t.message}. Check the codes belong to $ref and are not already packed."
                } finally {
                    client.close()
                }
            } else {
                status = "No server — DRAFT masters close automatically by their declared inners."
            }
            busy = false
        }
    }
}
