package bosca.cli.harness.egress

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Caches approved lookups per session and token digest for [ttl], holding at most [maxEntries]
 * sessions. Denied and unavailable results are not cached, so a session that has just become
 * active, or a Bosca outage that has just ended, is observed on the next request.
 */
internal class EgressLookupCache(
    private val delegate: EgressLookup,
    private val ttl: Duration = 30.seconds,
    private val maxEntries: Int = 4096,
    private val clock: () -> Long = System::nanoTime,
) : EgressLookup {
    private class Entry(val result: EgressLookupResult.Approved, val expires: Long)

    private val mutex = Mutex()
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)

    init { require(maxEntries > 0 && ttl.isPositive()) }

    override suspend fun lookup(credentials: EgressCredentials): EgressLookupResult {
        val key = credentials.cacheKey()
        mutex.withLock {
            val entry = entries[key]
            if (entry != null) {
                if (clock() - entry.expires < 0) return entry.result
                entries.remove(key)
            }
        }
        val result = delegate.lookup(credentials)
        if (result is EgressLookupResult.Approved) mutex.withLock {
            entries[key] = Entry(result, clock() + ttl.inWholeNanoseconds)
            while (entries.size > maxEntries) entries.remove(entries.keys.first())
        }
        return result
    }
}
