@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.experimentation.jobs

import bosca.di.ObjectProvider
import bosca.di.ProviderRegistry
import bosca.experimentation.model.AiInsights
import bosca.experimentation.model.AnalysisReport
import bosca.experimentation.model.ConversionGoal
import bosca.experimentation.model.ConversionGoalRole
import bosca.experimentation.model.Experiment
import bosca.experimentation.model.ExperimentResult
import bosca.experimentation.model.ExperimentStatus
import bosca.experimentation.model.FeatureFlag
import bosca.experimentation.model.FlagStatus
import bosca.experimentation.model.FlagType
import bosca.experimentation.model.GoalMetricType
import bosca.experimentation.model.Rollout
import bosca.experimentation.model.RolloutPolicy
import bosca.experimentation.model.RolloutPolicyMode
import bosca.experimentation.model.TargetingRule
import bosca.experimentation.model.VariationWeight
import bosca.experimentation.repository.AnalysisReportRepository
import bosca.experimentation.repository.FeatureFlagRepository
import bosca.experimentation.service.ExperimentService
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.JobQueue
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.slot
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end tests for [runExperimentAnalysis] — the orchestrator
 * that glues analysis-result aggregation, deterministic scoring,
 * AI commentary, and rollout-controller chaining into one job
 * execution.
 *
 * Pins the properties operators depend on:
 *
 *   1. Missing experiment / missing flag / empty results: early
 *      bail-out, no report written, no downstream chaining.
 *   2. Happy path: a full result set produces a persisted
 *      [AnalysisReport] whose `details` blob decodes as a typed
 *      [AnalysisReportDetails], whose `summary` / `recommendation`
 *      are the deterministic prose from the analyzer, and whose
 *      `confidence` matches the deterministic pipeline.
 *   3. AI step: absent analyzer → null aiInsights; analyzer throws →
 *      report still saved with null aiInsights (deterministic
 *      verdict stands on its own); analyzer succeeds → aiInsights
 *      populated on the persisted report.
 *   4. Rollout chaining: experiment with a non-null rollout policy
 *      AND status RUNNING → [RolloutPolicyJob] enqueued on the job
 *      queue after the report is saved. Without a policy, or when
 *      status != RUNNING, no enqueue.
 *
 * The aggregation step is injected as a function parameter so the
 * test can pass a no-op (real aggregation hits Trino and is tested
 * separately in `ExperimentResultAggregation` + `CupedEndToEndTest`).
 */
class ExperimentAnalysisExecutorTest {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Installs a `Json` provider in the DI registry for every test
     * in this class. The rollout-chaining path calls an inline
     * `Job(definition, ...)` factory in `bosca.sharedqueue.jobs`
     * which invokes `provide<Json>()` internally. Without this
     * provider, that call throws, the surrounding `runCatching`
     * swallows the error, and the follow-on-job assertion silently
     * passes for the wrong reason.
     */
    @BeforeTest
    fun installJsonProvider() {
        mockkObject(ProviderRegistry)
        every { ProviderRegistry.get(Json::class) } returns object : ObjectProvider<Json> {
            override val type = Json::class
            override suspend fun get(): Json = json
        }
    }

    @AfterTest
    fun uninstallJsonProvider() {
        unmockkObject(ProviderRegistry)
    }

    private val experimentId = UUID.parse("00000000-0000-0000-0000-000000000111")
    private val flagId = UUID.parse("00000000-0000-0000-0000-000000000222")
    private val ruleId = "rule-1"
    private val goalId = UUID.parse("00000000-0000-0000-0000-00000000a001")
    private val controlKey = "control"
    private val treatmentKey = "treatment"

    // -----------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------

    private fun variationsJson() = buildJsonArray {
        add(buildJsonObject {
            put("key", controlKey); put("name", "Control"); put("description", ""); put("value", false)
        })
        add(buildJsonObject {
            put("key", treatmentKey); put("name", "Treatment"); put("description", ""); put("value", true)
        })
    }

    private fun targetingRulesJson() = json.encodeToJsonElement(
        ListSerializer(TargetingRule.serializer()),
        listOf(
            TargetingRule(
                id = ruleId,
                name = "split",
                description = null,
                conditions = emptyList(),
                rollout = Rollout(
                    variationWeights = listOf(
                        VariationWeight(variationKey = controlKey, weight = 50),
                        VariationWeight(variationKey = treatmentKey, weight = 50),
                    )
                ),
            )
        ),
    )

    private fun experiment(
        status: ExperimentStatus = ExperimentStatus.RUNNING,
        rolloutPolicy: RolloutPolicy? = null,
    ): Experiment = Experiment(
        id = experimentId,
        featureFlagId = flagId,
        controlVariationKey = controlKey,
        name = "Test Experiment",
        description = "",
        hypothesis = "treatment > control",
        status = status,
        targetingRuleId = ruleId,
        rolloutPolicy = rolloutPolicy?.let {
            json.encodeToJsonElement(RolloutPolicy.serializer(), it)
        },
    )

