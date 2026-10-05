package bosca.search.cache

import bosca.search.model.SearchQuery
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SearchQueryCacheKeyTest {

    private val cacheName = "search:results"

    private fun remoteKey(query: SearchQuery): String =
        SearchQueryCacheKeySerializer.toLocalKey(cacheName, query).toRemoteKey()

    @Test
    fun `equal queries produce identical, stable keys`() {
        val query = SearchQuery(query = "same", offset = 1, limit = 2, filter = listOf("x = 1"))
        assertEquals(remoteKey(query), remoteKey(query.copy()), "the digest is deterministic for equal queries")
    }

    @Test
    fun `queries differing only in text produce different keys`() {
        assertNotEquals(
            remoteKey(SearchQuery(query = "a", offset = 0, limit = 10)),
            remoteKey(SearchQuery(query = "b", offset = 0, limit = 10)),
        )
    }

    @Test
    fun `queries differing only in pagination produce different keys`() {
        assertNotEquals(
            remoteKey(SearchQuery(query = "a", offset = 0, limit = 10)),
            remoteKey(SearchQuery(query = "a", offset = 10, limit = 10)),
        )
    }

    @Test
    fun `queries differing only in filter produce different keys`() {
        assertNotEquals(
            remoteKey(SearchQuery(query = "a", offset = 0, limit = 10, filter = listOf("type = metadata"))),
            remoteKey(SearchQuery(query = "a", offset = 0, limit = 10, filter = listOf("type = collection"))),
        )
    }

    @Test
    fun `queries differing only in target index produce different keys`() {
        assertNotEquals(
            remoteKey(SearchQuery(query = "a", offset = 0, limit = 10, storageSystemName = "index-one")),
            remoteKey(SearchQuery(query = "a", offset = 0, limit = 10, storageSystemName = "index-two")),
        )
    }

    @Test
    fun `the key is fixed-length regardless of query size`() {
        val tiny = remoteKey(SearchQuery(query = "a", offset = 0, limit = 10))
        val huge = remoteKey(
            SearchQuery(
                query = "a".repeat(5_000),
                offset = 0,
                limit = 10,
                filter = List(200) { "field_$it = $it" },
                vector = List(1_536) { it * 0.001 },
            )
        )
        assertEquals(tiny.length, huge.length, "hashing bounds the key length no matter how large the query is")
    }

    @Test
    fun `the key contains no separator collisions when the query text contains the separator`() {
        // A raw query of "a::b" must not corrupt parsing — the digest alphabet excludes "::".
        val remote = remoteKey(SearchQuery(query = "a::b::c", offset = null, limit = null))
        val rebuilt = SearchQueryCacheKeySerializer.fromRemoteKey(remote)
        assertEquals(remote, rebuilt.toRemoteKey(), "the rebuilt key re-emits exactly the same remote key")
    }

    @Test
    fun `a key rebuilt from its remote form is equal to and re-emits the original`() {
        val query = SearchQuery(
            query = "complex",
            offset = 10,
            limit = 5,
            facets = listOf("type", "lang"),
            filter = listOf("type = metadata", "lang = en"),
            sort = listOf("name:asc"),
            semanticRatio = 0.5,
            storageSystemId = UUID.parse("00000000-0000-0000-0000-000000000001"),
            storageSystemName = "Default Search Index",
            vector = listOf(0.1, 0.2, 0.3),
        )
        val original = SearchQueryCacheKeySerializer.toLocalKey(cacheName, query)
        val rebuilt = SearchQueryCacheKeySerializer.fromRemoteKey(original.toRemoteKey())

        // Identity is the digest, so the two keys are interchangeable for cache lookups/eviction even
        // though the rebuilt one cannot recover the original query.
        assertEquals(original, rebuilt, "rebuilt key shares the original's identity")
        assertEquals(original.hashCode(), rebuilt.hashCode(), "and therefore the same hashCode")
        assertEquals(original.toRemoteKey(), rebuilt.toRemoteKey())
        assertEquals(cacheName, rebuilt.cacheName)
    }

    @Test
    fun `the remote key is namespaced by the type tag and cache name`() {
        val remote = remoteKey(SearchQuery(query = "a", offset = 0, limit = 10))
        assertTrue(remote.startsWith("$SearchQueryCacheKeyPart::$cacheName::"), "remote key: $remote")
    }
}
