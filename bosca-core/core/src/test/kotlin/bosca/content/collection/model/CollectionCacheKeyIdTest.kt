package bosca.content.collection.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class CollectionCacheKeyIdTest {

    @Test
    fun `CollectionCacheKeyId stores all properties`() {
        val id = Uuid.random()
        val supplementaryId = Uuid.random()
        val key = CollectionCacheKeyId(
            id = id,
            state = "published",
            supplementaryId = supplementaryId,
            offset = 10L,
            limit = 25,
            languageTag = "en-US",
            contentTypes = listOf("text", "image"),
            includeMetadata = true,
            includeCollections = false,
            languageResolutionContext = "bibles",
        )
        assertEquals(id, key.id)
        assertEquals("published", key.state)
        assertEquals(supplementaryId, key.supplementaryId)
        assertEquals(10L, key.offset)
        assertEquals(25, key.limit)
        assertEquals("en-US", key.languageTag)
        assertEquals(listOf("text", "image"), key.contentTypes)
        assertEquals(true, key.includeMetadata)
        assertEquals(false, key.includeCollections)
        assertEquals("bibles", key.languageResolutionContext)
    }

    @Test
    fun `CollectionCacheKeyId optional fields default to null`() {
        val id = Uuid.random()
        val key = CollectionCacheKeyId(id = id)
        assertEquals(id, key.id)
        assertNull(key.state)
        assertNull(key.supplementaryId)
        assertNull(key.offset)
        assertNull(key.limit)
        assertNull(key.languageTag)
        assertNull(key.contentTypes)
        assertNull(key.includeMetadata)
        assertNull(key.includeCollections)
        assertNull(key.languageResolutionContext)
    }

    @Test
    fun `CollectionCacheKeyId equality is based on all fields`() {
        val id = Uuid.random()
        val k1 = CollectionCacheKeyId(id = id, state = "draft", offset = 0L, limit = 10)
        val k2 = CollectionCacheKeyId(id = id, state = "draft", offset = 0L, limit = 10)
        assertEquals(k1, k2)
        assertEquals(k1.hashCode(), k2.hashCode())
    }

    @Test
    fun `CollectionCacheKeyId equality and copy distinguish every field`() {
        val base = CollectionCacheKeyId(
            id = Uuid.random(),
            state = "published",
            supplementaryId = Uuid.random(),
            offset = 10,
            limit = 20,
            languageTag = "en",
            contentTypes = listOf("article"),
            includeMetadata = true,
            includeCollections = false,
            languageResolutionContext = "bibles",
        )
        assertEquals(base, base)
        assertEquals(base, base.copy())
        assertFalse(base.equals(null))
        assertFalse(base.equals("key"))
        listOf(
            base.copy(id = Uuid.random()),
            base.copy(state = "draft"),
            base.copy(supplementaryId = Uuid.random()),
            base.copy(offset = 11),
            base.copy(limit = 21),
            base.copy(languageTag = "fr"),
            base.copy(contentTypes = listOf("video")),
            base.copy(includeMetadata = false),
            base.copy(includeCollections = true),
            base.copy(languageResolutionContext = "recommendations"),
        ).forEach { assertNotEquals(base, it) }

        listOf(
            CollectionCacheKeyId(base.id, state = "published"),
            CollectionCacheKeyId(base.id, supplementaryId = base.supplementaryId),
            CollectionCacheKeyId(base.id, offset = 10),
            CollectionCacheKeyId(base.id, limit = 20),
            CollectionCacheKeyId(base.id, languageTag = "en"),
            CollectionCacheKeyId(base.id, contentTypes = listOf("article")),
            CollectionCacheKeyId(base.id, includeMetadata = true),
            CollectionCacheKeyId(base.id, includeCollections = false),
            CollectionCacheKeyId(base.id, languageResolutionContext = "bibles"),
        ).forEach { it.hashCode() }
    }

    @Test
    fun `CollectionCacheKeyId inequality when id differs`() {
        val k1 = CollectionCacheKeyId(id = Uuid.random())
        val k2 = CollectionCacheKeyId(id = Uuid.random())
        assertNotEquals(k1, k2)
    }

    @Test
    fun `CollectionCacheKeyId inequality when state differs`() {
        val id = Uuid.random()
        val k1 = CollectionCacheKeyId(id = id, state = "draft")
        val k2 = CollectionCacheKeyId(id = id, state = "published")
        assertNotEquals(k1, k2)
    }

    @Test
    fun `CollectionCacheKeyId copy creates modified instance`() {
        val key = CollectionCacheKeyId(id = Uuid.random(), state = "draft")
        val copied = key.copy(state = "published")
        assertEquals("published", copied.state)
        assertEquals(key.id, copied.id)
    }

    @Test
    fun `CollectionCacheKeyId constructors use collection and variant identity`() {
        val collection = Collection(id = Uuid.random(), name = "Collection", languageTag = "en", workflowStateId = "draft")
        val variant = CollectionLanguageVariant(Uuid.random(), "fr-CA", "Variant")

        assertEquals(collection.id, CollectionCacheKeyId(collection).id)
        assertEquals(variant.id, CollectionCacheKeyId(variant).id)
        assertEquals("fr-CA", CollectionCacheKeyId(variant).languageTag)
    }

    // --- CollectionCacheKey ---

    @Test
    fun `CollectionCacheKey stores cacheName and key`() {
        val keyId = CollectionCacheKeyId(id = Uuid.random())
        val cacheKey = CollectionCacheKey(cacheName = "collections", key = keyId)
        assertEquals("collections", cacheKey.cacheName)
        assertEquals(keyId, cacheKey.key)
    }

    @Test
    fun `CollectionCacheKey toRemoteKey contains id`() {
        val id = Uuid.random()
        val keyId = CollectionCacheKeyId(id = id)
        val cacheKey = CollectionCacheKey(cacheName = "collections", key = keyId)
        val remoteKey = cacheKey.toRemoteKey()
        assert(remoteKey.contains(id.toString())) { "Remote key should contain the UUID" }
    }

    @Test
    fun `CollectionCacheKey toRemoteKey contains cache name`() {
        val keyId = CollectionCacheKeyId(id = Uuid.random())
        val cacheKey = CollectionCacheKey(cacheName = "myCache", key = keyId)
        val remoteKey = cacheKey.toRemoteKey()
        assert(remoteKey.contains("myCache")) { "Remote key should contain cache name" }
    }

    @Test
    fun `CollectionCacheKey equality is based on cacheName and key`() {
        val keyId = CollectionCacheKeyId(id = Uuid.random(), state = "published")
        val ck1 = CollectionCacheKey(cacheName = "c", key = keyId)
        val ck2 = CollectionCacheKey(cacheName = "c", key = keyId)
        assertEquals(ck1, ck2)
        assertEquals(ck1.hashCode(), ck2.hashCode())
    }

    @Test
    fun `CollectionCacheKey inequality when cacheName differs`() {
        val keyId = CollectionCacheKeyId(id = Uuid.random())
        val ck1 = CollectionCacheKey(cacheName = "a", key = keyId)
        val ck2 = CollectionCacheKey(cacheName = "b", key = keyId)
        assertNotEquals(ck1, ck2)
    }

    @Test
    fun `CollectionCacheKeySerializer round trips every remote key field`() {
        val keyId = CollectionCacheKeyId(
            id = Uuid.random(),
            state = "published",
            supplementaryId = Uuid.random(),
            offset = 10,
            limit = 25,
            languageTag = "en-US",
            contentTypes = listOf("video", "article"),
            includeMetadata = false,
            includeCollections = true,
            languageResolutionContext = "bibles",
        )
        val local = CollectionCacheKeySerializer.toLocalKey("collections", keyId)
        val restored = CollectionCacheKeySerializer.fromRemoteKey(local.toRemoteKey())

        assertEquals("collections", restored.cacheName)
        assertEquals(keyId.copy(contentTypes = listOf("article", "video")), restored.key)
    }

    @Test
    fun `CollectionCacheKeySerializer supplies safe defaults for a truncated remote key`() {
        val id = Uuid.random()
        val restored = CollectionCacheKeySerializer.fromRemoteKey("c::collections::$id")

        assertEquals(id, restored.key.id)
        assertNull(restored.key.state)
        assertNull(restored.key.supplementaryId)
        assertNull(restored.key.offset)
        assertNull(restored.key.limit)
        assertNull(restored.key.languageTag)
        assertNull(restored.key.contentTypes)
        assertEquals(true, restored.key.includeMetadata)
        assertEquals(true, restored.key.includeCollections)
        assertNull(restored.key.languageResolutionContext)
    }

    @Test
    fun `CollectionCacheKeySerializer tolerates blank and malformed optional parts`() {
        val id = Uuid.random()
        val restored = CollectionCacheKeySerializer.fromRemoteKey(
            "c::collections::$id::::::not-a-long::not-an-int::::::not-boolean::not-boolean"
        )

        assertNull(restored.key.supplementaryId)
        assertNull(restored.key.offset)
        assertNull(restored.key.limit)
        assertNull(restored.key.languageTag)
        assertNull(restored.key.contentTypes)
        assertEquals(false, restored.key.includeMetadata)
        assertEquals(false, restored.key.includeCollections)
    }
}
