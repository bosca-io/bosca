@file:OptIn(InternalDI::class)

package bosca.search.pipeline

import bosca.cache.Cache
import bosca.cache.CacheKey
import bosca.cache.CacheKeySerializer
import bosca.cache.CacheManager
import bosca.cache.CacheValue
import bosca.cache.RequestCacheSerializer
import bosca.cache.RequestCacheSerializerImpl
import bosca.cache.withRequestCache
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.meilisearch.client.MeilisearchClient
import bosca.meilisearch.client.waitForTask
import bosca.pipelines.PipelineContext
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.PipelineValue
import bosca.search.IndexStorageSystem
import bosca.search.model.IndexConfiguration
import bosca.search.model.SearchQuery
import bosca.search.service.SearchService
import bosca.search.service.SearchServiceImpl
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.storage.model.StorageSystem
import bosca.storage.model.StorageSystemType
import bosca.storage.service.StorageSystemService
import bosca.test.resources.SharedMeilisearchContainer
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration

/**
 * End-to-end test of the [IndexDocumentNode] against a **real Meilisearch** (Testcontainers) through
 * the real [SearchServiceImpl] — the wiring that unit tests with a mocked SearchService can't cover:
 * index-name resolution, the document id keying, batch indexing, and the filtered deletes behind
 * `replace` and the removal signal. Only the storage-system lookup is mocked (to point the index name
 * at the container's index); the index write/read path is genuine.
 *
 * Requires Docker; runs in CI / any environment with a Docker daemon, like the other `*IntegrationTest`
 * / `*EndToEndTest` suites in the repo.
 */
class IndexDocumentNodeIntegrationTest {

    private lateinit var meili: SharedMeilisearchContainer
    private lateinit var client: MeilisearchClient
    private lateinit var searchService: SearchService

    private lateinit var indexUid: String
    private val json = Json { ignoreUnknownKeys = true }
    private val system = IndexStorageSystem(name = "Default Search Index")
    private val cacheManager = InMemoryCacheManager()
    private val context get() = PipelineContext(AuthenticationContext(null, null), json)

    @BeforeTest
    fun setup() {
        meili = SharedMeilisearchContainer()
        meili.start()
        val logicalIndexUid = "test"
        indexUid = meili.indexUid("sitea_$logicalIndexUid")
        runBlocking {
            client = MeilisearchClient(url = meili.url, apiKey = meili.apiKey, json = json)
            // Create the index and make contentId filterable so the replace/removal deleteByFilter works.
            client.waitForTask(client.createIndex(indexUid, "id").taskUid)
            client.waitForTask(client.updateFilterableAttributes(indexUid, listOf("contentId", "_type")).taskUid)

            val storage = mockk<StorageSystemService>()
            val storageSystem = StorageSystem(
                id = UUID.random(),
                name = "Default Search Index",
                description = "",
                type = StorageSystemType.SEARCH,
                configuration = json.encodeToJsonElement(
                    IndexConfiguration.serializer(),
                    IndexConfiguration(name = logicalIndexUid, primaryKey = "id", filterable = listOf("contentId", "_type")),
                ),
            )
            coEvery { storage.getByName(any()) } returns storageSystem
            coEvery { storage.get(any()) } returns storageSystem

            // SearchServiceImpl now caches search responses, so it needs a CacheManager at construction
            // and a request-cache context around index mutations (which evict the cache). Production gets
            // the latter from JobRunner/SuspendDataFetcher; here we register an in-memory cache and wrap
            // the exercising calls in withRequestCache.
            ProviderRegistry.clear()
            provides<CacheManager> { cacheManager }
            provides<RequestCacheSerializer> { RequestCacheSerializerImpl(json) }
            searchService = SearchServiceImpl(
                storage = storage,
                metadataService = mockk(relaxed = true),
                collectionService = mockk(relaxed = true),
                profileService = mockk(relaxed = true),
                client = client,
                json = json,
                jobQueue = mockk(relaxed = true),
                meilisearchConfiguration = bosca.search.configuration.MeilisearchConfiguration(
                    url = meili.url,
                    apiKey = meili.apiKey,
                    indexPrefix = "${meili.namespace}_sitea_",
                ),
            )
            provides<SearchService> { searchService }
        }
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
        if (::meili.isInitialized) meili.stop()
    }

    private fun input(value: JsonElement) = NodeInputs(mapOf("in" to PipelineValue.ofJson(value)))

