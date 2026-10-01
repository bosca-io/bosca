package bosca.cache.serializers

import bosca.cache.LongCacheKey
import bosca.cache.LongCacheKeyPart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LongKeySerializerTest {

    // --- toLocalKey ---

    @Test
    fun `toLocalKey creates LongCacheKey with correct fields`() {
        val result = LongKeySerializer.toLocalKey("items", 42L)
        assertIs<LongCacheKey>(result)
        assertEquals("items", result.cacheName)
        assertEquals(42L, result.key)
    }

    @Test
    fun `toLocalKey with zero`() {
        val result = LongKeySerializer.toLocalKey("items", 0L)
        assertEquals(0L, result.key)
    }

    @Test
    fun `toLocalKey with negative value`() {
        val result = LongKeySerializer.toLocalKey("items", -999L)
        assertEquals(-999L, result.key)
    }

    @Test
    fun `toLocalKey with Long MAX_VALUE`() {
        val result = LongKeySerializer.toLocalKey("items", Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, result.key)
    }

    @Test
    fun `toLocalKey with Long MIN_VALUE`() {
        val result = LongKeySerializer.toLocalKey("items", Long.MIN_VALUE)
        assertEquals(Long.MIN_VALUE, result.key)
    }

    // --- toRemoteKey ---

    @Test
    fun `toRemoteKey produces expected format`() {
        val cacheKey = LongKeySerializer.toLocalKey("items", 42L)
        val remote = LongKeySerializer.toRemoteKey(cacheKey)
        assertEquals("$LongCacheKeyPart::items::42", remote)
    }

    // --- toRemoteKeyPrefix ---

    @Test
    fun `toRemoteKeyPrefix produces prefix form`() {
        val cacheKey = LongKeySerializer.toLocalKey("items", 42L)
        val prefix = LongKeySerializer.toRemoteKeyPrefix(cacheKey)
        assertEquals("$LongCacheKeyPart::items::42", prefix)
    }

    // --- fromRemoteKey ---

    @Test
    fun `fromRemoteKey parses key correctly`() {
        val remote = "$LongCacheKeyPart::items::42"
        val result = LongKeySerializer.fromRemoteKey(remote)
        assertIs<LongCacheKey>(result)
        assertEquals("items", result.cacheName)
        assertEquals(42L, result.key)
    }

    @Test
    fun `fromRemoteKey handles negative values`() {
        val remote = "$LongCacheKeyPart::items::-100"
        val result = LongKeySerializer.fromRemoteKey(remote)
        assertEquals(-100L, result.key)
    }

    @Test
    fun `fromRemoteKey handles zero`() {
        val remote = "$LongCacheKeyPart::items::0"
        val result = LongKeySerializer.fromRemoteKey(remote)
        assertEquals(0L, result.key)
    }

    // --- Round-trip ---

    @Test
    fun `round-trip serialization preserves key`() {
        val original = LongKeySerializer.toLocalKey("myCache", 12345L)
        val remote = LongKeySerializer.toRemoteKey(original)
        val restored = LongKeySerializer.fromRemoteKey(remote)
        assertEquals(original.cacheName, restored.cacheName)
        assertEquals(original.key, restored.key)
    }

    @Test
    fun `round-trip with MAX_VALUE preserves key`() {
        val original = LongKeySerializer.toLocalKey("big", Long.MAX_VALUE)
        val remote = LongKeySerializer.toRemoteKey(original)
        val restored = LongKeySerializer.fromRemoteKey(remote)
        assertEquals(Long.MAX_VALUE, restored.key)
    }

    @Test
    fun `round-trip with MIN_VALUE preserves key`() {
        val original = LongKeySerializer.toLocalKey("small", Long.MIN_VALUE)
        val remote = LongKeySerializer.toRemoteKey(original)
        val restored = LongKeySerializer.fromRemoteKey(remote)
        assertEquals(Long.MIN_VALUE, restored.key)
    }
}