    private fun flag(): FeatureFlag = FeatureFlag(
        id = flagId,
        key = "test-flag",
        name = "Test Flag",
        description = "",
        type = FlagType.BOOLEAN,
        status = FlagStatus.ENABLED,
        variations = variationsJson(),
        defaultVariationKey = controlKey,
        targetingRules = targetingRulesJson(),
        salt = "salt",
    )

    private fun goal(role: ConversionGoalRole = ConversionGoalRole.PRIMARY): ConversionGoal =
        ConversionGoal(
            id = goalId,
            experimentId = experimentId,
            name = "Signups",
            eventType = bosca.analytics.model.EventType.Interaction,
            metricType = GoalMetricType.UNIQUE_CONVERSION,
            role = role,
        )

    /**
     * Build a pair of `ExperimentResult` rows whose chi-squared
     * confidence on `ExperimentResultAggregation`'s own helper
     * pushes the verdict to SHIP. The control row carries a null
     * confidence (baseline convention); the treatment row carries
     * a high confidence so `runDeterministicAnalysis` routes it
     * through the SHIP branch.
     */
    private fun shipResults(): List<ExperimentResult> {
        val n = 2000L
        val controlConv = 240L
        val treatmentConv = 360L
        val treatmentConfidence = chiSquaredConfidence(n, controlConv, n, treatmentConv)
        return listOf(
            ExperimentResult(
                experimentId = experimentId,
                variationKey = controlKey,
                goalId = goalId,
                impressions = n,
                conversions = controlConv,
                conversionRate = controlConv.toDouble() / n,
                confidenceLevel = null,
            ),
            ExperimentResult(
                experimentId = experimentId,
                variationKey = treatmentKey,
                goalId = goalId,
                impressions = n,
                conversions = treatmentConv,
                conversionRate = treatmentConv.toDouble() / n,
                confidenceLevel = treatmentConfidence,
                liftOverControl = 50.0,
            ),
        )
    }

    private data class Mocks(
        val experimentService: ExperimentService,
        val analysisReportRepository: AnalysisReportRepository,
        val flagRepository: FeatureFlagRepository,
        val jobQueue: JobQueue,
        val aggregatorCalls: MutableList<UUID>,
    )

    private fun setupMocks(
        experiment: Experiment? = experiment(),
        flag: FeatureFlag? = flag(),
        goals: List<ConversionGoal> = listOf(goal()),
        results: List<ExperimentResult> = shipResults(),
    ): Mocks {
        val experimentService = mockk<ExperimentService>(relaxed = true)
        val analysisReportRepository = mockk<AnalysisReportRepository>(relaxed = true)
        val flagRepository = mockk<FeatureFlagRepository>(relaxed = true)
        val jobQueue = mockk<JobQueue>(relaxed = true)

        coEvery { experimentService.getById(experimentId) } returns experiment
        coEvery { flagRepository.getById(flagId) } returns flag
        coEvery { experimentService.getConversionGoals(experimentId) } returns goals
        coEvery { experimentService.getResults(experimentId) } returns results

        return Mocks(
            experimentService, analysisReportRepository, flagRepository, jobQueue,
            mutableListOf(),
        )
    }

    private suspend fun run(
        mocks: Mocks,
        aiAnalyzer: ExperimentAiAnalyzer? = null,
    ) {
        runExperimentAnalysis(
            job = ExperimentAnalysisJob(experimentId = experimentId),
            experimentService = mocks.experimentService,
            analysisReportRepository = mocks.analysisReportRepository,
            flagRepository = mocks.flagRepository,
            json = json,
            aiAnalyzer = aiAnalyzer,
            jobQueue = mocks.jobQueue,
            aggregator = { id -> mocks.aggregatorCalls.add(id) },
        )
    }

    // -----------------------------------------------------------------
    // Bail-out paths
    // -----------------------------------------------------------------

    @Test
    fun `missing experiment bails out without touching the flag or report repo`() = runTest {
        val mocks = setupMocks(experiment = null)
        run(mocks)
        coVerify(exactly = 0) { mocks.flagRepository.getById(any()) }
        coVerify(exactly = 0) { mocks.analysisReportRepository.add(any()) }
        coVerify(exactly = 0) { mocks.jobQueue.enqueue(any()) }
        assertTrue(mocks.aggregatorCalls.isEmpty(),
            "aggregator should not run when the experiment is missing")
    }

