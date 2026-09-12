package com.veritransit.dashboard

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.time.Instant
import java.util.concurrent.Executors

/**
 * VeriTransit web dashboard — the back-office view of the field vault.
 *
 * Serves, with no framework beyond the JDK's built-in HTTP server:
 *   GET  /                    the consignment board (shipped / held / awaiting)
 *   GET  /report/<id>.pdf     the consignment's deterministic PDF report
 *   GET  /api/records         the vault as JSON
 *   POST /api/records         ingest one committed field inspection (upsert by
 *                             id) — this is how the officer app's "Confirm
 *                             Verdict & Sign" updates the board, flipping the
 *                             consignment to shipped with an OK TO PAY badge
 *                             and publishing its report.
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
        println("  sample PDF http://localhost:$port/report/VT-2024-8838.pdf")
        println("  ingest     POST http://localhost:$port/api/records")
        Thread.currentThread().join()
        server.stop(0)
    }

    private fun route(exchange: HttpExchange, vault: Vault) {
        val path = exchange.requestURI.path.trimEnd('/')
        when {
            path.isEmpty() || path == "/index.html" ->
                respond(exchange, 200, Pages.dashboard(vault, Instant.now()).toByteArray(), "text/html; charset=utf-8")

            path.startsWith("/report/") && path.endsWith(".pdf") -> {
                val id = path.removePrefix("/report/").removeSuffix(".pdf")
                val record = vault.find(id)
                if (record == null) {
                    respond(exchange, 404, "No consignment '$id' in the vault\n".toByteArray(), "text/plain")
                } else {
                    respond(exchange, 200, PdfReport.render(record), "application/pdf")
                }
            }

            else -> respond(exchange, 404, "Not found\n".toByteArray(), "text/plain")
        }
    }

    private fun api(exchange: HttpExchange, vault: Vault) {
        when (exchange.requestMethod) {
            "GET" -> respond(
                exchange,
                200,
                json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(InspectionRecord.serializer()),
                    vault.all,
                ).toByteArray(),
                "application/json",
            )

            "POST" -> {
                val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                val record = runCatching { json.decodeFromString(InspectionRecord.serializer(), body) }.getOrNull()
                if (record == null) {
                    respond(exchange, 400, """{"ok":false,"error":"unparseable record"}""".toByteArray(), "application/json")
                    return
                }
                vault.upsert(record)
                val state = ShipState.of(record.verdict)
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
