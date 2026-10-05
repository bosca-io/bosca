package bosca.recommendations.cache

import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class RecommendationFeedCacheKeyTest {

    @Test
    fun `model activation separates cached feeds while preserving profile invalidation`() {
        val original = RecommendationFeedCacheKeyId(UUID.random(), "default", "en-US", "en", 12)
        val activated = original.copy(modelVersion = 13)
        val originalKey = RecommendationFeedCacheKeySerializer.toLocalKey("recommendations:feed", original)
        val activatedKey = RecommendationFeedCacheKeySerializer.toLocalKey("recommendations:feed", activated)

        assertNotEquals(originalKey.toRemoteKey(), activatedKey.toRemoteKey())
        assertEquals(originalKey.toRemoteKeyPrefix(), activatedKey.toRemoteKeyPrefix())
        assertEquals(original, RecommendationFeedCacheKeySerializer.fromRemoteKey(originalKey.toRemoteKey()).key)
        assertEquals(activated, RecommendationFeedCacheKeySerializer.fromRemoteKey(activatedKey.toRemoteKey()).key)
    }

    @Test
    fun `cache key separates context and language representations and round trips`() {
        val profileId = UUID.random()
        val defaultEnglish = RecommendationFeedCacheKeyId(profileId, "default", "en", "en")
        val assetsEnglish = RecommendationFeedCacheKeyId(profileId, "assets", "en", "en")
        val regionalEnglish = RecommendationFeedCacheKeyId(profileId, "default", "en-US", "en")
        val defaultFrench = RecommendationFeedCacheKeyId(profileId, "default", "fr-CA", "fr")
        val profilePrefix = RecommendationFeedCacheKeySerializer.toLocalKey(
            "recommendations:feed",
            RecommendationFeedCacheKeyId(profileId),
        )
        val otherProfilePrefix = RecommendationFeedCacheKeySerializer.toLocalKey(
            "recommendations:feed",
            RecommendationFeedCacheKeyId(UUID.random()),
        )

        val defaultKey = RecommendationFeedCacheKeySerializer.toLocalKey("recommendations:feed", defaultEnglish)
        val assetsKey = RecommendationFeedCacheKeySerializer.toLocalKey("recommendations:feed", assetsEnglish)
        val regionalKey = RecommendationFeedCacheKeySerializer.toLocalKey("recommendations:feed", regionalEnglish)
        val frenchKey = RecommendationFeedCacheKeySerializer.toLocalKey("recommendations:feed", defaultFrench)

        assertNotEquals(defaultKey.toRemoteKey(), assetsKey.toRemoteKey())
        assertNotEquals(defaultKey.toRemoteKey(), regionalKey.toRemoteKey())
        assertNotEquals(defaultKey.toRemoteKey(), frenchKey.toRemoteKey())
        assertEquals(defaultKey.toRemoteKeyPrefix(), assetsKey.toRemoteKeyPrefix())
        assertEquals(defaultKey.toRemoteKeyPrefix(), regionalKey.toRemoteKeyPrefix())
        assertEquals(defaultKey.toRemoteKeyPrefix(), frenchKey.toRemoteKeyPrefix())
        assertEquals(defaultKey.toRemoteKeyPrefix(), profilePrefix.toRemoteKeyPrefix())
        assertNotEquals(profilePrefix.toRemoteKeyPrefix(), otherProfilePrefix.toRemoteKeyPrefix())
        assertEquals(defaultEnglish, RecommendationFeedCacheKeySerializer.fromRemoteKey(defaultKey.toRemoteKey()).key)
        assertEquals(regionalEnglish, RecommendationFeedCacheKeySerializer.fromRemoteKey(regionalKey.toRemoteKey()).key)
        assertEquals(defaultFrench, RecommendationFeedCacheKeySerializer.fromRemoteKey(frenchKey.toRemoteKey()).key)
    }

    @Test
    fun `cache key remains unambiguous after NATS sanitization`() {
        val profileId = UUID.random()
        val first = RecommendationFeedCacheKeyId(profileId, "foo-en-x", "fr", "en")
        val second = RecommendationFeedCacheKeyId(profileId, "foo", "en-x-fr", "en")
        val firstRemote = RecommendationFeedCacheKeySerializer
            .toLocalKey("recommendations:feed", first)
            .toRemoteKey()
        val secondRemote = RecommendationFeedCacheKeySerializer
            .toLocalKey("recommendations:feed", second)
            .toRemoteKey()

        // Mirrors NatsCache.sanitizeForNats, which is internal to the cache module. These two tuples
        // collided when their raw hyphenated values were concatenated with a separator that also became '-'.
        fun String.forNats() = replace("::", "-").replace(':', '-').replace(Regex("[^A-Za-z0-9\\-_/.=]"), "_")

        assertNotEquals(firstRemote.forNats(), secondRemote.forNats())
        assertEquals(first, RecommendationFeedCacheKeySerializer.fromRemoteKey(firstRemote).key)
        assertEquals(second, RecommendationFeedCacheKeySerializer.fromRemoteKey(secondRemote).key)
    }

    @Test
    fun `profile invalidation prefixes and partial cache keys decode without inventing facets`() {
        val id = UUID.random()
        val prefix = RecommendationFeedCacheKeySerializer.toLocalKey("recommendations:feed", RecommendationFeedCacheKeyId(id)).toRemoteKeyPrefix()
        assertEquals(RecommendationFeedCacheKeyId(id), RecommendationFeedCacheKeySerializer.fromRemoteKey(prefix).key)
        assertEquals(RecommendationFeedCacheKeyId(id, "reading"), RecommendationFeedCacheKeySerializer.fromRemoteKey("$prefix::72656164696e67").key)
        assertEquals(RecommendationFeedCacheKeyId(id, "reading", "en"), RecommendationFeedCacheKeySerializer.fromRemoteKey("$prefix::72656164696e67::656e").key)
        for (invalid in listOf("f", "gz", "0g")) {
            kotlin.test.assertFailsWith<IllegalArgumentException> {
                RecommendationFeedCacheKeySerializer.fromRemoteKey("$prefix::$invalid")
            }
        }
    }

}
