package bosca.cache.serializers

import bosca.cache.UUIDCacheKey
import bosca.cache.UUIDCacheKeyPart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.uuid.Uuid

class UUIDKeySerializerTest {

    private val testUuid = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")

    // --- toLocalKey ---

    @Test
    fun `toLocalKey creates UUIDCacheKey with correct fields`() {
        val result = UUIDKeySerializer.toLocalKey("users", testUuid)
        assertIs<UUIDCacheKey>(result)
        assertEquals("users", result.cacheName)
        assertEquals(testUuid, result.key)
    }

    @Test
    fun `toLocalKey with nil UUID`() {
        val nilUuid = Uuid.parse("00000000-0000-0000-0000-000000000000")
        val result = UUIDKeySerializer.toLocalKey("users", nilUuid)
        assertEquals(nilUuid, result.key)
    }

    // --- toRemoteKey ---

    @Test
    fun `toRemoteKey produces expected format`() {
        val cacheKey = UUIDKeySerializer.toLocalKey("users", testUuid)
        val remote = UUIDKeySerializer.toRemoteKey(cacheKey)
        assertEquals("$UUIDCacheKeyPart::users::550e8400-e29b-41d4-a716-446655440000", remote)
    }

    // --- toRemoteKeyPrefix ---

    @Test
    fun `toRemoteKeyPrefix produces prefix form`() {
        val cacheKey = UUIDKeySerializer.toLocalKey("users", testUuid)
        val prefix = UUIDKeySerializer.toRemoteKeyPrefix(cacheKey)
        assertEquals("$UUIDCacheKeyPart::users::550e8400-e29b-41d4-a716-446655440000", prefix)
    }

    // --- fromRemoteKey ---

    @Test
    fun `fromRemoteKey parses key correctly`() {
        val remote = "$UUIDCacheKeyPart::users::550e8400-e29b-41d4-a716-446655440000"
        val result = UUIDKeySerializer.fromRemoteKey(remote)
        assertIs<UUIDCacheKey>(result)
        assertEquals("users", result.cacheName)
        assertEquals(testUuid, result.key)
    }

    @Test
    fun `fromRemoteKey parses nil UUID`() {
        val remote = "$UUIDCacheKeyPart::users::00000000-0000-0000-0000-000000000000"
        val result = UUIDKeySerializer.fromRemoteKey(remote)
        assertEquals(Uuid.parse("00000000-0000-0000-0000-000000000000"), result.key)
    }

    // --- Round-trip ---

    @Test
    fun `round-trip serialization preserves key`() {
        val original = UUIDKeySerializer.toLocalKey("myCache", testUuid)
        val remote = UUIDKeySerializer.toRemoteKey(original)
        val restored = UUIDKeySerializer.fromRemoteKey(remote)
        assertEquals(original.cacheName, restored.cacheName)
        assertEquals(original.key, restored.key)
    }

    @Test
    fun `round-trip with random UUID preserves key`() {
        val randomId = Uuid.random()
        val original = UUIDKeySerializer.toLocalKey("cache", randomId)
        val remote = UUIDKeySerializer.toRemoteKey(original)
        val restored = UUIDKeySerializer.fromRemoteKey(remote)
        assertEquals(randomId, restored.key)
    }

    @Test
    fun `round-trip with nil UUID preserves key`() {
        val nilId = Uuid.parse("00000000-0000-0000-0000-000000000000")
        val original = UUIDKeySerializer.toLocalKey("cache", nilId)
        val remote = UUIDKeySerializer.toRemoteKey(original)
        val restored = UUIDKeySerializer.fromRemoteKey(remote)
        assertEquals(nilId, restored.key)
    }
}
