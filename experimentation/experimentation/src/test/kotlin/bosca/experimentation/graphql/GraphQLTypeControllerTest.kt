package bosca.experimentation.graphql

import bosca.analytics.model.EventType
import bosca.experimentation.model.AiInsights
import bosca.experimentation.model.AnalysisMethod
import bosca.experimentation.model.AnalysisReport
import bosca.experimentation.model.BayesianPrior
import bosca.experimentation.model.ConversionGoal
import bosca.experimentation.model.ConversionGoalRole
import bosca.experimentation.model.CupedCovariate
import bosca.experimentation.model.Experiment
import bosca.experimentation.model.ExperimentActivationFilter
import bosca.experimentation.model.ExperimentResult
import bosca.experimentation.model.ExperimentStatus
import bosca.experimentation.model.GoalMetricType
import bosca.experimentation.model.RolloutPolicy
import bosca.experimentation.model.RolloutPolicyAction
import bosca.experimentation.model.RolloutPolicyEvent
import bosca.experimentation.model.RolloutPolicyMode
import bosca.experimentation.model.RolloutStep
import bosca.experimentation.service.ExclusionLayerService
import bosca.experimentation.service.ExperimentService
import bosca.experimentation.service.FeatureFlagService
import bosca.serialization.UUID
import bosca.experimentation.FlagCacheTestSupport
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the typed GraphQL field resolvers on
 * `ConversionGoalTypeController`, `ExperimentTypeController`,
 * `ExperimentResultTypeController`, and the small resolver-only
 * classes in `RolloutPolicyTypeControllers.kt`.
 *
 * These resolvers look trivial — they mostly forward a field to its
 * typed counterpart on the domain model — but they carry two kinds
 * of risk worth pinning:
 *
 *   1. JSONB decoding. Each of `ConversionGoal.cupedCovariate`,
 *      `Experiment.rolloutPolicy`, and `Experiment.bayesianPrior`
 *      is stored as a `JsonElement` and decoded at read time via
 *      kotlinx.serialization. A malformed blob must throw (not
 *      silently resolve to null) — this is the "no ghost failures"
 *      contract. These tests pin both directions: a well-formed
 *      blob round-trips, a malformed blob throws at the field
 *      resolver.
 *   2. Input translation. `ConversionGoalInput` and
 *      `CupedCovariateInput` mirror the service-layer shapes with
 *      PascalCase GraphQL enums and must map cleanly onto the
 *      wire-format analytics `EventType` and every downstream
 *      model field. A silent drop on one of these mappings would
 *      cause admin-UI edits to lose data.
 */
class GraphQLTypeControllerTest {

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

    @Test
    fun `GraphQL path prefix inputs accept omitted null empty and populated lists`() {
        for ((field, expected) in listOf(
            "" to emptyList(),
            "\"pagePathPrefixes\": null," to emptyList(),
            "\"pagePathPrefixes\": []," to emptyList(),
            "\"pagePathPrefixes\": [\"/articles/\", \"/talks/\"]," to listOf("/articles/", "/talks/"),
        )) {
            val activation = json.decodeFromString(
                ExperimentActivationFilterInput.serializer(), "{$field \"eventType\": \"Impression\"}",
            )
            val goal = json.decodeFromString(
                ConversionGoalInput.serializer(), "{$field \"name\": \"Follow-up views\"}",
            )
            assertEquals(expected, activation.toModel().pagePathPrefixes, "activation: $field")
            assertEquals(expected, goal.toServiceInput().pagePathPrefixes, "goal: $field")
        }
    }

    @Test
    fun `ExperimentInput decodes PascalCase activation event type from GraphQL`() {
        val input = json.decodeFromString<ExperimentInput>(
            """
            {
              "featureFlagId": "00000000-0000-0000-0000-000000000001",
              "name": "Article activation",
              "controlVariationKey": "control",
              "activationFilter": {
                "eventType": "Impression",
                "elementType": "page",
                "pagePathPrefixes": ["/articles/"]
              }
            }
            """.trimIndent(),
        )

        val serviceInput = input.toServiceInput()
        assertEquals(EventType.Impression, serviceInput.activationFilter?.eventType)
        assertEquals(listOf("/articles/"), serviceInput.activationFilter?.pagePathPrefixes)
    }

    // -----------------------------------------------------------------
    // ConversionGoalTypeController — cupedCovariate decoding
    // -----------------------------------------------------------------

