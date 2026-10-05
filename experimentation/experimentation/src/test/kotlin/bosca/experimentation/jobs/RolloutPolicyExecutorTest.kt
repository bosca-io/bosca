@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.experimentation.jobs

import bosca.experimentation.model.AnalysisReport
import bosca.experimentation.model.ConversionGoalRole
import bosca.experimentation.model.Experiment
import bosca.experimentation.model.ExperimentStatus
import bosca.experimentation.model.FeatureFlag
import bosca.experimentation.model.FeatureFlagInput
import bosca.experimentation.model.FlagStatus
import bosca.experimentation.model.FlagType
import bosca.experimentation.model.Rollout
import bosca.experimentation.model.RolloutPolicy
import bosca.experimentation.model.RolloutPolicyAction
import bosca.experimentation.model.RolloutPolicyEvent
import bosca.experimentation.model.RolloutPolicyMode
import bosca.experimentation.model.RolloutStep
import bosca.experimentation.model.TargetingRule
import bosca.experimentation.model.Variation
import bosca.experimentation.model.VariationWeight
import bosca.experimentation.repository.AnalysisReportRepository
import bosca.experimentation.repository.ExperimentRepository
import bosca.experimentation.repository.FeatureFlagRepository
import bosca.experimentation.repository.RolloutPolicyEventRepository
import bosca.experimentation.service.ExperimentService
import bosca.experimentation.service.FeatureFlagService
import bosca.di.ObjectProvider
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.observability.ErrorCapture
import bosca.serialization.UUID
import bosca.serialization.OffsetDateTime
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
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Integration tests for [runRolloutPolicy] — the orchestrator that
 * loads experiment / flag / rule / latest-report state, calls
 * [decide], and applies the resulting [Action]. Pure-decider tests
 * already cover the (mode × verdict) decision matrix in
 * [RolloutPolicyDeciderTest]; this file pins the *I/O glue*:
 *
 *   - The orchestrator bails when the experiment is missing,
 *     paused, has no policy, has no attached rule, or has a
 *     missing flag — and does NOT touch the flag or audit log
 *     in those cases.
 *   - SHIP + ADAPTIVE_STEPS → flag edited with the next step's
 *     weights, audit event written, no status change.
 *   - HALT → flag edited to 0/100, experiment paused, audit
 *     event written.
 *   - SCHEDULED_STEPS Advance enqueues the next scheduled wake.
 *   - The decider's verdict / confidence are read from the
 *     latest analysis report's details JSON, exactly as the
 *     real analyzer wrote them.
 *
 * Every collaborator is mocked with mockk so the test runs purely
 * in-process, with no DI / database / job-queue infrastructure.
 */
class RolloutPolicyExecutorTest {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Installs a `Json` provider in the DI registry for the
     * duration of every test in this class. The
     * [enqueueNextScheduledStep] code path indirectly calls
     * `provide<Json>()` via the `Job(definition, ...)` inline
     * factory in `bosca.sharedqueue.jobs`, so without this
     * registration the inline call throws and the surrounding
     * runCatching swallows the exception, making the scheduled-
     * chain test pass-through silently incorrect.
     */
    @OptIn(InternalDI::class)
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
    private val controlKey = "control"
    private val treatmentKey = "treatment"
    private val experimentModified = OffsetDateTime.parse("2026-08-29T10:15:30Z")
    private val experimentRevision = 7L

    // -----------------------------------------------------------------
    // Test fixtures
    // -----------------------------------------------------------------

    private fun variationsJson() = buildJsonArray {
        add(buildJsonObject {
            put("key", controlKey); put("name", "Control"); put("description", ""); put("value", false)
        })
        add(buildJsonObject {
            put("key", treatmentKey); put("name", "Treatment"); put("description", ""); put("value", true)
        })
    }

    private fun targetingRulesJson(controlWeight: Int, treatmentWeight: Int) =
        json.encodeToJsonElement(
            ListSerializer(TargetingRule.serializer()),
            listOf(
                TargetingRule(
                    id = ruleId,
                    name = "main rollout",
                    description = null,
                    conditions = emptyList(),
                    rollout = Rollout(
                        variationWeights = listOf(
                            VariationWeight(controlKey, controlWeight),
                            VariationWeight(treatmentKey, treatmentWeight),
                        )
                    ),
                )
            ),
        )

