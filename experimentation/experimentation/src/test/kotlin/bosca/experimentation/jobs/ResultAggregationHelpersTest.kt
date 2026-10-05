package bosca.experimentation.jobs

import bosca.experimentation.model.BayesianPrior
import bosca.experimentation.model.ConversionGoal
import bosca.experimentation.model.ConversionGoalRole
import bosca.experimentation.model.GoalMetricType
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Direct tests for the pure helpers inside
 * `ExperimentResultAggregation.kt` that turn raw Trino-derived
 * stats into [bosca.experimentation.model.ExperimentResult] rows.
 * These helpers are the backbone of the aggregation job; the
 * surrounding Trino I/O is covered by the CUPED query builder
 * test + the repository integration test, and the pure math here
 * is covered by `ExperimentAnalysisTest` / `BayesianAnalysisTest`,
 * but this file pins the glue between them.
 *
 * What gets pinned:
 *   1. `perUserMean` / `perUserVariance` edge cases — the
 *      zero-sample and single-sample branches that the downstream
 *      Welch's t gate depends on.
 *   2. `buildUniqueConversionResult` — control vs treatment rows,
 *      confidence computed only on treatment, lift handling when
 *      the control rate is zero.
 *   3. `buildEventCountResult` — mean/variance from running totals
 *      divided by `impressions` (the zero-inflated denominator),
 *      the `confidenceLevel=null` guard when variance is missing,
 *      lift over the control mean.
 *   4. `augmentWithBayesian` — populates `probabilityBeatsControl`
 *      and `expectedLoss` on the treatment row but leaves the
 *      control row untouched; falls back gracefully when a
 *      variant's mean/variance is missing.
 */
class ResultAggregationHelpersTest {

    private val experimentId = UUID.parse("00000000-0000-0000-0000-0000000000e1")
    private val goalId = UUID.parse("00000000-0000-0000-0000-00000000001a")

    private fun goal(
        metricType: GoalMetricType = GoalMetricType.UNIQUE_CONVERSION,
        role: ConversionGoalRole = ConversionGoalRole.PRIMARY,
    ) = ConversionGoal(
        id = goalId, experimentId = experimentId, name = "test",
        metricType = metricType, role = role,
    )

    // -----------------------------------------------------------------
    // perUserMean / perUserVariance — invariants at the edges
    // -----------------------------------------------------------------

    @Test
    fun `perUserMean returns null for n equal to zero`() {
        assertNull(perUserMean(sumEvents = 0, n = 0))
        assertNull(perUserMean(sumEvents = 10, n = 0))
    }

    @Test
    fun `perUserMean divides total events by the full sample size`() {
        assertEquals(0.5, perUserMean(sumEvents = 5, n = 10))
        assertEquals(2.0, perUserMean(sumEvents = 200, n = 100))
    }

    @Test
    fun `perUserVariance returns null for n less than 2`() {
        assertNull(perUserVariance(sumEvents = 0, sumSquaredEvents = 0.0, n = 0))
        assertNull(perUserVariance(sumEvents = 5, sumSquaredEvents = 25.0, n = 1))
    }

    @Test
    fun `perUserVariance computes Bessel-corrected variance from running totals`() {
        // Hand-check: x = [1, 2, 3, 4] → mean = 2.5, Σx = 10,
        // Σx² = 30, Σ(x - x̄)² = 5, variance = 5 / (4 - 1) = 1.666…
        val variance = assertNotNull(
            perUserVariance(sumEvents = 10, sumSquaredEvents = 30.0, n = 4)
        )
        assertEquals(5.0 / 3.0, variance, 1e-9)
    }

