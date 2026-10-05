package bosca.search.service

import bosca.content.collection.jobs.CollectionIndexExecutor
import bosca.content.collection.jobs.CollectionIndexJob
import bosca.content.collection.model.Collection
import bosca.content.collection.service.CollectionService
import bosca.content.metadata.jobs.MetadataIndexExecutor
import bosca.content.metadata.jobs.MetadataIndexJob
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataService
import bosca.content.search.isContentIndex
import bosca.cache.ServiceCache
import bosca.di.ObjectProvider
import bosca.di.annotation.ProviderName
import bosca.meilisearch.client.MeilisearchApiException
import bosca.meilisearch.client.MeilisearchClient
import bosca.meilisearch.client.model.HybridSearch
import bosca.meilisearch.client.model.SearchRequest
import bosca.meilisearch.client.waitForTask
import bosca.search.model.RawSearchResult
import bosca.profile.model.Profile
import bosca.profile.profile.jobs.ProfileIndexExecutor
import bosca.profile.profile.jobs.ProfileIndexJob
import bosca.profile.profile.service.ProfileService
import bosca.search.IndexStorageSystem
import bosca.search.Indexable
import bosca.search.cache.SearchQueryCacheKeySerializer
import bosca.search.configuration.JobQueueNames
import bosca.search.model.IndexConfiguration
import bosca.search.model.SearchFilter
import bosca.search.model.SearchDocument
import bosca.search.model.SearchQuery
import bosca.search.model.SearchResult
import bosca.search.model.SearchResultFacet
import bosca.search.pipeline.SearchDocumentPipeline
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.enqueue
import bosca.storage.model.StorageSystem
import bosca.storage.model.StorageSystemType
import bosca.storage.service.StorageSystemService
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.slf4j.LoggerFactory

