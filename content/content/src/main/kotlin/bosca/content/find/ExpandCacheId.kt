package bosca.content.find

import bosca.cache.CacheKey
import bosca.cache.CacheKeySerializer
import bosca.cache.annotations.Serializer
import bosca.cache.serializers.buildCacheKey
import bosca.cache.serializers.separateForCacheKey
import bosca.content.ordering.Ordering
import bosca.serialization.JsonConverter.asJsonElement
import bosca.serialization.JsonConverter.asValue
import bosca.serialization.UUID

data class ExpandCacheId(
    val id: UUID,
    val state: String? = null,
    val ordering: List<Ordering>? = null,
    val offset: Long? = null,
    val limit: Int? = null
)

data class ExpandCacheKey(override val cacheName: String, override val key: ExpandCacheId) : CacheKey<ExpandCacheId> {

    override fun toRemoteKey(prefix: Boolean): String = buildCacheKey(prefix) {
        appendKeyPrefix("expd", cacheName)
        appendKeyPart(key.id)
        appendKeyPart(key.state)
        appendKeyPart(key.ordering?.asJsonElement()?.toString())
        appendKeyPart(key.offset)
        appendKeyPart(key.limit)
    }
}

@Serializer("expd")
object ExpandCacheKeySerializer : CacheKeySerializer<ExpandCacheId> {

    override fun toLocalKey(cacheName: String, value: ExpandCacheId): CacheKey<ExpandCacheId> {
        return ExpandCacheKey(cacheName, value)
    }

    override fun fromRemoteKey(key: String): CacheKey<ExpandCacheId> {
        val keyParts = key.separateForCacheKey()
        val ordering: List<Ordering>? = keyParts.getOrNull(3)?.asJsonElement()?.asValue<List<Ordering>>()
        val id = ExpandCacheId(UUID.parse(keyParts[1]), keyParts.getOrNull(2), ordering, keyParts.getOrNull(4)?.toLong(), keyParts.getOrNull(5)?.toInt())
        return ExpandCacheKey(keyParts.first(), id)
    }
}
