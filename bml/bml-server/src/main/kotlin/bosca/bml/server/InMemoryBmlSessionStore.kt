package bosca.bml.server

import bosca.bml.render.BmlSession
import bosca.bml.render.BmlSessionStore
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The default [BmlSessionStore]: an in-memory, bounded, TTL'd `ConcurrentHashMap` of server-session live-island
 * sessions. Zero-config and correct for single-instance / dev, but a session is lost if a load balancer
 * routes the client to a different instance — a multi-instance deployment should supply a distributed
 * [BmlSessionStore] (Redis / NATS KV via bosca-core's `CacheManager`, a DB, …) to [BmlServer] instead.
 *
 * Bounded by session count (oldest-access evicted past [maxSessions]) and idle TTL ([ttlMillis]). Page-scoped
 * entries have their own [maxPageStatesPerSession] bound and [pageStateTtlMillis]; site-scoped entries live for
 * the session. Thread-safe via [ConcurrentHashMap]; [clock]/[idGenerator] are injectable for tests. Operations
 * are non-blocking, so the `suspend` contract methods just return directly.
 */
class InMemoryBmlSessionStore(
    private val maxSessions: Int = 10_000,
    private val ttlMillis: Long = 30 * 60 * 1000L,
    private val maxPageStatesPerSession: Int = 256,
    private val pageStateTtlMillis: Long = ttlMillis,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { "sess_" + java.util.UUID.randomUUID().toString().replace("-", "") },
) : BmlSessionStore {

    override val idleTimeout: Duration = ttlMillis.milliseconds

    private class Entry(val session: BmlSession, @Volatile var lastAccess: Long)

    private val sessions = ConcurrentHashMap<String, Entry>()

    override suspend fun create(): BmlSession {
        evictExpired()
        if (sessions.size >= maxSessions) evictOldest()
        val id = idGenerator()
        val session = StoredSession(id, maxPageStatesPerSession, pageStateTtlMillis, clock)
        sessions[id] = Entry(session, clock())
        return session
    }

    override suspend fun get(id: String): BmlSession? {
        val entry = sessions[id] ?: return null
        if (clock() - entry.lastAccess > ttlMillis) {
            sessions.remove(id)
            return null
        }
        entry.lastAccess = clock()
        return entry.session
    }

    /** Current live session count (after pruning expired) — for tests/metrics. */
    fun size(): Int {
        evictExpired()
        return sessions.size
    }

    private fun evictExpired() {
        val now = clock()
        sessions.entries.removeIf { now - it.value.lastAccess > ttlMillis }
    }

    private fun evictOldest() {
        sessions.entries.minByOrNull { it.value.lastAccess }?.let { sessions.remove(it.key) }
    }

    private class StoredSession(
        override val id: String,
        private val maxPageStates: Int,
        private val pageStateTtlMillis: Long,
        private val clock: () -> Long,
    ) : BmlSession {
        private class Value(val json: String, @Volatile var lastAccess: Long)

        private val values = ConcurrentHashMap<String, Value>()

        override suspend fun get(key: String): String? {
            prunePageStates()
            val value = values[key] ?: return null
            value.lastAccess = clock()
            return value.json
        }

        override suspend fun put(key: String, json: String) {
            values[key] = Value(json, clock())
            prunePageStates()
        }

        override suspend fun putIfAbsent(key: String, json: String): Boolean {
            val now = clock()
            val existing = values.putIfAbsent(key, Value(json, now))
            existing?.lastAccess = now
            prunePageStates()
            return existing == null
        }

        private fun prunePageStates() {
            val now = clock()
            values.entries.removeIf { (key, value) ->
                key.startsWith(PAGE_STATE_PREFIX) && now - value.lastAccess > pageStateTtlMillis
            }
            while (true) {
                val pages = values.entries.filter { it.key.startsWith(PAGE_STATE_PREFIX) }
                if (pages.size <= maxPageStates) return
                val oldest = pages.minByOrNull { it.value.lastAccess } ?: return
                values.remove(oldest.key, oldest.value)
            }
        }

        private companion object {
            const val PAGE_STATE_PREFIX = "page."
        }
    }
}
