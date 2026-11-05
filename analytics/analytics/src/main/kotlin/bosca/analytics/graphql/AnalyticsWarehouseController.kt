package bosca.analytics.graphql

import bosca.analytics.model.AnalyticsQueryColumn
import bosca.analytics.model.AnalyticsSqlResult
import bosca.analytics.security.ANALYTICS_MANAGER_GROUP
import bosca.analytics.service.AnalyticsQueryExecutionService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.ApiTokenScopes
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityException

@TypeController
class AnalyticsWarehouseController(
    private val execution: AnalyticsQueryExecutionService,
    private val groups: GroupEvaluator,
) : GraphQLController<AnalyticsWarehouse> {
    private fun authorize(authentication: AuthenticationContext) {
        val principal = authentication.principal() ?: throw SecurityException("Unauthorized access")
        groups.verifyHasScope(authentication, ApiTokenScopes.ANALYTICS_SQL.name)
        if (!principal.hasGroup("administrators") && !principal.hasGroup(ANALYTICS_MANAGER_GROUP)) {
            groups.throwUnauthorized()
        }
    }

    @Field
    suspend fun catalogs(authentication: AuthenticationContext, offset: Int, limit: Int): List<String> {
        authorize(authentication)
        return execution.listCatalogs(offset, limit)
    }

    @Field
    suspend fun schemas(authentication: AuthenticationContext, catalog: String, offset: Int, limit: Int): List<String> {
        authorize(authentication)
        return execution.listSchemas(catalog, offset, limit)
    }

    @Field
    suspend fun tables(authentication: AuthenticationContext, catalog: String, schema: String, offset: Int, limit: Int): List<String> {
        authorize(authentication)
        return execution.listTables(catalog, schema, offset, limit)
    }

    @Field
    suspend fun describe(authentication: AuthenticationContext, catalog: String, schema: String, table: String): List<AnalyticsQueryColumn> {
        authorize(authentication)
        return execution.describeTable(catalog, schema, table)
    }

    @Field
    suspend fun execute(authentication: AuthenticationContext, sql: String, limit: Int): AnalyticsSqlResult {
        authorize(authentication)
        return execution.executeSql(sql, limit)
    }
}