    @Test
    fun `ConversionGoalTypeController cupedCovariate decodes a stored blob`() {
        val goal = ConversionGoal(
            id = UUID.random(),
            experimentId = UUID.random(),
            name = "Signups",
            eventType = EventType.Interaction,
            metricType = GoalMetricType.EVENT_COUNT,
            cupedCovariate = json.encodeToJsonElement(
                CupedCovariate.serializer(),
                CupedCovariate(
                    eventType = EventType.Interaction,
                    elementType = "click",
                    elementId = "checkout",
                    pagePath = "/checkout",
                    lookbackWindow = "P21D",
                ),
            ),
        )
        val controller = ConversionGoalTypeController(json)
        val decoded = assertNotNull(controller.cupedCovariate(goal))
        assertEquals(EventType.Interaction, decoded.eventType)
        assertEquals("click", decoded.elementType)
        assertEquals("P21D", decoded.lookbackWindow)
    }

    @Test
    fun `ConversionGoalTypeController cupedCovariate returns null when absent`() {
        val goal = ConversionGoal(
            id = UUID.random(),
            experimentId = UUID.random(),
            name = "No CUPED",
            eventType = EventType.Interaction,
            metricType = GoalMetricType.EVENT_COUNT,
            cupedCovariate = null,
        )
        val controller = ConversionGoalTypeController(json)
        assertNull(controller.cupedCovariate(goal))
    }

    @Test
    fun `ConversionGoalTypeController cupedCovariate throws on malformed JSON`() {
        val badBlob = buildJsonObject {
            put("eventType", "NotAnEventTypeEnumValue")
            put("lookbackWindow", "P14D")
        }
        val goal = ConversionGoal(
            id = UUID.random(),
            experimentId = UUID.random(),
            name = "Broken",
            eventType = EventType.Interaction,
            metricType = GoalMetricType.EVENT_COUNT,
            cupedCovariate = badBlob,
        )
        val controller = ConversionGoalTypeController(json)
        assertFails("malformed cupedCovariate must throw") {
            controller.cupedCovariate(goal)
        }
    }

    @Test
    fun `ConversionGoalTypeController exposes every plain field`() {
        val goal = ConversionGoal(
            id = UUID.random(),
            experimentId = UUID.random(),
            name = "Full goal",
            eventType = EventType.Impression,
            elementType = "page",
            elementId = "/home",
            metricType = GoalMetricType.UNIQUE_CONVERSION,
            pagePath = "/home",
            pagePathPrefixes = listOf("/articles/", "/talks/"),
            itemExtraKey = "campaign",
            itemExtraValue = "spring",
            role = ConversionGoalRole.GUARDRAIL,
        )
        val controller = ConversionGoalTypeController(json)
        assertEquals(goal.id, controller.id(goal))
        assertEquals(goal.experimentId, controller.experimentId(goal))
        assertEquals("Full goal", controller.name(goal))
        assertEquals(GraphQLEventType.Impression, controller.eventType(goal))
        assertEquals("page", controller.elementType(goal))
        assertEquals("/home", controller.elementId(goal))
        assertEquals(GoalMetricType.UNIQUE_CONVERSION, controller.metricType(goal))
        assertEquals("/home", controller.pagePath(goal))
        assertEquals(listOf("/articles/", "/talks/"), controller.pagePathPrefixes(goal))
        assertEquals("campaign", controller.itemExtraKey(goal))
        assertEquals("spring", controller.itemExtraValue(goal))
        assertEquals(ConversionGoalRole.GUARDRAIL, controller.role(goal))
        assertEquals(goal.created, controller.created(goal))
        assertNull(controller.eventType(goal.copy(eventType = null)))
    }

    // -----------------------------------------------------------------
    // ExperimentTypeController — rolloutPolicy / bayesianPrior decoding
    // -----------------------------------------------------------------

    private fun experimentWith(
        rolloutPolicy: RolloutPolicy? = null,
        analysisMethod: AnalysisMethod = AnalysisMethod.FREQUENTIST,
        prior: BayesianPrior? = null,
        activationFilter: ExperimentActivationFilter? = null,
    ): Experiment = Experiment(
        id = UUID.random(),
        featureFlagId = UUID.random(),
        controlVariationKey = "control",
        name = "Exp",
        description = "",
        hypothesis = "treatment > control",
        status = ExperimentStatus.RUNNING,
        targetingRuleId = "rule-1",
        activationFilter = activationFilter,
        rolloutPolicy = rolloutPolicy?.let {
            json.encodeToJsonElement(RolloutPolicy.serializer(), it)
        },
        analysisMethod = analysisMethod,
        bayesianPrior = prior?.let {
            json.encodeToJsonElement(BayesianPrior.serializer(), it)
        },
    )