    @Test
    fun `perUserVariance clamps tiny-negative floating point results to zero`() {
        // Pathological input that the running-total formula can
        // produce near zero variance with floating-point
        // cancellation. The clamp is the guard against the
        // ExperimentResult init block rejecting negative
        // variance — without it the downstream row construction
        // would throw on certain legitimate inputs.
        //
        // Construct: sum=10, Σx²=25, n=4 → (25 - 100/4) / 3 = 0 / 3 = 0
        // followed by a tiny negative perturbation via sumSquaredEvents.
        val variance = perUserVariance(
            sumEvents = 10,
            sumSquaredEvents = 24.9999999,
            n = 4,
        )
        assertNotNull(variance)
        assertTrue(variance >= 0.0, "variance must never be negative, got $variance")
    }

    // -----------------------------------------------------------------
    // buildUniqueConversionResult
    // -----------------------------------------------------------------

    @Test
    fun `buildUniqueConversionResult control row has null confidence and lift`() {
        val control = GoalCountStats(convertedUsers = 100, sumEvents = 100, sumSquaredEvents = 100.0)
        val result = buildUniqueConversionResult(
            experimentId = experimentId,
            variationKey = "control",
            goalId = goalId,
            impressions = 1000,
            controlImpressions = 1000,
            stats = control,
            controlStats = control,
            isControl = true,
        )
        assertEquals(100, result.conversions)
        assertEquals(0.1, result.conversionRate, 1e-9)
        assertNull(result.confidenceLevel, "control row must not carry a confidence value")
        assertNull(result.liftOverControl, "control row must not carry a lift value")
        // Unique conversion goals leave mean/variance null.
        assertNull(result.mean)
        assertNull(result.variance)
    }

    @Test
    fun `buildUniqueConversionResult treatment row computes rate, confidence, lift`() {
        val control = GoalCountStats(convertedUsers = 100, sumEvents = 100, sumSquaredEvents = 100.0)
        val treatment = GoalCountStats(convertedUsers = 150, sumEvents = 150, sumSquaredEvents = 150.0)
        val result = buildUniqueConversionResult(
            experimentId = experimentId,
            variationKey = "treatment",
            goalId = goalId,
            impressions = 1000,
            controlImpressions = 1000,
            stats = treatment,
            controlStats = control,
            isControl = false,
        )
        assertEquals(150, result.conversions)
        assertEquals(0.15, result.conversionRate, 1e-9)
        val confidence = assertNotNull(result.confidenceLevel)
        assertTrue(confidence > 0.99,
            "5% absolute difference on n=1000 is very significant, got $confidence")
        val lift = assertNotNull(result.liftOverControl)
        assertEquals(50.0, lift, 1e-9)
    }

    @Test
    fun `every treatment arm is compared with the selected control stats`() {
        val selectedControl = GoalCountStats(convertedUsers = 100, sumEvents = 100, sumSquaredEvents = 100.0)
        val better = buildUniqueConversionResult(
            experimentId = experimentId,
            variationKey = "better",
            goalId = goalId,
            impressions = 1000,
            controlImpressions = 1000,
            stats = GoalCountStats(150, 150, 150.0),
            controlStats = selectedControl,
            isControl = false,
        )
        val worse = buildUniqueConversionResult(
            experimentId = experimentId,
            variationKey = "worse",
            goalId = goalId,
            impressions = 1000,
            controlImpressions = 1000,
            stats = GoalCountStats(50, 50, 50.0),
            controlStats = selectedControl,
            isControl = false,
        )

        assertEquals(50.0, assertNotNull(better.liftOverControl), 1e-9)
        assertEquals(-50.0, assertNotNull(worse.liftOverControl), 1e-9)
        assertNotNull(better.confidenceLevel)
        assertNotNull(worse.confidenceLevel)
    }

    @Test
    fun `buildUniqueConversionResult lift is null when control rate is zero`() {
        val zeroControl = GoalCountStats(convertedUsers = 0, sumEvents = 0, sumSquaredEvents = 0.0)
        val treatment = GoalCountStats(convertedUsers = 100, sumEvents = 100, sumSquaredEvents = 100.0)
        val result = buildUniqueConversionResult(
            experimentId = experimentId,
            variationKey = "treatment",
            goalId = goalId,
            impressions = 1000,
            controlImpressions = 1000,
            stats = treatment,
            controlStats = zeroControl,
            isControl = false,
        )
        assertNull(result.liftOverControl,
            "lift must be null when the control rate is zero (division by zero would be infinite)")
    }

