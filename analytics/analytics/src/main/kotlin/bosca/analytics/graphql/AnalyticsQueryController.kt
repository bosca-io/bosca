package bosca.analytics.graphql

import bosca.analytics.model.AnalyticsQuery
import bosca.analytics.model.AnalyticsQueryColumn
import bosca.analytics.model.AnalyticsQueryParameter
import bosca.analytics.security.AnalyticsQueryPermissionEvaluator
import bosca.analytics.service.AnalyticsQueryExecutionService
import bosca.analytics.service.AnalyticsQueryService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.Permission
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext

@TypeController
class AnalyticsQueryController(
    private val queriesService: AnalyticsQueryService,
    private val executionService: AnalyticsQueryExecutionService,
    private val permissionEvaluator: AnalyticsQueryPermissionEvaluator
) : GraphQLController<AnalyticsQuery> {

    @Field
    fun id(query: AnalyticsQuery) = query.id

    @Field
    fun key(query: AnalyticsQuery) = query.key

    @Field
    fun name(query: AnalyticsQuery) = query.name

    @Field
    fun description(query: AnalyticsQuery) = query.description

    @Field
    fun query(query: AnalyticsQuery) = query.query

    @Field
    fun configuration(query: AnalyticsQuery) = query.configuration

    @Field
    fun refreshIntervalSeconds(query: AnalyticsQuery) = query.refreshIntervalSeconds

    @Field
    suspend fun parameters(query: AnalyticsQuery): List<AnalyticsQueryParameter> {
        return queriesService.getParameters(query.id)
    }

    @Field
    suspend fun columns(query: AnalyticsQuery): List<AnalyticsQueryColumn> {
        return executionService.getColumns(query.id)
    }

    @Field
    suspend fun permissions(authentication: AuthenticationContext?, query: AnalyticsQuery): List<Permission> {
        if (!permissionEvaluator.isAllowed(authentication, query, PermissionAction.MANAGE)) {
            return emptyList()
        }
        return queriesService.getPermissions(query)
            .map { Permission(it.groupId, it.action) }
    }
}