    private fun experimentController(
    ) = ExperimentTypeController(
        featureFlagService = mockk<FeatureFlagService>(relaxed = true),
        experimentService = mockk<ExperimentService>(relaxed = true),
        exclusionLayerService = mockk<ExclusionLayerService>(relaxed = true),
        json = json,
    )

    @Test
    fun `ExperimentTypeController rolloutPolicy decodes a stored blob`() = runTest {
        val policy = RolloutPolicy(
            mode = RolloutPolicyMode.ADAPTIVE_STEPS,
            treatmentVariationKey = "treatment",
            steps = listOf(
                RolloutStep(weightPercent = 25),
                RolloutStep(weightPercent = 50),
                RolloutStep(weightPercent = 100),
            ),
            minConfidence = 0.97,
        )
        val experiment = experimentWith(rolloutPolicy = policy)
        val decoded = assertNotNull(experimentController().rolloutPolicy(experiment))
        assertEquals(policy, decoded)
    }

    @Test
    fun `ExperimentTypeController rolloutPolicy returns null when absent`() = runTest {
        val experiment = experimentWith(rolloutPolicy = null)
        assertNull(experimentController().rolloutPolicy(experiment))
    }

    @Test
    fun `ExperimentTypeController rolloutPolicy throws on malformed JSON`() = runTest {
        // Build a JsonObject that does not match any valid
        // RolloutPolicy shape — missing required fields.
        val badBlob = buildJsonObject {
            put("mode", "NOT_A_MODE")
        }
        val experiment = Experiment(
            id = UUID.random(),
            featureFlagId = UUID.random(),
            controlVariationKey = "control",
            name = "Broken policy",
            description = "",
            hypothesis = "",
            status = ExperimentStatus.RUNNING,
            targetingRuleId = "rule-1",
            rolloutPolicy = badBlob,
        )
        assertFails("malformed rolloutPolicy must throw") {
            experimentController().rolloutPolicy(experiment)
        }
    }

    @Test
    fun `ExperimentTypeController bayesianPrior decodes and round-trips custom priors`() = runTest {
        val prior = BayesianPrior(
            betaPriorAlpha = 10.0,
            betaPriorBeta = 5.0,
            normalPriorMean = 0.5,
            normalPriorVariance = 1.0,
        )
        val experiment = experimentWith(
            analysisMethod = AnalysisMethod.BAYESIAN,
            prior = prior,
        )
        val decoded = assertNotNull(experimentController().bayesianPrior(experiment))
        assertEquals(prior, decoded)
        assertEquals(AnalysisMethod.BAYESIAN, experimentController().analysisMethod(experiment))
    }

    @Test
    fun `ExperimentTypeController bayesianPrior returns null when no prior configured`() = runTest {
        val experiment = experimentWith(prior = null)
        assertNull(experimentController().bayesianPrior(experiment))
    }

    @Test
    fun `activation filter controllers expose every field and nullable event type`() {
        val filter = ExperimentActivationFilter(
            eventType = EventType.Impression,
            elementType = "page",
            elementId = "article-page",
            pagePath = "/articles/featured",
            pagePathPrefixes = listOf("/articles/", "/talks/", "/studies/"),
            itemExtraKey = "campaign",
            itemExtraValue = "reader",
        )
        val controller = ExperimentActivationFilterTypeController()
        assertEquals(GraphQLEventType.Impression, controller.eventType(filter))
        assertEquals("page", controller.elementType(filter))
        assertEquals("article-page", controller.elementId(filter))
        assertEquals("/articles/featured", controller.pagePath(filter))
        assertEquals(listOf("/articles/", "/talks/", "/studies/"), controller.pagePathPrefixes(filter))
        assertEquals("campaign", controller.itemExtraKey(filter))
        assertEquals("reader", controller.itemExtraValue(filter))
        assertNull(controller.eventType(filter.copy(eventType = null)))
        assertEquals(filter, experimentController().activationFilter(experimentWith(activationFilter = filter)))
    }