    @Test
    fun `buildUniqueConversionResult guards each empty arm independently`() {
        val positive = GoalCountStats(10, 10, 10.0)
        val empty = GoalCountStats.ZERO
        val emptyTreatment = buildUniqueConversionResult(
            experimentId, "treatment", goalId,
            impressions = 0,
            controlImpressions = 100,
            stats = empty,
            controlStats = positive,
            isControl = false,
        )
        val emptyControl = buildUniqueConversionResult(
            experimentId, "treatment", goalId,
            impressions = 100,
            controlImpressions = 0,
            stats = positive,
            controlStats = empty,
            isControl = false,
        )
        assertEquals(0.0, emptyTreatment.conversionRate)
        assertNull(emptyTreatment.confidenceLevel)
        assertNull(emptyControl.confidenceLevel)
        assertNull(emptyControl.liftOverControl)
    }

    // -----------------------------------------------------------------
    // buildEventCountResult
    // -----------------------------------------------------------------

    @Test
    fun `buildEventCountResult computes mean and variance divided by impressions`() {
        // Σx = 200, Σx² = 500, n (impressions) = 100.
        // mean = 200/100 = 2.0
        // variance = (500 - 200²/100) / 99 = (500 - 400) / 99 ≈ 1.0101
        val stats = GoalCountStats(convertedUsers = 80, sumEvents = 200, sumSquaredEvents = 500.0)
        val result = buildEventCountResult(
            experimentId = experimentId,
            variationKey = "control",
            goalId = goalId,
            impressions = 100,
            controlImpressions = 100,
            stats = stats,
            controlStats = stats,
            isControl = true,
        )
        val mean = assertNotNull(result.mean)
        val variance = assertNotNull(result.variance)
        assertEquals(2.0, mean, 1e-9)
        assertEquals(100.0 / 99.0, variance, 1e-9)
        assertEquals(200, result.conversions,
            "event count goal stores the total event count in `conversions` for UI display")
    }

    @Test
    fun `buildEventCountResult treatment row computes Welch confidence + lift`() {
        val control = GoalCountStats(convertedUsers = 100, sumEvents = 100, sumSquaredEvents = 140.0)
        val treatment = GoalCountStats(convertedUsers = 100, sumEvents = 200, sumSquaredEvents = 450.0)
        val result = buildEventCountResult(
            experimentId = experimentId,
            variationKey = "treatment",
            goalId = goalId,
            impressions = 100,
            controlImpressions = 100,
            stats = treatment,
            controlStats = control,
            isControl = false,
        )
        assertNotNull(result.mean)
        val lift = assertNotNull(result.liftOverControl)
        // Control mean = 1.0, treatment mean = 2.0 → +100% lift.
        assertEquals(100.0, lift, 1e-9)
        // Welch's t returns a high confidence on a clean 2x
        // separation with low variance.
        val confidence = assertNotNull(result.confidenceLevel)
        assertTrue(confidence > 0.95)
    }

    @Test
    fun `buildEventCountResult returns null confidence under WELCH_MIN_SAMPLES_PER_ARM`() {
        // Both arms below the minimum sample size. Welch's t is
        // gated off to avoid reporting significance on too-small
        // samples — the confidence column stays null and
        // downstream consumers see "insufficient data".
        val stats = GoalCountStats(convertedUsers = 5, sumEvents = 10, sumSquaredEvents = 30.0)
        val result = buildEventCountResult(
            experimentId = experimentId,
            variationKey = "treatment",
            goalId = goalId,
            impressions = 5,
            controlImpressions = 5,
            stats = stats,
            controlStats = stats,
            isControl = false,
        )
        assertNull(result.confidenceLevel,
            "Welch's t should not run below WELCH_MIN_SAMPLES_PER_ARM, got ${result.confidenceLevel}")
    }

