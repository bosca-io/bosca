@file:OptIn(ExperimentalUuidApi::class)

package bosca.meilisearch.admin.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.meilisearch.admin.model.MeilisearchGlobalStats
import bosca.meilisearch.admin.model.MeilisearchNetworkRemote
import bosca.meilisearch.admin.model.MeilisearchNode
import bosca.meilisearch.admin.model.MeilisearchVersion
import bosca.meilisearch.admin.service.MeilisearchAdminService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.storage.model.StorageSystemType
import kotlin.uuid.ExperimentalUuidApi

/**
 * Resolves fields on the MeilisearchNode GraphQL type, including live
 * health checks, version queries, and statistics fetched from each
 * node's Meilisearch instance.
 */
@TypeController
class MeilisearchNodeController(
    private val service: MeilisearchAdminService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<MeilisearchNode> {

    @Field fun id(node: MeilisearchNode) = node.id.toString()
    @Field fun name(node: MeilisearchNode) = node.name
    @Field fun description(node: MeilisearchNode) = node.description
    @Field fun url(node: MeilisearchNode) = node.url
    @Field fun types(node: MeilisearchNode): List<StorageSystemType> = node.types

    @Field
    suspend fun healthy(authorization: AuthenticationContext, node: MeilisearchNode): Boolean {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return service.getNodeHealth(node.id)
    }

    @Field
    suspend fun indexes(authorization: AuthenticationContext, node: MeilisearchNode): List<String> {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return service.getNodeIndexes(node.id)
    }

    @Field
    suspend fun version(authorization: AuthenticationContext, node: MeilisearchNode): MeilisearchVersion? {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return service.getNodeVersion(node.id)
    }

    @Field
    suspend fun stats(authorization: AuthenticationContext, node: MeilisearchNode): MeilisearchGlobalStats? {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return service.getNodeStats(node.id)
    }

    @Field
    suspend fun storageSystemIds(authorization: AuthenticationContext, node: MeilisearchNode): List<String> {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return service.getStorageSystemIdsForNode(node.id).map { it.toString() }
    }

    @Field
    suspend fun networkRemotes(authorization: AuthenticationContext, node: MeilisearchNode): List<MeilisearchNetworkRemote> {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return service.getNetworkRemotes(node.id)
    }
}
