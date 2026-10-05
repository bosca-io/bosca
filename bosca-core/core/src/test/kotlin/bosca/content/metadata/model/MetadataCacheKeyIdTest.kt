package bosca.content.metadata.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class MetadataCacheKeyIdTest {

    // --- MetadataCacheKeyId ---

    @Test
    fun `MetadataCacheKeyId stores all properties`() {
        val id = Uuid.random()
        val key = MetadataCacheKeyId(id = id, version = 3, key = "thumbnail", stepId = 42L)
        assertEquals(id, key.id)
        assertEquals(3, key.version)
        assertEquals("thumbnail", key.key)
        assertEquals(42L, key.stepId)
    }

    @Test
    fun `MetadataCacheKeyId optional fields default to null`() {
        val id = Uuid.random()
        val key = MetadataCacheKeyId(id = id)
        assertEquals(id, key.id)
        assertNull(key.version)
        assertNull(key.key)
        assertNull(key.stepId)
    }

    @Test
    fun `MetadataCacheKeyId equality is based on all fields`() {
        val id = Uuid.random()
        val k1 = MetadataCacheKeyId(id = id, version = 1, key = "k", stepId = 5L)
        val k2 = MetadataCacheKeyId(id = id, version = 1, key = "k", stepId = 5L)
        assertEquals(k1, k2)
        assertEquals(k1.hashCode(), k2.hashCode())
    }

    @Test
    fun `MetadataCacheKeyId inequality when id differs`() {
        val k1 = MetadataCacheKeyId(id = Uuid.random())
        val k2 = MetadataCacheKeyId(id = Uuid.random())
        assertNotEquals(k1, k2)
    }

    @Test
    fun `MetadataCacheKeyId inequality when version differs`() {
        val id = Uuid.random()
        val k1 = MetadataCacheKeyId(id = id, version = 1)
        val k2 = MetadataCacheKeyId(id = id, version = 2)
        assertNotEquals(k1, k2)
    }

    @Test
    fun `MetadataCacheKeyId inequality when key differs`() {
        val id = Uuid.random()
        val k1 = MetadataCacheKeyId(id = id, key = "a")
        val k2 = MetadataCacheKeyId(id = id, key = "b")
        assertNotEquals(k1, k2)
    }

    @Test
    fun `MetadataCacheKeyId copy creates modified instance`() {
        val key = MetadataCacheKeyId(id = Uuid.random(), version = 1)
        val copied = key.copy(version = 2)
        assertEquals(2, copied.version)
        assertEquals(key.id, copied.id)
    }

    @Test
    fun `MetadataCacheKeyId from MetadataCacheKeyable`() {
        val metadataId = Uuid.random()
        val keyable = object : MetadataCacheKeyable {
            override val metadataId = metadataId
            override val version = 5
            override val key = "thumb"
            override val step = 10L
        }
        val cacheKeyId = MetadataCacheKeyId(keyable)
        assertEquals(metadataId, cacheKeyId.id)
        assertEquals(5, cacheKeyId.version)
        assertEquals("thumb", cacheKeyId.key)
        assertEquals(10L, cacheKeyId.stepId)
    }

    @Test
    fun `MetadataCacheKeyId constructors use metadata and relationship identity`() {
        val metadata = Metadata(
            id = Uuid.random(),
            version = 3,
            name = "Metadata",
            type = MetadataType.STANDARD,
            contentType = "text/plain",
            contentLength = 12,
            languageTag = "en",
            workflowStateId = "draft",
        )
        val relationship = MetadataRelationship(
            metadataId1 = Uuid.random(),
            metadataId2 = Uuid.random(),
            relationship = "related",
        )

        assertEquals(metadata.id, MetadataCacheKeyId(metadata).id)
        assertEquals(3, MetadataCacheKeyId(metadata).version)
        assertEquals(relationship.metadataId2, MetadataCacheKeyId(relationship).id)
    }

    // --- MetadataCacheKey ---

    @Test
    fun `MetadataCacheKey stores cacheName and key`() {
        val keyId = MetadataCacheKeyId(id = Uuid.random())
        val cacheKey = MetadataCacheKey(cacheName = "metadata", key = keyId)
        assertEquals("metadata", cacheKey.cacheName)
        assertEquals(keyId, cacheKey.key)
    }

    @Test
    fun `MetadataCacheKey toRemoteKey contains id`() {
        val id = Uuid.random()
        val keyId = MetadataCacheKeyId(id = id)
        val cacheKey = MetadataCacheKey(cacheName = "metadata", key = keyId)
        val remoteKey = cacheKey.toRemoteKey()
        assert(remoteKey.contains(id.toString())) { "Remote key should contain the UUID" }
    }

    @Test
    fun `MetadataCacheKey toRemoteKey contains cache name`() {
        val keyId = MetadataCacheKeyId(id = Uuid.random())
        val cacheKey = MetadataCacheKey(cacheName = "myMeta", key = keyId)
        val remoteKey = cacheKey.toRemoteKey()
        assert(remoteKey.contains("myMeta")) { "Remote key should contain cache name" }
    }

    @Test
    fun `MetadataCacheKey toRemoteKeyPrefix contains id`() {
        val id = Uuid.random()
        val keyId = MetadataCacheKeyId(id = id, version = 1, key = "k")
        val cacheKey = MetadataCacheKey(cacheName = "meta", key = keyId)
        val prefix = cacheKey.toRemoteKeyPrefix()
        assert(prefix.contains(id.toString())) { "Prefix should contain the UUID" }
        assert(prefix.contains("meta")) { "Prefix should contain cache name" }
    }

    @Test
    fun `MetadataCacheKey equality is based on cacheName and key`() {
        val keyId = MetadataCacheKeyId(id = Uuid.random(), version = 1)
        val ck1 = MetadataCacheKey(cacheName = "m", key = keyId)
        val ck2 = MetadataCacheKey(cacheName = "m", key = keyId)
        assertEquals(ck1, ck2)
        assertEquals(ck1.hashCode(), ck2.hashCode())
    }

    @Test
    fun `MetadataCacheKey inequality when cacheName differs`() {
        val keyId = MetadataCacheKeyId(id = Uuid.random())
        val ck1 = MetadataCacheKey(cacheName = "a", key = keyId)
        val ck2 = MetadataCacheKey(cacheName = "b", key = keyId)
        assertNotEquals(ck1, ck2)
    }

    @Test
    fun `MetadataCacheKeySerializer round trips every remote key field`() {
        val keyId = MetadataCacheKeyId(Uuid.random(), version = 7, key = "thumbnail", stepId = 42)
        val local = MetadataCacheKeySerializer.toLocalKey("metadata", keyId)
        val restored = MetadataCacheKeySerializer.fromRemoteKey(local.toRemoteKey())

        assertEquals("metadata", restored.cacheName)
        assertEquals(keyId, restored.key)
    }

    @Test
    fun `MetadataCacheKeySerializer accepts truncated and malformed optional fields`() {
        val id = Uuid.random()
        val truncated = MetadataCacheKeySerializer.fromRemoteKey("md::metadata::$id")
        val malformed = MetadataCacheKeySerializer.fromRemoteKey("md::metadata::$id::bad::key::bad")

        assertEquals(id, truncated.key.id)
        assertNull(truncated.key.version)
        assertNull(truncated.key.key)
        assertNull(truncated.key.stepId)
        assertNull(malformed.key.version)
        assertEquals("key", malformed.key.key)
        assertNull(malformed.key.stepId)
    }
}
