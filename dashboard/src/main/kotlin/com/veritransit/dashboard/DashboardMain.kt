package com.veritransit.dashboard

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.veritransit.dashboard.documents.ConsignmentFacts
import com.veritransit.dashboard.GoodsImage
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.Executors

/**
 * VeriTransit web dashboard — the back-office view of the dock receipt log.
 *
 * Serves, with no framework beyond the JDK's built-in HTTP server:
 *   GET  /                    the receipts board (accepted / held / awaiting)
 *   GET  /report/<id>.pdf     the receipt's deterministic PDF report
 *   GET  /api/records         the receipt log as JSON
 *   POST /api/records         ingest one committed dock receipt (upsert by id)
 *                             — this is how the app's "File Goods-Received
 *                             Note" updates the board, flipping the delivery to
 *                             accepted with an OK TO PAY badge and publishing
 *                             its report.
 */
object DashboardMain {

    private val json = Json { ignoreUnknownKeys = true }

    fun start(port: Int): HttpServer {
        val vault = Vault.seeded()
        val server = HttpServer.create(InetSocketAddress(port), 0)
        server.executor = Executors.newFixedThreadPool(4)

        server.createContext("/") { exchange -> route(exchange, vault) }
        server.createContext("/api/records") { exchange -> api(exchange, vault) }

        server.start()
        return server
    }

    fun run(port: Int) {
        val server = start(port)
        println("VeriTransit dashboard ready on http://0.0.0.0:$port")
        println("  board      http://localhost:$port/")
        println("  sample PDF http://localhost:$port/report/GRN-2025-8838.pdf")
        println("  ingest     POST http://localhost:$port/api/records")
        Thread.currentThread().join()
        server.stop(0)
    }

    private fun route(exchange: HttpExchange, vault: Vault) {
        val path = exchange.requestURI.path.trimEnd('/')
        when {
            path.isEmpty() || path == "/index.html" ->
                respond(exchange, 200, Pages.dashboard(vault, Instant.now()).toByteArray(), "text/html; charset=utf-8")

            path.startsWith("/receipt/") -> {
                val id = path.removePrefix("/receipt/")
                val record = vault.find(id)
                if (record == null) {
                    respond(exchange, 404, "No receipt '$id' in the log\n".toByteArray(), "text/plain")
                } else {
                    // The form answers on top of what the receipt implied; every
                    // unanswered fact stays unknown for the resolver.
                    val facts = ConsignmentFacts.fromRecord(record, queryParams(exchange))
                    respond(exchange, 200, Pages.receiptPage(record, facts, Instant.now()).toByteArray(), "text/html; charset=utf-8")
                }
            }

            path.startsWith("/img/") -> {
                // Frozen goods-family photographs, committed under resources —
                // never fetched at request time, so nothing here varies.
                val name = path.removePrefix("/img/").removeSuffix(".jpg").removePrefix("report-")
                val bytes = GoodsImage.entries
                    .firstOrNull { it.name.equals(name, ignoreCase = true) }
                    ?.bytes()
                if (bytes == null) {
                    respond(exchange, 404, "No such picture\n".toByteArray(), "text/plain")
                } else {
                    respond(exchange, 200, bytes, "image/jpeg")
                }
            }

            path.startsWith("/report/") && path.endsWith(".pdf") -> {
                val id = path.removePrefix("/report/").removeSuffix(".pdf")
                val record = vault.find(id)
                if (record == null) {
                    respond(exchange, 404, "No receipt '$id' in the log\n".toByteArray(), "text/plain")
                } else {
                    // Render in place — the board's viewer embeds this response,
                    // and a direct open shows the report rather than a download.
                    exchange.responseHeaders.add(
                        "Content-Disposition",
                        "inline; filename=\"${id.replace("\"", "")}.pdf\"",
                    )
                    respond(exchange, 200, PdfReport.render(record), "application/pdf")
                }
            }

            else -> respond(exchange, 404, "Not found\n".toByteArray(), "text/plain")
        }
    }

    /** `?a=1&b=x` off the request URI; the paperwork page's answers ride here. */
    private fun queryParams(exchange: HttpExchange): Map<String, String> =
        exchange.requestURI.rawQuery
            ?.split('&')
            ?.mapNotNull { pair ->
                val i = pair.indexOf('=')
                if (i <= 0) {
                    null
                } else {
                    URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8) to
                        URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8)
                }
            }
            ?.toMap()
            ?: emptyMap()

    private fun api(exchange: HttpExchange, vault: Vault) {
        when (exchange.requestMethod) {
            "GET" -> respond(
                exchange,
                200,
                json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(ReceivingRecord.serializer()),
                    vault.all,
                ).toByteArray(),
                "application/json",
            )

            "POST" -> {
                val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                val record = runCatching { json.decodeFromString(ReceivingRecord.serializer(), body) }.getOrNull()
                if (record == null) {
                    respond(exchange, 400, """{"ok":false,"error":"unparseable record"}""".toByteArray(), "application/json")
                    return
                }
                vault.upsert(record)
                val state = ShipState.of(record.outcome)
                println("ingest: ${record.id} -> ${state.label} (${state.paymentLabel})")
                respond(
                    exchange,
                    200,
                    """{"ok":true,"id":"${record.id}","state":"${state.name}","payment":"${state.paymentLabel}"}""".toByteArray(),
                    "application/json",
                )
            }

            else -> respond(exchange, 405, "Method not allowed\n".toByteArray(), "text/plain")
        }
    }

    private fun respond(exchange: HttpExchange, status: Int, bytes: ByteArray, type: String) {
        exchange.responseHeaders.add("Content-Type", type)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}

fun main(args: Array<String>) {
    val port = System.getenv("VERITRANSIT_DASHBOARD_PORT")?.toIntOrNull()
        ?: args.firstOrNull()?.toIntOrNull()
        ?: 8080
    DashboardMain.run(port)
}
