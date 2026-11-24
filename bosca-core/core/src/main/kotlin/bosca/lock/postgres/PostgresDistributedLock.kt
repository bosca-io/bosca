package bosca.lock.postgres

import bosca.db.ConnectionPool
import bosca.db.use
import bosca.lock.DistributedLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.time.TimeSource

class PostgresDistributedLock(private val pool: ConnectionPool, private val name: String) : DistributedLock {
    /** Token used to fence database updates performed while this lease is held. */
    val owner: UUID = UUID.randomUUID()
    @Volatile private var deadline: kotlin.time.TimeMark? = null
    override val isHeld: Boolean get() = deadline?.hasNotPassedNow() == true
    override suspend fun tryAcquire(ttlMillis: Long): Boolean {
        require(ttlMillis > 0)
        val started = TimeSource.Monotonic.markNow()
        val acquired = pool.connection().use { manager ->
            manager.useStatement("""
                insert into infrastructure.locks(name, owner, expires) values (?, ?, clock_timestamp() + (? * interval '1 millisecond'))
                on conflict (name) do update set owner = excluded.owner, expires = excluded.expires
                where infrastructure.locks.expires <= clock_timestamp()
            """.trimIndent()) { stmt ->
                stmt.setString(1, name); stmt.setObject(2, owner); stmt.setLong(3, ttlMillis); stmt.executeUpdate() > 0
            }
        }
        if (acquired) deadline = started + kotlin.time.Duration.parse("${ttlMillis}ms")
        return acquired && isHeld
    }
    override suspend fun acquire(ttlMillis: Long, waitTimeoutMillis: Long?, retryDelayMillis: Long): Boolean {
        require(waitTimeoutMillis == null || waitTimeoutMillis >= 0); require(retryDelayMillis > 0)
        val start = TimeSource.Monotonic.markNow()
        do {
            if (tryAcquire(ttlMillis)) return true
            val remaining = waitTimeoutMillis?.minus(start.elapsedNow().inWholeMilliseconds)
            if (remaining != null && remaining <= 0) return false
            delay(if (remaining == null) retryDelayMillis else minOf(retryDelayMillis, remaining))
        } while (true)
    }
    override suspend fun renew(ttlMillis: Long): Boolean {
        require(ttlMillis > 0)
        val started = TimeSource.Monotonic.markNow()
        val renewed = pool.connection().use { manager ->
            manager.useStatement("update infrastructure.locks set expires = clock_timestamp() + (? * interval '1 millisecond') where name = ? and owner = ? and expires > clock_timestamp()") { stmt ->
                stmt.setLong(1, ttlMillis); stmt.setString(2, name); stmt.setObject(3, owner); stmt.executeUpdate() > 0
            }
        }
        deadline = if (renewed) started + kotlin.time.Duration.parse("${ttlMillis}ms") else null
        return renewed && isHeld
    }
    override suspend fun release(): Boolean {
        try {
            return pool.connection().use { manager -> manager.useStatement("delete from infrastructure.locks where name = ? and owner = ?") { stmt ->
                stmt.setString(1, name); stmt.setObject(2, owner); stmt.executeUpdate() > 0
            } }
        } finally { deadline = null }
    }
    override suspend fun <T> withLock(ttlMillis: Long, waitTimeoutMillis: Long?, retryDelayMillis: Long, block: suspend () -> T): T? {
        if (!acquire(ttlMillis, waitTimeoutMillis, retryDelayMillis)) return null
        try { return block() } finally { withContext(NonCancellable) { release() } }
    }
}
