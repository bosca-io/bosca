package bosca.analytics.graphql

import bosca.analytics.model.AnalyticsQuery
import bosca.analytics.model.AnalyticsQueryExecutionParameterInput
import bosca.analytics.model.AnalyticsQueryResponse
import bosca.analytics.security.AnalyticsQueryPermissionEvaluator
import bosca.analytics.service.AnalyticsQueryExecutionService
import bosca.analytics.service.AnalyticsQueryService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

object AnalyticsQueries

@TypeController
class AnalyticsQueriesController(
    private val queriesService: AnalyticsQueryService,
    private val executionService: AnalyticsQueryExecutionService,
    private val permissionEvaluator: AnalyticsQueryPermissionEvaluator
) : GraphQLController<AnalyticsQueries> {

    @Field
    suspend fun all(authentication: AuthenticationContext): List<AnalyticsQuery> {
        val queries = queriesService.getQueries(0, Int.MAX_VALUE)
        return permissionEvaluator.filterAllowed(authentication, queries, PermissionAction.VIEW)
    }

    @Field
    suspend fun queryById(authentication: AuthenticationContext, id: UUID): AnalyticsQuery {
        val query = queriesService.getQueryById(id)
        permissionEvaluator.verifyAllowed(authentication, query, PermissionAction.VIEW)
        return query
    }

    @Field
    suspend fun queryByKey(authentication: AuthenticationContext, key: String): AnalyticsQuery? {
        val query = queriesService.getQueryByKey(key) ?: return null
        permissionEvaluator.verifyAllowed(authentication, query, PermissionAction.VIEW)
        return query
    }

    @Field
    suspend fun executeByKey(authentication: AuthenticationContext, key: String, parameters: List<AnalyticsQueryExecutionParameterInput>): AnalyticsQueryResponse {
        val query = queriesService.getQueryByKey(key) ?: error("Query not found: $key")
        permissionEvaluator.verifyAllowed(authentication, query, PermissionAction.EXECUTE)
        return executionService.execute(key, parameters)
    }

    @Field
    suspend fun execute(authentication: AuthenticationContext, queryId: UUID, parameters: List<AnalyticsQueryExecutionParameterInput>): AnalyticsQueryResponse {
        val query = queriesService.getQueryById(queryId)
        permissionEvaluator.verifyAllowed(authentication, query, PermissionAction.EXECUTE)
        return executionService.execute(queryId, parameters)
    }
}