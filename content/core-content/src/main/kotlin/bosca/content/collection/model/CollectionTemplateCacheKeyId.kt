package bosca.content.collection.model

import bosca.cache.CacheKey
import bosca.cache.CacheKeySerializer
import bosca.cache.annotations.Serializer
import bosca.cache.serializers.buildCacheKey
import bosca.cache.serializers.separateForCacheKey
import bosca.content.metadata.model.MetadataCacheKeyId
import bosca.serialization.UUID
import kotlinx.serialization.Serializable


@Serializable
data class CollectionTemplateCacheKeyId(val id: UUID, val version: Int? = null, val key: String? = null) {

    constructor(template: CollectionTemplate) : this(template.metadataId, template.version)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CollectionTemplateCacheKeyId

        if (version != other.version) return false
        if (id != other.id) return false
        if (key != other.key) return false

        return true
    }

    override fun hashCode(): Int {
        var result = version ?: 0
        result = 31 * result + id.hashCode()
        result = 31 * result + (key?.hashCode() ?: 0)
        return result
    }
}

data class CollectionTemplateCacheKey(
    override val cacheName: String,
    override val key: CollectionTemplateCacheKeyId
) : CacheKey<CollectionTemplateCacheKeyId> {

    override fun toRemoteKey(prefix: Boolean): String = buildCacheKey(prefix) {
        appendKeyPrefix("ctc", cacheName)
        appendKeyPart(key.id)
        appendKeyPart(key.version)
        appendKeyPart(key.key)
    }
}

@Serializer("ctc")
object CollectionTemplateCacheKeySerializer : CacheKeySerializer<CollectionTemplateCacheKeyId> {

    override fun toLocalKey(cacheName: String, value: CollectionTemplateCacheKeyId): CacheKey<CollectionTemplateCacheKeyId> {
        return CollectionTemplateCacheKey(cacheName, value)
    }

    override fun fromRemoteKey(key: String): CacheKey<CollectionTemplateCacheKeyId> {
        val keyParts = key.separateForCacheKey()
        val id = UUID.parse(keyParts[1])
        val version = keyParts.getOrNull(2)?.toIntOrNull()
        val key = keyParts.getOrNull(3)
        return CollectionTemplateCacheKey(keyParts.first(), CollectionTemplateCacheKeyId(id, version, key))
    }
}