package com.veritransit.server.routes

import com.veritransit.core.*
import com.veritransit.server.Services
import com.veritransit.server.auth.Sessions
import com.veritransit.server.auth.actor
import com.veritransit.server.auth.requireRole
import com.veritransit.server.crypto.ApiKeys
import com.veritransit.server.db.*
import com.veritransit.server.services.DocumentService
import com.veritransit.server.services.FinanceService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.CopyOnWriteArraySet

@Serializable
data class LoginRequest(val name: String, val password: String)

@Serializable
data class LoginResponse(val token: String, val name: String, val role: UserRole)

@Serializable
data class HoldRequest(val amount: Double, val reason: String)

@Serializable
data class ResolveRequest(val note: String)

/** The §8.3 surface. */
fun Application.apiRoutes(s: Services) {

    val liveSockets = CopyOnWriteArraySet<WebSocketSession>()
    s.scans.onScan { event ->
        val payload = Rows.json.encodeToString(event)
        liveSockets.forEach { socket ->
            runCatching { socket.outgoing.trySend(Frame.Text(payload)) }
        }
    }

    routing {
        get("/health") {
            val chain = s.audit.verifyChain()
            call.respond(mapOf(
                "status" to if (chain.ok) "ok" else "audit_chain_broken",
                "audit_entries" to chain.checked.toString(),
                "key_id" to s.signer.keyId,
            ))
        }

        // ------------------------------------------------- public surface
        // Only the two endpoints that establish identity are open; everything
        // else sits behind `authenticate("vt")`. A new route added to the
        // authenticated block below is protected by default — it takes an
        // explicit decision to expose anything.
        route("/v1") {
            post("/auth/login") {
                val req = call.receive<LoginRequest>()
                val token = s.sessions.login(req.name, req.password)
                val principal = s.sessions.verify(token)!!
                call.respond(LoginResponse(token, principal.subject, principal.role))
            }

            post("/devices/activate") {
                val req = call.receive<ActivateRequest>()
                // The activation code is the operator secret: a device is
                // enrolled by someone who already has admin access, once.
                if (req.activationCode != s.operatorSecret) {
                    return@post call.respond(HttpStatusCode.Unauthorized, ApiError("invalid activation code"))
                }
                val key = ApiKeys.generate()
                val label = req.label ?: "Device ${System.currentTimeMillis() % 10000}"
                // Single-tenant pilot (§6.2): the schema carries tenant_id for the
                // production shape, but this deployment has exactly one tenant and
                // no principal propagation yet — devices enrol into it explicitly
                // rather than silently into whoever is first.
                val id = s.db.queryOne(
                    """INSERT INTO devices (tenant_id, label, api_key_hash, activated_at)
                       SELECT id, ?, ?, now() FROM tenants ORDER BY created_at LIMIT 1
                       ON CONFLICT (tenant_id, label) DO UPDATE
                         SET api_key_hash = EXCLUDED.api_key_hash, activated_at = now(), retired_at = NULL
                       RETURNING id::text AS id""",
                    label, ApiKeys.hash(key),
                ) { it.str("id") }
                s.audit.append("admin", "DEVICE_ACTIVATED", label, buildJsonObject { put("device_id", id ?: "") })
                // The key is shown exactly once; only its digest is stored.
                call.respond(ActivateResponse(id ?: "", key, null))
            }
        }

        // ------------------------------------------------- authenticated surface
        authenticate("vt") {
            route("/v1") {

                // ---------------------------------------- sync
                get("/sync/bootstrap") {
                    // The bootstrap is the whole shift's data — every shipment,
                    // every package and its signed label. A device's last-seen
                    // time is stamped here because the request resolved its key.
                    val principal = call.principal<Sessions.Principal>()!!
                    call.respond(s.shipments.bootstrap(call.request.queryParameters["site"], s.publicKeys()))
                    if (principal.isDevice) s.db.update(
                        "UPDATE devices SET last_seen_at = now() WHERE id = ?::uuid", principal.subject,
                    )
                }

                // ---------------------------------------- shipments
                get("/shipments") { call.respond(s.shipments.list()) }

                post("/shipments") {
                    if (!call.requireRole(UserRole.ADMIN, UserRole.SUPERVISOR)) return@post
                    val req = call.receive<CreateShipmentRequest>()
                    call.respond(HttpStatusCode.Created, s.shipments.create(req, call.actor(s.sessions)))
                }

                get("/shipments/{ref}") {
                    val ref = call.parameters["ref"]!!
                    s.shipments.get(ref)?.let { call.respond(it) }
                        ?: call.respond(HttpStatusCode.NotFound, ApiError("unknown shipment $ref"))
                }

                get("/shipments/{ref}/report") {
                    val ref = call.parameters["ref"]!!
                    s.shipments.report(ref)?.let { call.respond(it) }
                        ?: call.respond(HttpStatusCode.NotFound, ApiError("unknown shipment $ref"))
                }

                get("/shipments/{ref}/packages") {
                    call.respond(s.shipments.packages(call.parameters["ref"]!!))
                }

                post("/shipments/{ref}/status") {
                    if (!call.requireRole(UserRole.ADMIN, UserRole.SUPERVISOR)) return@post
                    val ref = call.parameters["ref"]!!
                    val status = ShipmentStatus.valueOf(call.request.queryParameters["to"] ?: "OPEN")
                    // §7.3 — the dispatch gate. An incomplete or flagged load cannot
                    // be marked dispatched without an explicit, audited override.
                    if (status == ShipmentStatus.DISPATCHED) {
                        val report = s.shipments.report(ref)
                        val override = call.request.queryParameters["override"] == "true"
                        if (report != null && !report.complete && !override) {
                            return@post call.respond(HttpStatusCode.Conflict, ApiError(
                                "shipment is not complete",
                                "${report.accounted}/${report.expectedCount} accounted; " +
                                    "missing ${report.missing.size}, flagged ${report.flagged.size}. " +
                                    "Resolve, or repeat with override=true (audit-chained).",
                            ))
                        }
                        if (override) {
                            s.audit.append(call.actor(s.sessions), "DISPATCH_OVERRIDE", ref,
                                buildJsonObject { put("reason", call.request.queryParameters["reason"] ?: "") })
                        }
                    }
                    s.shipments.setStatus(ref, status, call.actor(s.sessions))
                    call.respond(s.shipments.get(ref) ?: ApiError("unknown shipment $ref"))
                }

                // ---------------------------------------- labels & packing
                post("/shipments/{ref}/labels:batch") {
                    if (!call.requireRole(UserRole.ADMIN, UserRole.PACKER)) return@post
                    val ref = call.parameters["ref"]!!
                    val req = call.receive<IssueLabelsRequest>()
                    call.respond(s.labels.issueBatch(ref, req, call.actor(s.sessions)))
                }

                post("/packages:close-master") {
                    if (!call.requireRole(UserRole.ADMIN, UserRole.PACKER)) return@post
                    val req = call.receive<CloseMasterRequest>()
                    call.respond(s.labels.closeMaster(req, call.actor(s.sessions)))
                }

                post("/labels/{code}:reprint") {
                    if (!call.requireRole(UserRole.ADMIN, UserRole.PACKER)) return@post
                    call.respond(s.labels.reprint(call.parameters["code"]!!, call.actor(s.sessions)))
                }

                // ---------------------------------------- scans
                post("/scans:batch") {
                    // Devices carry OFFICER; a signed-in officer, supervisor or
                    // admin may also file scans from the web console.
                    if (!call.requireRole(UserRole.ADMIN, UserRole.SUPERVISOR, UserRole.OFFICER)) return@post
                    val principal = call.principal<Sessions.Principal>()
                    val req = call.receive<ScanBatchRequest>()
                    val deviceId = principal?.takeIf { it.isDevice }?.subject
                    // actor() takes X-Officer only from devices; a signed-in
                    // principal is their own officer.
                    call.respond(s.scans.ingest(req.events, deviceId, call.actor(s.sessions)))
                }

                get("/scans") {
                    call.respond(s.scans.recent(
                        call.request.queryParameters["shipment"],
                        call.request.queryParameters["limit"]?.toIntOrNull() ?: 100,
                    ))
                }

                // ---------------------------------------- documents & match
                get("/shipments/{ref}/documents") { call.respond(s.documents.list(call.parameters["ref"]!!)) }

                post("/shipments/{ref}/documents") {
                    if (!call.requireRole(UserRole.ADMIN, UserRole.SUPERVISOR)) return@post
                    val ref = call.parameters["ref"]!!
                    val doc = call.receive<ShipmentDocument>()
                    val actor = call.actor(s.sessions)
                    try {
                        val stored = s.documents.attach(ref, doc, uploadedBy = actor,
                            confirmedBy = call.request.headers["X-Confirmed-By"])
                        // Attaching paperwork changes the answer, so the match and the
                        // finance state are recomputed rather than left stale.
                        s.documents.orderValue(ref)?.let {
                            s.finance.ensureTerms(ref, it, mapOf("on_verified_delivery_pct" to "70", "balance" to "net_30"))
                        }
                        call.respond(HttpStatusCode.Created, stored)
                    } catch (e: DocumentService.DuplicateDocument) {
                        call.respond(HttpStatusCode.Conflict, ApiError("duplicate document", e.message))
                    }
                }

                post("/shipments/{ref}/reconcile") {
                    if (!call.requireRole(UserRole.ADMIN, UserRole.SUPERVISOR, UserRole.OFFICER)) return@post
                    val ref = call.parameters["ref"]!!
                    val atReceipt = call.request.queryParameters["stage"] != "dispatch"
                    val run = s.matcher.runAndStore(ref, atReceipt, call.actor(s.sessions))
                    s.finance.applyReconciliation(ref, run, call.actor(s.sessions))
                    s.risk.recomputeAndStore(ref)
                    call.respond(run)
                }

                get("/shipments/{ref}/reconciliation") {
                    s.matcher.latest(call.parameters["ref"]!!)?.let { call.respond(it) }
                        ?: call.respond(HttpStatusCode.NotFound, ApiError("no reconciliation run yet"))
                }

                // ---------------------------------------- finance
                // Reads are for the people who act on them; writes are split
                // maker/checker/holder exactly as §5.2 assigns them.
                get("/shipments/{ref}/finance") {
                    if (!call.requireRole(
                            UserRole.ADMIN, UserRole.SUPERVISOR, UserRole.FINANCE_MAKER, UserRole.FINANCE_CHECKER,
                        )
                    ) return@get
                    s.finance.state(call.parameters["ref"]!!)?.let { call.respond(it) }
                        ?: call.respond(HttpStatusCode.NotFound, ApiError("no finance terms"))
                }

                get("/finance/queue") {
                    if (!call.requireRole(
                            UserRole.ADMIN, UserRole.SUPERVISOR, UserRole.FINANCE_MAKER, UserRole.FINANCE_CHECKER,
                        )
                    ) return@get
                    val status = FinanceStatus.valueOf(call.request.queryParameters["status"] ?: "HELD")
                    call.respond(s.finance.releaseQueue(status))
                }

                get("/shipments/{ref}/payments") {
                    if (!call.requireRole(
                            UserRole.ADMIN, UserRole.SUPERVISOR, UserRole.FINANCE_MAKER, UserRole.FINANCE_CHECKER,
                        )
                    ) return@get
                    call.respond(s.finance.events(call.parameters["ref"]!!))
                }

                post("/finance/{ref}:release") {
                    // The maker half of maker-checker.
                    if (!call.requireRole(UserRole.ADMIN, UserRole.FINANCE_MAKER)) return@post
                    val ref = call.parameters["ref"]!!
                    val req = runCatching { call.receive<ReleaseRequest>() }.getOrDefault(ReleaseRequest())
                    financeAction(call) { s.finance.requestRelease(ref, call.actor(s.sessions), req.amount, req.note) }
                }

                post("/finance/{ref}:approve") {
                    // The checker half — a different person, enforced again in
                    // FinanceService against the recorded request.
                    if (!call.requireRole(UserRole.ADMIN, UserRole.FINANCE_CHECKER)) return@post
                    val ref = call.parameters["ref"]!!
                    financeAction(call) { s.finance.approveRelease(ref, call.actor(s.sessions)) }
                }

                post("/finance/{ref}:hold") {
                    if (!call.requireRole(UserRole.ADMIN, UserRole.SUPERVISOR)) return@post
                    val ref = call.parameters["ref"]!!
                    val req = call.receive<HoldRequest>()
                    financeAction(call) { s.finance.hold(ref, call.actor(s.sessions), req.amount, req.reason) }
                }

                post("/finance/{ref}:resolve") {
                    // Clearing a hold re-opens money; only a supervisor (or the
                    // finance checker agreeing the numbers moved back) may do it.
                    if (!call.requireRole(UserRole.ADMIN, UserRole.SUPERVISOR)) return@post
                    val ref = call.parameters["ref"]!!
                    val req = call.receive<ResolveRequest>()
                    val override = call.request.queryParameters["override"] == "true"
                    financeAction(call) {
                        s.finance.resolve(ref, call.actor(s.sessions), req.note, override,
                            call.request.queryParameters["reason"])
                    }
                }

                // ---------------------------------------- risk
                get("/risk/shipments/{ref}") {
                    val ref = call.parameters["ref"]!!
                    val fresh = call.request.queryParameters["recompute"] == "true"
                    val score = if (fresh) s.risk.recomputeAndStore(ref) else s.risk.latest("shipment", ref)
                    score?.let { call.respond(it) }
                        ?: call.respond(HttpStatusCode.NotFound, ApiError("no risk score for $ref"))
                }

                get("/risk/parties") { call.respond(s.risk.leaderboard()) }

                get("/risk/parties/{id}") {
                    val id = call.parameters["id"]!!
                    s.risk.latest("party", id)?.let { call.respond(it) }
                        ?: call.respond(HttpStatusCode.NotFound, ApiError("no risk score for party $id"))
                }

                // ---------------------------------------- proof of delivery
                post("/shipments/{ref}/pod") {
                    if (!call.requireRole(UserRole.ADMIN, UserRole.SUPERVISOR, UserRole.OFFICER)) return@post
                    val submission = call.receive<PodSubmission>()
                    call.respond(HttpStatusCode.Created, s.pod.submit(submission, call.actor(s.sessions)))
                }

                get("/pod/{id}") {
                    s.pod.get(call.parameters["id"]!!)?.let { call.respond(it) }
                        ?: call.respond(HttpStatusCode.NotFound, ApiError("unknown certificate"))
                }

                get("/pod/{id}/render") {
                    s.pod.render(call.parameters["id"]!!)?.let { call.respondText(it, ContentType.Text.Plain) }
                        ?: call.respond(HttpStatusCode.NotFound, ApiError("unknown certificate"))
                }

                get("/shipments/{ref}/pod") { call.respond(s.pod.forShipment(call.parameters["ref"]!!)) }

                // ---------------------------------------- agent, audit, ops
                post("/agent/run/{ref}") {
                    if (!call.requireRole(UserRole.ADMIN, UserRole.SUPERVISOR)) return@post
                    val ref = call.parameters["ref"]!!
                    val trigger = call.request.queryParameters["trigger"] ?: "MANUAL"
                    call.respond(s.agent.run(ref, trigger))
                }

                get("/agent/actions") {
                    call.respond(s.agent.actions(call.request.queryParameters["shipment"]))
                }

                get("/agent/explain/{ref}") {
                    call.respondText(s.agent.explainHold(call.parameters["ref"]!!), ContentType.Text.Plain)
                }

                get("/discrepancies") {
                    call.respond(s.shipments.discrepancies(
                        call.request.queryParameters["shipment"],
                        call.request.queryParameters["open"] != "false",
                    ))
                }

                post("/discrepancies/{id}/resolve") {
                    if (!call.requireRole(UserRole.ADMIN, UserRole.SUPERVISOR)) return@post
                    val id = call.parameters["id"]!!
                    val resolution = call.request.queryParameters["resolution"] ?: "RESOLVED"
                    val note = call.request.queryParameters["note"]
                    val actor = call.actor(s.sessions)
                    s.db.update(
                        """UPDATE discrepancies SET resolved_at = now(), resolution = ?, note = ?,
                              resolved_by = (SELECT id FROM users WHERE name = ? LIMIT 1)
                        WHERE id = ?::uuid""",
                        resolution, note, actor, id,
                    )
                    s.audit.append(actor, "DISCREPANCY_RESOLVED", id,
                        buildJsonObject { put("resolution", resolution); put("note", note ?: "") })
                    call.respond(mapOf("id" to id, "resolution" to resolution))
                }

                get("/audit") {
                    if (!call.requireRole(UserRole.ADMIN, UserRole.SUPERVISOR)) return@get
                    call.respond(s.audit.range(
                        call.request.queryParameters["from"]?.toLongOrNull(),
                        call.request.queryParameters["to"]?.toLongOrNull(),
                    ))
                }

                get("/audit/verify") {
                    if (!call.requireRole(UserRole.ADMIN, UserRole.SUPERVISOR)) return@get
                    call.respond(s.audit.verifyChain())
                }

                get("/webhooks/deliveries") {
                    if (!call.requireRole(UserRole.ADMIN)) return@get
                    call.respond(s.webhooks.pending())
                }

                // ---------------------------------------- tamper voice alerts
                // Phone uploads the WAV it just spoke; the bot forwards it as a
                // Telegram voice message to the supervisor chat (§6.4).
                post("/alerts/voice") {
                    if (!call.requireRole(UserRole.ADMIN, UserRole.SUPERVISOR, UserRole.OFFICER)) return@post
                    val upload = call.receive<VoiceAlertUpload>()
                    call.respond(HttpStatusCode.Created, mapOf(
                        "id" to s.voiceAlerts.submit(upload, call.actor(s.sessions)),
                    ))
                }

                get("/alerts/voice/pending") {
                    call.respond(s.voiceAlerts.pending(
                        call.request.queryParameters["limit"]?.toIntOrNull() ?: 20,
                    ))
                }

                post("/alerts/voice/{id}/delivered") {
                    if (!call.requireRole(UserRole.ADMIN, UserRole.SUPERVISOR)) return@post
                    val id = call.parameters["id"]!!
                    val fileId = call.request.queryParameters["tg_file_id"]
                    s.voiceAlerts.markDelivered(id, fileId, call.actor(s.sessions))
                    call.respond(mapOf("id" to id, "delivered" to true))
                }
            }
        }

        // The live feed has one consumer — the admin browser. A browser cannot
        // send headers on a WebSocket handshake, so the session token travels
        // as a query parameter and the socket is closed before any data flows
        // unless it verifies.
        webSocket("/v1/live") {
            val principal = call.request.queryParameters["token"]?.let { s.sessions.verify(it) }
            if (principal == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
                return@webSocket
            }
            liveSockets += this
            try {
                for (frame in incoming) { /* client sends nothing; this keeps the socket open */ }
            } catch (_: ClosedReceiveChannelException) {
            } finally {
                liveSockets -= this
            }
        }
    }
}

/**
 * Finance state errors are the user's problem to fix, not a server fault:
 * "maker-checker: you requested this release" is a 409 with a readable
 * sentence, never a 500.
 */
private suspend inline fun financeAction(
    call: ApplicationCall,
    block: () -> FinanceActionResponse,
) {
    try {
        call.respond(block())
    } catch (e: FinanceService.StateError) {
        call.respond(HttpStatusCode.Conflict, ApiError("finance state", e.message))
    }
}