    private fun policy(
        mode: RolloutPolicyMode = RolloutPolicyMode.ADAPTIVE_STEPS,
        steps: List<Pair<Int, String?>> = listOf(10 to null, 25 to null, 50 to null, 100 to null),
        haltOnGuardrail: Boolean = true,
    ): RolloutPolicy = RolloutPolicy(
        mode = mode,
        treatmentVariationKey = treatmentKey,
        steps = steps.map { (w, d) -> RolloutStep(weightPercent = w, afterDuration = d) },
        minConfidence = 0.95,
        haltOnGuardrail = haltOnGuardrail,
    )

    private fun experiment(
        status: ExperimentStatus = ExperimentStatus.RUNNING,
        attached: Boolean = true,
        policyOverride: RolloutPolicy? = policy(),
    ): Experiment = Experiment(
        id = experimentId,
        featureFlagId = flagId,
        controlVariationKey = controlKey,
        name = "test exp",
        description = "",
        hypothesis = "treatment > control",
        status = status,
        targetingRuleId = if (attached) ruleId else null,
        rolloutPolicy = policyOverride?.let {
            json.encodeToJsonElement(RolloutPolicy.serializer(), it)
        },
        analysisRevision = experimentRevision,
        modified = experimentModified,
    )

    private fun flag(
        controlWeight: Int = 90,
        treatmentWeight: Int = 10,
    ): FeatureFlag = FeatureFlag(
        id = flagId,
        key = "test-flag",
        name = "Test Flag",
        description = "",
        type = FlagType.BOOLEAN,
        status = FlagStatus.ENABLED,
        variations = variationsJson(),
        defaultVariationKey = controlKey,
        targetingRules = targetingRulesJson(controlWeight, treatmentWeight),
        salt = "salt",
    )

    /**
     * Builds the typed [AnalysisReportDetails] for the supplied
     * verdict and treatment confidence. The fixture mirrors the
     * shape produced by [ExperimentAnalysisJobExecutor.buildAnalysisDetails]
     * — one primary `UNIQUE_CONVERSION` goal with a control row and a
     * treatment row — because that is the minimum the orchestrator
     * reads to compute its decision input.
     */
    private fun reportDetailsTyped(
        verdict: Verdict,
        treatmentConfidence: Double?,
        revision: Long = experimentRevision,
    ): AnalysisReportDetails =
        AnalysisReportDetails(
            verdict = verdict.name,
            controlVariationKey = controlKey,
            experimentRevision = revision,
            goals = listOf(
                GoalDetails(
                    goalId = "goal-1",
                    goalName = "Signups",
                    metricType = bosca.experimentation.model.GoalMetricType.UNIQUE_CONVERSION,
                    role = ConversionGoalRole.PRIMARY,
                    verdict = "WINNER",
                    variations = listOf(
                        VariationDetails(
                            variationKey = controlKey,
                            variationName = "Control",
                            isControl = true,
                            impressions = 1000,
                            conversions = 100,
                            rate = 0.10,
                        ),
                        VariationDetails(
                            variationKey = treatmentKey,
                            variationName = "Treatment",
                            isControl = false,
                            impressions = 1000,
                            conversions = 200,
                            rate = 0.20,
                            confidence = treatmentConfidence,
                        ),
                    ),
                )
            ),
        )

    private fun analysisReport(
        verdict: Verdict,
        confidence: Double? = 0.99,
        revision: Long = experimentRevision,
    ): AnalysisReport =
        AnalysisReport(
            id = UUID.parse("00000000-0000-0000-0000-000000000333"),
            experimentId = experimentId,
            summary = "test summary",
            recommendation = "test recommendation",
            details = json.encodeToJsonElement(
                AnalysisReportDetails.serializer(),
                reportDetailsTyped(verdict, confidence, revision),
            ),
            confidence = confidence,
        )

    /**
     * Builds the full set of mocked collaborators with the supplied
     * experiment, flag, and report. Returns the mocks so individual
     * tests can verify call shapes after the orchestrator runs.
     */
    private data class Mocks(
        val experimentRepository: ExperimentRepository,
        val experimentService: ExperimentService,
        val flagService: FeatureFlagService,
        val flagRepository: FeatureFlagRepository,
        val eventRepository: RolloutPolicyEventRepository,
        val analysisReportRepository: AnalysisReportRepository,
        val jobQueue: JobQueue,
    )

