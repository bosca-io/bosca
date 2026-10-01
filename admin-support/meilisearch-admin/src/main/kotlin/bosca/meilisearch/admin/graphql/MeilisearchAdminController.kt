@file:OptIn(ExperimentalUuidApi::class)

package bosca.meilisearch.admin.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.meilisearch.admin.model.MeilisearchGlobalStats
import bosca.meilisearch.admin.model.MeilisearchHealth
import bosca.meilisearch.admin.model.MeilisearchIndexInfo
import bosca.meilisearch.admin.model.MeilisearchKey
import bosca.meilisearch.admin.model.MeilisearchNode
import bosca.meilisearch.admin.model.MeilisearchTask
import bosca.meilisearch.admin.model.MeilisearchVersion
import bosca.meilisearch.admin.service.MeilisearchAdminService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import kotlin.uuid.ExperimentalUuidApi

/**
 * Exposes Meilisearch administrative data through the GraphQL API, allowing
 * administrators to inspect server health, indexes, tasks, API keys, and
 * multi-node topology.
 */
@TypeController
class MeilisearchAdminController(
    private val service: MeilisearchAdminService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<MeilisearchAdmin> {

    @Field
    suspend fun version(authorization: AuthenticationContext): MeilisearchVersion {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return service.getVersion()
    }

    @Field
    suspend fun health(authorization: AuthenticationContext): MeilisearchHealth {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return MeilisearchHealth(healthy = service.getHealth())
    }

    @Field
    suspend fun stats(authorization: AuthenticationContext): MeilisearchGlobalStats {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return service.getStats()
    }

    @Field
    suspend fun indexes(
        authorization: AuthenticationContext,
        offset: Int?,
        limit: Int?,
    ): List<MeilisearchIndexInfo> {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return service.getIndexes(offset, limit)
    }

    @Field
    suspend fun index(
        authorization: AuthenticationContext,
        uid: String,
    ): MeilisearchIndexInfo? {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return service.getIndex(uid)
    }

    @Field
    suspend fun tasks(
        authorization: AuthenticationContext,
        limit: Int?,
        from: Int?,
        status: List<String>?,
        type: List<String>?,
        indexUid: List<String>?,
    ): List<MeilisearchTask> {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return service.getTasks(limit, from, status, type, indexUid)
    }

    @Field
    suspend fun task(
        authorization: AuthenticationContext,
        uid: Int,
    ): MeilisearchTask {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return service.getTask(uid)
    }

    @Field
    suspend fun keys(authorization: AuthenticationContext): List<MeilisearchKey> {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return service.getKeys()
    }

    @Field
    suspend fun nodes(authorization: AuthenticationContext): List<MeilisearchNode> {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return service.getNodes()
    }

    @Field
    suspend fun node(
        authorization: AuthenticationContext,
        id: UUID,
    ): MeilisearchNode? {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return service.getNode(id)
    }
}