    @Test
    fun `buildEventCountResult handles empty treatment and control independently`() {
        val populated = GoalCountStats(10, 20, 50.0)
        val emptyTreatment = buildEventCountResult(
            experimentId, "treatment", goalId,
            impressions = 0,
            controlImpressions = 100,
            stats = GoalCountStats.ZERO,
            controlStats = populated,
            isControl = false,
        )
        val emptyControl = buildEventCountResult(
            experimentId, "treatment", goalId,
            impressions = 100,
            controlImpressions = 0,
            stats = populated,
            controlStats = GoalCountStats.ZERO,
            isControl = false,
        )
        assertNull(emptyTreatment.mean)
        assertNull(emptyTreatment.confidenceLevel)
        assertNull(emptyControl.confidenceLevel)
        assertNull(emptyControl.liftOverControl)
    }

    // -----------------------------------------------------------------
    // SESSION_DURATION — subject observations remain distinct from exposure
    // -----------------------------------------------------------------

    @Test
    fun `buildSessionDurationResult uses observed subjects for mean variance and confidence`() {
        // Control subject averages: 15 × 10s and 15 × 30s.
        val control = GoalCountStats(
            convertedUsers = 0,
            sumEvents = 0,
            sumSquaredEvents = 0.0,
            observationCount = 30,
            sumValues = 600.0,
            sumSquaredValues = 15_000.0,
        )
        // Treatment subject averages: 15 × 20s and 15 × 60s.
        val treatment = GoalCountStats(
            convertedUsers = 0,
            sumEvents = 0,
            sumSquaredEvents = 0.0,
            observationCount = 30,
            sumValues = 1_200.0,
            sumSquaredValues = 60_000.0,
        )
        val result = buildSessionDurationResult(
            experimentId = experimentId,
            variationKey = "treatment",
            goalId = goalId,
            impressions = 100,
            stats = treatment,
            controlStats = control,
            isControl = false,
        )
        assertEquals(30, result.observationCount)
        assertEquals(100, result.impressions)
        assertEquals(40.0, result.mean)
        assertEquals(12_000.0 / 29.0, result.variance)
        assertEquals(100.0, result.liftOverControl)
        assertNotNull(result.confidenceLevel)
        assertEquals(0, result.conversions, "session duration is not a conversion count")
    }

    @Test
    fun `buildSessionDurationResult preserves a single zero-second observation`() {
        val singleEventSession = GoalCountStats(
            convertedUsers = 0,
            sumEvents = 0,
            sumSquaredEvents = 0.0,
            observationCount = 1,
            sumValues = 0.0,
            sumSquaredValues = 0.0,
        )
        val result = buildSessionDurationResult(
            experimentId = experimentId,
            variationKey = "control",
            goalId = goalId,
            impressions = 5,
            stats = singleEventSession,
            controlStats = singleEventSession,
            isControl = true,
        )
        assertEquals(1, result.observationCount)
        assertEquals(0.0, result.mean)
        assertNull(result.variance, "one observation has no sample variance")
    }

    @Test
    fun `buildSessionDurationResult handles absent observations in either arm`() {
        val populated = GoalCountStats(
            0, 0, 0.0,
            observationCount = 30,
            sumValues = 60.0,
            sumSquaredValues = 150.0,
        )
        val emptyTreatment = buildSessionDurationResult(
            experimentId, "treatment", goalId, 100,
            GoalCountStats.ZERO, populated, isControl = false,
        )
        val emptyControl = buildSessionDurationResult(
            experimentId, "treatment", goalId, 100,
            populated, GoalCountStats.ZERO, isControl = false,
        )
        assertNull(emptyTreatment.mean)
        assertNull(emptyTreatment.confidenceLevel)
        assertNull(emptyControl.confidenceLevel)
        assertNull(emptyControl.liftOverControl)
    }