    private fun setupMocks(
        experiment: Experiment? = experiment(),
        flag: FeatureFlag? = flag(),
        latestReport: AnalysisReport? = analysisReport(Verdict.SHIP),
    ): Mocks {
        val experimentRepository = mockk<ExperimentRepository>(relaxed = true)
        val experimentService = mockk<ExperimentService>(relaxed = true)
        val flagService = mockk<FeatureFlagService>(relaxed = true)
        val flagRepository = mockk<FeatureFlagRepository>(relaxed = true)
        val eventRepository = mockk<RolloutPolicyEventRepository>(relaxed = true)
        val analysisReportRepository = mockk<AnalysisReportRepository>(relaxed = true)
        val jobQueue = mockk<JobQueue>(relaxed = true)

        coEvery { experimentRepository.getByIdForUpdate(experimentId) } returns experiment
        coEvery { flagRepository.getById(flagId) } returns flag
        if (experiment != null) {
            coEvery {
                analysisReportRepository.getLatestForRevision(
                    experimentId,
                    experiment.controlVariationKey,
                    experiment.analysisRevision,
                )
            } returns (if (latestReport != null) listOf(latestReport) else emptyList())
        }

        return Mocks(
            experimentRepository,
            experimentService,
            flagService,
            flagRepository,
            eventRepository,
            analysisReportRepository,
            jobQueue,
        )
    }

    private suspend fun run(
        mocks: Mocks,
        job: RolloutPolicyJob = RolloutPolicyJob(experimentId = experimentId, scheduledStepIndex = null),
    ) {
        runRolloutPolicy(
            job = job,
            experimentRepository = mocks.experimentRepository,
            experimentService = mocks.experimentService,
            flagService = mocks.flagService,
            flagRepository = mocks.flagRepository,
            eventRepository = mocks.eventRepository,
            analysisReportRepository = mocks.analysisReportRepository,
            jobQueue = mocks.jobQueue,
            json = json,
        )
    }

    // -----------------------------------------------------------------
    // Bail-out paths
    // -----------------------------------------------------------------

    @Test
    fun `experiment not found is a no-op`() = runTest {
        val mocks = setupMocks(experiment = null)
        run(mocks)
        coVerify(exactly = 1) { mocks.experimentRepository.getByIdForUpdate(experimentId) }
        coVerify(exactly = 0) { mocks.flagService.edit(any(), any()) }
        coVerify(exactly = 0) { mocks.eventRepository.add(any()) }
        coVerify(exactly = 0) { mocks.experimentService.setStatus(any(), any()) }
    }

    @Test
    fun `experiment not running is a no-op`() = runTest {
        val mocks = setupMocks(experiment = experiment(status = ExperimentStatus.PAUSED))
        run(mocks)
        coVerify(exactly = 0) { mocks.flagService.edit(any(), any()) }
        coVerify(exactly = 0) { mocks.eventRepository.add(any()) }
    }

    @Test
    fun `experiment without policy is a no-op`() = runTest {
        val mocks = setupMocks(experiment = experiment(policyOverride = null))
        run(mocks)
        coVerify(exactly = 0) { mocks.flagService.edit(any(), any()) }
        coVerify(exactly = 0) { mocks.eventRepository.add(any()) }
    }

    @Test
    fun `report selection uses the locked experiment revision`() = runTest {
        val currentRevision = experimentRevision + 1
        val mocks = setupMocks(
            experiment = experiment().copy(analysisRevision = currentRevision),
            latestReport = analysisReport(Verdict.SHIP, revision = currentRevision),
        )
        coEvery { mocks.flagService.edit(flagId, any()) } returns flag()

        run(mocks)

        coVerify(exactly = 1) {
            mocks.analysisReportRepository.getLatestForRevision(
                experimentId,
                controlKey,
                currentRevision,
            )
        }
        coVerify(exactly = 0) {
            mocks.analysisReportRepository.getLatestForRevision(
                experimentId,
                controlKey,
                experimentRevision,
            )
        }
    }

