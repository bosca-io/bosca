package bosca.cache.serializers

import bosca.cache.StringCacheKey
import bosca.cache.StringCacheKeyPart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StringKeySerializerTest {

    // --- toLocalKey ---

    @Test
    fun `toLocalKey creates StringCacheKey with correct fields`() {
        val result = StringKeySerializer.toLocalKey("sessions", "abc123")
        assertIs<StringCacheKey>(result)
        assertEquals("sessions", result.cacheName)
        assertEquals("abc123", result.key)
    }

    @Test
    fun `toLocalKey with empty string key`() {
        val result = StringKeySerializer.toLocalKey("sessions", "")
        assertEquals("", result.key)
    }

    @Test
    fun `toLocalKey with special characters`() {
        val result = StringKeySerializer.toLocalKey("cache", "key-with_special.chars@123")
        assertEquals("key-with_special.chars@123", result.key)
    }

    // --- toRemoteKey ---

    @Test
    fun `toRemoteKey produces expected format`() {
        val cacheKey = StringKeySerializer.toLocalKey("sessions", "abc123")
        val remote = StringKeySerializer.toRemoteKey(cacheKey)
        assertEquals("$StringCacheKeyPart::sessions::abc123", remote)
    }

    // --- toRemoteKeyPrefix ---

    @Test
    fun `toRemoteKeyPrefix produces prefix form`() {
        val cacheKey = StringKeySerializer.toLocalKey("sessions", "abc123")
        val prefix = StringKeySerializer.toRemoteKeyPrefix(cacheKey)
        assertEquals("$StringCacheKeyPart::sessions::abc123", prefix)
    }

    // --- fromRemoteKey ---

    @Test
    fun `fromRemoteKey parses key correctly`() {
        val remote = "$StringCacheKeyPart::sessions::abc123"
        val result = StringKeySerializer.fromRemoteKey(remote)
        assertIs<StringCacheKey>(result)
        assertEquals("sessions", result.cacheName)
        assertEquals("abc123", result.key)
    }

    @Test
    fun `fromRemoteKey handles empty value`() {
        val remote = "$StringCacheKeyPart::sessions::"
        val result = StringKeySerializer.fromRemoteKey(remote)
        assertEquals("", result.key)
    }

    // --- Round-trip ---

    @Test
    fun `round-trip serialization preserves key`() {
        val original = StringKeySerializer.toLocalKey("myCache", "my-key-value")
        val remote = StringKeySerializer.toRemoteKey(original)
        val restored = StringKeySerializer.fromRemoteKey(remote)
        assertEquals(original.cacheName, restored.cacheName)
        assertEquals(original.key, restored.key)
    }

    @Test
    fun `round-trip with empty key preserves value`() {
        val original = StringKeySerializer.toLocalKey("myCache", "")
        val remote = StringKeySerializer.toRemoteKey(original)
        val restored = StringKeySerializer.fromRemoteKey(remote)
        assertEquals("", restored.key)
    }

    @Test
    fun `round-trip with long key preserves value`() {
        val longKey = "a".repeat(1000)
        val original = StringKeySerializer.toLocalKey("myCache", longKey)
        val remote = StringKeySerializer.toRemoteKey(original)
        val restored = StringKeySerializer.fromRemoteKey(remote)
        assertEquals(longKey, restored.key)
    }
}
