@file:OptIn(ExperimentalUuidApi::class)

package bosca.meilisearch.admin.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.meilisearch.admin.model.MeilisearchNode
import bosca.meilisearch.admin.model.MeilisearchTask
import bosca.meilisearch.admin.service.MeilisearchAdminService
import bosca.meilisearch.admin.service.MeilisearchEmbedderInput
import bosca.meilisearch.admin.service.MeilisearchEditNodeInput
import bosca.meilisearch.admin.service.MeilisearchNetworkRemoteInput
import bosca.meilisearch.admin.service.MeilisearchNodeInput
import bosca.meilisearch.admin.service.MeilisearchSettingsInput
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import kotlin.uuid.ExperimentalUuidApi

/**
 * Handles GraphQL mutation operations for Meilisearch administration including
 * index lifecycle management, settings updates, embedder configuration, document
 * operations, task management, node topology changes, replication, and federation.
 */
@TypeController
class MeilisearchAdminMutationController(
    private val service: MeilisearchAdminService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<MeilisearchAdminMutation> {

    // ── Index Operations ──

    @Field
    suspend fun createIndex(
        authorization: AuthenticationContext,
        uid: String,
        primaryKey: String?,
    ): MeilisearchTask {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return service.createIndex(uid, primaryKey)
    }

    @Field
    suspend fun deleteIndex(
        authorization: AuthenticationContext,
        uid: String,
    ): MeilisearchTask {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return service.deleteIndex(uid)
    }

    @Field
    suspend fun swapIndexes(
        authorization: AuthenticationContext,
        indexes: List<List<String>>,
    ): MeilisearchTask {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return service.swapIndexes(indexes)
    }

    // ── Settings ──

    @Field
    suspend fun updateSettings(
        authorization: AuthenticationContext,
        uid: String,
        settings: MeilisearchSettingsInput,
    ): MeilisearchTask {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return service.updateSettings(uid, settings)
    }

    @Field
    suspend fun resetSettings(
        authorization: AuthenticationContext,
        uid: String,
    ): MeilisearchTask {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return service.resetSettings(uid)
    }

    // ── Embedders ──

    @Field
    suspend fun updateEmbedders(
        authorization: AuthenticationContext,
        uid: String,
        embedders: List<MeilisearchEmbedderInput>,
    ): MeilisearchTask {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return service.updateEmbedders(uid, embedders)
    }

    @Field
    suspend fun resetEmbedders(
        authorization: AuthenticationContext,
        uid: String,
    ): MeilisearchTask {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return service.resetEmbedders(uid)
    }

    // ── Documents ──

    @Field
    suspend fun deleteAllDocuments(
        authorization: AuthenticationContext,
        uid: String,
    ): MeilisearchTask {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return service.deleteAllDocuments(uid)
    }

    @Field
    suspend fun deleteDocuments(
        authorization: AuthenticationContext,
        uid: String,
        ids: List<String>,
    ): MeilisearchTask {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return service.deleteDocuments(uid, ids)
    }

    @Field
    suspend fun deleteDocumentsByFilter(
        authorization: AuthenticationContext,
        uid: String,
        filter: String,
    ): MeilisearchTask {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return service.deleteDocumentsByFilter(uid, filter)
    }

    // ── Tasks ──

    @Field
    suspend fun cancelTasks(
        authorization: AuthenticationContext,
        uids: List<Int>?,
        statuses: List<String>?,
        types: List<String>?,
        indexUids: List<String>?,
    ): MeilisearchTask {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return service.cancelTasks(uids, statuses, types, indexUids)
    }

    // ── Instance Operations ──

    @Field
    suspend fun createDump(authorization: AuthenticationContext): MeilisearchTask {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return service.createDump()
    }

    @Field
    suspend fun createSnapshot(authorization: AuthenticationContext): MeilisearchTask {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return service.createSnapshot()
    }

    // ── Node Management ──

    @Field
    suspend fun addNode(
        authorization: AuthenticationContext,
        input: MeilisearchNodeInput,
    ): MeilisearchNode {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return service.addNode(input.name, input.description ?: "", input.url, input.key, input.types)
    }

    @Field
    suspend fun editNode(
        authorization: AuthenticationContext,
        id: UUID,
        input: MeilisearchEditNodeInput,
    ): MeilisearchNode {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return service.editNode(id, input.name, input.description, input.url, input.key, input.types)
    }

    @Field
    suspend fun deleteNode(
        authorization: AuthenticationContext,
        id: UUID,
    ): Boolean {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return service.deleteNode(id)
    }

    @Field
    suspend fun assignNodeToStorageSystem(
        authorization: AuthenticationContext,
        storageSystemId: UUID,
        nodeId: UUID,
    ): Boolean {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return service.assignNodeToStorageSystem(storageSystemId, nodeId)
    }

    @Field
    suspend fun removeNodeFromStorageSystem(
        authorization: AuthenticationContext,
        storageSystemId: UUID,
        nodeId: UUID,
    ): Boolean {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return service.removeNodeFromStorageSystem(storageSystemId, nodeId)
    }

    // ── Replication ──

    @Field
    suspend fun exportToNode(
        authorization: AuthenticationContext,
        nodeId: UUID,
        indexUids: List<String>?,
        overrideSettings: Boolean?,
    ): MeilisearchTask {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return service.exportToNode(nodeId, indexUids, overrideSettings)
    }

    // ── Federation ──

    @Field
    suspend fun configureNetwork(
        authorization: AuthenticationContext,
        nodeId: UUID,
        remotes: List<MeilisearchNetworkRemoteInput>,
    ): Boolean {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return service.configureNetwork(nodeId, remotes)
    }
}