    @Test
    fun `missing flag bails out after aggregation without writing a report`() = runTest {
        val mocks = setupMocks(flag = null)
        run(mocks)
        assertEquals(listOf(experimentId), mocks.aggregatorCalls,
            "aggregator should run before the flag lookup")
        coVerify(exactly = 0) { mocks.analysisReportRepository.add(any()) }
    }

    @Test
    fun `empty results bails out without writing a report`() = runTest {
        val mocks = setupMocks(results = emptyList())
        run(mocks)
        assertEquals(listOf(experimentId), mocks.aggregatorCalls)
        coVerify(exactly = 0) { mocks.analysisReportRepository.add(any()) }
    }

    @Test
    fun `default variation experiment analyzes every flag variation without SRM`() = runTest {
        val mocks = setupMocks(
            experiment = experiment().copy(targetingRuleId = null),
            flag = flag().copy(targetingRules = null),
        )
        val reportSlot = slot<AnalysisReport>()
        coEvery { mocks.analysisReportRepository.add(capture(reportSlot)) } answers { firstArg() }

        run(mocks)

        val details = json.decodeFromJsonElement(
            AnalysisReportDetails.serializer(),
            reportSlot.captured.details,
        )
        assertNull(details.srm)
        assertEquals(setOf(controlKey, treatmentKey), details.goals.single().variations.map { it.variationKey }.toSet())
    }

    // -----------------------------------------------------------------
    // Happy path
    // -----------------------------------------------------------------

    @Test
    fun `SHIP path writes a typed analysis report with deterministic prose`() = runTest {
        val mocks = setupMocks()
        val reportSlot = slot<AnalysisReport>()
        coEvery { mocks.analysisReportRepository.add(capture(reportSlot)) } answers { firstArg() }

        run(mocks)

        assertEquals(listOf(experimentId), mocks.aggregatorCalls,
            "aggregator should run exactly once before analysis")
        coVerify(exactly = 1) { mocks.analysisReportRepository.add(any()) }

        val report = reportSlot.captured
        // Deterministic prose is the authoritative product surface
        // — assert it is non-empty and names the verdict branch.
        assertTrue(report.summary.isNotBlank(),
            "deterministic summary must be populated on every report")
        assertTrue(report.recommendation.isNotBlank(),
            "deterministic recommendation must be populated on every report")
        assertNotNull(report.confidence)

        // The typed details blob must round-trip through the
        // producer/consumer boundary without JSON casting.
        val details = json.decodeFromJsonElement(
            AnalysisReportDetails.serializer(),
            report.details,
        )
        assertEquals("SHIP", details.verdict,
            "SHIP fixture should produce SHIP verdict on the typed details blob")
        assertEquals(controlKey, details.controlVariationKey)
        assertEquals(1, details.goals.size)
        val goal = details.goals.single()
        assertEquals(ConversionGoalRole.PRIMARY, goal.role)
        assertNull(goal.unit)
        assertEquals(false, goal.coverageImbalanced)
        assertEquals(2, goal.variations.size)
        val treatmentRow = goal.variations.single { it.variationKey == treatmentKey }
        assertEquals(treatmentRow.impressions, treatmentRow.observationCount)
        assertEquals(1.0, treatmentRow.coverage)
        assertNotNull(treatmentRow.confidence,
            "treatment row should carry a confidence value on SHIP fixture")

        // AI insights should be absent when no analyzer is wired.
        assertNull(report.aiInsights,
            "aiInsights should be null when no analyzer is provided")
    }

    // -----------------------------------------------------------------
    // AI commentary
    // -----------------------------------------------------------------

    @Test
    fun `AI analyzer success populates aiInsights on the report`() = runTest {
        val mocks = setupMocks()
        val reportSlot = slot<AnalysisReport>()
        coEvery { mocks.analysisReportRepository.add(capture(reportSlot)) } answers { firstArg() }

        val aiAnalyzer = mockk<ExperimentAiAnalyzer>()
        coEvery { aiAnalyzer.summarize(any()) } returns AiInsights(
            hypothesisAssessment = "Confirmed: treatment moved signups.",
            crossGoalPatterns = "Single goal experiment — no cross-goal pattern.",
            followUpExperiments = listOf("Test a 75/25 ramp next."),
            srmRootCauseHints = null,
        )
        run(mocks, aiAnalyzer = aiAnalyzer)

        val report = reportSlot.captured
        assertNotNull(report.aiInsights,
            "aiInsights should be populated when the analyzer succeeds")
        // The deterministic prose is untouched; the AI output is
        // strictly additive. Re-decode the saved details blob to
        // confirm the deterministic verdict survives.
        val details = json.decodeFromJsonElement(
            AnalysisReportDetails.serializer(),
            report.details,
        )
        assertEquals("SHIP", details.verdict)
    }

