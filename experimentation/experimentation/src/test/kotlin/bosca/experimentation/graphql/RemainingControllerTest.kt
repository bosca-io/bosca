package bosca.experimentation.graphql

import bosca.experimentation.model.AiInsights
import bosca.experimentation.model.AnalysisReport
import bosca.experimentation.model.Assignment
import bosca.experimentation.model.ConversionGoal
import bosca.experimentation.model.ExclusionLayer
import bosca.experimentation.model.ExclusionLayerInput
import bosca.experimentation.model.Experiment
import bosca.experimentation.model.ExperimentResult
import bosca.experimentation.model.ExperimentStatus
import bosca.experimentation.model.GoalMetricType
import bosca.experimentation.repository.ConversionGoalRepository
import bosca.experimentation.service.ExclusionLayerService
import bosca.experimentation.service.ExperimentService
import bosca.experimentation.service.FeatureFlagService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.experimentation.FlagCacheTestSupport
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Unit tests for the remaining experimentation GraphQL controllers
 * that were at 0% coverage before this file. Each controller is a
 * thin delegator, but the tests pin the same three properties they
 * pin in the wider suite: admin auth enforcement, field forwarding
 * accuracy, and JSONB decoding throwing on malformed data.
 */
class RemainingControllerTest {

    private val cacheSupport = FlagCacheTestSupport()

