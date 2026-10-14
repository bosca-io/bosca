@file:OptIn(bosca.di.annotation.InternalDI::class)
package bosca.experimentation.graphql

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.experimentation.model.*
import bosca.experimentation.service.ExperimentService
import bosca.graphql.server.*
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import io.mockk.*
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Tracer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/** Runs the published SDL through KSP dispatchers, including nested input defaults and nullable results. */
class GoalAnalysisGraphQLIntegrationTest {
    @AfterTest fun cleanup() = ProviderRegistry.clear()

    @Test fun `Studio can save activation and query every descriptive field through the schema`() = runTest {
        ProviderRegistry.clear()
        provides<Json> { Json }
        val cache = bosca.experimentation.FlagCacheTestSupport()
        provides<bosca.cache.CacheManager> { cache.cacheManager }
        provides<bosca.cache.RequestCacheSerializer> { cache.requestCacheSerializer }
        provides<Tracer> { GlobalOpenTelemetry.getTracer("goal-analysis-test") }
        val pool = mockk<bosca.db.ConnectionPool>()
        every { pool.connection() } returns mockk(relaxed = true)
        provides<bosca.db.ConnectionPool> { pool }
        val experimentId = UUID.random()
        val service = mockk<ExperimentService>()
        val saved = slot<bosca.experimentation.model.ConversionGoalInput>()
        coEvery { service.addConversionGoal(experimentId, capture(saved)) } answers {
            val input = saved.captured
            ConversionGoal(experimentId = experimentId, name = input.name, activation = input.activation, outcomeScope = input.outcomeScope)
        }
        val mutation = ExperimentMutationControllerDispatcher(ExperimentMutationController(service, mockk(relaxed = true), mockk(relaxed = true)))
        val d = MetricDistribution(2, 450.0, 212.132, 450.0, 525.0, 570.0, 585.0, 597.0, 600.0)
        val population = GoalPopulation(3, 2, 2, 450.0, 45000.0, MetricInterval(100.0, 800.0), d)
        val analysis = GoalPopulationAnalysis("2026-09-01T10:30:00Z", true, false, population, population, population,
            2.0 / 3, MetricInterval(.2, .9), PlacementIntegrity(1, 0, 1, 1), 4, d,
            DestinationAttribution(2, 4, 1, 1, MetricInterval(.1, .9), 1, 2, 3, 4, 5), GoalDiagnostics(1, 2, 3, 4, 5))
        val result = ExperimentResult(experimentId = experimentId, variationKey = "Mid", goalId = UUID.random(), goalAnalysis = analysis)
        val source = checkNotNull(javaClass.getResource("/graphql/experimentation.graphqls")).readText()
        val names = listOf("GoalActivationMode", "GoalActivation", "GoalActivationInput", "GoalActivationRule", "GoalActivationRuleInput", "GoalOutcomeScope", "GoalOutcomeScopeInput", "ExperimentActivationFilter", "ExperimentActivationFilterInput", "EventType", "GoalMetricType", "ConversionGoalRole", "CupedCovariateInput", "ConversionGoalInput", "GoalPopulationAnalysis", "MetricInterval", "MetricDistribution", "GoalPopulation", "PlacementIntegrity", "DestinationAttribution", "GoalDiagnostics")
        val definitions = names.joinToString("\n") { name -> checkNotNull(Regex("(?:type|input|enum) $name \\{[^}]*}").find(source)) { name }.value }
        val dispatchers = listOf(
            GoalActivationTypeControllerDispatcher(GoalActivationTypeController()),
            GoalActivationRuleTypeControllerDispatcher(GoalActivationRuleTypeController()),
            GoalOutcomeScopeTypeControllerDispatcher(GoalOutcomeScopeTypeController()),
            ExperimentActivationFilterTypeControllerDispatcher(ExperimentActivationFilterTypeController()),
            GoalPopulationAnalysisTypeControllerDispatcher(GoalPopulationAnalysisTypeController()),
            MetricIntervalTypeControllerDispatcher(MetricIntervalTypeController()),
            MetricDistributionTypeControllerDispatcher(MetricDistributionTypeController()),
            GoalPopulationTypeControllerDispatcher(GoalPopulationTypeController()),
            PlacementIntegrityTypeControllerDispatcher(PlacementIntegrityTypeController()),
            DestinationAttributionTypeControllerDispatcher(DestinationAttributionTypeController()),
            GoalDiagnosticsTypeControllerDispatcher(GoalDiagnosticsTypeController()),
        )
        val goalFields = ConversionGoalTypeControllerDispatcher(ConversionGoalTypeController(Json)).type.fieldResolvers
        val resultFields = ExperimentResultTypeControllerDispatcher(ExperimentResultTypeController(mockk())).type.fieldResolvers
        val graph = GraphQL(ExecutableSchema.fromSdl("""
            scalar UUID
            scalar Long
            type Query { result: ExperimentResult! }
            type Mutation { addConversionGoal(experimentId: UUID!, goal: ConversionGoalInput!): ConversionGoal! }
            type ConversionGoal { activation: GoalActivation outcomeScope: GoalOutcomeScope }
            type ExperimentResult { goalAnalysis: GoalPopulationAnalysis }
            $definitions
        """.trimIndent(), runtimeWiring {
            scalar("UUID", ExtendedScalars.Uuid)
            scalar("Long", ExtendedScalars.Long)
            type("Query") { field("result") { result } }
            type("Mutation") { field("addConversionGoal", mutation.type.fieldResolvers.getValue("addConversionGoal")) }
            type("ConversionGoal") { for (key in listOf("activation", "outcomeScope")) field(key, goalFields.getValue(key)) }
            type("ExperimentResult") { field("goalAnalysis", resultFields.getValue("goalAnalysis")) }
            dispatchers.forEach { type(it.type) }
        }))
        val context = GraphQLContext(mapOf("authenticationContext" to mockk<AuthenticationContext>(relaxed = true)))
        val filter = "eventType elementType elementId elementIdContains pagePath pagePathPrefixes itemExtraKey itemExtraValue applicationIds recommendationCard"
        val response = graph.execute(GraphQLRequest("""
            mutation {
              addConversionGoal(experimentId: "$experimentId", goal: {
                name: "Cards", activation: { defaultFilter: { eventType: Impression, elementIdContains: "-rec-", recommendationCard: true },
                  variationRules: [{ variationKey: "None", mode: NOT_APPLICABLE }, { variationKey: "Mid", mode: EVENT, filter: {eventType: Impression, applicationIds: ["site"]}}] },
                outcomeScope: { recommendationCard: true, attributeRecommendationDestination: true }
              }) { activation { defaultFilter { $filter } variationRules { variationKey mode filter { $filter } } sameSession maxDelaySeconds applicationIds }
                outcomeScope { applicationIds sameSession maxDelaySeconds recommendationCard attributeRecommendationDestination } }
            }
        """.trimIndent(), context = context))
        assertTrue(response.errors.isEmpty(), response.errors.toString())
        assertFalse(checkNotNull(saved.captured.activation).sameSession)
        assertEquals(emptyList(), saved.captured.outcomeScope?.applicationIds)
        assertEquals(bosca.analytics.model.EventType.Impression, saved.captured.activation?.defaultFilter?.eventType)
        val distribution = "count mean standardDeviation p50 p75 p90 p95 p99 maximum"
        val populationFields = "subjects conversions observationCount estimate variance interval { lower upper } distribution { $distribution }"
        val query = graph.execute(GraphQLRequest("""
          { result { goalAnalysis { cutoff activationConfigured notApplicable
            overall { $populationFields } activated { $populationFields } notActivated { $populationFields }
            reach reachInterval { lower upper } placement { expectedOnly unexpectedOnly both neither }
            exposureEvents exposureIntensity { $distribution }
            attribution { engagedSubjects interactions attributedSubjects attributedInteractions interval { lower upper } unresolvedTargets unsupportedTypes missingSlugs missingSessions unmatchedDestinations }
            diagnostics { excludedPrincipals excludedInstallations duplicateDeliveries outsideApplication missingApplication }
          } } }
        """.trimIndent(), context = context))
        assertTrue(query.errors.isEmpty(), query.errors.toString())
        val actual = query.data?.jsonObject?.getValue("result")?.jsonObject?.getValue("goalAnalysis")
        assertEquals(Json { encodeDefaults = true }.encodeToJsonElement(GoalPopulationAnalysis.serializer(), analysis), actual)
    }
}