    @Test
    fun `AI analyzer failure leaves aiInsights null but still saves the report`() = runTest {
        val mocks = setupMocks()
        val reportSlot = slot<AnalysisReport>()
        coEvery { mocks.analysisReportRepository.add(capture(reportSlot)) } answers { firstArg() }

        val aiAnalyzer = mockk<ExperimentAiAnalyzer>()
        coEvery { aiAnalyzer.summarize(any()) } throws IllegalStateException("AI timeout")

        run(mocks, aiAnalyzer = aiAnalyzer)

        // Report still saved with deterministic prose — the AI
        // failure must not poison the load-bearing verdict path.
        coVerify(exactly = 1) { mocks.analysisReportRepository.add(any()) }
        val report = reportSlot.captured
        assertNull(report.aiInsights,
            "failed AI analyzer must leave aiInsights null; deterministic prose stands on its own")
        assertTrue(report.summary.isNotBlank())
    }

    // -----------------------------------------------------------------
    // Rollout chaining
    // -----------------------------------------------------------------

    @Test
    fun `experiment with rollout policy and RUNNING status enqueues follow-on job`() = runTest {
        val policy = RolloutPolicy(
            mode = RolloutPolicyMode.ADAPTIVE_STEPS,
            treatmentVariationKey = treatmentKey,
            steps = listOf(
                bosca.experimentation.model.RolloutStep(weightPercent = 25),
                bosca.experimentation.model.RolloutStep(weightPercent = 100),
            ),
        )
        val mocks = setupMocks(experiment = experiment(rolloutPolicy = policy))

        run(mocks)

        // The chain uses the `enqueue` extension on IJobDefinition
        // which calls JobQueue.enqueue(job). Mockk verifies the
        // underlying mock invocation.
        coVerify(atLeast = 1) { mocks.jobQueue.enqueue(any()) }
    }

    @Test
    fun `experiment without rollout policy does NOT enqueue follow-on job`() = runTest {
        val mocks = setupMocks(experiment = experiment(rolloutPolicy = null))
        run(mocks)
        coVerify(exactly = 0) { mocks.jobQueue.enqueue(any()) }
    }

    @Test
    fun `experiment not RUNNING does NOT enqueue follow-on job even with a policy`() = runTest {
        val policy = RolloutPolicy(
            mode = RolloutPolicyMode.MANUAL,
            treatmentVariationKey = treatmentKey,
        )
        val mocks = setupMocks(
            experiment = experiment(
                status = ExperimentStatus.PAUSED,
                rolloutPolicy = policy,
            )
        )
        run(mocks)
        coVerify(exactly = 0) { mocks.jobQueue.enqueue(any()) }
    }

    @Test
    fun `rollout chaining failure does not discard the persisted report`() = runTest {
        val policy = RolloutPolicy(
            mode = RolloutPolicyMode.ADAPTIVE_STEPS,
            treatmentVariationKey = treatmentKey,
            steps = listOf(
                bosca.experimentation.model.RolloutStep(weightPercent = 25),
                bosca.experimentation.model.RolloutStep(weightPercent = 100),
            ),
        )
        val mocks = setupMocks(experiment = experiment(rolloutPolicy = policy))
        coEvery { mocks.jobQueue.enqueue(any()) } throws IllegalStateException("queue unavailable")

        run(mocks)

        coVerify(exactly = 1) { mocks.analysisReportRepository.add(any()) }
        coVerify(exactly = 1) { mocks.jobQueue.enqueue(any()) }
    }

    // -----------------------------------------------------------------
    // Details round-trip
    // -----------------------------------------------------------------

    @Test
    fun `buildAnalysisDetailsFor produces a typed shape that round-trips`() {
        // Pure helper — no mocks needed. Ensures the producer
        // schema matches the consumer schema byte-for-byte.
        val analysis = DeterministicAnalysis(
            experimentName = "demo",
            hypothesis = "treatment wins",
            controlKey = controlKey,
            variationCount = 2,
            goalCount = 1,
            totalImpressions = 1000,
            totalConversions = 300,
            srm = null,
            goalAnalyses = emptyList(),
            verdict = Verdict.SHIP,
            summary = "s",
            recommendation = "r",
            confidence = 0.97,
            numComparisons = 1,
            effectiveAlpha = 0.05,
        )
        val experimentRevision = 7L
        val typed = buildAnalysisDetailsFor(analysis, experimentRevision)
        val encoded = json.encodeToJsonElement(
            AnalysisReportDetails.serializer(),
            typed,
        )
        val decoded = json.decodeFromJsonElement(
            AnalysisReportDetails.serializer(),
            encoded,
        )
        assertEquals(typed, decoded,
            "typed analysis report details must round-trip through JSON without loss")
        assertEquals("SHIP", decoded.verdict)
        assertEquals(experimentRevision, decoded.experimentRevision)
        assertEquals(0.05, decoded.multipleTesting?.effectiveAlpha)
    }
}
