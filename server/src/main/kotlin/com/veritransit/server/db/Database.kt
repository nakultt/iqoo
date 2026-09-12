package com.veritransit.server.db

import com.veritransit.server.Config
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.serialization.json.JsonElement
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

/**
 * A thin JDBC layer rather than an ORM.
 *
 * The schema leans on Postgres features an ORM tends to fight: JSONB fact
 * documents, enum types, partial unique indexes, an append-only table guarded by
 * triggers, and views that are the actual read models. Hand-written SQL against
 * those is shorter and clearer than mapping configuration, and it keeps the
 * queries in this repository identical to the ones in `db/README.md`.
 */
class Database(config: Config) : AutoCloseable {

    val dataSource: DataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = config.jdbcUrl
        username = config.dbUser
        password = config.dbPassword
        maximumPoolSize = 12
        minimumIdle = 2
        connectionTimeout = 10_000
        poolName = "veritransit"
        // Scans arrive in batches from the outbox; a short leak threshold turns a
        // stuck ingest into a log line rather than a silently exhausted pool.
        leakDetectionThreshold = 30_000
    })

    override fun close() = (dataSource as HikariDataSource).close()

    fun <T> connection(block: (Connection) -> T): T = dataSource.connection.use(block)

    /** Runs [block] in a transaction, rolling back on any exception. */
    fun <T> transaction(block: (Connection) -> T): T = connection { conn ->
        val previous = conn.autoCommit
        conn.autoCommit = false
        try {
            val result = block(conn)
            conn.commit()
            result
        } catch (t: Throwable) {
            runCatching { conn.rollback() }
            throw t
        } finally {
            conn.autoCommit = previous
        }
    }

    fun <T> query(sql: String, vararg params: Any?, map: (ResultSet) -> T): List<T> =
        connection { it.query(sql, *params, map = map) }

    fun <T> queryOne(sql: String, vararg params: Any?, map: (ResultSet) -> T): T? =
        query(sql, *params, map = map).firstOrNull()

    fun update(sql: String, vararg params: Any?): Int = connection { it.update(sql, *params) }
}

// --------------------------------------------------------------- helpers

fun <T> Connection.query(sql: String, vararg params: Any?, map: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { st ->
        st.bind(*params)
        st.executeQuery().use { rs ->
            buildList { while (rs.next()) add(map(rs)) }
        }
    }

fun <T> Connection.queryOne(sql: String, vararg params: Any?, map: (ResultSet) -> T): T? =
    query(sql, *params, map = map).firstOrNull()

fun Connection.update(sql: String, vararg params: Any?): Int =
    prepareStatement(sql).use { st -> st.bind(*params); st.executeUpdate() }

/**
 * Binds parameters, translating the few types JDBC does not take directly.
 *
 * [Jsonb] and [PgEnum] exist because Postgres will not implicitly cast a `text`
 * parameter into `jsonb` or a user-defined enum; marking the call site is
 * clearer than scattering `::jsonb` casts through the SQL and hoping they match.
 */
fun PreparedStatement.bind(vararg params: Any?): PreparedStatement = apply {
    params.forEachIndexed { i, p ->
        val idx = i + 1
        when (p) {
            null -> setObject(idx, null)
            is Jsonb -> setObject(idx, org.postgresql.util.PGobject().apply {
                type = "jsonb"; value = p.value
            })
            is PgEnum -> setObject(idx, org.postgresql.util.PGobject().apply {
                type = p.type; value = p.value
            })
            is Instant -> setTimestamp(idx, Timestamp.from(p))
            is Enum<*> -> setString(idx, p.name)
            else -> setObject(idx, p)
        }
    }
}

/** A JSONB parameter. */
@JvmInline
value class Jsonb(val value: String) {
    companion object {
        fun of(element: JsonElement): Jsonb = Jsonb(element.toString())
        val EMPTY_OBJECT = Jsonb("{}")
        val EMPTY_ARRAY = Jsonb("[]")
    }
}

/** A value destined for one of the schema's enum types. */
data class PgEnum(val type: String, val value: String)

// ResultSet conveniences that keep the mapping code readable.
fun ResultSet.str(col: String): String = getString(col) ?: ""
fun ResultSet.strOrNull(col: String): String? = getString(col)
fun ResultSet.int(col: String): Int = getInt(col)
fun ResultSet.intOrNull(col: String): Int? = getObject(col)?.let { getInt(col) }
fun ResultSet.dbl(col: String): Double = getDouble(col)
fun ResultSet.dblOrNull(col: String): Double? = getObject(col)?.let { getDouble(col) }
fun ResultSet.bool(col: String): Boolean = getBoolean(col)
fun ResultSet.instantOrNull(col: String): Instant? = getTimestamp(col)?.toInstant()
fun ResultSet.isoOrNull(col: String): String? = getTimestamp(col)?.toInstant()?.toString()
fun ResultSet.iso(col: String): String = isoOrNull(col) ?: ""
