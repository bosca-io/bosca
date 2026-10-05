@file:OptIn(ExperimentalUuidApi::class)

package bosca.meilisearch.admin.service

import bosca.meilisearch.admin.model.MeilisearchGlobalStats
import bosca.meilisearch.admin.model.MeilisearchIndexInfo
import bosca.meilisearch.admin.model.MeilisearchIndexSettings
import bosca.meilisearch.admin.model.MeilisearchIndexStats
import bosca.meilisearch.admin.model.MeilisearchKey
import bosca.meilisearch.admin.model.MeilisearchNetworkRemote
import bosca.meilisearch.admin.model.MeilisearchNode
import bosca.meilisearch.admin.model.MeilisearchTask
import bosca.meilisearch.admin.model.MeilisearchVersion
import bosca.serialization.UUID
import bosca.service.Service
import bosca.storage.model.StorageSystemType
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Provides administrative access to Meilisearch instance state, index management,
 * embedder configuration for semantic search, task monitoring, API key introspection,
 * and multi-node topology management including replication and federation network setup.
 */
interface MeilisearchAdminService : Service {

    /**
     * Retrieves the Meilisearch server version including package version,
     * commit SHA, and commit date.
     */
    suspend fun getVersion(): MeilisearchVersion

    /**
     * Checks whether the primary Meilisearch instance is healthy and accepting requests.
     */
    suspend fun getHealth(): Boolean

    /**
     * Retrieves aggregate statistics across all indexes on the primary instance,
     * including total index count, database size, and last update timestamp.
     */
    suspend fun getStats(): MeilisearchGlobalStats

    /**
     * Lists all indexes on the primary instance with optional pagination.
     *
     * @param offset zero-based offset for pagination
     * @param limit maximum number of indexes to return
     */
    suspend fun getIndexes(offset: Int?, limit: Int?): List<MeilisearchIndexInfo>

    /**
     * Retrieves metadata for a specific index by its UID.
     *
     * @param uid the unique identifier of the index
     */
    suspend fun getIndex(uid: String): MeilisearchIndexInfo?

    /**
     * Retrieves per-index statistics including document count, indexing status,
     * and field distribution.
     *
     * @param uid the index UID to query
     */
    suspend fun getIndexStats(uid: String): MeilisearchIndexStats

    /**
     * Retrieves the complete settings of an index including ranking rules,
     * attribute configurations, typo tolerance, and embedder settings.
     *
     * @param uid the index UID to query
     */
    suspend fun getIndexSettings(uid: String): MeilisearchIndexSettings

    /**
     * Browses documents in an index as structured JSON elements with optional
     * pagination and field selection. Each element preserves the full JSON
     * structure of the stored document.
     *
     * @param uid the index UID to browse
     * @param offset zero-based offset for pagination
     * @param limit maximum number of documents to return
     * @param fields subset of fields to include in each document
     */
    suspend fun getDocuments(uid: String, offset: Int?, limit: Int?, fields: List<String>?): List<JsonElement>

    /**
     * Creates a new index with the given UID and optional primary key.
     * Returns a task tracking the asynchronous creation.
     *
     * @param uid the unique identifier for the new index
     * @param primaryKey the primary key field for document deduplication
     */
    suspend fun createIndex(uid: String, primaryKey: String?): MeilisearchTask

    /**
     * Deletes an index by its UID. Returns a task tracking the asynchronous deletion.
     *
     * @param uid the index UID to delete
     */
    suspend fun deleteIndex(uid: String): MeilisearchTask

    /**
     * Atomically swaps the contents of index pairs.
     *
     * @param pairs list of index UID pairs to swap
     */
    suspend fun swapIndexes(pairs: List<List<String>>): MeilisearchTask

    /**
     * Updates settings for an index. Only fields present in the input are changed.
     *
     * @param uid the index UID to update
     * @param settings the settings fields to update
     */
    suspend fun updateSettings(uid: String, settings: MeilisearchSettingsInput): MeilisearchTask

    /**
     * Resets all settings for an index to their Meilisearch defaults.
     *
     * @param uid the index UID to reset
     */
    suspend fun resetSettings(uid: String): MeilisearchTask

