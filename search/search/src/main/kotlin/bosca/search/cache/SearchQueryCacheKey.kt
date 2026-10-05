package bosca.search.cache

import bosca.cache.CacheKey
import bosca.cache.CacheKeySerializer
import bosca.cache.annotations.Serializer
import bosca.cache.serializers.buildCacheKey
import bosca.cache.serializers.separateForCacheKey
import bosca.search.model.SearchQuery
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.Base64

/** Remote cache-key type tag for [SearchQuery]-keyed caches. */
const val SearchQueryCacheKeyPart = "sq"

/**
 * Cache key for a [SearchQuery].
 *
 * The key's identity is a SHA-256 digest of the canonical JSON of the whole query — text, pagination,
 * filters, facets, sort, semantic ratio, target index, and vector. Hashing keeps the remote key short
 * and fixed-length no matter how large the query is (a client-supplied embedding `vector` could
 * otherwise make the key many kilobytes), and the Base64-url digest can never contain the `::`
 * separator the cache infrastructure splits on.
 *
 * The digest is one-way and intentionally so: [SearchQueryCacheKeySerializer.fromRemoteKey] does not
 * recover the original query, and nothing needs it to. Cache reads/writes resolve misses from the
 * original [SearchQuery] handed to the [bosca.cache.ServiceCache], and evictions key off identity
 * ([equals]/[hashCode]) or the remote-key string — never the query carried on the key. A key rebuilt
 * from a remote string therefore carries a [PLACEHOLDER] query but the correct (digest) identity.
 */
class SearchQueryCacheKey private constructor(
    override val cacheName: String,
    override val key: SearchQuery,
    private val digest: String,
) : CacheKey<SearchQuery> {

    override fun toRemoteKey(prefix: Boolean): String = buildCacheKey(prefix) {
        appendKeyPrefix(SearchQueryCacheKeyPart, cacheName)
        appendKeyPart(digest)
    }

    // Identity is (cacheName, digest) — NOT the query — so a forward-built key and one rebuilt via
    // fromRemoteKey compare equal, and the local request-cache tier (which keys by this object) hits.
    override fun equals(other: Any?): Boolean =
        this === other || (other is SearchQueryCacheKey && cacheName == other.cacheName && digest == other.digest)

    override fun hashCode(): Int = 31 * cacheName.hashCode() + digest.hashCode()

    override fun toString(): String = "SearchQueryCacheKey(cacheName=$cacheName, digest=$digest)"

    companion object {

        // A dedicated, stable Json so the digest is independent of any service-level Json configuration.
        private val json = Json { encodeDefaults = true }

        // Stand-in query for keys rebuilt from a remote string, where the original query is neither
        // recoverable nor used. Identity comes from the digest, not this value.
        private val PLACEHOLDER = SearchQuery(query = "", offset = null, limit = null)

        fun of(cacheName: String, query: SearchQuery): SearchQueryCacheKey =
            SearchQueryCacheKey(cacheName, query, digest(query))

        fun ofDigest(cacheName: String, digest: String): SearchQueryCacheKey =
            SearchQueryCacheKey(cacheName, PLACEHOLDER, digest)

        private fun digest(query: SearchQuery): String {
            val canonical = json.encodeToString(SearchQuery.serializer(), query).encodeToByteArray()
            val sha = MessageDigest.getInstance("SHA-256").digest(canonical)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sha)
        }
    }
}

/**
 * [CacheKeySerializer] for caches keyed by [SearchQuery]. Registered into the global
 * [bosca.cache.serializers.CacheKeyRegistry] via [Serializer].
 */
@Serializer(SearchQueryCacheKeyPart)
object SearchQueryCacheKeySerializer : CacheKeySerializer<SearchQuery> {

    override fun toLocalKey(cacheName: String, value: SearchQuery): CacheKey<SearchQuery> =
        SearchQueryCacheKey.of(cacheName, value)

    override fun fromRemoteKey(key: String): CacheKey<SearchQuery> {
        val parts = key.separateForCacheKey()
        return SearchQueryCacheKey.ofDigest(parts[0], parts[1])
    }
}