    @Test
    fun `ExperimentTypeController rolloutPolicyEvents forwards to service with clamped limit`() = runTest {
        val expService = mockk<ExperimentService>(relaxed = true)
        val experiment = experimentWith()
        val fakeEvent = RolloutPolicyEvent(
            experimentId = experiment.id,
            action = RolloutPolicyAction.ADVANCED,
            reason = "test",
            oldWeights = buildJsonObject { put("control", 100) },
            newWeights = buildJsonObject { put("control", 75); put("treatment", 25) },
        )
        coEvery { expService.getRolloutPolicyEvents(experiment.id, 0L, 100) } returns listOf(fakeEvent)
        // A request for limit=500 should be clamped to 100 (the
        // controller's MAX_EVENTS_PAGE), preventing a caller from
        // paging the whole audit log in one request.
        val controller = ExperimentTypeController(
            featureFlagService = mockk(relaxed = true),
            experimentService = expService,
            exclusionLayerService = mockk(relaxed = true),
            json = json,
        )
        val result = controller.rolloutPolicyEvents(
            experiment = experiment, offset = 0L, limit = 500,
        )
        assertEquals(1, result.size)
        assertEquals(RolloutPolicyAction.ADVANCED, result.first().action)
    }

    // -----------------------------------------------------------------
    // ExperimentResultTypeController — Bayesian + CUPED fields
    // -----------------------------------------------------------------

    @Test
    fun `ExperimentResultTypeController exposes Bayesian and CUPED columns`() = runTest {
        val result = ExperimentResult(
            id = UUID.random(),
            experimentId = UUID.random(),
            variationKey = "treatment",
            goalId = UUID.random(),
            impressions = 1000,
            assignments = 1250,
            conversions = 150,
            conversionRate = 0.15,
            confidenceLevel = 0.96,
            liftOverControl = 5.0,
            mean = 0.15,
            variance = 0.12,
            probabilityBeatsControl = 0.98,
            expectedLoss = 0.002,
            adjustedMean = 0.15,
            adjustedVariance = 0.04,
        )
        val controller = ExperimentResultTypeController(
            conversionGoalRepository = mockk(relaxed = true),
        )
        assertEquals(0.96, controller.confidenceLevel(result))
        assertEquals(1250L, controller.assignments(result))
        assertEquals(0.98, controller.probabilityBeatsControl(result))
        assertEquals(0.002, controller.expectedLoss(result))
        assertEquals(0.15, controller.adjustedMean(result))
        assertEquals(0.04, controller.adjustedVariance(result))
    }

    // -----------------------------------------------------------------
    // RolloutPolicy* type controllers (plain forwarding)
    // -----------------------------------------------------------------

    @Test
    fun `RolloutPolicyTypeController forwards every field`() {
        val policy = RolloutPolicy(
            mode = RolloutPolicyMode.SCHEDULED_STEPS,
            treatmentVariationKey = "treatment",
            steps = listOf(RolloutStep(weightPercent = 25, afterDuration = "P1D")),
            incrementPercent = null,
            minConfidence = 0.95,
            guardrailThreshold = 0.95,
            guardrailMinRegressionPercent = 0.5,
            haltOnGuardrail = true,
        )
        val controller = RolloutPolicyTypeController()
        assertEquals(RolloutPolicyMode.SCHEDULED_STEPS, controller.mode(policy))
        assertEquals("treatment", controller.treatmentVariationKey(policy))
        assertEquals(1, controller.steps(policy)?.size)
        assertNull(controller.incrementPercent(policy))
        assertEquals(0.95, controller.minConfidence(policy))
        assertEquals(0.95, controller.guardrailThreshold(policy))
        assertEquals(0.5, controller.guardrailMinRegressionPercent(policy))
        assertEquals(true, controller.haltOnGuardrail(policy))
    }

    @Test
    fun `RolloutStepTypeController forwards every field`() {
        val step = RolloutStep(weightPercent = 50, afterDuration = "PT12H")
        val controller = RolloutStepTypeController()
        assertEquals(50, controller.weightPercent(step))
        assertEquals("PT12H", controller.afterDuration(step))
    }