    /**
     * Updates embedder configurations on an index, enabling or reconfiguring
     * semantic and vector search.
     *
     * @param uid the index UID to update
     * @param embedders the embedder configurations to apply
     */
    suspend fun updateEmbedders(uid: String, embedders: List<MeilisearchEmbedderInput>): MeilisearchTask

    /**
     * Removes all embedder configurations from an index, disabling semantic search.
     *
     * @param uid the index UID to reset
     */
    suspend fun resetEmbedders(uid: String): MeilisearchTask

    /**
     * Deletes all documents from an index. Returns a task tracking the operation.
     *
     * @param uid the index UID to clear
     */
    suspend fun deleteAllDocuments(uid: String): MeilisearchTask

    /**
     * Deletes specific documents by their IDs.
     *
     * @param uid the index UID
     * @param ids the document IDs to delete
     */
    suspend fun deleteDocuments(uid: String, ids: List<String>): MeilisearchTask

    /**
     * Deletes documents matching a Meilisearch filter expression.
     *
     * @param uid the index UID
     * @param filter the filter expression
     */
    suspend fun deleteDocumentsByFilter(uid: String, filter: String): MeilisearchTask

    /**
     * Lists recent tasks with optional filtering by status, type, and index UID.
     *
     * @param limit maximum number of tasks to return
     * @param from task UID to start from for pagination
     * @param status filter by task statuses
     * @param type filter by task types
     * @param indexUid filter by index UIDs
     */
    suspend fun getTasks(
        limit: Int?,
        from: Int?,
        status: List<String>?,
        type: List<String>?,
        indexUid: List<String>?,
    ): List<MeilisearchTask>

    /**
     * Retrieves details for a specific task by its UID.
     *
     * @param uid the task UID
     */
    suspend fun getTask(uid: Int): MeilisearchTask

    /**
     * Cancels tasks matching the given filter criteria.
     *
     * @param uids specific task UIDs to cancel
     * @param statuses cancel tasks with these statuses
     * @param types cancel tasks of these types
     * @param indexUids cancel tasks operating on these index UIDs
     */
    suspend fun cancelTasks(
        uids: List<Int>?,
        statuses: List<String>?,
        types: List<String>?,
        indexUids: List<String>?,
    ): MeilisearchTask

    /**
     * Lists all API keys registered on the primary Meilisearch instance.
     */
    suspend fun getKeys(): List<MeilisearchKey>

    /**
     * Creates a database dump for backup or migration purposes.
     */
    suspend fun createDump(): MeilisearchTask

    /**
     * Triggers a snapshot of the current database state.
     */
    suspend fun createSnapshot(): MeilisearchTask

    /**
     * Lists all Meilisearch nodes registered in the multi-node topology.
     */
    suspend fun getNodes(): List<MeilisearchNode>

    /**
     * Retrieves a specific node by its database ID.
     *
     * @param id the node's database UUID
     */
    suspend fun getNode(id: UUID): MeilisearchNode?

    /**
     * Checks whether the Meilisearch instance for the given node is healthy
     * and accepting requests.
     *
     * @param nodeId the node's database UUID
     */
    suspend fun getNodeHealth(nodeId: UUID): Boolean

    /**
     * Retrieves version information from the Meilisearch instance
     * associated with the given node.
     *
     * @param nodeId the node's database UUID
     */
    suspend fun getNodeVersion(nodeId: UUID): MeilisearchVersion?

    /**
     * Retrieves global statistics from the Meilisearch instance
     * associated with the given node.
     *
     * @param nodeId the node's database UUID
     */
    suspend fun getNodeStats(nodeId: UUID): MeilisearchGlobalStats?

    /**
     * Lists the index UIDs on the Meilisearch instance associated
     * with the given node.
     *
     * @param nodeId the node's database UUID
     */
    suspend fun getNodeIndexes(nodeId: UUID): List<String>

    /**
     * Registers a new Meilisearch node in the multi-node topology.
     *
     * @param name human-readable name
     * @param description node description
     * @param url the instance URL
     * @param apiKey the API key
     * @param types the search types this node supports
     */
    suspend fun addNode(
        name: String,
        description: String,
        url: String,
        apiKey: String,
        types: List<StorageSystemType>,
    ): MeilisearchNode

