package bosca.meilisearch.admin.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.meilisearch.admin.model.MeilisearchIndexInfo
import bosca.meilisearch.admin.model.MeilisearchIndexSettings
import bosca.meilisearch.admin.model.MeilisearchIndexStats
import bosca.meilisearch.admin.service.MeilisearchAdminService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import kotlinx.serialization.json.JsonElement

/**
 * Resolves fields on the MeilisearchIndex GraphQL type, including nested
 * stats, settings, and document browsing that require additional service calls.
 */
@TypeController(type = "MeilisearchIndex")
class MeilisearchIndexController(
    private val service: MeilisearchAdminService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<MeilisearchIndexInfo> {

    @Field
    fun uid(index: MeilisearchIndexInfo) = index.uid

    @Field
    fun primaryKey(index: MeilisearchIndexInfo) = index.primaryKey

    @Field
    fun createdAt(index: MeilisearchIndexInfo) = index.createdAt

    @Field
    fun updatedAt(index: MeilisearchIndexInfo) = index.updatedAt

    @Field
    suspend fun stats(authorization: AuthenticationContext, index: MeilisearchIndexInfo): MeilisearchIndexStats {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return service.getIndexStats(index.uid)
    }

    @Field
    suspend fun settings(authorization: AuthenticationContext, index: MeilisearchIndexInfo): MeilisearchIndexSettings {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return service.getIndexSettings(index.uid)
    }

    @Field
    suspend fun documents(
        authorization: AuthenticationContext,
        index: MeilisearchIndexInfo,
        offset: Int?,
        limit: Int?,
        fields: List<String>?,
    ): List<JsonElement> {
        groupEvaluator.verifyHasAdminGroup(authorization)
        return service.getDocuments(index.uid, offset, limit, fields)
    }
}
