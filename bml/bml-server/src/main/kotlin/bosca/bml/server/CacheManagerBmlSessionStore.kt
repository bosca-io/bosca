package bosca.bml.server

import bosca.bml.render.BmlSession
import bosca.bml.render.BmlSessionStore
import bosca.cache.Cache
import bosca.cache.CacheManager
import bosca.cache.StringCacheKey
import bosca.cache.serializers.StringKeySerializer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Distributed BML sessions backed by one cache entry per live-state key. The session id is only an opaque
 * namespace: NATS or Redis owns storage and expiry, while each request-local handle avoids duplicate reads.
 */
class CacheManagerBmlSessionStore(
    private val cacheManager: CacheManager,
    private val ttl: Duration = 30.minutes,
    private val cacheName: String = "bml-sessions",
    private val idGenerator: () -> String = { "sess_" + java.util.UUID.randomUUID().toString().replace("-", "") },
) : BmlSessionStore {

    override val idleTimeout: Duration get() = ttl

    private suspend fun cache(): Cache<String> = cacheManager.maybeAddCache(cacheName, StringKeySerializer, ttl)

    private fun stateKey(id: String, key: String) = StringCacheKey(cacheName, "$id:$key")

    override suspend fun create(): BmlSession {
        val id = idGenerator()
        require(SESSION_ID.matches(id)) { "BML session ids must be opaque `sess_` identifiers" }
        return CacheSession(id, newSession = true)
    }

    override suspend fun get(id: String): BmlSession? =
        id.takeIf(SESSION_ID::matches)?.let { CacheSession(it, newSession = false) }

    private inner class CacheSession(
        override val id: String,
        private val newSession: Boolean,
    ) : BmlSession {
        private val loaded = HashMap<String, String?>()

        override suspend fun get(key: String): String? {
            if (key in loaded) return loaded[key]
            val value = if (newSession) null else cache().get(stateKey(id, key)).value
            loaded[key] = value
            return value
        }

        override suspend fun put(key: String, json: String) {
            cache().put(stateKey(id, key), json)
            loaded[key] = json
        }

        override suspend fun putIfAbsent(key: String, json: String): Boolean {
            if (key in loaded && loaded[key] != null) return false
            val stored = cache().putIfAbsent(stateKey(id, key), json)
            if (stored) loaded[key] = json else loaded.remove(key)
            return stored
        }
    }

    private companion object {
        val SESSION_ID = Regex("sess_[A-Za-z0-9_-]{1,128}")
    }
}