    @Test
    fun `buildSessionDurationResult handles one-observation variance and zero control mean`() {
        val valid = GoalCountStats(
            0, 0, 0.0,
            observationCount = 30,
            sumValues = 60.0,
            sumSquaredValues = 150.0,
        )
        val single = GoalCountStats(
            0, 0, 0.0,
            observationCount = 1,
            sumValues = 2.0,
            sumSquaredValues = 4.0,
        )
        val zeroMean = GoalCountStats(
            0, 0, 0.0,
            observationCount = 30,
            sumValues = 0.0,
            sumSquaredValues = 0.0,
        )

        assertNull(
            buildSessionDurationResult(
                experimentId, "treatment", goalId, 30, single, valid, isControl = false,
            ).confidenceLevel,
        )
        assertNull(
            buildSessionDurationResult(
                experimentId, "treatment", goalId, 30, valid, single, isControl = false,
            ).confidenceLevel,
        )
        assertNull(
            buildSessionDurationResult(
                experimentId, "treatment", goalId, 30, valid, zeroMean, isControl = false,
            ).liftOverControl,
        )
    }

    // -----------------------------------------------------------------
    // augmentWithBayesian
    // -----------------------------------------------------------------

    @Test
    fun `augmentWithBayesian populates UNIQUE_CONVERSION posterior stats`() {
        val control = GoalCountStats(convertedUsers = 50, sumEvents = 50, sumSquaredEvents = 50.0)
        val treatment = GoalCountStats(convertedUsers = 100, sumEvents = 100, sumSquaredEvents = 100.0)
        val base = buildUniqueConversionResult(
            experimentId = experimentId,
            variationKey = "treatment",
            goalId = goalId,
            impressions = 500,
            controlImpressions = 500,
            stats = treatment,
            controlStats = control,
            isControl = false,
        )
        val augmented = augmentWithBayesian(
            base = base,
            goal = goal(metricType = GoalMetricType.UNIQUE_CONVERSION),
            controlImpressions = 500,
            controlStats = control,
            treatmentImpressions = 500,
            treatmentStats = treatment,
            prior = BayesianPrior.DEFAULT,
            seed = 42L,
        )
        val pWins = assertNotNull(augmented.probabilityBeatsControl,
            "Bayesian augment should populate probabilityBeatsControl")
        assertNotNull(augmented.expectedLoss,
            "Bayesian augment should populate expectedLoss")
        // 10% vs 20% with n=500 each is a strong effect — P(T > C) ≈ 1.
        assertTrue(pWins > 0.999,
            "strong effect should give posterior near 1, got $pWins")
    }

    @Test
    fun `augmentWithBayesian leaves EVENT_COUNT posteriors null when variance is missing`() {
        // Construct a treatment stats whose perUserVariance returns
        // null (n=1 trips the n<2 guard), forcing augmentWithBayesian
        // to short-circuit and leave the base row untouched.
        val stats = GoalCountStats(convertedUsers = 1, sumEvents = 3, sumSquaredEvents = 9.0)
        val base = buildEventCountResult(
            experimentId = experimentId,
            variationKey = "treatment",
            goalId = goalId,
            impressions = 1,
            controlImpressions = 1,
            stats = stats,
            controlStats = stats,
            isControl = false,
        )
        val augmented = augmentWithBayesian(
            base = base,
            goal = goal(metricType = GoalMetricType.EVENT_COUNT),
            controlImpressions = 1,
            controlStats = stats,
            treatmentImpressions = 1,
            treatmentStats = stats,
            prior = BayesianPrior.DEFAULT,
            seed = 7L,
        )
        assertNull(augmented.probabilityBeatsControl,
            "missing variance should short-circuit the posterior computation")
        assertNull(augmented.expectedLoss)
    }

