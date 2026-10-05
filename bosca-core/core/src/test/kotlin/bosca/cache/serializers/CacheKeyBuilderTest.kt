package bosca.cache.serializers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Verifies that [CacheKeyBuilder] and related utility functions correctly
 * assemble cache keys using the `::` separator convention, handle prefix
 * mode, and split keys back into parts.
 */
class CacheKeyBuilderTest {

    // --- buildCacheKey with prefix=false ---

    @Test
    fun `buildCacheKey without prefix builds key with separator between parts`() {
        val key = buildCacheKey(prefix = false) {
            appendKeyPrefix("type", "myCache")
            appendKeyPart("part1")
            appendKeyPart("part2")
        }
        assertEquals("type::myCache::part1::part2", key)
    }

    @Test
    fun `buildCacheKey without prefix includes all parts`() {
        val key = buildCacheKey(prefix = false) {
            appendKeyPrefix("pfx", "cache")
            appendKeyPart("a")
            appendKeyPart("b")
            appendKeyPart("c")
        }
        assertEquals("pfx::cache::a::b::c", key)
    }

    // --- buildCacheKey with prefix=true ---

    @Test
    fun `buildCacheKey with prefix only includes first key part`() {
        val key = buildCacheKey(prefix = true) {
            appendKeyPrefix("type", "myCache")
            appendKeyPart("first")
            appendKeyPart("second")
        }
        assertEquals("type::myCache::first", key)
    }

    @Test
    fun `buildCacheKey with prefix ignores subsequent parts after first`() {
        val key = buildCacheKey(prefix = true) {
            appendKeyPrefix("t", "c")
            appendKeyPart("only-this")
            appendKeyPart("not-this")
            appendKeyPart("nor-this")
        }
        assertEquals("t::c::only-this", key)
    }

    // --- appendKeyPrefix ---

    @Test
    fun `appendKeyPrefix adds part and cacheName with separator`() {
        val key = buildCacheKey(prefix = false) {
            appendKeyPrefix("prefix", "name")
        }
        assertEquals("prefix::name", key)
    }

    // --- appendKeyPart with null ---

    @Test
    fun `appendKeyPart with null appends empty separator when not in prefix mode`() {
        val key = buildCacheKey(prefix = false) {
            appendKeyPrefix("t", "c")
            appendKeyPart(null)
        }
        assertEquals("t::c::", key)
    }

    @Test
    fun `appendKeyPart with null in prefix mode throws error`() {
        assertFailsWith<IllegalStateException> {
            buildCacheKey(prefix = true) {
                appendKeyPrefix("t", "c")
                appendKeyPart(null)
            }
        }
    }

    // --- separateForCacheKey ---

    @Test
    fun `separateForCacheKey splits correctly and excludes first segment`() {
        val parts = "type::cache::part1::part2".separateForCacheKey()
        assertEquals(listOf("cache", "part1", "part2"), parts)
    }

    @Test
    fun `separateForCacheKey with single separator returns one element`() {
        val parts = "prefix::value".separateForCacheKey()
        assertEquals(listOf("value"), parts)
    }

    @Test
    fun `separateForCacheKey handles empty segments from null parts`() {
        val parts = "type::cache::".separateForCacheKey()
        assertEquals(listOf("cache", ""), parts)
    }
}
