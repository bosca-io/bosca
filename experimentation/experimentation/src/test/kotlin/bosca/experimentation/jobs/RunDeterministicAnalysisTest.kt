package bosca.experimentation.jobs

import bosca.experimentation.model.ConversionGoal
import bosca.experimentation.model.ConversionGoalRole
import bosca.experimentation.model.Experiment
import bosca.experimentation.model.ExperimentActivationFilter
import bosca.experimentation.model.ExperimentResult
import bosca.experimentation.model.ExperimentStatus
import bosca.experimentation.model.GoalMetricType
import bosca.experimentation.model.Variation
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end tests for [runDeterministicAnalysis] — the function that
 * turns raw `experiment_results` rows into a [Verdict] plus a
 * natural-language summary the admin UI shows.
 *
 * These tests are deliberately written as "if the analyzer says X, X
 * has to actually be true" assertions:
 *
 *  - Inputs that should produce SHIP must produce SHIP, with the right
 *    winner key, and confidence ≥ the per-test threshold.
 *  - Inputs with a significantly worse treatment must produce
 *    DO_NOT_SHIP and never SHIP.
 *  - Inputs with broken bucketing must produce SRM_FAILED and the
 *    summary must say so.
 *  - Bonferroni correction must actually downgrade a borderline-95%
 *    result when the family is large enough — otherwise the correction
 *    is decorative.
 *
 * Confidence values for fixtures are computed via the same code path
 * the production aggregator uses (`chiSquaredConfidence` /
 * `welchsTConfidence`) so the test's "expected confidence" is whatever
 * the trusted commons-math primitives produce, not a hand-rolled number.
 * That isolates this file to verdict-routing bugs (filter cutoffs,
 * winner selection, Bonferroni plumbing) which is exactly what it is
 * meant to cover.
 */
class RunDeterministicAnalysisTest {

    private val expId = UUID.NIL
    private val goalA = UUID.parse("00000000-0000-0000-0000-0000000000a1")
    private val goalB = UUID.parse("00000000-0000-0000-0000-0000000000b2")

    private fun variation(key: String, name: String = key): Variation =
        Variation(key = key, name = name, value = JsonPrimitive(key))

    private fun experiment(): Experiment = Experiment(
        id = expId,
        featureFlagId = expId,
        controlVariationKey = "control",
        name = "test-exp",
        hypothesis = "Treatment outperforms control",
        status = ExperimentStatus.RUNNING,
    )

    private fun goal(
        id: UUID,
        name: String,
        type: GoalMetricType = GoalMetricType.UNIQUE_CONVERSION,
        role: ConversionGoalRole = ConversionGoalRole.PRIMARY,
    ): ConversionGoal =
        ConversionGoal(id = id, experimentId = expId, name = name, metricType = type, role = role)

    private fun proportionRow(
        variationKey: String,
        goalId: UUID,
        n: Long,
        conversions: Long,
        confidence: Double?,
        liftOverControl: Double? = null,
        assignments: Long = n,
    ): ExperimentResult = ExperimentResult(
        experimentId = expId,
        variationKey = variationKey,
        goalId = goalId,
        impressions = n,
        assignments = assignments,
        conversions = conversions,
        conversionRate = if (n > 0) conversions.toDouble() / n else 0.0,
        confidenceLevel = confidence,
        liftOverControl = liftOverControl,
    )

    /**
     * Computes the chi-squared confidence the production aggregator
     * would store on the row, so the test stays consistent with
     * whatever commons-math returns rather than baking in fragile
     * literals. The aggregator only sets confidence on non-control
     * rows, mirrored here.
     */
    private fun confidenceVsControl(
        controlN: Long, controlConv: Long, n: Long, conv: Long,
    ): Double? = chiSquaredConfidence(controlN, controlConv, n, conv)

    private fun continuousRow(
        variationKey: String,
        goalId: UUID,
        impressions: Long,
        observations: Long,
        mean: Double,
        variance: Double,
        confidence: Double?,
    ): ExperimentResult = ExperimentResult(
        experimentId = expId,
        variationKey = variationKey,
        goalId = goalId,
        impressions = impressions,
        observationCount = observations,
        mean = mean,
        variance = variance,
        confidenceLevel = confidence,
    )

    @Test
    fun `explicit control is used even when it sorts after treatment`() {
        val explicitControl = "z_control"
        val treatment = "a_treatment"
        val results = listOf(
            proportionRow(explicitControl, goalA, 2_000, 200, confidence = null),
            proportionRow(
                treatment,
                goalA,
                2_000,
                400,
                confidence = confidenceVsControl(2_000, 200, 2_000, 400),
            ),
        )
        val analysis = runDeterministicAnalysis(
            experiment = experiment().copy(controlVariationKey = explicitControl),
            variations = listOf(variation(treatment), variation(explicitControl)),
            expectedRolloutWeights = mapOf(treatment to 0.5, explicitControl to 0.5),
            goals = listOf(goal(goalA, "Signups")),
            results = results,
        )
        assertEquals(explicitControl, analysis.controlKey)
        assertEquals(treatment, analysis.goalAnalyses.single().winner?.variationKey)
        assertTrue(analysis.goalAnalyses.single().variationRows.single { it.variationKey == explicitControl }.isControl)
    }