    @Test
    fun `augmentWithBayesian short-circuits when impressions are zero`() {
        val stats = GoalCountStats(0, 0, 0.0)
        val base = buildUniqueConversionResult(
            experimentId = experimentId,
            variationKey = "treatment",
            goalId = goalId,
            impressions = 0,
            controlImpressions = 0,
            stats = stats,
            controlStats = stats,
            isControl = false,
        )
        val augmented = augmentWithBayesian(
            base = base,
            goal = goal(),
            controlImpressions = 0,
            controlStats = stats,
            treatmentImpressions = 0,
            treatmentStats = stats,
            prior = BayesianPrior.DEFAULT,
            seed = 11L,
        )
        assertNull(augmented.probabilityBeatsControl,
            "zero-impression arm must not trigger the Beta posterior path")
    }

    @Test
    fun `augmentWithBayesian short-circuits when only treatment impressions are zero`() {
        val control = GoalCountStats(10, 10, 10.0)
        val base = buildUniqueConversionResult(
            experimentId, "treatment", goalId, 0, 100,
            GoalCountStats.ZERO, control, isControl = false,
        )
        val augmented = augmentWithBayesian(
            base, goal(), 100, control, 0, GoalCountStats.ZERO,
            BayesianPrior.DEFAULT, 12L,
        )
        assertNull(augmented.probabilityBeatsControl)
    }

    @Test
    fun `augmentWithBayesian populates event count and session duration posteriors`() {
        val control = GoalCountStats(
            20, 40, 100.0,
            observationCount = 20,
            sumValues = 40.0,
            sumSquaredValues = 100.0,
        )
        val treatment = GoalCountStats(
            20, 60, 200.0,
            observationCount = 20,
            sumValues = 60.0,
            sumSquaredValues = 200.0,
        )
        val base = buildEventCountResult(
            experimentId, "treatment", goalId, 100, 100,
            treatment, control, isControl = false,
        )
        val eventResult = augmentWithBayesian(
            base, goal(GoalMetricType.EVENT_COUNT), 100, control, 100, treatment,
            BayesianPrior.DEFAULT, 13L,
        )
        val sessionResult = augmentWithBayesian(
            base, goal(GoalMetricType.SESSION_DURATION), 100, control, 100, treatment,
            BayesianPrior.DEFAULT, 14L,
        )
        assertNotNull(eventResult.probabilityBeatsControl)
        assertNotNull(eventResult.expectedLoss)
        assertNotNull(sessionResult.probabilityBeatsControl)
        assertNotNull(sessionResult.expectedLoss)
    }

    @Test
    fun `augmentWithBayesian checks every missing continuous statistic independently`() {
        val valid = GoalCountStats(10, 20, 50.0)
        val empty = GoalCountStats.ZERO
        val single = GoalCountStats(1, 2, 4.0)
        val base = buildEventCountResult(
            experimentId, "treatment", goalId, 10, 10,
            valid, valid, isControl = false,
        )
        data class Case(
            val controlImpressions: Long,
            val controlStats: GoalCountStats,
            val treatmentImpressions: Long,
            val treatmentStats: GoalCountStats,
        )
        val cases = listOf(
            Case(0L, empty, 10L, valid),
            Case(1L, single, 10L, valid),
            Case(10L, valid, 0L, empty),
            Case(10L, valid, 1L, single),
        )

        for (case in cases) {
            val result = augmentWithBayesian(
                base = base,
                goal = goal(GoalMetricType.EVENT_COUNT),
                controlImpressions = case.controlImpressions,
                controlStats = case.controlStats,
                treatmentImpressions = case.treatmentImpressions,
                treatmentStats = case.treatmentStats,
                prior = BayesianPrior.DEFAULT,
                seed = 15L,
            )
            assertNull(result.probabilityBeatsControl)
            assertNull(result.expectedLoss)
        }
    }
}