    @Test
    fun `RolloutPolicyEventTypeController forwards every field`() {
        val event = RolloutPolicyEvent(
            id = UUID.random(),
            experimentId = UUID.random(),
            action = RolloutPolicyAction.HALTED,
            reason = "guardrail tripped",
            oldWeights = buildJsonObject { put("control", 50); put("treatment", 50) },
            newWeights = buildJsonObject { put("control", 100) },
        )
        val controller = RolloutPolicyEventTypeController()
        assertEquals(event.id, controller.id(event))
        assertEquals(event.experimentId, controller.experimentId(event))
        assertEquals(RolloutPolicyAction.HALTED, controller.action(event))
        assertEquals("guardrail tripped", controller.reason(event))
        assertNotNull(controller.oldWeights(event))
        assertNotNull(controller.newWeights(event))
        assertEquals(event.created, controller.created(event))
    }

    @Test
    fun `BayesianPriorTypeController forwards every field`() {
        val prior = BayesianPrior(
            betaPriorAlpha = 2.0,
            betaPriorBeta = 8.0,
            normalPriorMean = 0.1,
            normalPriorVariance = 0.5,
        )
        val controller = BayesianPriorTypeController()
        assertEquals(2.0, controller.betaPriorAlpha(prior))
        assertEquals(8.0, controller.betaPriorBeta(prior))
        assertEquals(0.1, controller.normalPriorMean(prior))
        assertEquals(0.5, controller.normalPriorVariance(prior))
    }

    @Test
    fun `CupedCovariateTypeController forwards every field`() {
        val covariate = CupedCovariate(
            eventType = EventType.Interaction,
            elementType = "click",
            elementId = "cta",
            pagePath = "/landing",
            lookbackWindow = "P30D",
        )
        val controller = CupedCovariateTypeController()
        assertEquals(GraphQLEventType.Interaction, controller.eventType(covariate))
        assertEquals("click", controller.elementType(covariate))
        assertEquals("cta", controller.elementId(covariate))
        assertEquals("/landing", controller.pagePath(covariate))
        assertEquals("P30D", controller.lookbackWindow(covariate))
    }

    // -----------------------------------------------------------------
    // ConversionGoalInput / CupedCovariateInput translation
    // -----------------------------------------------------------------

    @Test
    fun `ConversionGoalInput round-trips role + cupedCovariate through toServiceInput`() {
        val cupedInput = CupedCovariateInput(
            eventType = GraphQLEventType.Interaction,
            elementType = "click",
            elementId = "checkout-btn",
            pagePath = "/checkout",
            lookbackWindow = "P21D",
        )
        val goalInput = ConversionGoalInput(
            name = "Primary goal",
            eventType = GraphQLEventType.Interaction,
            elementType = "button",
            elementId = "submit",
            metricType = GoalMetricType.EVENT_COUNT,
            pagePath = "/form",
            pagePathPrefixes = listOf("/articles/", "/talks/", "/studies/"),
            role = ConversionGoalRole.GUARDRAIL,
            cupedCovariate = cupedInput,
        )
        val service = goalInput.toServiceInput()
        assertEquals("Primary goal", service.name)
        assertEquals(EventType.Interaction, service.eventType)
        assertEquals("button", service.elementType)
        assertEquals("submit", service.elementId)
        assertEquals(GoalMetricType.EVENT_COUNT, service.metricType)
        assertEquals("/form", service.pagePath)
        assertEquals(listOf("/articles/", "/talks/", "/studies/"), service.pagePathPrefixes)
        assertEquals(ConversionGoalRole.GUARDRAIL, service.role)

        val covariate = assertNotNull(service.cupedCovariate)
        assertEquals(EventType.Interaction, covariate.eventType)
        assertEquals("click", covariate.elementType)
        assertEquals("checkout-btn", covariate.elementId)
        assertEquals("/checkout", covariate.pagePath)
        assertEquals("P21D", covariate.lookbackWindow)
    }

    @Test
    fun `ConversionGoalInput defaults role to PRIMARY and cupedCovariate to null`() {
        val goalInput = ConversionGoalInput(
            name = "Minimal",
            eventType = null,
            metricType = GoalMetricType.UNIQUE_CONVERSION,
        )
        val service = goalInput.toServiceInput()
        assertEquals(ConversionGoalRole.PRIMARY, service.role)
        assertNull(service.eventType)
        assertNull(service.cupedCovariate)
    }

    @Test
    fun `CupedCovariateInput toModel drops null event type`() {
        val input = CupedCovariateInput(
            eventType = null,
            lookbackWindow = "P14D",
        )
        val model = input.toModel()
        assertNull(model.eventType)
        assertEquals("P14D", model.lookbackWindow)
        assertNull(CupedCovariateTypeController().eventType(model))
    }
}
