@file:OptIn(ExperimentalUuidApi::class)

package bosca.meilisearch.admin.service

import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.connection
import bosca.meilisearch.admin.model.MeilisearchNode
import bosca.meilisearch.admin.repository.MeilisearchNodeRepository
import bosca.serialization.UUID
import bosca.storage.model.StorageSystemType
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Test implementation of [MeilisearchNodeRepository] that executes SQL directly
 * against a PostgreSQL test container via the [ConnectionManager] in the current
 * coroutine context, bypassing the KSP-generated repository.
 */
class TestMeilisearchNodeRepository(
    private val connectionPool: ConnectionPool,
) : MeilisearchNodeRepository {

    override suspend fun findAll(): List<MeilisearchNode> {
        val cm = connection()
        return cm.useStatement("SELECT * FROM meilisearch_nodes ORDER BY name") { stmt ->
            val rs = stmt.executeQuery()
            val nodes = mutableListOf<MeilisearchNode>()
            while (rs.next()) {
                nodes.add(rowToNode(rs))
            }
            nodes
        }
    }

    override suspend fun findById(id: UUID): MeilisearchNode? {
        val cm = connection()
        return cm.useStatement("SELECT * FROM meilisearch_nodes WHERE id = ?") { stmt ->
            stmt.setObject(1, java.util.UUID.fromString(id.toString()))
            val rs = stmt.executeQuery()
            if (rs.next()) rowToNode(rs) else null
        }
    }

    override suspend fun findByStorageSystemId(storageSystemId: UUID): List<MeilisearchNode> {
        val cm = connection()
        return cm.useStatement(
            """
            SELECT n.* FROM meilisearch_nodes n
            INNER JOIN storage_system_nodes ssn ON ssn.node_id = n.id
            WHERE ssn.storage_system_id = ?
            ORDER BY n.name
            """.trimIndent()
        ) { stmt ->
            stmt.setObject(1, java.util.UUID.fromString(storageSystemId.toString()))
            val rs = stmt.executeQuery()
            val nodes = mutableListOf<MeilisearchNode>()
            while (rs.next()) {
                nodes.add(rowToNode(rs))
            }
            nodes
        }
    }

    override suspend fun add(node: MeilisearchNode): MeilisearchNode {
        val cm = connection()
        return cm.useStatement(
            """
            INSERT INTO meilisearch_nodes (id, name, description, url, api_key, api_key_nonce, types, configuration)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            RETURNING *
            """.trimIndent()
        ) { stmt ->
            stmt.setObject(1, java.util.UUID.fromString(node.id.toString()))
            stmt.setString(2, node.name)
            stmt.setString(3, node.description)
            stmt.setString(4, node.url)
            stmt.setBytes(5, node.apiKey)
            stmt.setBytes(6, node.apiKeyNonce)
            stmt.setArray(7, cm.createArrayOf("text", node.types.map { it.name }.toTypedArray()))
            stmt.setString(8, node.configuration.toString())
            val rs = stmt.executeQuery()
            rs.next()
            rowToNode(rs)
        }
    }

    override suspend fun update(node: MeilisearchNode): MeilisearchNode {
        val cm = connection()
        return cm.useStatement(
            """
            UPDATE meilisearch_nodes
            SET name = ?, description = ?, url = ?, api_key = ?, api_key_nonce = ?,
                types = ?, configuration = ?::jsonb
            WHERE id = ?
            RETURNING *
            """.trimIndent()
        ) { stmt ->
            stmt.setString(1, node.name)
            stmt.setString(2, node.description)
            stmt.setString(3, node.url)
            stmt.setBytes(4, node.apiKey)
            stmt.setBytes(5, node.apiKeyNonce)
            stmt.setArray(6, cm.createArrayOf("text", node.types.map { it.name }.toTypedArray()))
            stmt.setString(7, node.configuration.toString())
            stmt.setObject(8, java.util.UUID.fromString(node.id.toString()))
            val rs = stmt.executeQuery()
            rs.next()
            rowToNode(rs)
        }
    }

    override suspend fun deleteById(id: UUID) {
        val cm = connection()
        cm.useStatement("DELETE FROM meilisearch_nodes WHERE id = ?") { stmt ->
            stmt.setObject(1, java.util.UUID.fromString(id.toString()))
            stmt.execute()
        }
    }

    override suspend fun assignToStorageSystem(storageSystemId: UUID, nodeId: UUID) {
        val cm = connection()
        cm.useStatement(
            "INSERT INTO storage_system_nodes (storage_system_id, node_id) VALUES (?, ?)"
        ) { stmt ->
            stmt.setObject(1, java.util.UUID.fromString(storageSystemId.toString()))
            stmt.setObject(2, java.util.UUID.fromString(nodeId.toString()))
            stmt.execute()
        }
    }

    override suspend fun removeFromStorageSystem(storageSystemId: UUID, nodeId: UUID) {
        val cm = connection()
        cm.useStatement(
            "DELETE FROM storage_system_nodes WHERE storage_system_id = ? AND node_id = ?"
        ) { stmt ->
            stmt.setObject(1, java.util.UUID.fromString(storageSystemId.toString()))
            stmt.setObject(2, java.util.UUID.fromString(nodeId.toString()))
            stmt.execute()
        }
    }

    override suspend fun findStorageSystemIdsByNodeId(nodeId: UUID): List<UUID> {
        val cm = connection()
        return cm.useStatement(
            "SELECT storage_system_id FROM storage_system_nodes WHERE node_id = ?"
        ) { stmt ->
            stmt.setObject(1, java.util.UUID.fromString(nodeId.toString()))
            val rs = stmt.executeQuery()
            val ids = mutableListOf<UUID>()
            while (rs.next()) {
                ids.add(Uuid.parse(rs.getObject("storage_system_id").toString()))
            }
            ids
        }
    }

    private fun rowToNode(rs: java.sql.ResultSet): MeilisearchNode {
        val typesArray = rs.getArray("types")
        val types = if (typesArray != null) {
            @Suppress("UNCHECKED_CAST")
            (typesArray.array as Array<String>).mapNotNull { name ->
                try {
                    StorageSystemType.valueOf(name)
                } catch (_: Exception) {
                    null
                }
            }
        } else {
            emptyList()
        }
        return MeilisearchNode(
            id = Uuid.parse(rs.getObject("id").toString()),
            name = rs.getString("name"),
            description = rs.getString("description"),
            url = rs.getString("url"),
            apiKey = rs.getBytes("api_key"),
            apiKeyNonce = rs.getBytes("api_key_nonce"),
            types = types,
            configuration = rs.getString("configuration")?.let {
                kotlinx.serialization.json.Json.parseToJsonElement(it)
            } ?: kotlinx.serialization.json.JsonObject(emptyMap()),
        )
    }
}
