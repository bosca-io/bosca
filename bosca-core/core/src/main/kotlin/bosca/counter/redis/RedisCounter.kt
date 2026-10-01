package bosca.counter.redis

import bosca.counter.Counter
import bosca.redis.RedisConnectionPool
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.api.coroutines
import kotlinx.coroutines.flow.toList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * Redis-backed [Counter]: `INCRBY` for the atomic increment, `MGET` for a whole-window read. Every key
 * gets a [retention] TTL (re)set on write, so time-bucketed counters (per-minute, per-hour) expire on
 * their own instead of accumulating forever.
 */
@OptIn(ExperimentalLettuceCoroutinesApi::class)
class RedisCounter(
    private val connections: RedisConnectionPool,
    private val retention: Duration = DEFAULT_RETENTION,
) : Counter {

    override suspend fun increment(key: String, by: Long): Long {
        val connection = connections.connection()
        try {
            val commands = connection.coroutines()
            val total = commands.incrby(key, by) ?: 0L
            commands.expire(key, retention.inWholeSeconds)
            return total
        } finally {
            connections.release(connection)
        }
    }

    override suspend fun get(key: String): Long {
        val connection = connections.connection()
        try {
            return connection.coroutines().get(key)?.toLongOrNull() ?: 0L
        } finally {
            connections.release(connection)
        }
    }

    override suspend fun get(keys: List<String>): Map<String, Long> {
        if (keys.isEmpty()) return emptyMap()
        val connection = connections.connection()
        try {
            val totals = LinkedHashMap<String, Long>(keys.size)
            keys.forEach { totals[it] = 0L }
            connection.coroutines().mget(*keys.toTypedArray()).toList().forEach { kv ->
                if (kv.hasValue()) totals[kv.key] = kv.value.toLongOrNull() ?: 0L
            }
            return totals
        } finally {
            connections.release(connection)
        }
    }

    companion object {
        /** Bucketed counters only feed short-window rate reads, so a week of retention is ample. */
        val DEFAULT_RETENTION: Duration = 7.days
    }
}