    @Test
    fun `flag missing is a no-op`() = runTest {
        val mocks = setupMocks(flag = null)
        run(mocks)
        coVerify(exactly = 0) { mocks.flagService.edit(any(), any()) }
        coVerify(exactly = 0) { mocks.eventRepository.add(any()) }
    }

    @Test
    fun `missing attached rule is a no-op`() = runTest {
        val mocks = setupMocks(flag = flag().copy(targetingRules = null))

        run(mocks)

        coVerify(exactly = 0) { mocks.flagService.edit(any(), any()) }
        coVerify(exactly = 0) { mocks.eventRepository.add(any()) }
    }

    @Test
    fun `policy treatment must differ from control`() = runTest {
        val invalidPolicy = policy().copy(treatmentVariationKey = controlKey)
        val mocks = setupMocks(experiment = experiment(policyOverride = invalidPolicy))

        assertFailsWith<IllegalArgumentException> { run(mocks) }
    }

    @Test
    fun `policy treatment must exist in attached rollout`() = runTest {
        val invalidPolicy = policy().copy(treatmentVariationKey = "missing")
        val mocks = setupMocks(experiment = experiment(policyOverride = invalidPolicy))

        assertFailsWith<IllegalArgumentException> { run(mocks) }
    }

    @Test
    fun `experiment control must exist in attached rollout`() = runTest {
        val treatmentOnlyRules = json.encodeToJsonElement(
            ListSerializer(TargetingRule.serializer()),
            listOf(
                TargetingRule(
                    id = ruleId,
                    rollout = Rollout(listOf(VariationWeight(treatmentKey, 100))),
                ),
            ),
        )
        val mocks = setupMocks(flag = flag().copy(targetingRules = treatmentOnlyRules))

        assertFailsWith<IllegalArgumentException> { run(mocks) }
    }

    @Test
    fun `unknown stored verdict is treated as no data`() {
        val details = reportDetailsTyped(Verdict.SHIP, 0.99).copy(verdict = "FUTURE_VERDICT")

        assertEquals(Verdict.NO_DATA, readLatestVerdictFromDetails(details, experimentId))
    }

    @Test
    fun `verdict reader handles absent empty and known verdicts`() {
        assertEquals(Verdict.NO_DATA, readLatestVerdictFromDetails(null, experimentId))
        assertEquals(
            Verdict.NO_DATA,
            readLatestVerdictFromDetails(reportDetailsTyped(Verdict.SHIP, 0.99).copy(verdict = ""), experimentId),
        )
        assertEquals(
            Verdict.SHIP,
            readLatestVerdictFromDetails(reportDetailsTyped(Verdict.SHIP, 0.99), experimentId),
        )
    }

    @Test
    fun `current report marker requires both control and revision`() {
        val current = reportDetailsTyped(Verdict.SHIP, 0.99)
        assertTrue(isCurrentAnalysisDetails(current, experiment()))
        assertTrue(!isCurrentAnalysisDetails(current.copy(controlVariationKey = "old-control"), experiment()))
        assertTrue(!isCurrentAnalysisDetails(current.copy(experimentRevision = experimentRevision - 1), experiment()))
    }

    @Test
    fun `primary confidence reader filters rows and returns the maximum`() {
        val base = reportDetailsTyped(Verdict.SHIP, 0.70)
        val primary = base.goals.single()
        val irrelevantGoal = primary.copy(
            role = ConversionGoalRole.SECONDARY,
            variations = listOf(primary.variations.last().copy(confidence = 1.0)),
        )
        val expandedPrimary = primary.copy(
            variations = listOf(
                primary.variations.first(),
                primary.variations.last().copy(variationKey = "other", confidence = 1.0),
                primary.variations.last().copy(confidence = null),
                primary.variations.last().copy(confidence = 0.90),
                primary.variations.last().copy(confidence = 0.80),
            ),
        )
        val details = base.copy(goals = listOf(irrelevantGoal, expandedPrimary))

        assertEquals(null, readLatestPrimaryConfidenceFromDetails(null, treatmentKey))
        assertEquals(0.90, readLatestPrimaryConfidenceFromDetails(details, treatmentKey))
    }

