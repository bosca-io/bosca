package bosca.recommendations.cache

import bosca.cache.CacheKey
import bosca.cache.CacheKeySerializer
import bosca.cache.annotations.Serializer
import bosca.cache.serializers.buildCacheKey
import bosca.cache.serializers.separateForCacheKey
import bosca.serialization.UUID

data class RecommendationFeedCacheKeyId(
    val profileId: UUID,
    val contextType: String? = null,
    val sourceLanguageTag: String? = null,
    val resolvedLanguageTag: String? = null,
    val modelVersion: Long? = null,
)

data class RecommendationFeedCacheKey(
    override val cacheName: String,
    override val key: RecommendationFeedCacheKeyId,
) : CacheKey<RecommendationFeedCacheKeyId> {

    override fun toRemoteKey(prefix: Boolean): String = buildCacheKey(prefix) {
        appendKeyPrefix(RECOMMENDATION_FEED_CACHE_KEY_PART, cacheName)
        appendKeyPart(key.profileId)
        appendKeyPart(key.contextType?.encodeCacheComponent())
        appendKeyPart(key.sourceLanguageTag?.encodeCacheComponent())
        appendKeyPart(key.resolvedLanguageTag?.encodeCacheComponent())
        appendKeyPart(key.modelVersion)
    }
}

@Serializer(RECOMMENDATION_FEED_CACHE_KEY_PART)
object RecommendationFeedCacheKeySerializer : CacheKeySerializer<RecommendationFeedCacheKeyId> {

    override fun toLocalKey(
        cacheName: String,
        value: RecommendationFeedCacheKeyId,
    ): CacheKey<RecommendationFeedCacheKeyId> = RecommendationFeedCacheKey(cacheName, value)

    override fun fromRemoteKey(key: String): CacheKey<RecommendationFeedCacheKeyId> {
        val parts = key.separateForCacheKey()
        return RecommendationFeedCacheKey(
            cacheName = parts[0],
            key = RecommendationFeedCacheKeyId(
                profileId = UUID.parse(parts[1]),
                contextType = parts.getOrNull(2)?.decodeCacheComponent(),
                sourceLanguageTag = parts.getOrNull(3)?.decodeCacheComponent(),
                resolvedLanguageTag = parts.getOrNull(4)?.decodeCacheComponent(),
                modelVersion = parts.getOrNull(5)?.takeIf { it.isNotEmpty() }?.toLong(),
            ),
        )
    }
}

// NATS replaces the cache-key separator with '-'. Encoding variable components with an alphabet that
// excludes '-' makes the component boundaries unambiguous after that sanitization. Hex also round-trips
// arbitrary Unicode context names and BCP 47 tags without depending on a platform-specific Base64 API.
private fun String.encodeCacheComponent(): String = encodeToByteArray().joinToString(separator = "") { byte ->
    val value = byte.toInt() and 0xff
    "${HEX_DIGITS[value ushr 4]}${HEX_DIGITS[value and 0x0f]}"
}

private fun String.decodeCacheComponent(): String {
    require(length % 2 == 0) { "Invalid recommendation feed cache-key component" }
    return ByteArray(length / 2) { index ->
        val offset = index * 2
        val high = digitToValue(this[offset])
        val low = digitToValue(this[offset + 1])
        ((high shl 4) or low).toByte()
    }.decodeToString()
}

private fun digitToValue(char: Char): Int = HEX_DIGITS.indexOf(char).also {
    require(it >= 0) { "Invalid recommendation feed cache-key component" }
}

private const val HEX_DIGITS = "0123456789abcdef"
private const val RECOMMENDATION_FEED_CACHE_KEY_PART = "rfd"
