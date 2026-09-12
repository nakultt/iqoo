package com.veritransit.server.services

import com.veritransit.core.*
import com.veritransit.server.crypto.AuditLog
import com.veritransit.server.crypto.Signer
import com.veritransit.server.db.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.SecureRandom
import java.sql.Connection
import java.time.LocalDate

/**
 * Label issuance and the pack-station hierarchy write (§4.5).
 *
 * The ordering here is the product requirement, not an implementation detail:
 * every database row lands *before* a label is printed. A printed label whose
 * package row failed to commit is a physical object in the world that the
 * platform does not know about — the one failure mode that cannot be undone by
 * retrying.
 */
class LabelService(
    private val db: Database,
    private val signer: Signer,
    private val audit: AuditLog,
) {
    private val random = SecureRandom()

    /** Crockford base32 — no I, L, O or U, so a handwritten code cannot be misread. */
    private val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    private fun newCode(): String =
        "VT-P-" + (1..8).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")

    /**
     * §8.3 `POST /v1/shipments/{ref}/labels:batch` — the bulk variant of §4.5
     * ("300 units ÷ 10 per master = 30 masters"). One transaction: either the
     * whole batch exists or none of it does.
     */
    fun issueBatch(ref: String, req: IssueLabelsRequest, actor: String): IssueLabelsResponse =
        db.transaction { conn ->
            val shipmentId = shipmentId(conn, ref) ?: error("unknown shipment $ref")
            val today = LocalDate.now().toEpochDay()
            val issued = mutableListOf<IssuedLabel>()

            for (line in req.lines) {
                repeat(line.masters) {
                    val masterCode = newCode()
                    insertPackage(
                        conn, shipmentId, masterCode,
                        kind = if (line.unitsPerMaster > 1) PackageKind.MASTER else PackageKind.UNIT,
                        parent = null, contents = masterContents(line), sku = line.sku, hsn = line.hsn,
                        qty = line.unitsPerMaster, poLine = line.poLineNo,
                    )
                    issued += signAndStore(conn, masterCode, ref, today, masterContents(line), line.unitsPerMaster,
                        if (line.unitsPerMaster > 1) PackageKind.MASTER else PackageKind.UNIT, null)

                    // Inner boxes exist as rows whether or not they get their own
                    // label: an unlabelled supplier-preprinted inner is still
                    // something the receiver counts against the master (§4.4).
                    if (line.unitsPerMaster > 1 && line.labelInners) {
                        repeat(line.unitsPerMaster) {
                            val unitCode = newCode()
                            insertPackage(
                                conn, shipmentId, unitCode, PackageKind.UNIT, masterCode,
                                line.contents, line.sku, line.hsn, 1, line.poLineNo,
                            )
                            issued += signAndStore(conn, unitCode, ref, today, line.contents, 1,
                                PackageKind.UNIT, masterCode)
                        }
                    }
                }
            }

            conn.update(
                """UPDATE shipments SET expected_count =
                     (SELECT count(*) FROM packages
                       WHERE shipment_id = ?::uuid AND (kind <> 'UNIT' OR parent_code IS NULL))
                   WHERE id = ?::uuid""",
                shipmentId, shipmentId,
            )

            audit.append(conn, actor, "LABELS_ISSUED", ref, buildJsonObject {
                put("count", issued.size)
                put("key_id", signer.keyId)
            })

            IssueLabelsResponse(ref, issued, issued.size)
        }

    /**
     * §8.3 `POST /v1/packages:close-master` — the packer scans the inner boxes
     * into a carton and taps *Close master*. The master's declared child count
     * comes from the codes actually scanned, so the label cannot claim ten when
     * nine went in.
     */
    fun closeMaster(req: CloseMasterRequest, actor: String): IssuedLabel = db.transaction { conn ->
        val shipmentId = shipmentId(conn, req.shipmentRef) ?: error("unknown shipment ${req.shipmentRef}")
        require(req.childCodes.isNotEmpty()) { "a master must contain at least one inner box" }

        // Every child must already exist on this shipment and be unparented —
        // otherwise we would be silently stealing a box out of another carton.
        val children = conn.query(
            """SELECT package_code, parent_code, shipment_id::text AS sid FROM packages
                WHERE package_code = ANY (?)""",
            req.childCodes.toTypedArray(),
        ) { Triple(it.str("package_code"), it.strOrNull("parent_code"), it.str("sid")) }

        val found = children.map { it.first }.toSet()
        val unknown = req.childCodes.filterNot { it in found }
        require(unknown.isEmpty()) { "unknown inner boxes: ${unknown.joinToString()}" }

        val wrongShipment = children.filter { it.third != shipmentId }.map { it.first }
        require(wrongShipment.isEmpty()) { "inner boxes on another shipment: ${wrongShipment.joinToString()}" }

        val alreadyPacked = children.filter { it.second != null }.map { it.first }
        require(alreadyPacked.isEmpty()) { "already inside another master: ${alreadyPacked.joinToString()}" }

        val masterCode = newCode()
        val contents = req.contents ?: conn.queryOne(
            "SELECT contents FROM packages WHERE package_code = ?", req.childCodes.first(),
        ) { it.str("contents") }?.let { "${req.childCodes.size} × $it" } ?: "${req.childCodes.size} inner boxes"

        insertPackage(
            conn, shipmentId, masterCode, PackageKind.MASTER, null, contents,
            sku = conn.queryOne("SELECT sku FROM packages WHERE package_code = ?", req.childCodes.first()) {
                it.strOrNull("sku")
            },
            hsn = null, qty = req.childCodes.size, poLine = req.poLineNo,
        )

        conn.update(
            "UPDATE packages SET parent_code = ? WHERE package_code = ANY (?)",
            masterCode, req.childCodes.toTypedArray(),
        )

        val label = signAndStore(
            conn, masterCode, req.shipmentRef, LocalDate.now().toEpochDay(),
            contents, req.childCodes.size, PackageKind.MASTER, null,
        )

        conn.update(
            """UPDATE shipments SET expected_count =
                 (SELECT count(*) FROM packages
                   WHERE shipment_id = ?::uuid AND (kind <> 'UNIT' OR parent_code IS NULL))
               WHERE id = ?::uuid""",
            shipmentId, shipmentId,
        )

        audit.append(conn, actor, "MASTER_CLOSED", req.shipmentRef, buildJsonObject {
            put("master", masterCode)
            put("children", req.childCodes.size)
        })

        label
    }

    /**
     * §8.3 reprint. The old copy is superseded rather than deleted: a label
     * already stuck to a carton keeps verifying cryptographically, so only the
     * binding check can tell an officer it has been replaced (`REPRINT_SUPERSEDED`).
     */
    fun reprint(packageCode: String, actor: String): IssuedLabel = db.transaction { conn ->
        val row = conn.queryOne(
            """SELECT p.id::text AS pid, s.ref, p.contents, p.qty, p.kind, p.parent_code,
                      COALESCE(MAX(l.copy_no), 0) AS copy
                 FROM packages p JOIN shipments s ON s.id = p.shipment_id
                 LEFT JOIN labels l ON l.package_id = p.id
                WHERE p.package_code = ?
                GROUP BY p.id, s.ref, p.contents, p.qty, p.kind, p.parent_code""",
            packageCode,
        ) {
            mapOf(
                "pid" to it.str("pid"), "ref" to it.str("ref"), "contents" to it.str("contents"),
                "qty" to it.int("qty").toString(), "kind" to it.str("kind"),
                "parent" to (it.strOrNull("parent_code") ?: ""), "copy" to it.int("copy").toString(),
            )
        } ?: error("unknown package $packageCode")

        conn.update(
            "UPDATE labels SET superseded_at = now() WHERE package_id = ?::uuid AND superseded_at IS NULL",
            row["pid"],
        )

        val nextCopy = row["copy"]!!.toInt() + 1
        val token = signer.issueLabel(packageCode, row["ref"]!!, nextCopy, LocalDate.now().toEpochDay())
        conn.update(
            """INSERT INTO labels (package_id, copy_no, payload, signature, key_id)
               VALUES (?::uuid, ?, ?, ?, ?)""",
            row["pid"], nextCopy, token.toString(), token.signatureB64, signer.keyId,
        )

        audit.append(conn, actor, "LABEL_REPRINTED", packageCode, buildJsonObject {
            put("copy_no", nextCopy)
            put("supersedes", nextCopy - 1)
        })

        IssuedLabel(
            packageCode, PackageKind.valueOf(row["kind"]!!),
            row["parent"]!!.ifBlank { null }, token.toString(), row["contents"], row["qty"]!!.toInt(),
        )
    }

    // ------------------------------------------------------------- internals

    private fun masterContents(line: LabelLine) =
        if (line.unitsPerMaster > 1) "${line.unitsPerMaster} × ${line.contents}" else line.contents

    private fun shipmentId(conn: Connection, ref: String): String? =
        conn.queryOne("SELECT id::text AS id FROM shipments WHERE ref = ?", ref) { it.str("id") }

    private fun insertPackage(
        conn: Connection, shipmentId: String, code: String, kind: PackageKind,
        parent: String?, contents: String?, sku: String?, hsn: String?, qty: Int, poLine: Int?,
    ) {
        conn.update(
            """INSERT INTO packages (shipment_id, package_code, kind, parent_code, contents,
                                     sku, hsn, qty, po_line_no, status)
               VALUES (?::uuid, ?, ?::package_kind, ?, ?, ?, ?, ?, ?, 'CREATED')""",
            shipmentId, code, kind.name, parent, contents, sku, hsn, qty, poLine,
        )
    }

    private fun signAndStore(
        conn: Connection, code: String, ref: String, epochDay: Long,
        contents: String?, qty: Int, kind: PackageKind, parent: String?,
    ): IssuedLabel {
        val token = signer.issueLabel(code, ref, 1, epochDay)
        conn.update(
            """INSERT INTO labels (package_id, copy_no, payload, signature, key_id)
               SELECT id, 1, ?, ?, ? FROM packages WHERE package_code = ?""",
            token.toString(), token.signatureB64, signer.keyId, code,
        )
        conn.update("UPDATE packages SET status = 'PRINTED' WHERE package_code = ?", code)
        return IssuedLabel(code, kind, parent, token.toString(), contents, qty)
    }
}
