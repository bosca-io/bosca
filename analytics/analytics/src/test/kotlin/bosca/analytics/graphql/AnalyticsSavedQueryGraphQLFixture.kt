package bosca.analytics.graphql

import bosca.analytics.security.AnalyticsQueryPermissionEvaluator
import bosca.analytics.service.AnalyticsQueryExecutionService
import bosca.analytics.service.AnalyticsQueryService
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
import kotlinx.serialization.json.Json

/** Uses the published saved-query SDL and generated dispatchers, including the real permission evaluator. */
@OptIn(bosca.di.annotation.InternalDI::class)
class AnalyticsSavedQueryGraphQLFixture(queries: AnalyticsQueryService, execution: AnalyticsQueryExecutionService) : AutoCloseable {
    private val graphql: GraphQL
    init {
        ProviderRegistry.clear()
        provides<Json> { Json }
        provides<Tracer> { GlobalOpenTelemetry.getTracer("analytics-saved-query-test") }
        val manager = mockk<ConnectionManager>(relaxed = true)
        provides<ConnectionPool> { mockk { every { connection() } returns manager } }
        val permissions = AnalyticsQueryPermissionEvaluator(queries, mockk(), GroupEvaluator(mockk()))
        val wiring = RuntimeWiringBuilder().apply {
            scalar("JSON", ExtendedScalars.Json)
            scalar("UUID", bosca.graphql.scalars.UUID.Type)
            scalar("DateTime", bosca.graphql.scalars.DateTime.Type)
            scalar("Long", ExtendedScalars.Long)
            type("Query") { field("analytics") { Analytics } }
            type("Analytics") {
                field("queries", AnalyticsControllerDispatcher(AnalyticsController()).type.fieldResolvers.getValue("queries"))
            }
            type(AnalyticsQueriesControllerDispatcher(AnalyticsQueriesController(queries, execution, permissions)).type)
            type(AnalyticsQueryResponseControllerDispatcher(AnalyticsQueryResponseController()).type)
            type(AnalyticsQueryEvidenceControllerDispatcher(AnalyticsQueryEvidenceController()).type)
            type(AnalyticsQueryBindingControllerDispatcher(AnalyticsQueryBindingController()).type)
        }.build()
        val sdl = requireNotNull(javaClass.getResourceAsStream("/graphql/analytics/analytics.graphqls")).bufferedReader().use { it.readText() }
        val types = listOf("AnalyticsQueries", "AnalyticsQueryResponse", "AnalyticsQueryEvidence", "AnalyticsQueryBinding", "AnalyticsQueryExecutionParameterInput", "QueryParameterType")
            .joinToString("\n") { name -> requireNotNull(Regex("(?:type|input|enum) $name \\{[^}]*}").find(sdl)).value }
        graphql = GraphQL(ExecutableSchema.fromSdl("""
            scalar JSON
            scalar UUID
            scalar DateTime
            scalar Long
            type Query { analytics: Analytics! }
            type Analytics { queries: AnalyticsQueries! }
            type AnalyticsQuery { id: UUID! }
            $types
        """.trimIndent(), wiring), exceptionHandler = DataFetcherExceptionHandler { exception, path, location, _ ->
            GraphQLError(exception.toString(), locations = listOfNotNull(location), path = path)
        })
    }

    suspend fun call(query: String, authentication: AuthenticationContext) =
        graphql.execute(GraphQLRequest(query, context = GraphQLContext(mapOf("authenticationContext" to authentication))))

    override fun close() { ProviderRegistry.clear() }
}