    @Test
    fun `null targeting rule attachment is a no-op`() = runTest {
        val mocks = setupMocks(experiment = experiment().copy(targetingRuleId = null))

        run(mocks)

        coVerify(exactly = 0) { mocks.flagService.edit(any(), any()) }
        coVerify(exactly = 0) { mocks.eventRepository.add(any()) }
    }

    // -----------------------------------------------------------------
    // Happy path: SHIP → adaptive Advance
    // -----------------------------------------------------------------

    @Test
    fun `SHIP verdict on adaptive steps advances rollout and writes audit event`() = runTest {
        val mocks = setupMocks(
            experiment = experiment(),
            flag = flag(controlWeight = 90, treatmentWeight = 10),
            latestReport = analysisReport(Verdict.SHIP, confidence = 0.99),
        )
        val flagInputSlot = slot<FeatureFlagInput>()
        coEvery { mocks.flagService.edit(flagId, capture(flagInputSlot)) } returns flag()

        val eventSlot = slot<RolloutPolicyEvent>()
        coEvery { mocks.eventRepository.add(capture(eventSlot)) } answers { firstArg() }

        run(mocks)

        // Flag was edited with the rule's rollout updated to step 1
        // (which is 25% per the default fixture step list).
        coVerify(exactly = 1) { mocks.flagService.edit(flagId, any()) }
        val savedRules = json.decodeFromJsonElement(
            ListSerializer(TargetingRule.serializer()),
            assertNotNull(flagInputSlot.captured.targetingRules, "captured flag input must include targetingRules"),
        )
        val savedRule = savedRules.single { it.id == ruleId }
        val savedTreatmentWeight = savedRule.rollout.variationWeights
            .single { it.variationKey == treatmentKey }.weight
        val savedControlWeight = savedRule.rollout.variationWeights
            .single { it.variationKey == controlKey }.weight
        assertEquals(25, savedTreatmentWeight, "treatment should advance to step 1 weight (25)")
        assertEquals(75, savedControlWeight, "control should drop to 100 - 25 = 75")

        // Status not changed (Advance, not Complete or Halt).
        coVerify(exactly = 0) { mocks.experimentService.setStatus(any(), any()) }

        // Audit event recorded with action ADVANCED.
        assertEquals(RolloutPolicyAction.ADVANCED, eventSlot.captured.action)
        assertEquals(experimentId, eventSlot.captured.experimentId)
        assertTrue(eventSlot.captured.reason.contains("ADAPTIVE_STEPS"))
    }

    // -----------------------------------------------------------------
    // HALT path
    // -----------------------------------------------------------------

    @Test
    fun `HALT verdict zeros treatment, pauses experiment, writes audit event`() = runTest {
        val mocks = setupMocks(
            experiment = experiment(),
            flag = flag(controlWeight = 50, treatmentWeight = 50),
            latestReport = analysisReport(Verdict.HALT, confidence = null),
        )
        val flagInputSlot = slot<FeatureFlagInput>()
        coEvery { mocks.flagService.edit(flagId, capture(flagInputSlot)) } returns flag()
        val eventSlot = slot<RolloutPolicyEvent>()
        coEvery { mocks.eventRepository.add(capture(eventSlot)) } answers { firstArg() }

        run(mocks)

        // Flag was edited so treatment dropped to 0 and control
        // absorbed the full rollout while the treatment remains
        // explicitly represented for later re-analysis.
        coVerify(exactly = 1) { mocks.flagService.edit(flagId, any()) }
        val savedRules = json.decodeFromJsonElement(
            ListSerializer(TargetingRule.serializer()),
            assertNotNull(flagInputSlot.captured.targetingRules, "captured flag input must include targetingRules"),
        )
        val savedRule = savedRules.single { it.id == ruleId }
        val controlEntry = savedRule.rollout.variationWeights
            .singleOrNull { it.variationKey == controlKey }
        val treatmentEntry = savedRule.rollout.variationWeights
            .singleOrNull { it.variationKey == treatmentKey }
        assertEquals(100, controlEntry?.weight,
            "control should absorb the full rollout after HALT, got ${savedRule.rollout.variationWeights}")
        assertEquals(0, treatmentEntry?.weight,
            "treatment should remain in the rollout at zero after HALT")

        // Experiment paused (not completed — operator can resume
        // after fixing the guardrail).
        coVerify(exactly = 1) { mocks.experimentService.setStatus(experimentId, ExperimentStatus.PAUSED) }

        // Audit event recorded as HALTED.
        assertEquals(RolloutPolicyAction.HALTED, eventSlot.captured.action)
        assertTrue(eventSlot.captured.reason.contains("HALT"))
    }

