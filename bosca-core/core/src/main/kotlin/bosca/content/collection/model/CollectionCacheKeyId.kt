package bosca.content.collection.model

import bosca.cache.CacheKey
import bosca.cache.CacheKeySerializer
import bosca.cache.annotations.Serializer
import bosca.cache.serializers.buildCacheKey
import bosca.cache.serializers.separateForCacheKey
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class CollectionCacheKeyId(
    @Contextual
    val id: UUID,
    val state: String? = null,
    @Contextual
    val supplementaryId: UUID? = null,
    val offset: Long? = null,
    val limit: Int? = null,
    val languageTag: String? = null,
    val contentTypes: List<String>? = null,
    val includeMetadata: Boolean? = null,
    val includeCollections: Boolean? = null,
    val languageResolutionContext: String? = null,
) {

    constructor(collection: Collection) : this(collection.id, null, null, null, null, null)

    constructor(variant: CollectionLanguageVariant) : this(variant.id, null, null, null, null, variant.languageTag)
}

data class CollectionCacheKey(
    override val cacheName: String,
    override val key: CollectionCacheKeyId
) : CacheKey<CollectionCacheKeyId> {

    override fun toRemoteKey(prefix: Boolean): String = buildCacheKey(prefix) {
        appendKeyPrefix("c", cacheName)
        appendKeyPart(key.id)
        appendKeyPart(key.state)
        appendKeyPart(key.supplementaryId)
        appendKeyPart(key.offset)
        appendKeyPart(key.limit)
        appendKeyPart(key.languageTag)
        appendKeyPart(key.contentTypes?.sorted()?.joinToString(","))
        appendKeyPart(key.includeMetadata)
        appendKeyPart(key.includeCollections)
        appendKeyPart(key.languageResolutionContext)
    }
}

@Serializer("c")
object CollectionCacheKeySerializer : CacheKeySerializer<CollectionCacheKeyId> {

    override fun toLocalKey(cacheName: String, value: CollectionCacheKeyId): CacheKey<CollectionCacheKeyId> {
        return CollectionCacheKey(cacheName, value)
    }

    override fun fromRemoteKey(key: String): CacheKey<CollectionCacheKeyId> {
        val keyParts = key.separateForCacheKey()
        val id = UUID.parse(keyParts[1])
        val state = keyParts.getOrNull(2)
        val supplementaryId = keyParts.getOrNull(3)?.takeIf { it.isNotEmpty() }?.let { UUID.parse(it) }
        val offset = keyParts.getOrNull(4)?.toLongOrNull()
        val limit = keyParts.getOrNull(5)?.toIntOrNull()
        val languageTag = keyParts.getOrNull(6)?.takeIf { it.isNotBlank() }
        val contentTypes = keyParts.getOrNull(7)?.takeIf { it.isNotBlank() }?.split(',')
        val includeMetadata = keyParts.getOrNull(8)?.toBoolean() ?: true
        val includeCollections = keyParts.getOrNull(9)?.toBoolean() ?: true
        val languageResolutionContext = keyParts.getOrNull(10)?.takeIf { it.isNotBlank() }
        return CollectionCacheKey(
            keyParts.first(),
            CollectionCacheKeyId(
                id,
                state,
                supplementaryId,
                offset,
                limit,
                languageTag,
                contentTypes,
                includeMetadata,
                includeCollections,
                languageResolutionContext,
            ),
        )
    }
}
