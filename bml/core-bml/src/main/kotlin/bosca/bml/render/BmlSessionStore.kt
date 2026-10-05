package bosca.bml.render

import kotlin.time.Duration

/**
 * Pluggable store of [BmlSession]s for `scope="server-session"` live-island state. The contract is intentionally
 * tiny — mint a session, look one up by id — so the backend is swappable:
 *
 * - **`InMemoryBmlSessionStore`** (bml-server, the default) — a bounded, TTL'd `ConcurrentHashMap`. Fine for
 *   single-instance / dev, but a session is lost if a load balancer routes the client to another instance.
 * - A **distributed** store — Redis / NATS KV (e.g. via bosca-core's `CacheManager`), a database, or any
 *   shared backend — keeps sessions consistent across instances. Supply one to [bosca.bml.server.BmlServer].
 *
 * [get]/[create] are `suspend` so a distributed implementation can do real I/O without blocking a thread
 * (Bosca's "every I/O boundary is a suspend function" rule).
 */
interface BmlSessionStore {
    /** Idle lifetime used by both stored state and the opaque browser cookie. */
    val idleTimeout: Duration

    /** A session handle for a recognized opaque [id], or null when the store rejects the id. */
    suspend fun get(id: String): BmlSession?

    /** Mint a new, empty session with a fresh opaque id. */
    suspend fun create(): BmlSession
}