    @BeforeTest
    fun setUp() {
        cacheSupport.installInDi()
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val experimentId = UUID.random()

    private fun adminAuth(): AuthenticationContext = mockk(relaxed = true)

    // -----------------------------------------------------------------
    // AnalysisReportTypeController
    // -----------------------------------------------------------------

    @Test
    fun `AnalysisReportTypeController exposes every plain field`() {
        val now = java.time.OffsetDateTime.now()
        val reportId = UUID.random()
        val insights = AiInsights(
            hypothesisAssessment = "ok",
            crossGoalPatterns = "ok",
            followUpExperiments = listOf("x"),
            srmRootCauseHints = null,
        )
        val report = AnalysisReport(
            id = reportId,
            experimentId = experimentId,
            summary = "s",
            recommendation = "r",
            details = buildJsonObject { put("verdict", "SHIP") },
            confidence = 0.99,
            aiInsights = json.encodeToJsonElement(AiInsights.serializer(), insights),
            created = now,
        )
        val controller = AnalysisReportTypeController(json)
        assertEquals(reportId, controller.id(report))
        assertEquals(experimentId, controller.experimentId(report))
        assertEquals("s", controller.summary(report))
        assertEquals("r", controller.recommendation(report))
        assertEquals(report.details, controller.details(report))
        assertEquals(0.99, controller.confidence(report))
        assertEquals(now, controller.created(report))
        val decoded = assertNotNull(controller.aiInsights(report))
        assertEquals(insights, decoded)
    }

    @Test
    fun `AnalysisReportTypeController aiInsights returns null when absent`() {
        val report = AnalysisReport(
            experimentId = experimentId,
            summary = "s",
            recommendation = "r",
            details = buildJsonObject { put("verdict", "SHIP") },
            aiInsights = null,
        )
        val controller = AnalysisReportTypeController(json)
        assertNull(controller.aiInsights(report))
    }

    @Test
    fun `AnalysisReportTypeController aiInsights throws on malformed JSON`() {
        val report = AnalysisReport(
            experimentId = experimentId,
            summary = "s",
            recommendation = "r",
            details = buildJsonObject { put("verdict", "SHIP") },
            aiInsights = buildJsonObject { put("not", "a valid AiInsights") },
        )
        val controller = AnalysisReportTypeController(json)
        assertFails("malformed aiInsights must throw") {
            controller.aiInsights(report)
        }
    }

    // -----------------------------------------------------------------
    // ExperimentQueryController
    // -----------------------------------------------------------------

    @Test
    fun `ExperimentQueryController all clamps limit to 100 and guards admin`() = runTest {
        val service = mockk<ExperimentService>(relaxed = true)
        val group = mockk<GroupEvaluator>(relaxed = true)
        val controller = ExperimentQueryController(service, group)
        val auth = adminAuth()
        coEvery { service.getAll(0L, 100) } returns emptyList()
        controller.all(auth, offset = -1L, limit = 999)
        coVerify(exactly = 1) { group.verifyHasAdminGroup(auth) }
        coVerify(exactly = 1) { service.getAll(0L, 100) }
    }

    @Test
    fun `ExperimentQueryController experiment forwards to service`() = runTest {
        val service = mockk<ExperimentService>(relaxed = true)
        val group = mockk<GroupEvaluator>(relaxed = true)
        val controller = ExperimentQueryController(service, group)
        val auth = adminAuth()
        val exp = Experiment(
            id = experimentId,
            featureFlagId = UUID.random(),
            controlVariationKey = "control",
            name = "n",
            description = "",
            hypothesis = "",
            status = ExperimentStatus.DRAFT,
        )
        coEvery { service.getById(experimentId) } returns exp
        assertEquals(exp, controller.experiment(auth, experimentId))
    }

    @Test
    fun `ExperimentQueryController byFlag forwards to service`() = runTest {
        val service = mockk<ExperimentService>(relaxed = true)
        val group = mockk<GroupEvaluator>(relaxed = true)
        val controller = ExperimentQueryController(service, group)
        val auth = adminAuth()
        val flagId = UUID.random()
        coEvery { service.getByFlagId(flagId) } returns emptyList()
        val result = controller.byFlag(auth, flagId)
        assertEquals(0, result.size)
        coVerify(exactly = 1) { service.getByFlagId(flagId) }
    }

    // -----------------------------------------------------------------
    // ExclusionLayer controllers
    // -----------------------------------------------------------------

    @Test
    fun `ExclusionLayerQueryController forwards all + layer behind admin gate`() = runTest {
        val service = mockk<ExclusionLayerService>(relaxed = true)
        val group = mockk<GroupEvaluator>(relaxed = true)
        val controller = ExclusionLayerQueryController(service, group)
        val auth = adminAuth()
        val id = UUID.random()
        val layer = ExclusionLayer(id = id, name = "main", description = "d")
        coEvery { service.getAll() } returns listOf(layer)
        coEvery { service.getById(id) } returns layer
        assertEquals(listOf(layer), controller.all(auth))
        assertEquals(layer, controller.layer(auth, id))
        coVerify(exactly = 2) { group.verifyHasAdminGroup(auth) }
    }

    @Test
    fun `ExclusionLayerMutationController forwards every mutation behind admin gate`() = runTest {
        val service = mockk<ExclusionLayerService>(relaxed = true)
        val group = mockk<GroupEvaluator>(relaxed = true)
        val controller = ExclusionLayerMutationController(service, group)
        val auth = adminAuth()
        val id = UUID.random()
        val input = ExclusionLayerInput(name = "main", description = "d")
        val layer = ExclusionLayer(id = id, name = "main", description = "d")
        coEvery { service.add(input) } returns layer
        coEvery { service.edit(id, input) } returns layer
        coJustRun { service.delete(id) }
        assertEquals(layer, controller.add(auth, input))
        assertEquals(layer, controller.edit(auth, id, input))
        assertEquals(true, controller.delete(auth, id))
        coVerify(exactly = 3) { group.verifyHasAdminGroup(auth) }
    }

    @Test
    fun `ExclusionLayerTypeController forwards every field including experiments`() = runTest {
        val service = mockk<ExclusionLayerService>(relaxed = true)
        val controller = ExclusionLayerTypeController(service)
        val id = UUID.random()
        val layer = ExclusionLayer(id = id, name = "main", description = "d")
        val exp = Experiment(
            id = experimentId,
            featureFlagId = UUID.random(),
            controlVariationKey = "control",
            name = "n",
            description = "",
            hypothesis = "",
            status = ExperimentStatus.RUNNING,
        )
        coEvery { service.getExperiments(id, 0L, 100) } returns listOf(exp)
        assertEquals(id, controller.id(layer))
        assertEquals("main", controller.name(layer))
        assertEquals("d", controller.description(layer))
        assertEquals(listOf(exp), controller.experiments(layer, 0L, 100))
        assertEquals(layer.created, controller.created(layer))
    }

    // -----------------------------------------------------------------
    // AssignmentTypeController
    // -----------------------------------------------------------------

    @Test
    fun `AssignmentTypeController forwards every field`() {
        val now = java.time.OffsetDateTime.now()
        val assignment = Assignment(
            id = UUID.random(),
            experimentId = experimentId,
            variationKey = "treatment",
            principalId = UUID.random(),
            installationId = "device-1",
            assignedAt = now,
        )
        val controller = AssignmentTypeController()
        assertEquals(assignment.id, controller.id(assignment))
        assertEquals(experimentId, controller.experimentId(assignment))
        assertEquals("treatment", controller.variationKey(assignment))
        assertEquals(assignment.principalId, controller.principalId(assignment))
        assertEquals("device-1", controller.installationId(assignment))
        assertEquals(now, controller.assignedAt(assignment))
    }

    // -----------------------------------------------------------------
    // ExperimentResultTypeController — remaining plain fields
    // -----------------------------------------------------------------

    @Test
    fun `ExperimentResultTypeController exposes impressions conversions and liftOverControl`() {
        val goalRepo = mockk<ConversionGoalRepository>(relaxed = true)
        val controller = ExperimentResultTypeController(goalRepo)
        val result = ExperimentResult(
            id = UUID.random(),
            experimentId = experimentId,
            variationKey = "treatment",
            goalId = UUID.random(),
            impressions = 5000,
            conversions = 450,
            conversionRate = 0.09,
            confidenceLevel = 0.97,
            liftOverControl = 12.5,
            mean = 0.09,
            variance = 0.08,
        )
        assertEquals(5000L, controller.impressions(result))
        assertEquals(5000L, controller.observationCount(result))
        assertEquals(450L, controller.conversions(result))
        assertEquals(0.09, controller.conversionRate(result))
        assertEquals(12.5, controller.liftOverControl(result))
        assertEquals(0.09, controller.mean(result))
        assertEquals(0.08, controller.variance(result))
    }

    @Test
    fun `ExperimentResultTypeController exposes Bayesian and CUPED fields`() {
        val goalRepo = mockk<ConversionGoalRepository>(relaxed = true)
        val controller = ExperimentResultTypeController(goalRepo)
        val now = java.time.OffsetDateTime.now()
        val result = ExperimentResult(
            id = UUID.random(),
            experimentId = experimentId,
            variationKey = "treatment",
            goalId = UUID.random(),
            impressions = 5000,
            conversions = 450,
            conversionRate = 0.09,
            confidenceLevel = 0.97,
            liftOverControl = 12.5,
            mean = 0.09,
            variance = 0.08,
            probabilityBeatsControl = 0.95,
            expectedLoss = 0.002,
            adjustedMean = 0.088,
            adjustedVariance = 0.075,
            updatedAt = now,
        )
        assertEquals(0.95, controller.probabilityBeatsControl(result))
        assertEquals(0.002, controller.expectedLoss(result))
        assertEquals(0.088, controller.adjustedMean(result))
        assertEquals(0.075, controller.adjustedVariance(result))
        assertEquals(now, controller.updatedAt(result))
        assertEquals(result.id, controller.id(result))
        assertEquals(experimentId, controller.experimentId(result))
        assertEquals("treatment", controller.variationKey(result))
        assertEquals(result.goalId, controller.goalId(result))
        assertEquals(0.97, controller.confidenceLevel(result))
    }

    // -----------------------------------------------------------------
    // FlagEvaluationTypeController
    // -----------------------------------------------------------------

    @Test
    fun `FlagEvaluationTypeController forwards every field`() {
        val evaluation = bosca.experimentation.model.FlagEvaluation(
            flagKey = "feature-x",
            variationKey = "treatment",
            value = kotlinx.serialization.json.JsonPrimitive(true),
            experimentId = experimentId,
        )
        val controller = FlagEvaluationTypeController()
        assertEquals("feature-x", controller.flagKey(evaluation))
        assertEquals("treatment", controller.variationKey(evaluation))
        assertEquals(kotlinx.serialization.json.JsonPrimitive(true), controller.value(evaluation))
        assertEquals(experimentId, controller.experimentId(evaluation))
    }

    @Test
    fun `FlagEvaluationTypeController experimentId is null when not in experiment`() {
        val evaluation = bosca.experimentation.model.FlagEvaluation(
            flagKey = "feature-y",
            variationKey = "control",
            value = kotlinx.serialization.json.JsonPrimitive(false),
            experimentId = null,
        )
        val controller = FlagEvaluationTypeController()
        assertNull(controller.experimentId(evaluation))
    }

    // -----------------------------------------------------------------
    // AiInsightsTypeController
    // -----------------------------------------------------------------

    @Test
    fun `AiInsightsTypeController forwards every field`() {
        val insights = AiInsights(
            hypothesisAssessment = "confirmed",
            crossGoalPatterns = "signup up, revenue down",
            followUpExperiments = listOf("widen rollout", "test pricing"),
            srmRootCauseHints = "cookie loss on redirect",
        )
        val controller = AiInsightsTypeController()
        assertEquals("confirmed", controller.hypothesisAssessment(insights))
        assertEquals("signup up, revenue down", controller.crossGoalPatterns(insights))
        assertEquals(listOf("widen rollout", "test pricing"), controller.followUpExperiments(insights))
        assertEquals("cookie loss on redirect", controller.srmRootCauseHints(insights))
    }

    @Test
    fun `AiInsightsTypeController srmRootCauseHints null when absent`() {
        val insights = AiInsights(
            hypothesisAssessment = "ok",
            crossGoalPatterns = "ok",
            followUpExperiments = emptyList(),
            srmRootCauseHints = null,
        )
        val controller = AiInsightsTypeController()
        assertNull(controller.srmRootCauseHints(insights))
    }

    // -----------------------------------------------------------------
    // ExperimentTypeController — plain field resolvers
    // -----------------------------------------------------------------

    @Test
    fun `ExperimentTypeController forwards all plain fields`() {
        val now = java.time.OffsetDateTime.now()
        val flagId = UUID.random()
        val excludedPrincipalId = UUID.random()
        val experiment = Experiment(
            id = experimentId,
            featureFlagId = flagId,
            controlVariationKey = "control",
            name = "exp-1",
            description = "desc",
            hypothesis = "hypo",
            status = ExperimentStatus.RUNNING,
            targetingRuleId = "rule-1",
            excludedPrincipalIds = listOf(excludedPrincipalId),
            startDate = now.minusDays(7),
            endDate = now,
            targetSampleSize = 10000,
            created = now.minusDays(14),
            modified = now.minusDays(1),
        )
        val controller = ExperimentTypeController(
            featureFlagService = mockk(relaxed = true),
            experimentService = mockk(relaxed = true),
            exclusionLayerService = mockk(relaxed = true),

            json = json,
        )
        assertEquals(experimentId, controller.id(experiment))
        assertEquals(flagId, controller.featureFlagId(experiment))
        assertEquals("exp-1", controller.name(experiment))
        assertEquals("desc", controller.description(experiment))
        assertEquals("hypo", controller.hypothesis(experiment))
        assertEquals(ExperimentStatus.RUNNING, controller.status(experiment))
        assertEquals("rule-1", controller.targetingRuleId(experiment))
        assertEquals(now.minusDays(7), controller.startDate(experiment))
        assertEquals(now, controller.endDate(experiment))
        assertEquals(10000L, controller.targetSampleSize(experiment))
        assertEquals(now.minusDays(14), controller.created(experiment))
        assertEquals(now.minusDays(1), controller.modified(experiment))
        assertEquals("control", controller.controlVariationKey(experiment))
        assertEquals(listOf(excludedPrincipalId), controller.excludedPrincipalIds(experiment))
        assertEquals(bosca.experimentation.model.AnalysisMethod.FREQUENTIST, controller.analysisMethod(experiment))
    }

    @Test
    fun `ExperimentTypeController exclusionLayer returns null when no layer assigned`() = runTest {
        val experiment = Experiment(
            id = experimentId,
            featureFlagId = UUID.random(),
            controlVariationKey = "control",
            name = "n",
            description = "",
            hypothesis = "",
            status = ExperimentStatus.DRAFT,
            exclusionLayerId = null,
        )
        val controller = ExperimentTypeController(
            featureFlagService = mockk(relaxed = true),
            experimentService = mockk(relaxed = true),
            exclusionLayerService = mockk(relaxed = true),

            json = json,
        )
        assertNull(controller.exclusionLayer(experiment))
    }

    @Test
    fun `ExperimentTypeController exclusionLayer delegates when layer assigned`() = runTest {
        val layerId = UUID.random()
        val layer = ExclusionLayer(id = layerId, name = "main", description = "d")
        val experiment = Experiment(
            id = experimentId,
            featureFlagId = UUID.random(),
            controlVariationKey = "control",
            name = "n",
            description = "",
            hypothesis = "",
            status = ExperimentStatus.DRAFT,
            exclusionLayerId = layerId,
        )
        val exclusionLayerService = mockk<ExclusionLayerService>()
        coEvery { exclusionLayerService.getById(layerId) } returns layer
        val controller = ExperimentTypeController(
            featureFlagService = mockk(relaxed = true),
            experimentService = mockk(relaxed = true),
            exclusionLayerService = exclusionLayerService,

            json = json,
        )
        assertEquals(layer, controller.exclusionLayer(experiment))
    }

    @Test
    fun `ExperimentTypeController conversionGoals clamps offset and limit`() = runTest {
        val experimentService = mockk<ExperimentService>()
        coEvery { experimentService.getConversionGoals(experimentId, 0L, 100) } returns emptyList()
        val controller = ExperimentTypeController(
            featureFlagService = mockk(relaxed = true),
            experimentService = experimentService,
            exclusionLayerService = mockk(relaxed = true),

            json = json,
        )
        val experiment = Experiment(
            id = experimentId,
            featureFlagId = UUID.random(),
            controlVariationKey = "control",
            name = "n",
            description = "",
            hypothesis = "",
            status = ExperimentStatus.DRAFT,
        )
        controller.conversionGoals(experiment, offset = -5L, limit = 999)
        coVerify(exactly = 1) { experimentService.getConversionGoals(experimentId, 0L, 100) }
    }

    @Test
    fun `ExperimentTypeController results clamps offset and limit`() = runTest {
        val experimentService = mockk<ExperimentService>()
        coEvery { experimentService.getResults(experimentId, 0L, 200) } returns emptyList()
        val controller = ExperimentTypeController(
            featureFlagService = mockk(relaxed = true),
            experimentService = experimentService,
            exclusionLayerService = mockk(relaxed = true),

            json = json,
        )
        val experiment = Experiment(
            id = experimentId,
            featureFlagId = UUID.random(),
            controlVariationKey = "control",
            name = "n",
            description = "",
            hypothesis = "",
            status = ExperimentStatus.DRAFT,
        )
        controller.results(experiment, offset = -1L, limit = 999)
        coVerify(exactly = 1) { experimentService.getResults(experimentId, 0L, 200) }
    }

    @Test
    fun `ExperimentTypeController analysisReports clamps offset and limit`() = runTest {
        val experimentService = mockk<ExperimentService>()
        coEvery {
            experimentService.getAnalysisReportsForRevision(experimentId, "control", 0L, 0L, 100)
        } returns emptyList()
        val controller = ExperimentTypeController(
            featureFlagService = mockk(relaxed = true),
            experimentService = experimentService,
            exclusionLayerService = mockk(relaxed = true),

            json = json,
        )
        val experiment = Experiment(
            id = experimentId,
            featureFlagId = UUID.random(),
            controlVariationKey = "control",
            name = "n",
            description = "",
            hypothesis = "",
            status = ExperimentStatus.DRAFT,
        )
        controller.analysisReports(experiment, offset = -1L, limit = 500)
        coVerify(exactly = 1) {
            experimentService.getAnalysisReportsForRevision(experimentId, "control", 0L, 0L, 100)
        }
    }

    @Test
    fun `ExperimentTypeController marks only reports from the current analysis revision`() = runTest {
        val experiment = Experiment(
            id = experimentId,
            featureFlagId = UUID.random(),
            controlVariationKey = "7",
            name = "n",
            analysisRevision = 7L,
        )
        fun report(id: UUID, control: JsonPrimitive, revision: JsonPrimitive) = AnalysisReport(
            id = id,
            experimentId = experimentId,
            summary = "summary",
            recommendation = "SHIP",
            details = buildJsonObject {
                put("controlVariationKey", control)
                put("experimentRevision", revision)
            },
        )
        val currentId = UUID.random()
        val staleId = UUID.random()
        val quotedRevisionId = UUID.random()
        val numericControlId = UUID.random()
        val experimentService = mockk<ExperimentService>()
        coEvery {
            experimentService.getAnalysisReportsForRevision(experimentId, "7", 7L, 0L, 100)
        } returns listOf(
            report(currentId, JsonPrimitive("7"), JsonPrimitive(7L)),
            report(staleId, JsonPrimitive("7"), JsonPrimitive(6L)),
            report(quotedRevisionId, JsonPrimitive("7"), JsonPrimitive("7")),
            report(numericControlId, JsonPrimitive(7L), JsonPrimitive(7L)),
        )
        val controller = ExperimentTypeController(
            featureFlagService = mockk(relaxed = true),
            experimentService = experimentService,
            exclusionLayerService = mockk(relaxed = true),
            json = json,
        )

        val reports = controller.analysisReports(experiment, offset = 0L, limit = 100)

        assertEquals(JsonPrimitive(true), (reports[0].details as JsonObject)["isCurrent"])
        assertEquals(JsonPrimitive(false), (reports[1].details as JsonObject)["isCurrent"])
        assertEquals(JsonPrimitive(false), (reports[2].details as JsonObject)["isCurrent"])
        assertEquals(JsonPrimitive(false), (reports[3].details as JsonObject)["isCurrent"])
    }

    @Test
    fun `ExperimentTypeController tolerates legacy and malformed analysis report markers`() = runTest {
        val experiment = Experiment(
            id = experimentId,
            featureFlagId = UUID.random(),
            controlVariationKey = "control",
            name = "n",
            analysisRevision = 3L,
        )
        fun report(details: kotlinx.serialization.json.JsonElement) = AnalysisReport(
            experimentId = experimentId,
            summary = "summary",
            recommendation = "CONTINUE",
            details = details,
        )
        val primitiveDetails = JsonPrimitive("legacy")
        val experimentService = mockk<ExperimentService>()
        coEvery {
            experimentService.getAnalysisReportsForRevision(experimentId, "control", 3L, 0L, 100)
        } returns listOf(
            report(primitiveDetails),
            report(buildJsonObject { put("experimentRevision", JsonPrimitive(3L)) }),
            report(buildJsonObject {
                put("controlVariationKey", buildJsonObject {})
                put("experimentRevision", JsonPrimitive(3L))
            }),
            report(buildJsonObject {
                put("controlVariationKey", JsonPrimitive("control"))
                put("experimentRevision", buildJsonObject {})
            }),
            report(buildJsonObject { put("controlVariationKey", JsonPrimitive("control")) }),
        )
        val controller = ExperimentTypeController(
            featureFlagService = mockk(relaxed = true),
            experimentService = experimentService,
            exclusionLayerService = mockk(relaxed = true),
            json = json,
        )

        val reports = controller.analysisReports(experiment, offset = 0L, limit = 100)

        assertEquals(primitiveDetails, reports[0].details)
        reports.drop(1).forEach { report ->
            assertEquals(JsonPrimitive(false), (report.details as JsonObject)["isCurrent"])
        }
    }

    @Test
    fun `ExperimentResultTypeController resolves its conversion goal through the cache`() = runTest {
        val goalId = UUID.random()
        val goal = ConversionGoal(id = goalId, experimentId = experimentId, name = "Goal")
        val repository = mockk<ConversionGoalRepository>()
        coEvery { repository.getById(goalId) } returns goal
        val controller = ExperimentResultTypeController(repository)
        val result = ExperimentResult(
            experimentId = experimentId,
            variationKey = "control",
            goalId = goalId,
        )

        cacheSupport.withFlagCache {
            assertEquals(goal, controller.goal(result))
        }
    }

    @Test
    fun `ExperimentTypeController resolves its feature flag`() = runTest {
        val flagId = UUID.random()
        val service = mockk<FeatureFlagService>()
        val flag = bosca.experimentation.model.FeatureFlag(
            id = flagId,
            key = "flag",
            name = "Flag",
            variations = JsonPrimitive("[]"),
            defaultVariationKey = "control",
        )
        coEvery { service.getById(flagId) } returns flag
        val controller = ExperimentTypeController(
            featureFlagService = service,
            experimentService = mockk(relaxed = true),
            exclusionLayerService = mockk(relaxed = true),
            json = json,
        )
        val experiment = Experiment(
            id = experimentId,
            featureFlagId = flagId,
            controlVariationKey = "control",
            name = "Experiment",
        )

        assertEquals(flag, controller.featureFlag(experiment))
    }
}
