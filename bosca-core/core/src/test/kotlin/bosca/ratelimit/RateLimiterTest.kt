package bosca.ratelimit

import bosca.cache.Cache
import bosca.cache.CacheKey
import bosca.cache.CacheKeySerializer
import bosca.cache.CacheManager
import bosca.cache.CacheValue
import bosca.cache.StringCacheKey
import bosca.cache.serializers.StringKeySerializer
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RateLimiterTest {

    private val cacheManager = mockk<CacheManager>()
    private val cache = InMemoryStringCache()

    private lateinit var limiter: RateLimiter

    @BeforeTest
    fun setup() {
        coEvery { cacheManager.maybeAddCache("test:rate-limit", StringKeySerializer, any()) } returns cache
        limiter = RateLimiter(cacheManager, maxAttempts = 3, cacheName = "test:rate-limit")
    }

    @AfterTest
    fun tearDown() = runTest { cache.clear() }

    @Test
    fun `a fresh key is not rate limited`() = runTest {
        assertFalse(limiter.isRateLimited("k"))
    }

    @Test
    fun `a key becomes limited only once it reaches the cap`() = runTest {
        limiter.recordFailure("k")
        limiter.recordFailure("k")
        assertFalse(limiter.isRateLimited("k")) // 2 < 3
        limiter.recordFailure("k")
        assertTrue(limiter.isRateLimited("k")) // 3 >= 3
    }

    @Test
    fun `keys are counted independently`() = runTest {
        repeat(3) { limiter.recordFailure("a") }
        assertTrue(limiter.isRateLimited("a"))
        assertFalse(limiter.isRateLimited("b"))
    }

    @Test
    fun `the key is matched case-insensitively`() = runTest {
        repeat(3) { limiter.recordFailure("Owner@Example.com") }
        assertTrue(limiter.isRateLimited("owner@example.com"))
    }

    @Test
    fun `recordSuccess decrements and lifts the limit, removing the key at zero`() = runTest {
        repeat(3) { limiter.recordFailure("k") }
        assertTrue(limiter.isRateLimited("k"))

        limiter.recordSuccess("k") // 3 -> 2, below the cap again
        assertFalse(limiter.isRateLimited("k"))

        limiter.recordSuccess("k") // 2 -> 1
        limiter.recordSuccess("k") // 1 -> 0, key removed
        assertFalse(limiter.isRateLimited("k"))
    }

    @Test
    fun `recordSuccess on an unknown key is a no-op`() = runTest {
        limiter.recordSuccess("never-seen")
        assertFalse(limiter.isRateLimited("never-seen"))
    }

    @Test
    fun `null and malformed cached counts recover as zero`() = runTest {
        val nullKey = StringCacheKey("test:rate-limit", "null")
        cache.put(nullKey, null)
        assertFalse(limiter.isRateLimited("null"))
        limiter.recordFailure("null")
        assertFalse(limiter.isRateLimited("null"))
        limiter.recordSuccess("null")

        val malformedKey = StringCacheKey("test:rate-limit", "malformed")
        cache.put(malformedKey, "not-a-number")
        assertFalse(limiter.isRateLimited("malformed"))
        limiter.recordFailure("malformed")
        assertFalse(limiter.isRateLimited("malformed"))
        cache.put(malformedKey, "not-a-number")
        limiter.recordSuccess("malformed")
        assertFalse(limiter.isRateLimited("malformed"))
    }
}

/** Minimal in-memory [Cache] so the limiter's counter ticks for real under test. */
private class InMemoryStringCache : Cache<String> {
    private val map = mutableMapOf<String, String?>()
    override val keySerializer: CacheKeySerializer<String> = StringKeySerializer
    override val estimatedSize: Long get() = map.size.toLong()

    private fun rk(key: CacheKey<String>) = key.toRemoteKey()
    private fun cv(v: String?, e: Boolean) = object : CacheValue {
        override val value = v
        override val exists = e
    }

    override suspend fun get(key: CacheKey<String>): CacheValue {
        val k = rk(key)
        return if (map.containsKey(k)) cv(map[k], true) else cv(null, false)
    }

    override suspend fun getBatch(keys: List<CacheKey<String>>): List<CacheValue> = keys.map { get(it) }

    override suspend fun put(key: CacheKey<String>, value: String?) {
        map[rk(key)] = value
    }

    override suspend fun putBatch(entries: List<Pair<CacheKey<String>, String?>>) {
        entries.forEach { put(it.first, it.second) }
    }

    override suspend fun remove(key: CacheKey<String>, keyPrefix: Boolean): CacheValue? {
        val k = rk(key)
        return if (map.containsKey(k)) cv(map.remove(k), true) else null
    }

    override suspend fun clear() {
        map.clear()
    }

    override suspend fun evictExpiredItems() {}
}