@ServiceImplementation
class SearchServiceImpl(
    private val storage: StorageSystemService,
    private val metadataService: ObjectProvider<MetadataService>,
    private val collectionService: ObjectProvider<CollectionService>,
    private val profileService: ObjectProvider<ProfileService>,
    private val client: MeilisearchClient,
    private val json: kotlinx.serialization.json.Json,
    @ProviderName(JobQueueNames.indexJobQueue)
    private val jobQueue: JobQueue,
    private val meilisearchConfiguration: bosca.search.configuration.MeilisearchConfiguration,
) : SearchService {

    /**
     * Caches search responses keyed by the full [SearchQuery]. [search] and [searchRaw] share it —
     * they run the same Meilisearch query and differ only in how the cached hits are projected. The
     * cache is evicted whenever an index is mutated through this service (see [clearSearchCache]); the
     * TTL is only a backstop. Caching here (not in the GraphQL controller) is deliberate: the controller
     * applies per-user permission filtering on top of these results, so the cached value must stay
     * user-agnostic and shareable across requests.
     */
    private val searchCache = ServiceCache(
        SEARCH_CACHE_NAME,
        SearchQueryCacheKeySerializer,
    ) { query ->
        executeSearch(query)
    }

    override suspend fun search(query: SearchQuery): SearchResult {
        val raw = searchCache.get(query) ?: emptyResult(query)
        return SearchResult(
            documents = raw.hits.mapNotNull { it.toDocument() },
            estimatedHits = raw.estimatedHits,
            facets = raw.facets,
            system = raw.system,
        )
    }

    override suspend fun searchRaw(query: SearchQuery): RawSearchResult =
        searchCache.get(query) ?: emptyResult(query)

    /**
     * Runs the actual Meilisearch query. Invoked by [searchCache] on a miss, so its return value is
     * exactly what gets cached. A missing index is treated as an empty — but still cacheable — result
     * rather than an error, matching the previous behavior.
     */
    private suspend fun executeSearch(query: SearchQuery): RawSearchResult = try {
        val (system, configuration) = getConfiguration(query.storageSystemId, query.storageSystemName)
        val response = client.search(meilisearchConfiguration.indexUid(configuration.name), query.toRequest(configuration))
        RawSearchResult(
            hits = response.hits,
            estimatedHits = (response.estimatedTotalHits ?: response.totalHits ?: 0).toLong(),
            facets = response.facetDistribution.toFacets(),
            system = IndexStorageSystem(system.id, system.name),
        )
    } catch (e: MeilisearchApiException) {
        if (e.code == "index_not_found") {
            emptyResult(query)
        } else {
            throw e
        }
    }

    private fun emptyResult(query: SearchQuery): RawSearchResult =
        RawSearchResult(emptyList(), emptyList(), 0, IndexStorageSystem(query.storageSystemId, query.storageSystemName))

    /** Evicts all cached search responses. Called after any mutation of an index through this service. */
    private suspend fun clearSearchCache() = searchCache.clear()

    override suspend fun deleteAll(system: IndexStorageSystem) {
        val (_, configuration) = getConfiguration(system.id, system.name)
        val task = client.deleteAllDocuments(meilisearchConfiguration.indexUid(configuration.name))
        client.waitForTask(task.taskUid)
        clearSearchCache()
    }

    override suspend fun index(system: IndexStorageSystem, document: JsonElement) {
        index(system, listOf(document))
    }

    override suspend fun index(system: IndexStorageSystem, document: List<JsonElement>) {
        val (_, configuration) = getConfiguration(system.id, system.name)
        val task = client.addDocuments(meilisearchConfiguration.indexUid(configuration.name), json.encodeToString(document), "id")
        client.waitForTask(task.taskUid)
        clearSearchCache()
    }

    override suspend fun fetch(system: IndexStorageSystem, id: String): JsonElement? {
        val (_, configuration) = getConfiguration(system.id, system.name)
        val body = client.getDocument(meilisearchConfiguration.indexUid(configuration.name), id) ?: return null
        return json.parseToJsonElement(body)
    }

    override suspend fun delete(system: IndexStorageSystem, id: String) {
        val (_, configuration) = getConfiguration(system.id, system.name)
        val task = client.deleteDocument(meilisearchConfiguration.indexUid(configuration.name), id)
        client.waitForTask(task.taskUid)
        clearSearchCache()
    }

    override suspend fun deleteByFilter(system: IndexStorageSystem, filter: SearchFilter) {
        val (_, configuration) = getConfiguration(system.id, system.name)
        val task = client.deleteDocumentsByFilter(meilisearchConfiguration.indexUid(configuration.name), filter.toFilterString())
        client.waitForTask(task.taskUid)
        clearSearchCache()
    }

    override suspend fun index(item: Indexable) {
        val systems = storage.getAll().filter { it.accepts(item) }
        for (storageSystem in systems) {
            when (item) {
                is Metadata -> MetadataIndexJob(
                    storage = IndexStorageSystem(storageSystem.id, storageSystem.name),
                    id = item.id,
                    version = item.version
                ).enqueue(jobQueue, MetadataIndexExecutor::class)

                is Collection -> CollectionIndexJob(
                    storage = IndexStorageSystem(storageSystem.id, storageSystem.name),
                    id = item.id,
                ).enqueue(jobQueue, CollectionIndexExecutor::class)

                is Profile -> ProfileIndexJob(
                    storage = IndexStorageSystem(storageSystem.id, storageSystem.name),
                    id = item.id
                ).enqueue(jobQueue, ProfileIndexExecutor::class)

                else -> {
                    log.error("unsupported item type: ${item::class.simpleName}")
                    continue
                }
            }
        }
    }

    override suspend fun delete(item: Indexable) {
        for (storageSystem in storage.getAll().filter { it.accepts(item) }) {
            when (item) {
                is Metadata -> MetadataIndexJob(
                    storage = IndexStorageSystem(storageSystem.id),
                    deleteOnly = true,
                    id = item.id,
                    version = item.version
                ).enqueue(jobQueue, MetadataIndexExecutor::class)

                is Collection -> CollectionIndexJob(
                    storage = IndexStorageSystem(storageSystem.id),
                    deleteOnly = true,
                    id = item.id,
                ).enqueue(jobQueue, CollectionIndexExecutor::class)

                is Profile -> ProfileIndexJob(
                    storage = IndexStorageSystem(storageSystem.id),
                    deleteOnly = true,
                    id = item.id
                ).enqueue(jobQueue, ProfileIndexExecutor::class)

                else -> {
                    log.error("unsupported item type: ${item::class.simpleName}")
                    continue
                }
            }
        }
    }

    private fun SearchQuery.toRequest(configuration: IndexConfiguration): SearchRequest = SearchRequest(
        q = query,
        offset = offset,
        limit = limit,
        facets = facets,
        filter = filter,
        sort = sort,
        hybrid = configuration.embedders.firstOrNull()?.let {
            HybridSearch(
                embedder = it.name,
                semanticRatio = semanticRatio ?: configuration.chat?.searchParameters?.hybrid?.semanticRatio,
            )
        },
        vector = vector,
    )

    private suspend fun JsonObject.toDocument(): SearchDocument? {
        val documentId = (((this["contentId"] as? JsonPrimitive)?.contentOrNull)
            ?: ((this["id"] as? JsonPrimitive)?.contentOrNull))?.let { UUID.parseOrNull(it) } ?: return null
        val type = (this["_type"] as? JsonPrimitive)?.contentOrNull
        // The index is eventually consistent with the database, so a hit can reference an entity
        // that has since been deleted. Skip such hits instead of failing the whole search.
        val document = when (type) {
            "metadata" -> SearchDocument(metadata = metadataService.get().getById(documentId))
            "collection" -> SearchDocument(collection = collectionService.get().getById(documentId))
            "profile" -> SearchDocument(profile = profileService.get().getAllByIds(listOf(documentId)).firstOrNull())
            else -> null
        } ?: return null
        if (document.metadata == null && document.collection == null && document.profile == null) {
            log.warn("Search hit references a missing $type: $documentId, skipping (stale index entry)")
            return null
        }
        return document
    }

    private fun Map<String, Map<String, Int>>?.toFacets(): List<SearchResultFacet> =
        this?.flatMap { (field, values) ->
            values.map { (value, count) ->
                SearchResultFacet(field = field, value = value, count = count.toLong())
            }
        } ?: emptyList()

    private suspend fun getConfiguration(id: UUID?, name: String?): Pair<StorageSystem, IndexConfiguration> {
        val storageSystem = when {
            id != null -> storage.get(id)
            name != null -> storage.getByName(name)
            else -> error("system id or name is required")
        } ?: throw NoSuchElementException("storage system not found")
        val configElement = storageSystem.configuration.takeIf { it != JsonNull }
            ?: JsonObject(mapOf(
                "indexName" to JsonPrimitive(name ?: storageSystem.name)
            ))
        return Pair(
            storageSystem,
            json.decodeFromJsonElement(IndexConfiguration.serializer(), configElement)
        )
    }

    companion object {

        private const val SEARCH_CACHE_NAME = "search:results"

        private val log = LoggerFactory.getLogger(SearchServiceImpl::class.java)
    }
}

private fun StorageSystem.accepts(item: Indexable): Boolean =
    type == StorageSystemType.SEARCH && when (item) {
        is Profile -> name == SearchDocumentPipeline.PROFILE_INDEX || name == SearchDocumentPipeline.ADMIN_INDEX
        is Metadata, is Collection -> isContentIndex
        else -> false
    }
