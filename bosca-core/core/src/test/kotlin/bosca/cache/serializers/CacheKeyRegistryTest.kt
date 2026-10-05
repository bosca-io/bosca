package bosca.cache.serializers

import bosca.cache.CacheKey
import bosca.cache.CacheKeySerializer
import bosca.cache.LongCacheKey
import bosca.cache.StringCacheKey
import bosca.cache.UUIDCacheKey
import bosca.cache.UnitCacheKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.uuid.Uuid

class CacheKeyRegistryTest {

    // --- UUID key lookup ---

    @Test
    fun `toCacheKey returns UUIDCacheKey for UUID key`() {
        val id = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")
        val result = CacheKeyRegistry.toCacheKey("myCache", id)
        assertIs<UUIDCacheKey>(result)
        assertEquals("myCache", result.cacheName)
        assertEquals(id, result.key)
    }

    // --- String key lookup ---

    @Test
    fun `toCacheKey returns StringCacheKey for String key`() {
        val result = CacheKeyRegistry.toCacheKey("myCache", "hello")
        assertIs<StringCacheKey>(result)
        assertEquals("myCache", result.cacheName)
        assertEquals("hello", result.key)
    }

    @Test
    fun `toCacheKey returns StringCacheKey for empty string key`() {
        val result = CacheKeyRegistry.toCacheKey("myCache", "")
        assertIs<StringCacheKey>(result)
        assertEquals("", result.key)
    }

    // --- Long key lookup ---

    @Test
    fun `toCacheKey returns LongCacheKey for Long key`() {
        val result = CacheKeyRegistry.toCacheKey("myCache", 42L)
        assertIs<LongCacheKey>(result)
        assertEquals("myCache", result.cacheName)
        assertEquals(42L, result.key)
    }

    @Test
    fun `toCacheKey returns LongCacheKey for zero`() {
        val result = CacheKeyRegistry.toCacheKey("myCache", 0L)
        assertIs<LongCacheKey>(result)
        assertEquals(0L, result.key)
    }

    @Test
    fun `toCacheKey returns LongCacheKey for negative value`() {
        val result = CacheKeyRegistry.toCacheKey("myCache", -100L)
        assertIs<LongCacheKey>(result)
        assertEquals(-100L, result.key)
    }

    // --- Unit key lookup ---

    @Test
    fun `toCacheKey returns UnitCacheKey for Unit key`() {
        val result = CacheKeyRegistry.toCacheKey("myCache", Unit)
        assertIs<UnitCacheKey>(result)
        assertEquals("myCache", result.cacheName)
        assertEquals(Unit, result.key)
    }

    // --- Error cases ---

    @Test
    fun `toCacheKey throws for unsupported key type`() {
        assertFailsWith<IllegalStateException> {
            CacheKeyRegistry.toCacheKey("myCache", 3.14)
        }
    }

    @Test
    fun `toCacheKey throws for null key`() {
        assertFailsWith<IllegalStateException> {
            CacheKeyRegistry.toCacheKey<Any?>("myCache", null)
        }
    }

    // --- Custom serializer registration ---

    @Test
    fun `register adds custom serializer that can be looked up`() {
        val customSerializer = object : CacheKeySerializer<Int> {
            override fun toLocalKey(cacheName: String, value: Int): CacheKey<Int> {
                return object : CacheKey<Int> {
                    override val cacheName: String = cacheName
                    override val key: Int = value
                    override fun toRemoteKey(prefix: Boolean): String = "int::$cacheName::$value"
                }
            }

            override fun fromRemoteKey(key: String): CacheKey<Int> {
                error("not needed for test")
            }
        }
        CacheKeyRegistry.register(Int::class, "int", customSerializer)
        val result = CacheKeyRegistry.toCacheKey("test", 42)
        assertEquals("test", result.cacheName)
        assertEquals(42, result.key)
    }
}
