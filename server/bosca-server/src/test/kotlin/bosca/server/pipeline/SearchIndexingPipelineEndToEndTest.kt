@file:OptIn(InternalDI::class)

package bosca.server.pipeline

import bosca.cache.Cache
import bosca.cache.CacheKey
import bosca.cache.CacheKeySerializer
import bosca.cache.CacheManager
import bosca.cache.CacheValue
import bosca.cache.RequestCacheSerializer
import bosca.cache.RequestCacheSerializerImpl
import bosca.cache.withRequestCache
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.pipeline.MetadataEventToMetadataNode
import bosca.content.metadata.service.MetadataService
import bosca.content.transformations.MetadataToSearchDocument
import bosca.content.transformations.pipeline.BuildMetadataSearchDocumentNode
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.meilisearch.client.MeilisearchClient
import bosca.meilisearch.client.waitForTask
import bosca.pipelines.PipelineContext
import bosca.pipelines.PipelineExecutorImpl
import bosca.pipelines.builtin.GetIdNode
import bosca.pipelines.model.Pipeline
import bosca.pipelines.model.PipelineEdge
import bosca.pipelines.node.InputNode
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.service.requireCompleted
import bosca.search.IndexStorageSystem
import bosca.search.model.IndexConfiguration
import bosca.search.model.MetadataSearchContext
import bosca.search.pipeline.IndexDocumentNode
import bosca.search.service.SearchService
import bosca.search.service.SearchServiceImpl
import bosca.security.service.AuthenticationContext
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.storage.model.StorageSystem
import bosca.storage.model.StorageSystemType
import bosca.storage.service.StorageSystemService
import bosca.test.resources.SharedMeilisearchContainer
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
 * End-to-end proof that the seeded "index metadata" pipeline shape actually works: the real
 * [PipelineExecutorImpl] runs the real node chain `Input → Get Id → Get Metadata → Build Metadata
 * Search Document → Index Document` and the document lands in a **real Meilisearch** (Testcontainers),
 * including the visibility gate routing a no-longer-published entity to removal.
 *
 * The pipeline engine, the Build node (gate + JSONata + encode), the Index node, and Meilisearch are
 * all real. Only the *data sources* are mocked — `MetadataService` returns the entity and
 * `MetadataToSearchDocument.toContext` returns the rich context (both unit-tested elsewhere). The
 * async event→job→runner hop (NATS) is out of scope here; the executor's durable path is covered by
 * `PipelineDurableExecutionEndToEndTest` and triggering by `PipelineEventDispatcherImplTest`.
 *
 * Requires Docker.
 */
class SearchIndexingPipelineEndToEndTest {

    private lateinit var meili: SharedMeilisearchContainer
    private lateinit var client: MeilisearchClient
    private lateinit var searchService: SearchService
    private val metadataService = mockk<MetadataService>()
    private val transform = mockk<MetadataToSearchDocument>()

    private lateinit var indexUid: String
    private val json = Json { ignoreUnknownKeys = true }
    private val system = IndexStorageSystem(name = "Default Search Index")
    private val cacheManager = InMemoryCacheManager()
    private val context get() = PipelineContext(AuthenticationContext(null, null), json)
    private val executor = PipelineExecutorImpl()

    // JSONata that turns the built context into a minimal search document with the required `id` key.
    private val expression =
        """{ "id": ${'$'}string(metadata.id), "contentId": ${'$'}string(metadata.id), "name": metadata.name, "_type": "metadata" }"""

    @BeforeTest
    fun setup() {
        meili = SharedMeilisearchContainer()
        meili.start()
        indexUid = meili.indexUid("test")
        runBlocking {
            client = MeilisearchClient(meili.url, meili.apiKey, json)
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
                    IndexConfiguration(name = indexUid, primaryKey = "id", filterable = listOf("contentId", "_type")),
                ),
            )
            coEvery { storage.getByName(any()) } returns storageSystem
            coEvery { storage.get(any()) } returns storageSystem

            // SearchServiceImpl now caches search responses, so it needs a CacheManager at construction and a
            // request-cache context around the index mutations that evict the cache — the pipeline's index and
            // removal nodes route through `searchService.index`/`delete`, which clear the cache. Production gets
            // that context from JobRunner/SuspendDataFetcher; here we register an in-memory cache and wrap the
            // pipeline runs in withRequestCache.
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
                ),
            )
            provides<MetadataService> { metadataService }
            provides<MetadataToSearchDocument> { transform }
            provides<SearchService> { searchService }
        }
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
        if (::meili.isInitialized) meili.stop()
    }

    private fun metadata(id: UUID, workflowStateId: String) = Metadata(
        id = id,
        name = "Hello",
        type = MetadataType.STANDARD,
        languageTag = "en",
        contentType = "text/plain",
        contentLength = 10,
        created = OffsetDateTime.now(),
        modified = OffsetDateTime.now(),
        public = true,
        workflowStateId = workflowStateId,
    )

    private fun metadataContext(metadata: Metadata) = MetadataSearchContext(
        metadata = metadata,
        content = "",
        relationships = emptyMap(),
        categories = emptyList(),
        collections = emptyMap(),
        attributes = JsonObject(emptyMap()),
        slug = "",
        bibleBooks = null,
    )

    /** The seeded "index metadata" graph, single Default-index branch. */
    private fun indexPipeline() = Pipeline(
        id = UUID.random(),
        name = "Index Metadata — Created",
        acceptedInputType = "test.MetadataCreated",
        triggered = true,
        nodes = listOf(
            InputNode(id = "input", acceptedType = "test.MetadataCreated"),
            GetIdNode(id = "getId"),
            MetadataEventToMetadataNode(id = "get"),
            BuildMetadataSearchDocumentNode(id = "build", expression = expression),
            IndexDocumentNode(id = "index"),
        ),
        edges = listOf(
            PipelineEdge(id = "e1", source = "input", target = "getId"),
            PipelineEdge(id = "e2", source = "getId", target = "get"),
            PipelineEdge(id = "e3", source = "get", target = "build"),
            PipelineEdge(id = "e4", source = "build", target = "index"),
        ),
    )

    private fun eventInput(id: UUID) = PipelineValue.ofJson(buildJsonObject { put("id", id.toString()) })

    @Test
    fun `the seeded metadata index pipeline runs end to end into real meilisearch`() = runBlocking {
        withRequestCache {
            val id = UUID.random()
            val md = metadata(id, "published")
            coEvery { metadataService.getById(id) } returns md
            coEvery { transform.toContext(any(), md) } returns metadataContext(md)

            executor.execute(indexPipeline(), eventInput(id), context).requireCompleted()

            val stored = searchService.fetch(system, id.toString())
            assertNotNull(stored, "the pipeline indexed the metadata")
            assertEquals("Hello", stored.jsonObject["name"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `the pipeline removes a metadata that is no longer published`() = runBlocking {
        withRequestCache {
            val id = UUID.random()
            val published = metadata(id, "published")
            coEvery { metadataService.getById(id) } returns published
            coEvery { transform.toContext(any(), published) } returns metadataContext(published)
            executor.execute(indexPipeline(), eventInput(id), context).requireCompleted()
            assertNotNull(searchService.fetch(system, id.toString()), "indexed while published")

            // The same metadata becomes unpublished — the Build node's gate routes it to a removal signal.
            coEvery { metadataService.getById(id) } returns metadata(id, "draft")
            executor.execute(indexPipeline(), eventInput(id), context).requireCompleted()
            assertNull(searchService.fetch(system, id.toString()), "unpublishing removed it from the index")
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