    @Test
    fun `HALT with haltOnGuardrail false produces a held event without flag edit`() = runTest {
        val mocks = setupMocks(
            experiment = experiment(policyOverride = policy(haltOnGuardrail = false)),
            flag = flag(controlWeight = 50, treatmentWeight = 50),
            latestReport = analysisReport(Verdict.HALT),
        )
        val eventSlot = slot<RolloutPolicyEvent>()
        coEvery { mocks.eventRepository.add(capture(eventSlot)) } answers { firstArg() }

        run(mocks)

        coVerify(exactly = 0) { mocks.flagService.edit(any(), any()) }
        coVerify(exactly = 0) { mocks.experimentService.setStatus(any(), any()) }
        assertEquals(RolloutPolicyAction.HELD, eventSlot.captured.action)
        assertTrue(eventSlot.captured.reason.contains("dry-run"))
    }

    // -----------------------------------------------------------------
    // Hold paths
    // -----------------------------------------------------------------

    @Test
    fun `non-SHIP verdict on adaptive holds and writes a HELD event`() = runTest {
        val mocks = setupMocks(
            experiment = experiment(),
            flag = flag(),
            latestReport = analysisReport(Verdict.KEEP_RUNNING),
        )
        val eventSlot = slot<RolloutPolicyEvent>()
        coEvery { mocks.eventRepository.add(capture(eventSlot)) } answers { firstArg() }

        run(mocks)

        coVerify(exactly = 0) { mocks.flagService.edit(any(), any()) }
        coVerify(exactly = 0) { mocks.experimentService.setStatus(any(), any()) }
        assertEquals(RolloutPolicyAction.HELD, eventSlot.captured.action)
    }

    @Test
    fun `SHIP verdict below minConfidence holds`() = runTest {
        val mocks = setupMocks(
            experiment = experiment(),
            flag = flag(),
            // Confidence 0.92 — below the policy's minConfidence of
            // 0.95, so the decider holds even though the verdict is
            // SHIP.
            latestReport = analysisReport(Verdict.SHIP, confidence = 0.92),
        )
        val eventSlot = slot<RolloutPolicyEvent>()
        coEvery { mocks.eventRepository.add(capture(eventSlot)) } answers { firstArg() }

        run(mocks)

        coVerify(exactly = 0) { mocks.flagService.edit(any(), any()) }
        assertEquals(RolloutPolicyAction.HELD, eventSlot.captured.action)
        assertTrue(eventSlot.captured.reason.contains("0.920"),
            "held reason should name the failing confidence")
    }

    @Test
    fun `report from an earlier experiment revision cannot advance rollout after control returns`() = runTest {
        val mocks = setupMocks(
            experiment = experiment(),
            flag = flag(),
            latestReport = analysisReport(
                Verdict.SHIP,
                confidence = 0.99,
                revision = experimentRevision - 1,
            ),
        )
        val eventSlot = slot<RolloutPolicyEvent>()
        coEvery { mocks.eventRepository.add(capture(eventSlot)) } answers { firstArg() }

        run(mocks)

        coVerify(exactly = 0) { mocks.flagService.edit(any(), any()) }
        assertEquals(RolloutPolicyAction.HELD, eventSlot.captured.action)
        assertTrue(eventSlot.captured.reason.contains("NO_DATA"))
    }

    @Test
    fun `correctly typed current markers do not hide malformed report details`() = runTest {
        val malformedReport = analysisReport(Verdict.SHIP).copy(
            details = buildJsonObject {
                put("controlVariationKey", controlKey)
                put("experimentRevision", experimentRevision)
                put("goals", "not an array")
            },
        )
        val reports = mockk<AnalysisReportRepository>()
        coEvery {
            reports.getLatestForRevision(experimentId, controlKey, experimentRevision)
        } returns listOf(malformedReport)

        assertFailsWith<SerializationException> {
            readLatestAnalysisDetails(reports, experiment())
        }
    }

