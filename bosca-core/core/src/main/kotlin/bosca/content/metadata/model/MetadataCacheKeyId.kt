package bosca.content.metadata.model

import bosca.cache.CacheKey
import bosca.cache.CacheKeySerializer
import bosca.cache.annotations.Serializer
import bosca.cache.serializers.buildCacheKey
import bosca.cache.serializers.separateForCacheKey
import bosca.serialization.UUID

/**
 * Contract for entities that can produce a [MetadataCacheKeyId] for cache key generation.
 *
 * Implementors expose the composite fields (metadata ID, version, key, step) needed
 * to construct a unique cache key for metadata-related cache entries.
 */
interface MetadataCacheKeyable {
    /** The UUID of the metadata item this cache key refers to. */
    val metadataId: UUID
    /** The version of the metadata item, or `null` if version-independent. */
    val version: Int?
    /** An optional sub-key for distinguishing different cached aspects of the same metadata. */
    val key: String?
    /** An optional workflow step identifier associated with this cache entry. */
    val step: Long?
}

data class MetadataCacheKeyId(
    val id: UUID,
    val version: Int? = null,
    val key: String? = null,
    val stepId: Long? = null
) {

    constructor(metadata: Metadata) : this(metadata.id, metadata.version, null)

    constructor(metadata: MetadataRelationship) : this(metadata.metadataId2, null, null)

    constructor(metadata: MetadataCacheKeyable): this(metadata.metadataId, metadata.version, metadata.key, metadata.step)
}

data class MetadataCacheKey(
    override val cacheName: String,
    override val key: MetadataCacheKeyId
) : CacheKey<MetadataCacheKeyId> {

    override fun toRemoteKey(prefix: Boolean): String = buildCacheKey(prefix) {
        appendKeyPrefix("md", cacheName)
        appendKeyPart(key.id)
        appendKeyPart(key.version)
        appendKeyPart(key.key)
        appendKeyPart(key.stepId)
    }

    override fun toRemoteKeyPrefix(): String = buildCacheKey(true) {
        appendKeyPrefix("md", cacheName)
        appendKeyPart(key.id)
    }
}

@Serializer("md")
object MetadataCacheKeySerializer : CacheKeySerializer<MetadataCacheKeyId> {

    override fun toLocalKey(cacheName: String, value: MetadataCacheKeyId): CacheKey<MetadataCacheKeyId> {
        return MetadataCacheKey(cacheName, value)
    }

    override fun fromRemoteKey(key: String): CacheKey<MetadataCacheKeyId> {
        val keyParts = key.separateForCacheKey()
        val id = UUID.parse(keyParts[1])
        val version = keyParts.getOrNull(2)?.toIntOrNull()
        val key = keyParts.getOrNull(3)
        val stepId = keyParts.getOrNull(4)?.toLongOrNull()
        return MetadataCacheKey(keyParts.first(), MetadataCacheKeyId(id, version, key, stepId))
    }
}