package bosca.cli.harness.egress

import kotlinx.coroutines.runBlocking
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class EgressLookupCacheTest {
    private val session = "0b5f8f2e-5c0a-4d55-9a49-1b6f6d1c2e3f"

    @Test fun `approved lookups are cached per session and token for the TTL and bounded`() = runBlocking {
        var now = 0L
        val calls = mutableListOf<EgressCredentials>()
        val approved = EgressLookupResult.Approved(setOf(EgressDestination("api.example.com", 443)))
        val cache = EgressLookupCache({ credentials -> calls += credentials; if (credentials.token == "denied") EgressLookupResult.Denied else approved },
            ttl = 30.seconds, maxEntries = 2, clock = { now })
        val first = EgressCredentials(session, "token-a")
        assertEquals(approved, cache.lookup(first))
        assertEquals(approved, cache.lookup(first))
        assertEquals(1, calls.size)
        cache.lookup(first.copy(token = "token-b"))
        assertEquals(2, calls.size, "A different token is a different cache entry")
        now += 29.seconds.inWholeNanoseconds
        cache.lookup(first)
        assertEquals(2, calls.size)
        now += 2.seconds.inWholeNanoseconds
        cache.lookup(first)
        assertEquals(3, calls.size, "Entries expire after the TTL")
        cache.lookup(first.copy(token = "denied")); cache.lookup(first.copy(token = "denied"))
        assertEquals(5, calls.size, "Denials are not cached")
    }

    @Test fun `the least recently used session is evicted beyond the bound`() = runBlocking {
        var calls = 0
        val cache = EgressLookupCache({ calls++; EgressLookupResult.Approved(emptySet()) }, maxEntries = 2)
        val (a, b, c) = listOf("a", "b", "c").map { EgressCredentials(session, "token-$it") }
        cache.lookup(a); cache.lookup(b); cache.lookup(a)
        assertEquals(2, calls)
        cache.lookup(c)
        cache.lookup(a)
        assertEquals(3, calls, "The recently used entry survives eviction")
        cache.lookup(b)
        assertEquals(4, calls, "The least recently used entry was evicted")
    }
}