    // -----------------------------------------------------------------
    // Scheduled-step chain
    // -----------------------------------------------------------------

    @Test
    fun `scheduled step advance applies the step and enqueues the next`() = runTest {
        val scheduledPolicy = policy(
            mode = RolloutPolicyMode.SCHEDULED_STEPS,
            steps = listOf(10 to "P1D", 25 to "P1D", 50 to "P1D", 100 to "P1D"),
        )
        val mocks = setupMocks(
            experiment = experiment(policyOverride = scheduledPolicy),
            flag = flag(controlWeight = 90, treatmentWeight = 10),
            // Verdict is irrelevant in scheduled mode (except HALT
            // which we test separately) — use NO_DATA to be sure.
            latestReport = analysisReport(Verdict.NO_DATA, confidence = null),
        )
        val flagInputSlot = slot<FeatureFlagInput>()
        coEvery { mocks.flagService.edit(flagId, capture(flagInputSlot)) } returns flag()

        run(
            mocks,
            job = RolloutPolicyJob(experimentId = experimentId, scheduledStepIndex = 1),
        )

        // Step 1 = 25% (zero-indexed), so treatment should be 25%
        // and control 75%.
        val savedRules = json.decodeFromJsonElement(
            ListSerializer(TargetingRule.serializer()),
            assertNotNull(flagInputSlot.captured.targetingRules, "captured flag input must include targetingRules"),
        )
        val savedRule = savedRules.single { it.id == ruleId }
        val savedTreatmentWeight = savedRule.rollout.variationWeights
            .single { it.variationKey == treatmentKey }.weight
        assertEquals(25, savedTreatmentWeight)

        // Next scheduled wake should be enqueued via enqueueLater.
        // Mockk's relaxed mock returns whatever and we just verify
        // the method was called with a job carrying scheduledStepIndex = 2.
        coVerify(atLeast = 1) { mocks.jobQueue.enqueueLater(any(), any()) }
    }

    @Test
    fun `scheduled wake on paused experiment is a no-op`() = runTest {
        val scheduledPolicy = policy(mode = RolloutPolicyMode.SCHEDULED_STEPS,
            steps = listOf(10 to "P1D", 25 to "P1D", 100 to "P1D"))
        val mocks = setupMocks(
            experiment = experiment(status = ExperimentStatus.PAUSED, policyOverride = scheduledPolicy),
        )
        run(
            mocks,
            job = RolloutPolicyJob(experimentId = experimentId, scheduledStepIndex = 1),
        )
        coVerify(exactly = 0) { mocks.flagService.edit(any(), any()) }
        coVerify(exactly = 0) { mocks.eventRepository.add(any()) }
        coVerify(exactly = 0) { mocks.jobQueue.enqueueLater(any(), any()) }
    }

    @Test
    fun `scheduled wake with HALT verdict halts instead of advancing`() = runTest {
        val scheduledPolicy = policy(mode = RolloutPolicyMode.SCHEDULED_STEPS,
            steps = listOf(10 to "P1D", 25 to "P1D", 100 to "P1D"))
        val mocks = setupMocks(
            experiment = experiment(policyOverride = scheduledPolicy),
            flag = flag(controlWeight = 90, treatmentWeight = 10),
            latestReport = analysisReport(Verdict.HALT),
        )
        val eventSlot = slot<RolloutPolicyEvent>()
        coEvery { mocks.eventRepository.add(capture(eventSlot)) } answers { firstArg() }

        run(
            mocks,
            job = RolloutPolicyJob(experimentId = experimentId, scheduledStepIndex = 1),
        )

        // Halt path: flag edited (to 100/0), experiment paused, no
        // next-step enqueue.
        coVerify(exactly = 1) { mocks.flagService.edit(flagId, any()) }
        coVerify(exactly = 1) { mocks.experimentService.setStatus(experimentId, ExperimentStatus.PAUSED) }
        coVerify(exactly = 0) { mocks.jobQueue.enqueueLater(any(), any()) }
        assertEquals(RolloutPolicyAction.HALTED, eventSlot.captured.action)
    }

    // -----------------------------------------------------------------
    // Final step → COMPLETE transition
    // -----------------------------------------------------------------