    @Test
    fun `indexes a document into meilisearch and it is retrievable`() = runBlocking {
        withRequestCache {
            val doc = buildJsonObject {
                put("id", "m1"); put("contentId", "m1"); put("_type", "metadata"); put("name", "Hello")
            }
            IndexDocumentNode(id = "n1").run(context, input(doc))

            val stored = searchService.fetch(system, "m1")
            assertNotNull(stored, "the document was indexed")
            assertEquals("Hello", stored.jsonObject["name"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `replace clears variants dropped from the new document set`() = runBlocking {
        withRequestCache {
            val both = buildJsonArray {
                add(buildJsonObject { put("id", "c-en"); put("contentId", "c"); put("_type", "collection") })
                add(buildJsonObject { put("id", "c-fr"); put("contentId", "c"); put("_type", "collection") })
            }
            IndexDocumentNode(id = "n1", replace = true).run(context, input(both))
            assertNotNull(searchService.fetch(system, "c-en"))
            assertNotNull(searchService.fetch(system, "c-fr"))

            // Re-index with only the English variant present — the French variant must be cleared.
            val onlyEn = buildJsonArray {
                add(buildJsonObject { put("id", "c-en"); put("contentId", "c"); put("_type", "collection") })
            }
            IndexDocumentNode(id = "n1", replace = true).run(context, input(onlyEn))
            assertNotNull(searchService.fetch(system, "c-en"), "the kept variant remains")
            assertNull(searchService.fetch(system, "c-fr"), "the dropped variant is removed by replace")
        }
    }

    @Test
    fun `a removal signal deletes the document`() = runBlocking {
        withRequestCache {
            IndexDocumentNode(id = "n1").run(
                context,
                input(buildJsonObject { put("id", "p1"); put("contentId", "p1"); put("_type", "profile") }),
            )
            assertNotNull(searchService.fetch(system, "p1"))

            val signal = buildJsonObject {
                put(SearchDocumentPipeline.CONTENT_ID_FIELD, "p1")
                put(SearchDocumentPipeline.ACTION_FIELD, SearchDocumentPipeline.ACTION_DELETE)
            }
            IndexDocumentNode(id = "n1").run(context, input(signal))
            assertNull(searchService.fetch(system, "p1"), "the removal signal deletes the document")
        }
    }

    @Test
    fun `searchRaw caches results and indexing evicts the cache`() = runBlocking {
        withRequestCache {
            val query = SearchQuery(query = "", offset = 0, limit = 20, storageSystemName = system.name)

            searchService.index(system, listOf(buildJsonObject {
                put("id", "d1"); put("contentId", "d1"); put("_type", "metadata")
            }))
            // First search runs against Meilisearch and populates the cache.
            assertEquals(1, searchService.searchRaw(query).hits.size, "the first search sees the indexed document")

            // Indexing a second document must evict the cached single-hit result so the next search is fresh.
            searchService.index(system, listOf(buildJsonObject {
                put("id", "d2"); put("contentId", "d2"); put("_type", "metadata")
            }))
            assertEquals(2, searchService.searchRaw(query).hits.size, "indexing evicted the cache; the new document is visible")
        }
    }

    // Minimal in-memory CacheManager so the cached SearchServiceImpl can be exercised without a real
    // Redis/NATS backend. Stores serialized payloads by remote key, exactly like the production tiers.
    private class InMemoryCacheManager : CacheManager {

        private val caches = ConcurrentHashMap<String, Cache<*>>()

        override val cacheNames: Set<String> get() = caches.keys

        override suspend fun <K> maybeAddCache(name: String, keySerializer: CacheKeySerializer<K>, expiration: Duration): Cache<K> {
            @Suppress("UNCHECKED_CAST")
            return caches.getOrPut(name) { InMemoryCache(keySerializer) } as Cache<K>
        }

        @Suppress("UNCHECKED_CAST")
        override suspend fun <K> getCache(name: String): Cache<K> =
            (caches[name] ?: error("cache $name not found")) as Cache<K>

        override suspend fun evictExpiredItems() {}

        override suspend fun clearAll() = caches.values.forEach { it.clear() }
    }

    private class InMemoryCache<K>(override val keySerializer: CacheKeySerializer<K>) : Cache<K> {

        private val store = ConcurrentHashMap<String, String>()

        override suspend fun get(key: CacheKey<K>): CacheValue {
            val k = key.toRemoteKey()
            return SimpleCacheValue(store[k], store.containsKey(k))
        }

        override suspend fun getBatch(keys: List<CacheKey<K>>): List<CacheValue> = keys.map { get(it) }

        override suspend fun put(key: CacheKey<K>, value: String?) {
            if (value == null) store.remove(key.toRemoteKey()) else store[key.toRemoteKey()] = value
        }

        override suspend fun putBatch(entries: List<Pair<CacheKey<K>, String?>>) =
            entries.forEach { (key, value) -> put(key, value) }

        override suspend fun remove(key: CacheKey<K>, keyPrefix: Boolean): CacheValue? {
            if (keyPrefix) {
                val prefix = key.toRemoteKeyPrefix()
                store.keys.filter { it.startsWith(prefix) }.forEach { store.remove(it) }
                return null
            }
            val old = store.remove(key.toRemoteKey())
            return old?.let { SimpleCacheValue(it, true) }
        }

        override suspend fun clear() = store.clear()

        override suspend fun evictExpiredItems() {}

        override val estimatedSize: Long get() = store.size.toLong()
    }

    private class SimpleCacheValue(override val value: String?, override val exists: Boolean) : CacheValue
}
