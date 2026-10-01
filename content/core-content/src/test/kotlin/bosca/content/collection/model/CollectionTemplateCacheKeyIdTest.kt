package bosca.content.collection.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class CollectionTemplateCacheKeyIdTest {

    @Test
    fun `CollectionTemplateCacheKeyId stores all fields`() {
        val id = Uuid.random()
        val cacheKey = CollectionTemplateCacheKeyId(id = id, version = 3, key = "template-key")
        assertEquals(id, cacheKey.id)
        assertEquals(3, cacheKey.version)
        assertEquals("template-key", cacheKey.key)
    }

    @Test
    fun `CollectionTemplateCacheKeyId defaults optional fields to null`() {
        val id = Uuid.random()
        val cacheKey = CollectionTemplateCacheKeyId(id = id)
        assertNull(cacheKey.version)
        assertNull(cacheKey.key)
    }

    @Test
    fun `CollectionTemplateCacheKeyId equals with same fields`() {
        val id = Uuid.random()
        val key1 = CollectionTemplateCacheKeyId(id = id, version = 1, key = "k")
        val key2 = CollectionTemplateCacheKeyId(id = id, version = 1, key = "k")
        assertEquals(key1, key2)
    }

    @Test
    fun `CollectionTemplateCacheKeyId hashCode consistent with equals`() {
        val id = Uuid.random()
        val key1 = CollectionTemplateCacheKeyId(id = id, version = 2, key = "test")
        val key2 = CollectionTemplateCacheKeyId(id = id, version = 2, key = "test")
        assertEquals(key1.hashCode(), key2.hashCode())
    }

    @Test
    fun `CollectionTemplateCacheKeyId not equal when version differs`() {
        val id = Uuid.random()
        val key1 = CollectionTemplateCacheKeyId(id = id, version = 1)
        val key2 = CollectionTemplateCacheKeyId(id = id, version = 2)
        assertNotEquals(key1, key2)
    }

    @Test
    fun `CollectionTemplateCacheKeyId not equal when id differs`() {
        val key1 = CollectionTemplateCacheKeyId(id = Uuid.random(), version = 1)
        val key2 = CollectionTemplateCacheKeyId(id = Uuid.random(), version = 1)
        assertNotEquals(key1, key2)
    }

    @Test
    fun `CollectionTemplateCacheKeyId not equal when key differs`() {
        val id = Uuid.random()
        val key1 = CollectionTemplateCacheKeyId(id = id, key = "a")
        val key2 = CollectionTemplateCacheKeyId(id = id, key = "b")
        assertNotEquals(key1, key2)
    }

    @Test
    fun `CollectionTemplateCacheKeyId equals with null versions`() {
        val id = Uuid.random()
        val key1 = CollectionTemplateCacheKeyId(id = id)
        val key2 = CollectionTemplateCacheKeyId(id = id)
        assertEquals(key1, key2)
    }

    @Test
    fun `CollectionTemplateCacheKeyId hashCode with null fields`() {
        val id = Uuid.random()
        val key1 = CollectionTemplateCacheKeyId(id = id)
        val key2 = CollectionTemplateCacheKeyId(id = id)
        assertEquals(key1.hashCode(), key2.hashCode())
    }

    @Test
    fun `CollectionTemplateCacheKeyId not equal to different type`() {
        val id = Uuid.random()
        val key = CollectionTemplateCacheKeyId(id = id)
        assertNotEquals<Any>(key, "not a cache key")
    }

    @Test
    fun `CollectionTemplateCacheKeyId equals itself`() {
        val id = Uuid.random()
        val key = CollectionTemplateCacheKeyId(id = id, version = 1, key = "test")
        assertEquals(key, key)
    }
}
