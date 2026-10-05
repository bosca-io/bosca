package bosca.cache

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.uuid.Uuid

class CacheKeyTest {

    // --- UUIDCacheKey ---

    @Test
    fun `UUIDCacheKey stores cacheName and key`() {
        val id = Uuid.random()
        val key = UUIDCacheKey(cacheName = "users", key = id)
        assertEquals("users", key.cacheName)
        assertEquals(id, key.key)
    }

    @Test
    fun `UUIDCacheKey toRemoteKey produces string with key value`() {
        val id = Uuid.random()
        val key = UUIDCacheKey(cacheName = "users", key = id)
        val remoteKey = key.toRemoteKey()
        assert(remoteKey.contains("users")) { "Remote key should contain cache name" }
        assert(remoteKey.contains(id.toString())) { "Remote key should contain UUID" }
    }

    @Test
    fun `UUIDCacheKey toRemoteKey prefix includes cache name but still includes key`() {
        val id = Uuid.random()
        val key = UUIDCacheKey(cacheName = "users", key = id)
        val prefix = key.toRemoteKey(prefix = true)
        assert(prefix.contains("users")) { "Prefix should contain cache name" }
        assert(prefix.contains(UUIDCacheKeyPart)) { "Prefix should contain type part" }
    }

    @Test
    fun `UUIDCacheKey toRemoteKeyPrefix matches toRemoteKey with prefix true`() {
        val key = UUIDCacheKey(cacheName = "test", key = Uuid.random())
        assertEquals(key.toRemoteKey(prefix = true), key.toRemoteKeyPrefix())
    }

    // --- StringCacheKey ---

    @Test
    fun `StringCacheKey stores cacheName and key`() {
        val key = StringCacheKey(cacheName = "sessions", key = "abc123")
        assertEquals("sessions", key.cacheName)
        assertEquals("abc123", key.key)
    }

    @Test
    fun `StringCacheKey toRemoteKey produces string with key value`() {
        val key = StringCacheKey(cacheName = "sessions", key = "abc123")
        val remoteKey = key.toRemoteKey()
        assert(remoteKey.contains("sessions")) { "Remote key should contain cache name" }
        assert(remoteKey.contains("abc123")) { "Remote key should contain string key" }
    }

    // --- LongCacheKey ---

    @Test
    fun `LongCacheKey stores cacheName and key`() {
        val key = LongCacheKey(cacheName = "items", key = 42L)
        assertEquals("items", key.cacheName)
        assertEquals(42L, key.key)
    }

    @Test
    fun `LongCacheKey toRemoteKey produces string with key value`() {
        val key = LongCacheKey(cacheName = "items", key = 42L)
        val remoteKey = key.toRemoteKey()
        assert(remoteKey.contains("items")) { "Remote key should contain cache name" }
        assert(remoteKey.contains("42")) { "Remote key should contain long key" }
    }

    // --- UnitCacheKey ---

    @Test
    fun `UnitCacheKey stores cacheName and Unit key`() {
        val key = UnitCacheKey(cacheName = "singleton")
        assertEquals("singleton", key.cacheName)
        assertEquals(Unit, key.key)
    }

    @Test
    fun `UnitCacheKey toRemoteKey produces string`() {
        val key = UnitCacheKey(cacheName = "singleton")
        val remoteKey = key.toRemoteKey()
        assert(remoteKey.contains("singleton")) { "Remote key should contain cache name" }
    }

    // --- Equality ---

    @Test
    fun `UUIDCacheKeys with same values are equal`() {
        val id = Uuid.random()
        val key1 = UUIDCacheKey(cacheName = "test", key = id)
        val key2 = UUIDCacheKey(cacheName = "test", key = id)
        assertEquals(key1, key2)
    }

    @Test
    fun `UUIDCacheKeys with different keys are not equal`() {
        val key1 = UUIDCacheKey(cacheName = "test", key = Uuid.random())
        val key2 = UUIDCacheKey(cacheName = "test", key = Uuid.random())
        assertNotEquals(key1, key2)
    }

    @Test
    fun `UUIDCacheKeys with different cache names are not equal`() {
        val id = Uuid.random()
        val key1 = UUIDCacheKey(cacheName = "a", key = id)
        val key2 = UUIDCacheKey(cacheName = "b", key = id)
        assertNotEquals(key1, key2)
    }

    // --- Remote key format verification ---

    @Test
    fun `UUIDCacheKey toRemoteKey has expected format`() {
        val id = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")
        val key = UUIDCacheKey(cacheName = "users", key = id)
        assertEquals("uuid::users::550e8400-e29b-41d4-a716-446655440000", key.toRemoteKey())
    }

    @Test
    fun `UUIDCacheKey toRemoteKey prefix omits key value`() {
        val id = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")
        val key = UUIDCacheKey(cacheName = "users", key = id)
        assertEquals("uuid::users::550e8400-e29b-41d4-a716-446655440000", key.toRemoteKeyPrefix())
    }

