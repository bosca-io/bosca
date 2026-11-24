package bosca.counter.postgres

import bosca.counter.Counter
import bosca.db.ConnectionPool
import bosca.db.use
import bosca.db.DatabaseDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/**
 * Counter rows in `infrastructure.counters`. [increment] is one upsert per call; [add] accumulates
 * additions in memory and applies them every [flushInterval] in one upsert per batch, so write-heavy
 * metrics do not take a pooled connection and a row lock per addition. Additions still buffered when the
 * process stops abruptly are lost; [close] applies them on orderly shutdown.
 */
class PostgresCounter(
    private val pool: ConnectionPool,
    private val retention: Duration = 7.days,
    private val flushInterval: Duration = 1.seconds,
) : Counter {
    private val pending = ConcurrentHashMap<String, Long>()
    private val scope = CoroutineScope(SupervisorJob() + DatabaseDispatcher)

    init {
        require(retention.isPositive() && retention.isFinite() && flushInterval.isPositive())
        scope.launch {
            while (isActive) {
                delay(flushInterval)
                try {
                    flushPending()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Failed to apply buffered counter additions; retrying", e)
                }
            }
        }
    }

    override suspend fun add(key: String, by: Long) {
        pending.merge(key, by, Long::plus)
    }

    /** Applies buffered [add] calls; additions that fail to apply are restored for the next flush. */
    internal suspend fun flushPending() {
        val deltas = buildMap { for (key in pending.keys) pending.remove(key)?.let { put(key, it) } }.toSortedMap()
        if (deltas.isEmpty()) return
        try {
            pool.connection().use { manager ->
                // Sorted keys keep row-lock order consistent across replicas flushing overlapping buckets.
                manager.useStatement("""
                    insert into infrastructure.counters(key, value, expires)
                    select k, v, clock_timestamp() + (? * interval '1 millisecond') from unnest(?::text[], ?::bigint[]) with ordinality as t(k, v, n) order by n
                    on conflict (key) do update set
                        value = (case when infrastructure.counters.expires > clock_timestamp() then infrastructure.counters.value else 0 end) + excluded.value,
                        expires = excluded.expires
                """.trimIndent()) { stmt ->
                    stmt.setLong(1, retention.inWholeMilliseconds)
                    stmt.setArray(2, stmt.connection.createArrayOf("text", deltas.keys.toTypedArray()))
                    stmt.setArray(3, stmt.connection.createArrayOf("bigint", deltas.values.toTypedArray()))
                    stmt.executeUpdate()
                }
            }
        } catch (e: Exception) {
            deltas.forEach { (key, delta) -> pending.merge(key, delta, Long::plus) }
            throw e
        }
    }

    /** Stops periodic flushing and applies the remaining buffered additions. */
    suspend fun close() {
        scope.coroutineContext[Job]?.cancelAndJoin()
        flushPending()
    }

    override suspend fun increment(key: String, by: Long): Long = pool.connection().use { manager ->
        manager.useStatement("""
            insert into infrastructure.counters(key, value, expires) values (?, ?, clock_timestamp() + (? * interval '1 millisecond'))
            on conflict (key) do update set
                value = (case when infrastructure.counters.expires > clock_timestamp() then infrastructure.counters.value else 0 end) + excluded.value,
                expires = excluded.expires returning value
        """.trimIndent()) { stmt ->
            stmt.setString(1, key); stmt.setLong(2, by); stmt.setLong(3, retention.inWholeMilliseconds)
            stmt.executeQuery().use { it.next(); it.getLong(1) }
        }
    }
    override suspend fun get(key: String): Long = pool.connection().use { manager ->
        manager.useStatement("select value from infrastructure.counters where key = ? and expires > clock_timestamp()") { stmt ->
            stmt.setString(1, key); stmt.executeQuery().use { if (it.next()) it.getLong(1) else 0 }
        }
    }
    override suspend fun get(keys: List<String>): Map<String, Long> {
        if (keys.isEmpty()) return emptyMap()
        val totals = keys.associateWith { 0L }.toMutableMap()
        pool.connection().use { manager -> manager.useStatement("select key, value from infrastructure.counters where key in (${keys.joinToString { "?" }}) and expires > clock_timestamp()") { stmt ->
            keys.forEachIndexed { i, key -> stmt.setString(i + 1, key) }
            stmt.executeQuery().use { result -> while (result.next()) totals[result.getString(1)] = result.getLong(2) }
        } }
        return totals
    }
    /** Reclaims expired buckets. Expired values are invisible even before cleanup. */
    suspend fun evictExpiredItems() { pool.connection().use { m -> m.useStatement("delete from infrastructure.counters where expires <= clock_timestamp()") { it.executeUpdate() } } }

    private companion object {
        private val log = LoggerFactory.getLogger(PostgresCounter::class.java)
    }
}
