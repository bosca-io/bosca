package bosca.content.find

import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExpandCacheIdTest {

    @Test
    fun `ExpandCacheId creation with only required id`() {
        val id = UUID.random()
        val cacheId = ExpandCacheId(id = id)
        assertEquals(id, cacheId.id)
        assertNull(cacheId.state)
        assertNull(cacheId.ordering)
        assertNull(cacheId.offset)
        assertNull(cacheId.limit)
    }

    @Test
    fun `ExpandCacheId creation with all fields`() {
        val id = UUID.random()
        val cacheId = ExpandCacheId(
            id = id,
            state = "published",
            offset = 0L,
            limit = 50
        )
        assertEquals(id, cacheId.id)
        assertEquals("published", cacheId.state)
        assertEquals(0L, cacheId.offset)
        assertEquals(50, cacheId.limit)
    }

    @Test
    fun `ExpandCacheKey toRemoteKey includes id`() {
        val id = UUID.random()
        val cacheId = ExpandCacheId(id = id)
        val cacheKey = ExpandCacheKey(cacheName = "test-cache", key = cacheId)
        val remoteKey = cacheKey.toRemoteKey(prefix = false)
        assertTrue(remoteKey.contains(id.toString()), "Remote key should contain the UUID")
    }

    @Test
    fun `ExpandCacheKey toRemoteKey includes state when provided`() {
        val id = UUID.random()
        val cacheId = ExpandCacheId(id = id, state = "active")
        val cacheKey = ExpandCacheKey(cacheName = "cache1", key = cacheId)
        val remoteKey = cacheKey.toRemoteKey(prefix = false)
        assertTrue(remoteKey.contains("active"), "Remote key should contain the state")
    }

    @Test
    fun `ExpandCacheKey cacheName is preserved`() {
        val cacheId = ExpandCacheId(id = UUID.random())
        val cacheKey = ExpandCacheKey(cacheName = "my-cache", key = cacheId)
        assertEquals("my-cache", cacheKey.cacheName)
    }

    @Test
    fun `ExpandCacheKey key references original ExpandCacheId`() {
        val id = UUID.random()
        val cacheId = ExpandCacheId(id = id, state = "draft", offset = 5L, limit = 10)
        val cacheKey = ExpandCacheKey(cacheName = "c", key = cacheId)
        assertEquals(id, cacheKey.key.id)
        assertEquals("draft", cacheKey.key.state)
        assertEquals(5L, cacheKey.key.offset)
        assertEquals(10, cacheKey.key.limit)
    }

    @Test
    fun `ExpandCacheKeySerializer toLocalKey creates ExpandCacheKey`() {
        val cacheId = ExpandCacheId(id = UUID.random(), state = "test")
        val result = ExpandCacheKeySerializer.toLocalKey("testcache", cacheId)
        assertEquals("testcache", result.cacheName)
        assertEquals(cacheId.id, result.key.id)
        assertEquals("test", result.key.state)
    }

    private fun assertTrue(condition: Boolean, message: String) {
        kotlin.test.assertTrue(condition, message)
    }
}
