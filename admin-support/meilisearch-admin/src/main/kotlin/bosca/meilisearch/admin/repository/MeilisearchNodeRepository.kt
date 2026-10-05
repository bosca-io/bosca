@file:OptIn(ExperimentalUuidApi::class)

package bosca.meilisearch.admin.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.meilisearch.admin.model.MeilisearchNode
import bosca.serialization.UUID
import kotlin.uuid.ExperimentalUuidApi

/**
 * Provides database access for Meilisearch node records and the join table
 * that maps storage systems to their assigned nodes, enabling multi-node
 * topology management for search replication and index locality.
 */
@Repository
interface MeilisearchNodeRepository {

    @Query("select * from meilisearch_nodes order by name")
    suspend fun findAll(): List<MeilisearchNode>

    @Query("select * from meilisearch_nodes where id = :id")
    suspend fun findById(id: UUID): MeilisearchNode?

    @Query(
        """
        select n.* from meilisearch_nodes n
        inner join storage_system_nodes ssn on ssn.node_id = n.id
        where ssn.storage_system_id = :storageSystemId
        order by n.name
        """
    )
    suspend fun findByStorageSystemId(storageSystemId: UUID): List<MeilisearchNode>

    @Query(
        """
        insert into meilisearch_nodes (id, name, description, url, api_key, api_key_nonce, types, configuration)
        values (:id, :name, :description, :url, :apiKey, :apiKeyNonce, :types, :configuration)
        returning *
        """
    )
    suspend fun add(node: MeilisearchNode): MeilisearchNode

    @Query(
        """
        update meilisearch_nodes
        set name = :name, description = :description, url = :url,
            api_key = :apiKey, api_key_nonce = :apiKeyNonce,
            types = :types, configuration = :configuration
        where id = :id
        returning *
        """
    )
    suspend fun update(node: MeilisearchNode): MeilisearchNode

    @Query("delete from meilisearch_nodes where id = :id")
    suspend fun deleteById(id: UUID)

    @Query("insert into storage_system_nodes (storage_system_id, node_id) values (:storageSystemId, :nodeId) on conflict do nothing")
    suspend fun assignToStorageSystem(storageSystemId: UUID, nodeId: UUID)

    @Query("delete from storage_system_nodes where storage_system_id = :storageSystemId and node_id = :nodeId")
    suspend fun removeFromStorageSystem(storageSystemId: UUID, nodeId: UUID)

    @Query("select storage_system_id from storage_system_nodes where node_id = :nodeId")
    suspend fun findStorageSystemIdsByNodeId(nodeId: UUID): List<UUID>
}