    /**
     * Updates an existing node's configuration. Only non-null parameters are applied.
     *
     * @param id the node's database UUID
     * @param name updated name
     * @param description updated description
     * @param url updated URL
     * @param apiKey updated API key
     * @param types updated search types
     */
    suspend fun editNode(
        id: UUID,
        name: String?,
        description: String?,
        url: String?,
        apiKey: String?,
        types: List<StorageSystemType>?,
    ): MeilisearchNode

    /**
     * Removes a node from the topology and all storage system assignments.
     *
     * @param id the node's database UUID
     */
    suspend fun deleteNode(id: UUID): Boolean

    /**
     * Assigns a node to a storage system so it serves that system's search indexes.
     *
     * @param storageSystemId the storage system UUID
     * @param nodeId the node UUID
     */
    suspend fun assignNodeToStorageSystem(storageSystemId: UUID, nodeId: UUID): Boolean

    /**
     * Removes a node from a storage system assignment.
     *
     * @param storageSystemId the storage system UUID
     * @param nodeId the node UUID
     */
    suspend fun removeNodeFromStorageSystem(storageSystemId: UUID, nodeId: UUID): Boolean

    /**
     * Exports data from the primary Meilisearch instance to a specific node
     * using the Meilisearch export API for replication.
     *
     * @param nodeId the target node's database UUID
     * @param indexUids specific index UIDs to export, or all if null
     * @param overrideSettings whether to override existing settings on the target
     */
    suspend fun exportToNode(nodeId: UUID, indexUids: List<String>?, overrideSettings: Boolean?): MeilisearchTask

    /**
     * Configures the federation network on a specific node by registering
     * remote Meilisearch instances for distributed search via the /network route.
     *
     * @param nodeId the node's database UUID
     * @param remotes the remote instances to register
     */
    suspend fun configureNetwork(nodeId: UUID, remotes: List<MeilisearchNetworkRemoteInput>): Boolean

    /**
     * Returns the storage system IDs assigned to the given node, enabling
     * the controller to resolve assignments without bypassing the service layer.
     *
     * @param nodeId the node's database UUID
     */
    suspend fun getStorageSystemIdsForNode(nodeId: UUID): List<UUID>

    /**
     * Retrieves the currently configured federation network remotes for a node.
     *
     * @param nodeId the node's database UUID
     */
    suspend fun getNetworkRemotes(nodeId: UUID): List<MeilisearchNetworkRemote>
}

/**
 * Input data for updating index settings. Only non-null fields are applied.
 */
@Serializable
data class MeilisearchSettingsInput(
    val rankingRules: List<String>? = null,
    val searchableAttributes: List<String>? = null,
    val displayedAttributes: List<String>? = null,
    val filterableAttributes: List<String>? = null,
    val sortableAttributes: List<String>? = null,
    val stopWords: List<String>? = null,
    val distinctAttribute: String? = null,
    val proximityPrecision: String? = null,
    val searchCutoffMs: Int? = null,
)

/**
 * Input data for configuring an embedder on a Meilisearch index.
 * For REST embedders, includes the provider URL, request/response mapping templates,
 * and custom HTTP headers.
 */
@Serializable
data class MeilisearchEmbedderInput(
    val name: String,
    val source: String,
    val model: String? = null,
    val key: String? = null,
    val dimensions: Int? = null,
    val documentTemplate: String? = null,
    val documentTemplateMaxBytes: Int? = null,
    val url: String? = null,
    val request: JsonElement? = null,
    val response: JsonElement? = null,
    val headers: JsonElement? = null,
)

/**
 * Input data for creating a new Meilisearch node in the multi-node topology.
 */
@Serializable
data class MeilisearchNodeInput(
    val name: String,
    val description: String? = null,
    val url: String,
    val key: String,
    val types: List<StorageSystemType>,
)

/**
 * Input data for updating an existing Meilisearch node. Only non-null fields are applied.
 */
@Serializable
data class MeilisearchEditNodeInput(
    val name: String? = null,
    val description: String? = null,
    val url: String? = null,
    val key: String? = null,
    val types: List<StorageSystemType>? = null,
)

/**
 * Input data for registering a remote instance in the federation network.
 */
@Serializable
data class MeilisearchNetworkRemoteInput(
    val name: String,
    val url: String,
)