    @Test
    fun `SHIP on final adaptive step marks experiment COMPLETED`() = runTest {
        val mocks = setupMocks(
            experiment = experiment(),
            // Flag already at 50/50 — the next adaptive step is
            // 100, which the decider returns as Complete.
            flag = flag(controlWeight = 50, treatmentWeight = 50),
            latestReport = analysisReport(Verdict.SHIP, confidence = 0.99),
        )
        val eventSlot = slot<RolloutPolicyEvent>()
        coEvery { mocks.eventRepository.add(capture(eventSlot)) } answers { firstArg() }

        run(mocks)

        coVerify(exactly = 1) { mocks.flagService.edit(flagId, any()) }
        coVerify(exactly = 1) { mocks.experimentService.setStatus(experimentId, ExperimentStatus.COMPLETED) }
        assertEquals(RolloutPolicyAction.COMPLETED, eventSlot.captured.action)
    }

    @Test
    fun `audit persistence failure is captured and does not escape`() = runTest {
        val repository = mockk<RolloutPolicyEventRepository>()
        val failures = mutableListOf<Pair<Throwable, Map<String, Any?>>>()
        val failure = IllegalStateException("database unavailable")
        coEvery { repository.add(any()) } throws failure

        writeRolloutEvent(
            repository = repository,
            experimentId = experimentId,
            action = Action.Hold("wait"),
            oldWeights = mapOf(controlKey to 90, treatmentKey to 10),
            newWeights = mapOf(controlKey to 90, treatmentKey to 10),
            json = json,
            errorCapture = ErrorCapture { throwable, _, context -> failures += throwable to context },
        )

        assertEquals(failure, failures.single().first)
        assertEquals("writeRolloutEvent", failures.single().second["location"])
    }

    @Test
    fun `scheduled next step without duration is not enqueued`() = runTest {
        val queue = mockk<JobQueue>(relaxed = true)
        val scheduled = policy(
            mode = RolloutPolicyMode.ADAPTIVE_STEPS,
            steps = listOf(10 to "P1D", 25 to null),
        )

        enqueueNextScheduledStep(queue, experimentId, scheduled, currentIndex = 0)

        coVerify(exactly = 0) { queue.enqueueLater(any(), any()) }
    }

    @Test
    fun `scheduled enqueue stops for absent steps and after final step`() = runTest {
        val queue = mockk<JobQueue>(relaxed = true)

        enqueueNextScheduledStep(
            queue,
            experimentId,
            policy(mode = RolloutPolicyMode.MANUAL).copy(steps = null),
            currentIndex = 0,
        )
        enqueueNextScheduledStep(
            queue,
            experimentId,
            policy(mode = RolloutPolicyMode.SCHEDULED_STEPS, steps = listOf(100 to "P1D")),
            currentIndex = 0,
        )

        coVerify(exactly = 0) { queue.enqueueLater(any(), any()) }
    }

    @Test
    fun `invalid scheduled duration is captured and not enqueued`() = runTest {
        val queue = mockk<JobQueue>(relaxed = true)
        val failures = mutableListOf<Throwable>()
        val scheduled = policy(
            mode = RolloutPolicyMode.SCHEDULED_STEPS,
            steps = listOf(10 to "P1D", 25 to "tomorrow"),
        )

        enqueueNextScheduledStep(
            queue,
            experimentId,
            scheduled,
            currentIndex = 0,
            errorCapture = ErrorCapture { throwable, _, _ -> failures += throwable },
        )

        assertEquals(1, failures.size)
        coVerify(exactly = 0) { queue.enqueueLater(any(), any()) }
    }

    @Test
    fun `scheduled enqueue failure is captured`() = runTest {
        val queue = mockk<JobQueue>()
        val failures = mutableListOf<Throwable>()
        val failure = IllegalStateException("queue unavailable")
        coEvery { queue.enqueueLater(any(), any()) } throws failure
        val scheduled = policy(
            mode = RolloutPolicyMode.SCHEDULED_STEPS,
            steps = listOf(10 to "P1D", 25 to "P2D"),
        )

        enqueueNextScheduledStep(
            queue,
            experimentId,
            scheduled,
            currentIndex = 0,
            errorCapture = ErrorCapture { throwable, _, _ -> failures += throwable },
        )

        assertEquals(failure, failures.single())
    }
}
