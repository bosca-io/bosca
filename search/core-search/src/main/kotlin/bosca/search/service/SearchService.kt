package bosca.search.service

import bosca.search.IndexStorageSystem
import bosca.search.Indexable
import bosca.search.model.RawSearchResult
import bosca.search.model.SearchFilter
import bosca.search.model.SearchQuery
import bosca.search.model.SearchResult
import bosca.serialization.UUID
import bosca.service.Service
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Service for indexing, deleting, and querying documents in the platform's search engine
 * (e.g., Meilisearch).
 *
 * Documents are organized into indexes identified by [IndexStorageSystem] entries. This service
 * supports both low-level operations on raw JSON documents within a specific index and
 * higher-level operations on [Indexable] domain objects that know how to represent themselves
 * for search indexing.
 */
interface SearchService : Service {

    /**
     * Executes a search query and returns matching results with optional faceting, filtering,
     * sorting, and semantic search capabilities.
     *
     * @param query the [SearchQuery] containing the search text, filters, pagination, and target index
     * @return a [SearchResult] containing the matched documents and facet distributions
     */
    suspend fun search(query: SearchQuery): SearchResult

    /**
     * Executes a search query and returns raw JSON hits without resolving them to domain
     * objects. Use this when querying indexes that contain document types not known to the
     * core content model (e.g., git files, custom plugin documents).
     *
     * @param query the [SearchQuery] containing the search text, filters, pagination, and target index
     * @return a [RawSearchResult] containing the raw JSON hits and facet distributions
     */
    suspend fun searchRaw(query: SearchQuery): RawSearchResult

    /**
     * Removes all indexed documents from the specified index, effectively clearing it.
     *
     * @param system the index storage system whose documents should be deleted
     */
    suspend fun deleteAll(system: IndexStorageSystem)

    /**
     * Indexes a single JSON document into the specified index. If a document with the
     * same primary key already exists, it will be replaced.
     *
     * @param system the target index storage system
     * @param document the JSON document to index
     */
    suspend fun index(system: IndexStorageSystem, document: JsonElement)

    /**
     * Indexes a batch of JSON documents into the specified index. Documents whose primary
     * keys already exist will be replaced.
     *
     * @param system the target index storage system
     * @param document the list of JSON documents to index
     */
    suspend fun index(system: IndexStorageSystem, document: List<JsonElement>)

    /**
     * Fetches a single raw JSON document from the specified index by its primary key, or
     * returns `null` if no document with that key exists. Use this for read-modify-write
     * flows where the caller needs to mutate an existing document's fields and write it
     * back (e.g., maintaining an overlay/tag array on a content-addressed document).
     *
     * @param system the index storage system to read from
     * @param id the primary key of the document to fetch
     */
    suspend fun fetch(system: IndexStorageSystem, id: String): JsonElement?

    /**
     * Deletes a single document from the specified index by its primary key.
     *
     * @param system the index storage system to delete from
     * @param id the primary key of the document to delete
     */
    suspend fun delete(system: IndexStorageSystem, id: String)

    /**
     * Deletes all documents from the specified index that match the given filter conditions.
     * The filter is constructed safely from a [SearchFilter] to prevent filter injection attacks.
     *
     * @param system the index storage system to delete from
     * @param filter the type-safe filter conditions identifying documents to delete
     */
    suspend fun deleteByFilter(system: IndexStorageSystem, filter: SearchFilter)

    /**
     * Indexes an [Indexable] domain object, which self-describes its search representation.
     * Only items where [Indexable.isSearchable] is `true` will typically be indexed.
     *
     * @param item the domain object to index
     */
    suspend fun index(item: Indexable)

    /**
     * Removes an [Indexable] domain object from the search index.
     *
     * @param item the domain object to remove from the index
     */
    suspend fun delete(item: Indexable)
}
