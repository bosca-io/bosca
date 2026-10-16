package bosca.graalvm

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Expiry
import java.time.Duration
import kotlin.test.Test

class CaffeineNativeSmokeTest {

    @Test
    fun `weighted cache supports access expiration`() {
        var now = 0L
        val cache = Caffeine.newBuilder()
            .maximumWeight(100)
            .weigher { _: String, value: String -> value.length }
            .expireAfterAccess(Duration.ofSeconds(10))
            .ticker { now }
            .build<String, String>()

        cache.put("key", "value")
        check(cache.getIfPresent("key") == "value")
        now = Duration.ofSeconds(11).toNanos()
        check(cache.getIfPresent("key") == null)
    }

    @Test
    fun `strong reference cache configurations support eviction and expiration`() {
        for (maximum in 0..2) {
            for (expiration in 0..4) {
                for (options in 0..15) {
                    checkConfiguration(maximum, expiration, options)
                }
            }
        }
    }

    private fun checkConfiguration(maximum: Int, expiration: Int, options: Int) {
        var now = 0L
        val builder = Caffeine.newBuilder().ticker { now }.executor { it.run() }
        when (maximum) {
            1 -> builder.maximumSize(1)
            2 -> builder.maximumWeight(2).weigher { _: String, value: String -> value.length }
        }
        val lifetime = Duration.ofSeconds(10)
        if (expiration == 1 || expiration == 3) builder.expireAfterAccess(lifetime)
        if (expiration == 2 || expiration == 3) builder.expireAfterWrite(lifetime)
        if (expiration == 4) builder.expireAfter(Expiry.creating<String, String> { _, _ -> lifetime })
        if (options and 1 != 0) builder.recordStats()
        if (options and 2 != 0) builder.removalListener<String, String> { _, _, _ -> }
        if (options and 4 != 0) builder.refreshAfterWrite(Duration.ofMinutes(1))
        val cache = if (options and 8 != 0) {
            builder.buildAsync<String, String> { _ -> "loaded" }.synchronous()
        } else {
            builder.build<String, String> { _ -> "loaded" }
        }

        cache.put("first", "aa")
        check(cache.getIfPresent("first") == "aa")
        if (options and 1 != 0) check(cache.stats().hitCount() == 1L)
        cache.put("second", "bb")
        cache.cleanUp()
        if (maximum != 0) check(cache.estimatedSize() == 1L)
        if (expiration != 0) {
            now = lifetime.plusSeconds(1).toNanos()
            check(cache.getIfPresent("first") == null)
            check(cache.getIfPresent("second") == null)
        }
        cache.invalidateAll()
        cache.cleanUp()
        check(cache.estimatedSize() == 0L)
    }

    companion object {
        /** Runs the same cache operations in a standalone native image using BoscaFeature. */
        @JvmStatic
        fun main(args: Array<String>) {
            val test = CaffeineNativeSmokeTest()
            test.`weighted cache supports access expiration`()
            test.`strong reference cache configurations support eviction and expiration`()
            println("Caffeine native smoke test passed: weighted access expiration and 240 cache configurations")
        }
    }
}
