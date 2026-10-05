package bosca.cache.serializers

import bosca.cache.UnitCacheKey
import bosca.cache.UnitCacheKeyPart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class UnitKeySerializerTest {

    // --- toLocalKey ---

    @Test
    fun `toLocalKey creates UnitCacheKey with correct cache name`() {
        val result = UnitKeySerializer.toLocalKey("singleton", Unit)
        assertIs<UnitCacheKey>(result)
        assertEquals("singleton", result.cacheName)
        assertEquals(Unit, result.key)
    }

    @Test
    fun `toLocalKey with different cache names`() {
        val result1 = UnitKeySerializer.toLocalKey("config", Unit)
        val result2 = UnitKeySerializer.toLocalKey("settings", Unit)
        assertEquals("config", result1.cacheName)
        assertEquals("settings", result2.cacheName)
    }

    // --- toRemoteKey ---

    @Test
    fun `toRemoteKey produces expected format`() {
        val cacheKey = UnitKeySerializer.toLocalKey("singleton", Unit)
        val remote = UnitKeySerializer.toRemoteKey(cacheKey)
        assertEquals("$UnitCacheKeyPart::singleton::kotlin.Unit", remote)
    }

    // --- toRemoteKeyPrefix ---

    @Test
    fun `toRemoteKeyPrefix produces prefix form`() {
        val cacheKey = UnitKeySerializer.toLocalKey("singleton", Unit)
        val prefix = UnitKeySerializer.toRemoteKeyPrefix(cacheKey)
        assertEquals("$UnitCacheKeyPart::singleton::kotlin.Unit", prefix)
    }

    // --- fromRemoteKey ---

    @Test
    fun `fromRemoteKey parses key correctly`() {
        val remote = "$UnitCacheKeyPart::singleton::kotlin.Unit"
        val result = UnitKeySerializer.fromRemoteKey(remote)
        assertIs<UnitCacheKey>(result)
        assertEquals("singleton", result.cacheName)
        assertEquals(Unit, result.key)
    }

    @Test
    fun `fromRemoteKey with different cache name`() {
        val remote = "$UnitCacheKeyPart::config::kotlin.Unit"
        val result = UnitKeySerializer.fromRemoteKey(remote)
        assertEquals("config", result.cacheName)
    }

    // --- Round-trip ---

    @Test
    fun `round-trip serialization preserves cache name`() {
        val original = UnitKeySerializer.toLocalKey("myCache", Unit)
        val remote = UnitKeySerializer.toRemoteKey(original)
        val restored = UnitKeySerializer.fromRemoteKey(remote)
        assertEquals(original.cacheName, restored.cacheName)
        assertEquals(original.key, restored.key)
    }

    @Test
    fun `round-trip with various cache names`() {
        for (name in listOf("a", "my-cache", "cache_with_underscores", "CamelCase")) {
            val original = UnitKeySerializer.toLocalKey(name, Unit)
            val remote = UnitKeySerializer.toRemoteKey(original)
            val restored = UnitKeySerializer.fromRemoteKey(remote)
            assertEquals(name, restored.cacheName)
        }
    }
}
