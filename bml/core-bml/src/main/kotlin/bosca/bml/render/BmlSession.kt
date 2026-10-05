package bosca.bml.render

/**
 * Per-client server-side session holding `scope="server-session"` live-island state — the serialized model for
 * each state key. Created on page render (identified to the client only by an opaque HttpOnly cookie; the
 * **values never reach the client**) and looked up on each `@click` action so the server can load, mutate,
 * and persist the model. Minted by a [BmlSessionStore]; the default in-memory store is `InMemoryBmlSessionStore`
 * in `bml-server`, but a distributed store (Redis / NATS KV / DB) can be supplied instead.
 *
 * [get]/[put] are `suspend` so a distributed backend can do real I/O without blocking a thread.
 */
interface BmlSession {
    /** Opaque session id (the only thing about server state that reaches the client, via the cookie). */
    val id: String

    /** The stored serialized model for [key], or null if absent. */
    suspend fun get(key: String): String?

    /** Store/replace the serialized model for [key]. */
    suspend fun put(key: String, json: String)

    /** Store [json] only when [key] is absent, returning whether it was stored. */
    suspend fun putIfAbsent(key: String, json: String): Boolean
}