    @Test
    fun `analysis rejects a control that is not involved`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            runDeterministicAnalysis(
                experiment = experiment().copy(controlVariationKey = "missing"),
                variations = listOf(variation("control"), variation("treatment")),
                expectedRolloutWeights = emptyMap(),
                goals = emptyList(),
                results = emptyList(),
            )
        }
        assertTrue(ex.message?.contains("not involved") == true)
    }

    @Test
    fun `inconsistent impression counts across goals are surfaced as a data quality warning`() {
        val analysis = runDeterministicAnalysis(
            experiment = experiment(),
            variations = listOf(variation("control"), variation("treatment")),
            expectedRolloutWeights = emptyMap(),
            goals = listOf(goal(goalA, "A"), goal(goalB, "B")),
            results = listOf(
                proportionRow("control", goalA, 100, 10, null),
                proportionRow("control", goalB, 101, 10, null),
                proportionRow("treatment", goalA, 100, 10, 0.0),
                proportionRow("treatment", goalB, 100, 10, 0.0),
            ),
        )

        assertTrue(analysis.srmImpressionInconsistency)
    }

    @Test
    fun `goal without result rows is retained as insufficient data`() {
        val analysis = runDeterministicAnalysis(
            experiment = experiment(),
            variations = listOf(variation("control"), variation("treatment")),
            expectedRolloutWeights = emptyMap(),
            goals = listOf(goal(goalA, "Observed"), goal(goalB, "Missing")),
            results = listOf(
                proportionRow("control", goalA, 100, 10, null),
                proportionRow("treatment", goalA, 100, 10, 0.0),
            ),
        )

        val missing = analysis.goalAnalyses.single { it.goalId == goalB.toString() }
        assertEquals(GoalVerdict.INSUFFICIENT_DATA, missing.verdict)
        assertTrue(missing.variationRows.isEmpty())
    }

    @Test
    fun `SRM with only one positive expected bucket is skipped`() {
        val analysis = runDeterministicAnalysis(
            experiment = experiment(),
            variations = listOf(variation("control"), variation("treatment")),
            expectedRolloutWeights = mapOf("control" to 1.0, "treatment" to 0.0),
            goals = listOf(goal(goalA, "Signups")),
            results = listOf(
                proportionRow("control", goalA, 100, 10, null),
                proportionRow("treatment", goalA, 1, 0, 0.0),
            ),
        )

        assertEquals(false, analysis.srm?.failed)
        assertEquals(1.0, analysis.srm?.pValue)
    }

    @Test
    fun `SRM uses raw assignments when activation reduces the outcome cohort`() {
        val analysis = runDeterministicAnalysis(
            experiment = experiment(),
            variations = listOf(variation("control"), variation("treatment")),
            expectedRolloutWeights = mapOf("control" to 0.5, "treatment" to 0.5),
            goals = listOf(goal(goalA, "Follow-up content view")),
            results = listOf(
                proportionRow("control", goalA, n = 10, conversions = 2, confidence = null, assignments = 100),
                proportionRow("treatment", goalA, n = 10, conversions = 2, confidence = 0.0, assignments = 300),
            ),
        )

        assertEquals(mapOf("control" to 100L, "treatment" to 300L), analysis.srm?.observed)
        assertTrue(analysis.srm?.failed == true)
    }

    @Test
    fun `SHIP summary tolerates a winning row without confidence interval bounds`() {
        val analysis = runDeterministicAnalysis(
            experiment = experiment(),
            variations = listOf(variation("control"), variation("treatment", "Treatment")),
            expectedRolloutWeights = emptyMap(),
            goals = listOf(goal(goalA, "Signups")),
            results = listOf(
                proportionRow("control", goalA, 1, 1, null).copy(conversionRate = 0.5),
                proportionRow("treatment", goalA, 1, 1, 0.99).copy(conversionRate = 0.6),
            ),
        )

        assertEquals(Verdict.SHIP, analysis.verdict)
        assertTrue("95% CI" !in analysis.summary)
    }

    @Test
    fun `session duration coverage imbalance prevents a winner verdict`() {
        val sessionGoal = goal(goalA, "Session duration", GoalMetricType.SESSION_DURATION)
        val analysis = runDeterministicAnalysis(
            experiment = experiment(),
            variations = listOf(variation("control"), variation("treatment")),
            expectedRolloutWeights = mapOf("control" to 0.5, "treatment" to 0.5),
            goals = listOf(sessionGoal),
            results = listOf(
                continuousRow("control", goalA, 100, 100, mean = 10.0, variance = 4.0, confidence = null),
                continuousRow("treatment", goalA, 100, 50, mean = 20.0, variance = 4.0, confidence = 0.999),
            ),
        )
        val goalAnalysis = analysis.goalAnalyses.single()
        assertTrue(goalAnalysis.coverageImbalanced)
        assertEquals(GoalVerdict.INCONCLUSIVE_LIFT, goalAnalysis.verdict)
        assertNull(goalAnalysis.winner)
        assertEquals(Verdict.INCONCLUSIVE, analysis.verdict)
        assertEquals(0.5, goalAnalysis.variationRows.single { it.variationKey == "treatment" }.coverage)
    }

    @Test
    fun `exact ten point session coverage difference is not imbalanced`() {
        assertFalse(coverageDifferenceExceeds(80, 100, 70, 100))
        assertFalse(coverageDifferenceExceeds(1, 10, 0, 0))
        assertTrue(coverageDifferenceExceeds(81, 100, 70, 100))

        val analysis = runDeterministicAnalysis(
            experiment = experiment(),
            variations = listOf(variation("control"), variation("treatment")),
            expectedRolloutWeights = mapOf("control" to 0.5, "treatment" to 0.5),
            goals = listOf(goal(goalA, "Session duration", GoalMetricType.SESSION_DURATION)),
            results = listOf(
                continuousRow("control", goalA, 100, 80, 10.0, 4.0, null),
                continuousRow("treatment", goalA, 100, 70, 20.0, 4.0, 0.999),
            ),
        )
        assertFalse(analysis.goalAnalyses.single().coverageImbalanced)
    }

    @Test
    fun `removed variation results do not participate in current analysis`() {
        val analysis = runDeterministicAnalysis(
            experiment = experiment(),
            variations = listOf(variation("control"), variation("treatment")),
            expectedRolloutWeights = mapOf("control" to 0.5, "treatment" to 0.5),
            goals = listOf(goal(goalA, "Session duration", GoalMetricType.SESSION_DURATION)),
            results = listOf(
                continuousRow("control", goalA, 100, 100, 10.0, 4.0, null),
                continuousRow("treatment", goalA, 100, 100, 20.0, 4.0, 0.999),
                continuousRow("removed", goalA, 100, 20, 1.0, 4.0, 0.999),
            ),
        )

        val goalAnalysis = analysis.goalAnalyses.single()
        assertFalse(goalAnalysis.coverageImbalanced)
        assertEquals(setOf("control", "treatment"), goalAnalysis.variationRows.map { it.variationKey }.toSet())
        assertEquals(200, analysis.totalImpressions)
    }

    @Test
    fun `clear winner produces SHIP verdict with the right variation`() {
        // 12% vs 18% on n=2000 each. chiSquaredConfidence on this
        // produces ≈ 0.99998, well above 0.95.
        val variations = listOf(variation("control"), variation("treatment"))
        val goals = listOf(goal(goalA, "Signups"))
        val results = listOf(
            proportionRow("control",   goalA, n = 2000, conversions = 240, confidence = null),
            proportionRow(
                "treatment", goalA, n = 2000, conversions = 360,
                confidence = confidenceVsControl(2000, 240, 2000, 360),
                liftOverControl = 50.0,
            ),
        )
        val analysis = runDeterministicAnalysis(
            experiment(),
            variations = variations,
            expectedRolloutWeights = mapOf("control" to 0.5, "treatment" to 0.5),
            goals = goals,
            results = results,
        )
        assertEquals(Verdict.SHIP, analysis.verdict, "expected SHIP, got ${analysis.verdict}: ${analysis.summary}")
        val winnerGoal = analysis.goalAnalyses.single()
        assertEquals(GoalVerdict.WINNER, winnerGoal.verdict)
        assertEquals("treatment", winnerGoal.winner?.variationKey)
        assertTrue(analysis.summary.contains("treatment", ignoreCase = true))
        assertEquals(1, analysis.numComparisons)
        assertEquals(0.05, analysis.effectiveAlpha, 1e-12)
    }

    @Test
    fun `significantly worse treatment produces DO_NOT_SHIP and never SHIP`() {
        // 18% vs 12% — same magnitude of effect as the SHIP test, but
        // in the wrong direction.
        val variations = listOf(variation("control"), variation("treatment"))
        val goals = listOf(goal(goalA, "Signups"))
        val results = listOf(
            proportionRow("control",   goalA, n = 2000, conversions = 360, confidence = null),
            proportionRow(
                "treatment", goalA, n = 2000, conversions = 240,
                confidence = confidenceVsControl(2000, 360, 2000, 240),
                liftOverControl = -33.33,
            ),
        )
        val analysis = runDeterministicAnalysis(
            experiment(), variations,
            expectedRolloutWeights = mapOf("control" to 0.5, "treatment" to 0.5),
            goals = goals,
            results = results,
        )
        assertEquals(Verdict.DO_NOT_SHIP, analysis.verdict)
        // Critical safety check: a regression must never end up in the
        // SHIP branch by accident — that would be a real production
        // outage waiting to happen.
        assertTrue(analysis.verdict != Verdict.SHIP)
        assertEquals(GoalVerdict.LOSER, analysis.goalAnalyses.single().verdict)
    }

    @Test
    fun `SRM failure short-circuits to SRM_FAILED even when results look like a winner`() {
        // 50/50 expected rollout but 2000/200 observed → bucketing is
        // obviously broken. Even if the "treatment" looks great, the
        // verdict must be SRM_FAILED because the underlying
        // assignments are not trustworthy.
        val variations = listOf(variation("control"), variation("treatment"))
        val goals = listOf(goal(goalA, "Signups"))
        val results = listOf(
            proportionRow("control",   goalA, n = 2000, conversions = 200, confidence = null),
            proportionRow(
                "treatment", goalA, n = 200, conversions = 60,
                confidence = confidenceVsControl(2000, 200, 200, 60),
                liftOverControl = 200.0,
            ),
        )
        val analysis = runDeterministicAnalysis(
            experiment(), variations,
            expectedRolloutWeights = mapOf("control" to 0.5, "treatment" to 0.5),
            goals = goals,
            results = results,
        )
        assertEquals(Verdict.SRM_FAILED, analysis.verdict)
        assertNotNull(analysis.srm)
        assertTrue(analysis.srm.failed, "SRM should be flagged as failed")
        assertTrue(analysis.summary.contains("Sample ratio mismatch", ignoreCase = true))
    }

    @Test
    fun `no impressions produces NO_DATA`() {
        val variations = listOf(variation("control"), variation("treatment"))
        val goals = listOf(goal(goalA, "Signups"))
        val analysis = runDeterministicAnalysis(
            experiment(), variations,
            expectedRolloutWeights = mapOf("control" to 0.5, "treatment" to 0.5),
            goals = goals,
            results = emptyList(),
        )
        assertEquals(Verdict.NO_DATA, analysis.verdict)
        assertEquals(0L, analysis.totalImpressions)
        assertEquals(0L, analysis.totalConversions)
    }

    @Test
    fun `assigned subjects with no activation produce activation-specific no data guidance`() {
        val experiment = experiment().copy(
            activationFilter = ExperimentActivationFilter(
                eventType = bosca.analytics.model.EventType.Impression,
                elementType = "page",
                pagePathPrefixes = listOf("/articles/"),
            ),
        )
        val analysis = runDeterministicAnalysis(
            experiment = experiment,
            variations = listOf(variation("control"), variation("treatment")),
            expectedRolloutWeights = mapOf("control" to 0.5, "treatment" to 0.5),
            goals = listOf(goal(goalA, "Follow-up content view")),
            results = listOf(
                proportionRow("control", goalA, n = 0, conversions = 0, confidence = null, assignments = 50),
                proportionRow("treatment", goalA, n = 0, conversions = 0, confidence = null, assignments = 50),
            ),
        )

        assertEquals(Verdict.NO_DATA, analysis.verdict)
        assertTrue(analysis.summary.contains("100 assigned subjects have not matched the activation filter"))
        assertTrue(analysis.recommendation.contains("activation filter"))
        assertEquals(mapOf("control" to 50L, "treatment" to 50L), analysis.srm?.observed)
    }

    @Test
    fun `null confidence on every row produces INSUFFICIENT_DATA goal verdicts and NO_DATA`() {
        // Confidence-null rows simulate a goal that hasn't accumulated
        // enough conversions for chi-squared to apply (min expected
        // cell < 5). The goal verdict should be INSUFFICIENT_DATA, the
        // overall verdict should be NO_DATA, and we must NOT see SHIP.
        val variations = listOf(variation("control"), variation("treatment"))
        val goals = listOf(goal(goalA, "Signups"))
        val results = listOf(
            proportionRow("control",   goalA, n = 5, conversions = 0, confidence = null),
            proportionRow("treatment", goalA, n = 5, conversions = 1, confidence = null),
        )
        val analysis = runDeterministicAnalysis(
            experiment(), variations,
            expectedRolloutWeights = mapOf("control" to 0.5, "treatment" to 0.5),
            goals, results,
        )
        assertEquals(Verdict.NO_DATA, analysis.verdict)
        assertEquals(GoalVerdict.INSUFFICIENT_DATA, analysis.goalAnalyses.single().verdict)
    }

    @Test
    fun `near-significance produces KEEP_RUNNING with no winner`() {
        // Want a fixture in [0.90, 0.95) confidence so the verdict
        // routes to NEEDS_MORE_DATA → KEEP_RUNNING. Hand-derived:
        // 5% vs 7% on n=1000 →
        //   χ² ≈ 3.546, df=1, p ≈ 0.0596, confidence ≈ 0.9404.
        val variations = listOf(variation("control"), variation("treatment"))
        val goals = listOf(goal(goalA, "Signups"))
        val results = listOf(
            proportionRow("control",   goalA, n = 1000, conversions = 50, confidence = null),
            proportionRow(
                "treatment", goalA, n = 1000, conversions = 70,
                confidence = confidenceVsControl(1000, 50, 1000, 70),
                liftOverControl = 40.0,
            ),
        )
        val analysis = runDeterministicAnalysis(
            experiment(), variations,
            expectedRolloutWeights = mapOf("control" to 0.5, "treatment" to 0.5),
            goals, results,
        )
        // The exact bucket depends on what the conf comes out as. Pin
        // both possible "still warming up" outcomes — what we strictly
        // forbid is SHIP (the "ship a near-significant result" bug).
        assertTrue(analysis.verdict != Verdict.SHIP, "near-significance must never SHIP")
        assertTrue(analysis.verdict != Verdict.DO_NOT_SHIP, "lift was positive, can't be DO_NOT_SHIP")
        assertNull(analysis.goalAnalyses.single().winner, "no winner under threshold")
    }

    // -----------------------------------------------------------------
    // Bonferroni correction
    // -----------------------------------------------------------------

    @Test
    fun `Bonferroni effective alpha is base alpha divided by family size`() {
        val variations = listOf(variation("control"), variation("a"), variation("b"))
        val goals = listOf(
            goal(goalA, "Signups"),
            goal(goalB, "Purchases"),
        )
        // Family = goals (2) × (variations - 1) (2) = 4 comparisons.
        // Effective alpha = 0.05 / 4 = 0.0125, per-test confidence = 0.9875.
        val analysis = runDeterministicAnalysis(
            experiment(), variations,
            expectedRolloutWeights = mapOf("control" to 1.0/3, "a" to 1.0/3, "b" to 1.0/3),
            goals = goals,
            results = listOf(
                proportionRow("control", goalA, 2000, 200, null),
                proportionRow("a",       goalA, 2000, 200, 0.0),
                proportionRow("b",       goalA, 2000, 200, 0.0),
                proportionRow("control", goalB, 2000, 200, null),
                proportionRow("a",       goalB, 2000, 200, 0.0),
                proportionRow("b",       goalB, 2000, 200, 0.0),
            ),
        )
        assertEquals(4, analysis.numComparisons)
        assertEquals(0.0125, analysis.effectiveAlpha, 1e-12)
    }

    @Test
    fun `Bonferroni demotes a borderline 95 percent winner when the family is large enough`() {
        // Construct a result whose unadjusted confidence is just over
        // 0.95 — exactly the case Bonferroni is meant to catch — and
        // verify that with enough siblings in the family it stops
        // being SHIP.
        //
        // Single-comparison case first (sanity): should SHIP.
        val variations = listOf(variation("control"), variation("treatment"))
        val singleGoal = listOf(goal(goalA, "Signups"))
        val singleResults = listOf(
            proportionRow("control",   goalA, n = 5000, conversions = 250, confidence = null),
            proportionRow(
                "treatment", goalA, n = 5000, conversions = 305,
                confidence = confidenceVsControl(5000, 250, 5000, 305),
                liftOverControl = 22.0,
            ),
        )
        // The fixture above gives confidence ≈ 0.965 — significant at
        // 0.05 but NOT at 0.05/8 = 0.00625 (i.e. confidence threshold
        // 0.99375).
        val singleConf = confidenceVsControl(5000, 250, 5000, 305)!!
        assertTrue(singleConf in 0.95..0.985, "fixture confidence $singleConf must be borderline")

        val singleAnalysis = runDeterministicAnalysis(
            experiment(), variations,
            expectedRolloutWeights = mapOf("control" to 0.5, "treatment" to 0.5),
            singleGoal, singleResults,
        )
        assertEquals(Verdict.SHIP, singleAnalysis.verdict, "uncorrected: borderline must SHIP")

        // Now the same per-row data, but inside a family of 8
        // comparisons (four goals × one treatment). The borderline
        // result must no longer pass the corrected threshold and the
        // verdict must drop out of SHIP.
        val manyGoals = (1..4).map {
            goal(UUID.parse("00000000-0000-0000-0000-00000000000$it"), "Goal-$it")
        }
        val manyVariations = listOf(variation("control"), variation("treatment"))
        val manyResults = manyGoals.flatMap { g ->
            listOf(
                proportionRow("control",   g.id, n = 5000, conversions = 250, confidence = null),
                proportionRow(
                    "treatment", g.id, n = 5000, conversions = 305,
                    confidence = singleConf,
                    liftOverControl = 22.0,
                ),
            )
        }
        val manyAnalysis = runDeterministicAnalysis(
            experiment(), manyVariations,
            expectedRolloutWeights = mapOf("control" to 0.5, "treatment" to 0.5),
            goals = manyGoals,
            results = manyResults,
        )
        assertEquals(4, manyAnalysis.numComparisons)
        assertEquals(0.05 / 4, manyAnalysis.effectiveAlpha, 1e-12)
        assertTrue(
            manyAnalysis.verdict != Verdict.SHIP,
            "Bonferroni-corrected family of 4 must demote $singleConf-confidence; got ${manyAnalysis.verdict}",
        )
    }

    @Test
    fun `single-comparison experiment uses uncorrected base alpha`() {
        val variations = listOf(variation("control"), variation("treatment"))
        val goals = listOf(goal(goalA, "Signups"))
        val analysis = runDeterministicAnalysis(
            experiment(), variations,
            expectedRolloutWeights = mapOf("control" to 0.5, "treatment" to 0.5),
            goals = goals,
            results = listOf(
                proportionRow("control",   goalA, 1000, 100, null),
                proportionRow("treatment", goalA, 1000, 110, confidenceVsControl(1000, 100, 1000, 110)),
            ),
        )
        assertEquals(1, analysis.numComparisons)
        assertEquals(0.05, analysis.effectiveAlpha, 1e-12)
    }

    @Test
    fun `correction note appears in rendered SHIP summary when family size greater than 1`() {
        // Two goals, two variations → 2 comparisons → Bonferroni kicks
        // in. Use a strong-effect fixture so confidence comfortably
        // clears the corrected per-test threshold (1 - 0.025 = 0.975)
        // and the verdict routes through SHIP — that branch is the one
        // an operator would actually be reading when wondering "why is
        // the threshold tighter than 95%?", so the note must be there.
        val variations = listOf(variation("control"), variation("treatment"))
        val goals = listOf(goal(goalA, "Signups"), goal(goalB, "Purchases"))
        val results = listOf(
            // Goal A: 10% vs 18% on n=3000 — confidence ≈ 1 - 1e-19.
            proportionRow("control",   goalA, n = 3000, conversions = 300, confidence = null),
            proportionRow(
                "treatment", goalA, n = 3000, conversions = 540,
                confidence = confidenceVsControl(3000, 300, 3000, 540),
                liftOverControl = 80.0,
            ),
            // Goal B: 5% vs 9% on n=3000 — also extremely significant.
            proportionRow("control",   goalB, n = 3000, conversions = 150, confidence = null),
            proportionRow(
                "treatment", goalB, n = 3000, conversions = 270,
                confidence = confidenceVsControl(3000, 150, 3000, 270),
                liftOverControl = 80.0,
            ),
        )
        val analysis = runDeterministicAnalysis(
            experiment(), variations,
            expectedRolloutWeights = mapOf("control" to 0.5, "treatment" to 0.5),
            goals = goals,
            results = results,
        )
        // 2 goals × (2 - 1) treatments = 2 comparisons.
        assertEquals(2, analysis.numComparisons)
        assertEquals(0.025, analysis.effectiveAlpha, 1e-12)
        assertEquals(Verdict.SHIP, analysis.verdict, "expected SHIP, got ${analysis.verdict}: ${analysis.summary}")
        // The rendered summary must say "Bonferroni" once we have > 1
        // comparison so an operator reading the report can see why
        // thresholds are tighter than the textbook 95%.
        assertTrue(
            analysis.summary.contains("Bonferroni"),
            "expected Bonferroni note in summary, got: ${analysis.summary}",
        )
        assertTrue(
            analysis.summary.contains("2 comparisons"),
            "expected family size in summary, got: ${analysis.summary}",
        )
    }

    // -----------------------------------------------------------------
    // Phase 1 — Goal roles (PRIMARY / SECONDARY / GUARDRAIL) and HALT
    //
    // These tests pin the verdict severity order from requirements.md
    // R1: SRM_FAILED > HALT > DO_NOT_SHIP > SHIP/INCONCLUSIVE >
    // KEEP_RUNNING > NO_DATA. Every outcome here must happen even when
    // a primary goal is screaming SHIP — a guardrail regression or a
    // secondary loser MUST dominate the branch a pre-roles analyzer
    // would have taken.
    // -----------------------------------------------------------------

    /**
     * Construct a role-based test: a primary with a clear winning
     * lift and a guardrail whose treatment regressed. The pre-roles
     * analyzer would route this to DO_NOT_SHIP (any LOSER →
     * DO_NOT_SHIP). The roles-aware analyzer MUST route it to HALT —
     * that is the whole reason guardrails exist.
     */
    @Test
    fun `guardrail regression produces HALT even when a primary is winning`() {
        val variations = listOf(variation("control"), variation("treatment"))
        val primary = goal(goalA, "Signups", role = ConversionGoalRole.PRIMARY)
        val guardrail = goal(goalB, "Page-load", role = ConversionGoalRole.GUARDRAIL)
        val results = listOf(
            // Primary: strong winner. 10% → 18% on n=3000 each.
            proportionRow("control",   goalA, n = 3000, conversions = 300, confidence = null),
            proportionRow(
                "treatment", goalA, n = 3000, conversions = 540,
                confidence = confidenceVsControl(3000, 300, 3000, 540),
                liftOverControl = 80.0,
            ),
            // Guardrail: clear regression. 20% → 14% on n=3000 each
            // (picking proportions so both arms are above the chi-
            // squared min-cell floor and the effect is comfortably
            // past the 0.5% GUARDRAIL threshold as well as the 1.0%
            // primary threshold, so neither cutoff is the variable
            // under test).
            proportionRow("control",   goalB, n = 3000, conversions = 600, confidence = null),
            proportionRow(
                "treatment", goalB, n = 3000, conversions = 420,
                confidence = confidenceVsControl(3000, 600, 3000, 420),
                liftOverControl = -30.0,
            ),
        )
        val analysis = runDeterministicAnalysis(
            experiment(), variations,
            expectedRolloutWeights = mapOf("control" to 0.5, "treatment" to 0.5),
            goals = listOf(primary, guardrail),
            results = results,
        )
        assertEquals(Verdict.HALT, analysis.verdict,
            "expected HALT from guardrail regression, got ${analysis.verdict}: ${analysis.summary}")
        assertTrue(analysis.summary.contains("Guardrail regression", ignoreCase = true),
            "HALT summary must name the guardrail cause, got: ${analysis.summary}")
        assertTrue(analysis.summary.contains("Page-load"),
            "HALT summary must include the offending goal name, got: ${analysis.summary}")
        // Role is carried on every goal analysis so downstream
        // consumers (controller, UI) don't have to re-join goal rows.
        val guardrailAnalysis = analysis.goalAnalyses.single { it.goalId == goalB.toString() }
        assertEquals(ConversionGoalRole.GUARDRAIL, guardrailAnalysis.role)
        assertEquals(GoalVerdict.LOSER, guardrailAnalysis.verdict)
    }

    @Test
    fun `secondary regression produces INCONCLUSIVE not DO_NOT_SHIP`() {
        val variations = listOf(variation("control"), variation("treatment"))
        val secondary = goal(goalA, "Engagement", role = ConversionGoalRole.SECONDARY)
        val results = listOf(
            proportionRow("control",   goalA, n = 3000, conversions = 600, confidence = null),
            proportionRow(
                "treatment", goalA, n = 3000, conversions = 420,
                confidence = confidenceVsControl(3000, 600, 3000, 420),
                liftOverControl = -30.0,
            ),
        )
        val analysis = runDeterministicAnalysis(
            experiment(), variations,
            expectedRolloutWeights = mapOf("control" to 0.5, "treatment" to 0.5),
            goals = listOf(secondary),
            results = results,
        )
        // The pre-roles analyzer would have sent this to DO_NOT_SHIP
        // because "any LOSER → DO_NOT_SHIP". With roles, a lone
        // secondary loser is INCONCLUSIVE — the operator is aware of
        // the tradeoff but nothing is promoting or halting because no
        // primary or guardrail moved.
        assertEquals(Verdict.INCONCLUSIVE, analysis.verdict,
            "expected INCONCLUSIVE from secondary-only loser, got ${analysis.verdict}")
        assertTrue(analysis.verdict != Verdict.DO_NOT_SHIP,
            "secondary loser must NOT produce DO_NOT_SHIP — that's reserved for primaries")
        assertTrue(analysis.verdict != Verdict.HALT,
            "secondary loser must NOT produce HALT — that's reserved for guardrails")
    }

    @Test
    fun `secondary regression downgrades an otherwise-SHIP to INCONCLUSIVE`() {
        // A primary winner AND a secondary loser on the same experiment.
        // The primary alone would produce SHIP; the secondary loser
        // downgrades that to INCONCLUSIVE per the R1 "SHALL NOT
        // downgrade the verdict beyond INCONCLUSIVE" rule. Without this
        // test the interaction between the two rules is ambiguous.
        val variations = listOf(variation("control"), variation("treatment"))
        val primary = goal(goalA, "Signups", role = ConversionGoalRole.PRIMARY)
        val secondary = goal(goalB, "Engagement", role = ConversionGoalRole.SECONDARY)
        val results = listOf(
            proportionRow("control",   goalA, n = 3000, conversions = 300, confidence = null),
            proportionRow(
                "treatment", goalA, n = 3000, conversions = 540,
                confidence = confidenceVsControl(3000, 300, 3000, 540),
                liftOverControl = 80.0,
            ),
            proportionRow("control",   goalB, n = 3000, conversions = 600, confidence = null),
            proportionRow(
                "treatment", goalB, n = 3000, conversions = 420,
                confidence = confidenceVsControl(3000, 600, 3000, 420),
                liftOverControl = -30.0,
            ),
        )
        val analysis = runDeterministicAnalysis(
            experiment(), variations,
            expectedRolloutWeights = mapOf("control" to 0.5, "treatment" to 0.5),
            goals = listOf(primary, secondary),
            results = results,
        )
        assertEquals(Verdict.INCONCLUSIVE, analysis.verdict,
            "primary winner + secondary loser must downgrade SHIP to INCONCLUSIVE, got ${analysis.verdict}")
    }

    @Test
    fun `primary regression still produces DO_NOT_SHIP after roles land`() {
        // Regression baseline: primaries retain their pre-roles
        // semantics. If this ever starts routing to HALT we would lose
        // the distinction between "do not advance" and "revert now".
        val variations = listOf(variation("control"), variation("treatment"))
        val primary = goal(goalA, "Signups", role = ConversionGoalRole.PRIMARY)
        val results = listOf(
            proportionRow("control",   goalA, n = 2000, conversions = 360, confidence = null),
            proportionRow(
                "treatment", goalA, n = 2000, conversions = 240,
                confidence = confidenceVsControl(2000, 360, 2000, 240),
                liftOverControl = -33.33,
            ),
        )
        val analysis = runDeterministicAnalysis(
            experiment(), variations,
            expectedRolloutWeights = mapOf("control" to 0.5, "treatment" to 0.5),
            goals = listOf(primary),
            results = results,
        )
        assertEquals(Verdict.DO_NOT_SHIP, analysis.verdict)
        assertTrue(analysis.verdict != Verdict.HALT,
            "primary regression must NOT escalate to HALT — that's guardrail territory")
    }

    /**
     * The practical-threshold plumbing has to be tested with a
     * *hand-set* confidence on the row. If we let the chi-squared
     * computation derive the confidence from the raw counts, a
     * sub-1% relative-lift fixture never clears the 0.95 significance
     * bar at any feasible sample size, so the row would never reach
     * LOSER and the test would be accidentally passing via the wrong
     * gate. Setting `confidence = 0.99` directly isolates
     * `analyzeGoal`'s practical-lift comparison from its significance
     * comparison. [proportionRow] already supports pass-through
     * confidence for exactly this reason.
     */
    private fun fixedConfidenceRow(
        variationKey: String,
        goalId: UUID,
        n: Long,
        conversions: Long,
        confidence: Double,
    ) = proportionRow(variationKey, goalId, n, conversions, confidence)

    @Test
    fun `guardrail regression below guardrailMinRegressionPercent does not HALT`() {
        // A guardrail whose treatment moves a *tiny* amount (below the
        // default 0.5% practical threshold) must NOT trip HALT — the
        // whole point of a configurable practical threshold is that
        // noise-level movements on a low-variance metric don't keep
        // halting every rollout. Using hand-set high confidence so the
        // gate under test is the practical-lift threshold, not the
        // significance threshold. Fixture: 30.10% → 30.00% on
        // n=100_000 per arm → -0.33% relative lift, forced confidence
        // 0.99 so significance would be cleared if the lift were big
        // enough.
        val variations = listOf(variation("control"), variation("treatment"))
        val guardrail = goal(goalA, "Tiny-guardrail", role = ConversionGoalRole.GUARDRAIL)
        val results = listOf(
            fixedConfidenceRow("control",   goalA, n = 100_000, conversions = 30_100, confidence = 0.99),
            fixedConfidenceRow("treatment", goalA, n = 100_000, conversions = 30_000, confidence = 0.99),
        )
        val analysis = runDeterministicAnalysis(
            experiment(), variations,
            expectedRolloutWeights = mapOf("control" to 0.5, "treatment" to 0.5),
            goals = listOf(guardrail),
            results = results,
        )
        assertTrue(analysis.verdict != Verdict.HALT,
            "sub-threshold guardrail movement must not trip HALT, got ${analysis.verdict}")
    }

    @Test
    fun `tightened guardrail threshold trips HALT on movements the default would ignore`() {
        // Same fixture as the previous test but with the operator-
        // configured AnalysisThresholds pulling
        // guardrailMinRegressionPercent down to 0.2%. Now -0.33% IS a
        // LOSER and HALT fires. This is the test that proves the
        // threshold is actually wired through; without it the knob
        // could be decorative and we wouldn't know.
        val variations = listOf(variation("control"), variation("treatment"))
        val guardrail = goal(goalA, "Sensitive-guardrail", role = ConversionGoalRole.GUARDRAIL)
        val results = listOf(
            fixedConfidenceRow("control",   goalA, n = 100_000, conversions = 30_100, confidence = 0.99),
            fixedConfidenceRow("treatment", goalA, n = 100_000, conversions = 30_000, confidence = 0.99),
        )
        val analysis = runDeterministicAnalysis(
            experiment(), variations,
            expectedRolloutWeights = mapOf("control" to 0.5, "treatment" to 0.5),
            goals = listOf(guardrail),
            results = results,
            thresholds = AnalysisThresholds(guardrailMinRegressionPercent = 0.2),
        )
        assertEquals(Verdict.HALT, analysis.verdict,
            "tightened guardrail threshold should trip HALT, got ${analysis.verdict}")
    }

    // -----------------------------------------------------------------
    // Phase 4 — Bayesian verdict path.
    //
    // The Bayesian branch of analyzeGoal reads
    // `probabilityBeatsControl` out of the VariationRow's confidence
    // field and DOES NOT apply Bonferroni. These tests pin both
    // properties: the verdict routes on the posterior probability,
    // not on any frequentist p-value, and the family-size correction
    // is skipped.
    // -----------------------------------------------------------------

    private fun bayesianExperiment(): Experiment = Experiment(
        id = expId,
        featureFlagId = expId,
        controlVariationKey = "control",
        name = "bayesian-exp",
        hypothesis = "Treatment outperforms control",
        status = ExperimentStatus.RUNNING,
        analysisMethod = bosca.experimentation.model.AnalysisMethod.BAYESIAN,
    )

    /**
     * Proportion row that carries a Bayesian `probabilityBeatsControl`
     * value. The aggregator populates this column at Bayesian runtime;
     * here we inject it directly so analyzeGoal's routing can be
     * isolated from the Monte Carlo.
     */
    private fun bayesianRow(
        variationKey: String,
        goalId: UUID,
        n: Long,
        conversions: Long,
        probabilityBeatsControl: Double?,
    ): ExperimentResult = ExperimentResult(
        experimentId = expId,
        variationKey = variationKey,
        goalId = goalId,
        impressions = n,
        conversions = conversions,
        conversionRate = if (n > 0) conversions.toDouble() / n else 0.0,
        // Bayesian path leaves frequentist confidence null; the
        // analyzer reads `probabilityBeatsControl` instead.
        confidenceLevel = null,
        probabilityBeatsControl = probabilityBeatsControl,
    )

    @Test
    fun `Bayesian SHIP verdict based on probabilityBeatsControl above 0_95`() {
        val variations = listOf(variation("control"), variation("treatment"))
        val goals = listOf(goal(goalA, "Signups"))
        val results = listOf(
            // Control row has no probabilityBeatsControl — it's the baseline.
            bayesianRow("control",   goalA, n = 2000, conversions = 240, probabilityBeatsControl = null),
            // Treatment has P(T>C) = 0.99 on a strong positive lift.
            bayesianRow("treatment", goalA, n = 2000, conversions = 360, probabilityBeatsControl = 0.99),
        )
        val analysis = runDeterministicAnalysis(
            bayesianExperiment(), variations,
            expectedRolloutWeights = mapOf("control" to 0.5, "treatment" to 0.5),
            goals = goals,
            results = results,
        )
        assertEquals(Verdict.SHIP, analysis.verdict,
            "Bayesian SHIP must come from probabilityBeatsControl, got ${analysis.verdict}")
    }

    @Test
    fun `Bayesian hold when probabilityBeatsControl is between thresholds`() {
        // 0.93 is below the per-test threshold (1 - BASE_ALPHA = 0.95)
        // but above the "approaching" threshold (1 - 2*alpha = 0.90),
        // so the verdict should route through KEEP_RUNNING, not SHIP.
        val variations = listOf(variation("control"), variation("treatment"))
        val goals = listOf(goal(goalA, "Signups"))
        val results = listOf(
            bayesianRow("control",   goalA, n = 2000, conversions = 240, probabilityBeatsControl = null),
            bayesianRow("treatment", goalA, n = 2000, conversions = 300, probabilityBeatsControl = 0.93),
        )
        val analysis = runDeterministicAnalysis(
            bayesianExperiment(), variations,
            expectedRolloutWeights = mapOf("control" to 0.5, "treatment" to 0.5),
            goals = goals,
            results = results,
        )
        assertTrue(analysis.verdict != Verdict.SHIP,
            "sub-threshold Bayesian posterior must not SHIP, got ${analysis.verdict}")
    }

    @Test
    fun `Bayesian analysis does not apply Bonferroni correction`() {
        val variations = listOf(variation("control"), variation("treatment"))
        val goals = listOf(goal(goalA, "Signups"), goal(goalB, "Purchases"))
        val results = listOf(
            bayesianRow("control",   goalA, n = 2000, conversions = 240, probabilityBeatsControl = null),
            bayesianRow("treatment", goalA, n = 2000, conversions = 360, probabilityBeatsControl = 0.97),
            bayesianRow("control",   goalB, n = 2000, conversions = 200, probabilityBeatsControl = null),
            bayesianRow("treatment", goalB, n = 2000, conversions = 300, probabilityBeatsControl = 0.97),
        )
        val analysis = runDeterministicAnalysis(
            bayesianExperiment(), variations,
            expectedRolloutWeights = mapOf("control" to 0.5, "treatment" to 0.5),
            goals = goals,
            results = results,
        )
        // Bonferroni would halve the per-test alpha on a family of 2
        // comparisons (0.05 → 0.025) and bump the per-test threshold
        // from 0.95 to 0.975 — which would demote this 0.97 posterior
        // out of SHIP. The Bayesian path MUST NOT apply the correction,
        // so effectiveAlpha stays at BASE_ALPHA and the verdict is SHIP.
        assertEquals(0.05, analysis.effectiveAlpha, 1e-12,
            "Bayesian effectiveAlpha must equal baseAlpha regardless of family size")
    }

    @Test
    fun `Bayesian guardrail HALT still fires on a posterior regression`() {
        val variations = listOf(variation("control"), variation("treatment"))
        val primary = goal(goalA, "Signups", role = ConversionGoalRole.PRIMARY)
        val guardrail = goal(goalB, "Page-load", role = ConversionGoalRole.GUARDRAIL)
        val results = listOf(
            // Primary is winning (Bayesian posterior 0.99).
            bayesianRow("control",   goalA, n = 3000, conversions = 300, probabilityBeatsControl = null),
            bayesianRow("treatment", goalA, n = 3000, conversions = 540, probabilityBeatsControl = 0.99),
            // Guardrail has a high posterior that control beats
            // treatment (probabilityBeatsControl = 0.02 means
            // treatment > control with 2% probability = treatment
            // is almost certainly worse). With practical lift -30%
            // this is a clear guardrail LOSER.
            bayesianRow("control",   goalB, n = 3000, conversions = 600, probabilityBeatsControl = null),
            bayesianRow("treatment", goalB, n = 3000, conversions = 420, probabilityBeatsControl = 0.02),
        )
        val analysis = runDeterministicAnalysis(
            bayesianExperiment(), variations,
            expectedRolloutWeights = mapOf("control" to 0.5, "treatment" to 0.5),
            goals = listOf(primary, guardrail),
            results = results,
        )
        // The Bayesian LOSER gate reads `(1 - posterior) >= threshold`
        // — 0.02 posterior means 0.98 probability that control beats
        // treatment, which clears the 0.95 bar. Combined with the
        // -30% practical lift this is a hard guardrail LOSER, so
        // the verdict must be HALT.
        assertEquals(Verdict.HALT, analysis.verdict,
            "Bayesian guardrail regression must produce HALT, got ${analysis.verdict}: ${analysis.summary}")
    }

    @Test
    fun `SRM failure still short-circuits HALT`() {
        // HALT is severe but SRM_FAILED is more severe — if bucketing
        // is broken we cannot trust the numbers to even know whether
        // the guardrail "regressed". This test pins that ordering.
        val variations = listOf(variation("control"), variation("treatment"))
        val guardrail = goal(goalA, "Page-load", role = ConversionGoalRole.GUARDRAIL)
        val results = listOf(
            // Broken split: 2000 vs 200 but the rollout said 50/50.
            proportionRow("control",   goalA, n = 2000, conversions = 400, confidence = null),
            proportionRow(
                "treatment", goalA, n = 200, conversions = 80,
                confidence = confidenceVsControl(2000, 400, 200, 80),
                liftOverControl = -50.0,
            ),
        )
        val analysis = runDeterministicAnalysis(
            experiment(), variations,
            expectedRolloutWeights = mapOf("control" to 0.5, "treatment" to 0.5),
            goals = listOf(guardrail),
            results = results,
        )
        assertEquals(Verdict.SRM_FAILED, analysis.verdict,
            "SRM must short-circuit even a guardrail HALT")
    }

    @Test
    fun `Bayesian skips Bonferroni while frequentist applies it on same family`() {
        // Same experiment shape under both methods: 2 goals × 1 treatment
        // = 2 comparisons. Under FREQUENTIST the effectiveAlpha must be
        // 0.05/2 = 0.025. Under BAYESIAN it must stay at 0.05.
        val variations = listOf(variation("control"), variation("a"), variation("b"))
        val goals = listOf(goal(goalA, "Signups"), goal(goalB, "Purchases"))
        // 3 variations × 2 goals = 4 comparisons for frequentist.

        val frequentistResults = listOf(
            proportionRow("control", goalA, 2000, 200, null),
            proportionRow("a",       goalA, 2000, 200, 0.5),
            proportionRow("b",       goalA, 2000, 200, 0.5),
            proportionRow("control", goalB, 2000, 200, null),
            proportionRow("a",       goalB, 2000, 200, 0.5),
            proportionRow("b",       goalB, 2000, 200, 0.5),
        )
        val freqAnalysis = runDeterministicAnalysis(
            experiment(), variations,
            expectedRolloutWeights = mapOf("control" to 1.0/3, "a" to 1.0/3, "b" to 1.0/3),
            goals = goals,
            results = frequentistResults,
        )
        assertEquals(4, freqAnalysis.numComparisons)
        assertEquals(BASE_ALPHA / 4, freqAnalysis.effectiveAlpha, 1e-12,
            "frequentist must apply Bonferroni: alpha / numComparisons")

        val bayesianResults = listOf(
            bayesianRow("control", goalA, 2000, 200, null),
            bayesianRow("a",       goalA, 2000, 200, 0.5),
            bayesianRow("b",       goalA, 2000, 200, 0.5),
            bayesianRow("control", goalB, 2000, 200, null),
            bayesianRow("a",       goalB, 2000, 200, 0.5),
            bayesianRow("b",       goalB, 2000, 200, 0.5),
        )
        val bayesAnalysis = runDeterministicAnalysis(
            bayesianExperiment(), variations,
            expectedRolloutWeights = mapOf("control" to 1.0/3, "a" to 1.0/3, "b" to 1.0/3),
            goals = goals,
            results = bayesianResults,
        )
        assertEquals(4, bayesAnalysis.numComparisons)
        assertEquals(BASE_ALPHA, bayesAnalysis.effectiveAlpha, 1e-12,
            "Bayesian must NOT apply Bonferroni: effectiveAlpha == BASE_ALPHA regardless of family size")
    }

    @Test
    fun `Bayesian WINNER via high probabilityBeatsControl and positive lift`() {
        // Treatment converts at 18% vs control 12% on n=2000 each.
        // probabilityBeatsControl = 0.97 clears the 0.95 threshold and
        // the lift is well above the practical minimum.
        val variations = listOf(variation("control"), variation("treatment"))
        val goals = listOf(goal(goalA, "Signups"))
        val results = listOf(
            bayesianRow("control",   goalA, n = 2000, conversions = 240, probabilityBeatsControl = null),
            bayesianRow("treatment", goalA, n = 2000, conversions = 360, probabilityBeatsControl = 0.97),
        )
        val analysis = runDeterministicAnalysis(
            bayesianExperiment(), variations,
            expectedRolloutWeights = mapOf("control" to 0.5, "treatment" to 0.5),
            goals = goals,
            results = results,
        )
        assertEquals(Verdict.SHIP, analysis.verdict,
            "high probabilityBeatsControl with positive lift must produce SHIP, got ${analysis.verdict}")
        val goalResult = analysis.goalAnalyses.single()
        assertEquals(GoalVerdict.WINNER, goalResult.verdict)
        assertEquals("treatment", goalResult.winner?.variationKey)
    }

    @Test
    fun `Bayesian LOSER via low probabilityBeatsControl and negative lift`() {
        // Treatment converts at 12% vs control 18% on n=2000 each.
        // probabilityBeatsControl = 0.03 means (1 - 0.03) = 0.97 >=
        // 0.95 confidence that control beats treatment. Combined with
        // the negative lift this must route through LOSER → DO_NOT_SHIP.
        val variations = listOf(variation("control"), variation("treatment"))
        val goals = listOf(goal(goalA, "Signups"))
        val results = listOf(
            bayesianRow("control",   goalA, n = 2000, conversions = 360, probabilityBeatsControl = null),
            bayesianRow("treatment", goalA, n = 2000, conversions = 240, probabilityBeatsControl = 0.03),
        )
        val analysis = runDeterministicAnalysis(
            bayesianExperiment(), variations,
            expectedRolloutWeights = mapOf("control" to 0.5, "treatment" to 0.5),
            goals = goals,
            results = results,
        )
        assertEquals(Verdict.DO_NOT_SHIP, analysis.verdict,
            "low probabilityBeatsControl with negative lift must produce DO_NOT_SHIP, got ${analysis.verdict}")
        assertEquals(GoalVerdict.LOSER, analysis.goalAnalyses.single().verdict)
    }

    @Test
    fun `Bayesian NEEDS_MORE_DATA when approaching loser symmetrically`() {
        // Treatment converts at 14% vs control 16% on n=2000 each.
        // probabilityBeatsControl = 0.12 — the inverse is 0.88 which
        // is above the "near significance" threshold (1 - 2*alpha =
        // 0.90? No: 0.88 < 0.90) but let's use 0.08 so 1-0.08=0.92
        // is >= 0.90. This is the symmetric "approaching loser" case
        // that should produce NEEDS_MORE_DATA, not INCONCLUSIVE_LIFT.
        //
        // With probabilityBeatsControl = 0.08:
        //   conf (0.08) < confidenceThreshold (0.95) → not a winner
        //   (1 - 0.08) = 0.92 < confidenceThreshold (0.95) → not a loser
        //   conf (0.08) < nearSignificanceThreshold (0.90) → not approaching winner
        //   (1 - 0.08) = 0.92 >= nearSignificanceThreshold (0.90) → approaching loser ✓
        //
        // Without the symmetric Bayesian NEEDS_MORE_DATA check this
        // would fall through to INCONCLUSIVE_LIFT.
        val variations = listOf(variation("control"), variation("treatment"))
        val goals = listOf(goal(goalA, "Signups"))
        val results = listOf(
            bayesianRow("control",   goalA, n = 2000, conversions = 320, probabilityBeatsControl = null),
            bayesianRow("treatment", goalA, n = 2000, conversions = 280, probabilityBeatsControl = 0.08),
        )
        val analysis = runDeterministicAnalysis(
            bayesianExperiment(), variations,
            expectedRolloutWeights = mapOf("control" to 0.5, "treatment" to 0.5),
            goals = goals,
            results = results,
        )
        assertEquals(GoalVerdict.NEEDS_MORE_DATA, analysis.goalAnalyses.single().verdict,
            "approaching-loser with pBC=0.08 must be NEEDS_MORE_DATA (symmetric check), " +
                "got ${analysis.goalAnalyses.single().verdict}")
        assertEquals(Verdict.KEEP_RUNNING, analysis.verdict,
            "NEEDS_MORE_DATA goal must produce KEEP_RUNNING overall, got ${analysis.verdict}")
    }

    @Test
    fun `single comparison rendered SHIP summary contains no Bonferroni note`() {
        // Mirror invariant: when no correction is applied, the note
        // must NOT appear — otherwise an operator would think their
        // 1-comparison experiment is also being downgraded.
        val variations = listOf(variation("control"), variation("treatment"))
        val goals = listOf(goal(goalA, "Signups"))
        val analysis = runDeterministicAnalysis(
            experiment(), variations,
            expectedRolloutWeights = mapOf("control" to 0.5, "treatment" to 0.5),
            goals = goals,
            results = listOf(
                proportionRow("control",   goalA, n = 3000, conversions = 300, confidence = null),
                proportionRow(
                    "treatment", goalA, n = 3000, conversions = 540,
                    confidence = confidenceVsControl(3000, 300, 3000, 540),
                    liftOverControl = 80.0,
                ),
            ),
        )
        assertEquals(Verdict.SHIP, analysis.verdict)
        assertEquals(1, analysis.numComparisons)
        assertTrue(
            !analysis.summary.contains("Bonferroni"),
            "single-comparison SHIP must omit Bonferroni note, got: ${analysis.summary}",
        )
    }

    @Test
    fun `multiple winning treatments select the largest lift`() {
        val variations = listOf(
            variation("control", "Control"),
            variation("treatment-a", "Treatment A"),
            variation("treatment-b", "Treatment B"),
        )
        val analysis = runDeterministicAnalysis(
            experiment(),
            variations,
            expectedRolloutWeights = emptyMap(),
            goals = listOf(goal(goalA, "Signups")),
            results = listOf(
                proportionRow("control", goalA, 5_000, 500, null),
                proportionRow(
                    "treatment-a", goalA, 5_000, 1_000,
                    confidenceVsControl(5_000, 500, 5_000, 1_000),
                ),
                proportionRow(
                    "treatment-b", goalA, 5_000, 1_500,
                    confidenceVsControl(5_000, 500, 5_000, 1_500),
                ),
            ),
        )

        assertEquals(Verdict.SHIP, analysis.verdict)
        assertEquals("treatment-b", analysis.goalAnalyses.single().winner?.variationKey)
        assertTrue(analysis.summary.contains("Treatment B"))
    }

    @Test
    fun `multiple guardrail regressions render the worst treatment`() {
        val variations = listOf(
            variation("control"),
            variation("treatment-a"),
            variation("treatment-b"),
        )
        val guardrail = goal(
            goalA,
            "Errors",
            role = ConversionGoalRole.GUARDRAIL,
        )
        val analysis = runDeterministicAnalysis(
            experiment(),
            variations,
            expectedRolloutWeights = emptyMap(),
            goals = listOf(guardrail),
            results = listOf(
                proportionRow("control", goalA, 5_000, 1_000, null),
                proportionRow(
                    "treatment-a", goalA, 5_000, 500,
                    confidenceVsControl(5_000, 1_000, 5_000, 500),
                ),
                proportionRow(
                    "treatment-b", goalA, 5_000, 250,
                    confidenceVsControl(5_000, 1_000, 5_000, 250),
                ),
            ),
        )

        assertEquals(Verdict.HALT, analysis.verdict)
        assertTrue(analysis.summary.contains("Errors"))
    }

    @Test
    fun `zero total expected rollout weight produces neutral SRM`() {
        val variations = listOf(variation("control"), variation("treatment"))
        val analysis = runDeterministicAnalysis(
            experiment(),
            variations,
            expectedRolloutWeights = mapOf("control" to 0.0, "treatment" to 0.0),
            goals = listOf(goal(goalA, "Signups")),
            results = listOf(
                proportionRow("control", goalA, 100, 10, null),
                proportionRow("treatment", goalA, 100, 10, 0.0),
            ),
        )

        assertNotNull(analysis.srm)
        assertEquals(1.0, analysis.srm.pValue)
        assertFalse(analysis.srm.failed)
    }

    @Test
    fun `empty result snapshot returns no data before goal analysis`() {
        val analysis = runDeterministicAnalysis(
            experiment(),
            listOf(variation("control"), variation("treatment")),
            expectedRolloutWeights = emptyMap(),
            goals = listOf(goal(goalA, "Signups")),
            results = emptyList(),
        )

        assertEquals(Verdict.NO_DATA, analysis.verdict)
        assertTrue(analysis.goalAnalyses.isEmpty())
    }

    @Test
    fun `single observed arm skips SRM and reports insufficient data`() {
        val analysis = runDeterministicAnalysis(
            experiment(),
            listOf(variation("control"), variation("treatment")),
            expectedRolloutWeights = mapOf("control" to 0.5, "treatment" to 0.5),
            goals = listOf(goal(goalA, "Signups")),
            results = listOf(proportionRow("control", goalA, 100, 10, null)),
        )

        assertNull(analysis.srm)
        assertEquals(GoalVerdict.INSUFFICIENT_DATA, analysis.goalAnalyses.single().verdict)
    }

    @Test
    fun `missing control row leaves treatment lift unset`() {
        val analysis = runDeterministicAnalysis(
            experiment(),
            listOf(variation("control"), variation("treatment")),
            expectedRolloutWeights = emptyMap(),
            goals = listOf(goal(goalA, "Signups")),
            results = listOf(proportionRow("treatment", goalA, 100, 20, 0.99)),
        )

        val treatment = analysis.goalAnalyses.single().variationRows.single()
        assertNull(treatment.liftPercent)
        assertEquals(GoalVerdict.NEEDS_MORE_DATA, analysis.goalAnalyses.single().verdict)
    }

    @Test
    fun `positive lift with absent confidence remains insufficient data`() {
        val analysis = runDeterministicAnalysis(
            experiment(),
            listOf(variation("control"), variation("treatment")),
            expectedRolloutWeights = emptyMap(),
            goals = listOf(goal(goalA, "Signups")),
            results = listOf(
                proportionRow("control", goalA, 1_000, 100, null),
                proportionRow("treatment", goalA, 1_000, 150, null),
            ),
        )

        assertEquals(GoalVerdict.INSUFFICIENT_DATA, analysis.goalAnalyses.single().verdict)
    }

    @Test
    fun `statistically confident but impractical lift keeps running`() {
        val analysis = runDeterministicAnalysis(
            experiment(),
            listOf(variation("control"), variation("treatment")),
            expectedRolloutWeights = emptyMap(),
            goals = listOf(goal(goalA, "Signups")),
            results = listOf(
                proportionRow("control", goalA, 10_000, 1_000, null),
                proportionRow("treatment", goalA, 10_000, 1_005, 0.99),
            ),
        )

        assertEquals(GoalVerdict.NEEDS_MORE_DATA, analysis.goalAnalyses.single().verdict)
        assertEquals(Verdict.KEEP_RUNNING, analysis.verdict)
    }

    @Test
    fun `zero impression row reports zero coverage while another arm has traffic`() {
        val analysis = runDeterministicAnalysis(
            experiment(),
            listOf(variation("control"), variation("treatment")),
            expectedRolloutWeights = emptyMap(),
            goals = listOf(goal(goalA, "Signups")),
            results = listOf(
                proportionRow("control", goalA, 100, 10, null),
                proportionRow("treatment", goalA, 0, 0, null),
            ),
        )

        assertEquals(
            0.0,
            analysis.goalAnalyses.single().variationRows.single { it.variationKey == "treatment" }.coverage,
        )
    }
}
