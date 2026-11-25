package bosca.analytics.graphql

import bosca.analytics.service.AnalyticsQueryExecutionService
import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.graphql.server.*
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import io.mockk.*
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Tracer
import kotlinx.serialization.json.*

/** Exercises the published warehouse SDL, namespace accessor and generated dispatchers. */
@OptIn(bosca.di.annotation.InternalDI::class)
class AnalyticsWarehouseGraphQLFixture(execution: AnalyticsQueryExecutionService) : AutoCloseable {
    private val graphql: GraphQL
    init {
        ProviderRegistry.clear()
        provides<Json> { Json }
        provides<Tracer> { GlobalOpenTelemetry.getTracer("analytics-warehouse-test") }
        val manager = mockk<ConnectionManager>(relaxed = true)
        provides<ConnectionPool> { mockk { every { connection() } returns manager } }
        val wiring = RuntimeWiringBuilder().apply {
            scalar("JSON", ExtendedScalars.Json); scalar("DateTime", bosca.graphql.scalars.DateTime.Type); scalar("Long", ExtendedScalars.Long)
            type("Query") { field("analytics") { Analytics } }
            type("Analytics") {
                field("warehouse", AnalyticsControllerDispatcher(AnalyticsController()).type.fieldResolvers.getValue("warehouse"))
            }
            type(AnalyticsWarehouseControllerDispatcher(AnalyticsWarehouseController(execution, GroupEvaluator(mockk()))).type)
            type(AnalyticsSqlResultControllerDispatcher(AnalyticsSqlResultController()).type)
            type(AnalyticsQueryColumnControllerDispatcher(AnalyticsQueryColumnController()).type)
        }.build()
        val sdl = requireNotNull(javaClass.getResourceAsStream("/graphql/analytics/analytics.graphqls")).bufferedReader().use { it.readText() }
        val warehouse = sdl.substring(sdl.indexOf("\"Direct read-only warehouse")).substringBefore("\"Original execution evidence")
        graphql = GraphQL(ExecutableSchema.fromSdl("""
            scalar JSON
            scalar DateTime
            scalar Long
            type Query { analytics: Analytics! }
            type Analytics { warehouse: AnalyticsWarehouse! }
            type AnalyticsQueryColumn { name: String!, typeName: String!, nullable: Boolean! }
            $warehouse
        """.trimIndent(), wiring))
    }

    suspend fun call(query: String, authentication: AuthenticationContext, variables: JsonObject = JsonObject(emptyMap())) =
        graphql.execute(GraphQLRequest(query, variables = variables, context = GraphQLContext(mapOf("authenticationContext" to authentication))))

    override fun close() { ProviderRegistry.clear() }
}