    @Test
    fun `StringCacheKey toRemoteKey has expected format`() {
        val key = StringCacheKey(cacheName = "sessions", key = "abc123")
        assertEquals("str::sessions::abc123", key.toRemoteKey())
    }

    @Test
    fun `StringCacheKey with empty key value`() {
        val key = StringCacheKey(cacheName = "sessions", key = "")
        assertEquals("str::sessions::", key.toRemoteKey())
        assertEquals("", key.key)
    }

    @Test
    fun `StringCacheKey toRemoteKeyPrefix matches toRemoteKey with prefix true`() {
        val key = StringCacheKey(cacheName = "test", key = "mykey")
        assertEquals(key.toRemoteKey(prefix = true), key.toRemoteKeyPrefix())
    }

    @Test
    fun `LongCacheKey toRemoteKey has expected format`() {
        val key = LongCacheKey(cacheName = "items", key = 42L)
        assertEquals("lng::items::42", key.toRemoteKey())
    }

    @Test
    fun `LongCacheKey with zero key`() {
        val key = LongCacheKey(cacheName = "items", key = 0L)
        assertEquals("lng::items::0", key.toRemoteKey())
        assertEquals(0L, key.key)
    }

    @Test
    fun `LongCacheKey with negative key`() {
        val key = LongCacheKey(cacheName = "items", key = -1L)
        assertEquals("lng::items::-1", key.toRemoteKey())
        assertEquals(-1L, key.key)
    }

    @Test
    fun `LongCacheKey with max value`() {
        val key = LongCacheKey(cacheName = "items", key = Long.MAX_VALUE)
        assertEquals("lng::items::${Long.MAX_VALUE}", key.toRemoteKey())
    }

    @Test
    fun `LongCacheKey toRemoteKeyPrefix matches toRemoteKey with prefix true`() {
        val key = LongCacheKey(cacheName = "test", key = 99L)
        assertEquals(key.toRemoteKey(prefix = true), key.toRemoteKeyPrefix())
    }

    @Test
    fun `UnitCacheKey toRemoteKey has expected format`() {
        val key = UnitCacheKey(cacheName = "singleton")
        assertEquals("unit::singleton::kotlin.Unit", key.toRemoteKey())
    }

    @Test
    fun `UnitCacheKey toRemoteKeyPrefix matches toRemoteKey with prefix true`() {
        val key = UnitCacheKey(cacheName = "singleton")
        assertEquals(key.toRemoteKey(prefix = true), key.toRemoteKeyPrefix())
    }

    // --- StringCacheKey equality ---

    @Test
    fun `StringCacheKeys with same values are equal`() {
        val key1 = StringCacheKey(cacheName = "test", key = "hello")
        val key2 = StringCacheKey(cacheName = "test", key = "hello")
        assertEquals(key1, key2)
        assertEquals(key1.hashCode(), key2.hashCode())
    }

    @Test
    fun `StringCacheKeys with different keys are not equal`() {
        val key1 = StringCacheKey(cacheName = "test", key = "a")
        val key2 = StringCacheKey(cacheName = "test", key = "b")
        assertNotEquals(key1, key2)
    }

    // --- LongCacheKey equality ---

    @Test
    fun `LongCacheKeys with same values are equal`() {
        val key1 = LongCacheKey(cacheName = "test", key = 42L)
        val key2 = LongCacheKey(cacheName = "test", key = 42L)
        assertEquals(key1, key2)
        assertEquals(key1.hashCode(), key2.hashCode())
    }

    @Test
    fun `LongCacheKeys with different keys are not equal`() {
        val key1 = LongCacheKey(cacheName = "test", key = 1L)
        val key2 = LongCacheKey(cacheName = "test", key = 2L)
        assertNotEquals(key1, key2)
    }

    // --- UnitCacheKey equality ---

    @Test
    fun `UnitCacheKeys with same cache name are equal`() {
        val key1 = UnitCacheKey(cacheName = "test")
        val key2 = UnitCacheKey(cacheName = "test")
        assertEquals(key1, key2)
        assertEquals(key1.hashCode(), key2.hashCode())
    }

    @Test
    fun `UnitCacheKeys with different cache names are not equal`() {
        val key1 = UnitCacheKey(cacheName = "a")
        val key2 = UnitCacheKey(cacheName = "b")
        assertNotEquals(key1, key2)
    }

    // --- Constant values ---

    @Test
    fun `cache key part constants have expected values`() {
        assertEquals("uuid", UUIDCacheKeyPart)
        assertEquals("lng", LongCacheKeyPart)
        assertEquals("str", StringCacheKeyPart)
        assertEquals("unit", UnitCacheKeyPart)
    }
}
