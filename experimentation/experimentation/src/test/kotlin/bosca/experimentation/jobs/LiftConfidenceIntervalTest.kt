package bosca.experimentation.jobs

import bosca.experimentation.model.ExperimentResult
import bosca.experimentation.model.GoalMetricType
import bosca.serialization.UUID
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies [computeLiftWithCi] against hand-derived fixtures. The math
 * is two distinct paths — Wald-on-proportions and Welch-on-means — so
 * each path gets:
 *   1. a positive-control fixture with a known scipy/by-hand expected value,
 *   2. a sign/symmetry check on the bounds,
 *   3. a CI-widens-with-Bonferroni-alpha invariant,
 *   4. a degenerate-input null-out check.
 *
 * Tolerances are tight (≤ 0.05 percentage points on the bounds): drift
 * past that means the t critical value or the standard-error formula
 * has regressed, both of which would silently push reported lift CIs
 * away from what the analyst expects on the page.
 */
class LiftConfidenceIntervalTest {

    private val expId = UUID.NIL
    private val goalId = UUID.NIL

    private fun proportionResult(key: String, n: Long, conversions: Long): ExperimentResult =
        ExperimentResult(
            experimentId = expId,
            variationKey = key,
            goalId = goalId,
            impressions = n,
            conversions = conversions,
            conversionRate = if (n > 0) conversions.toDouble() / n else 0.0,
        )

    private fun meanResult(key: String, n: Long, mean: Double, variance: Double): ExperimentResult =
        ExperimentResult(
            experimentId = expId,
            variationKey = key,
            goalId = goalId,
            impressions = n,
            conversions = (mean * n).toLong(),
            conversionRate = 0.0,
            mean = mean,
            variance = variance,
        )

    private fun assertClose(expected: Double, actual: Double?, tolerance: Double, label: String) {
        assertNotNull(actual, "$label: expected non-null bound")
        assertTrue(
            abs(expected - actual) <= tolerance,
            "$label: expected $expected ± $tolerance, got $actual",
        )
    }

    // -----------------------------------------------------------------
    // UNIQUE_CONVERSION (proportion) CI
    // -----------------------------------------------------------------

    @Test
    fun `proportion lift CI matches hand-derived delta-method bounds`() {
        // pC=0.10, pT=0.12, nC=nT=2000. Delta method on ratio r = pT/pC.
        //   Var(pC) = 0.10*0.90/2000 = 4.5e-5
        //   Var(pT) = 0.12*0.88/2000 = 5.28e-5
        //   r = 1.2; Var(r) ≈ r^2*(Var(pT)/pT^2 + Var(pC)/pC^2)
        //              = 1.44 * (5.28e-5/0.0144 + 4.5e-5/0.01)
        //              = 1.44 * (0.003666... + 0.0045)
        //              ≈ 0.011760
        //   SE(r) ≈ 0.108444
        //   df = 3998, t₀.₉₇₅ ≈ 1.96057
        //   half-width on r ≈ 1.96057 * 0.108444 ≈ 0.21261
        //   point lift = 20.0%, CI ≈ [-1.261%, +41.261%]
        val control = proportionResult("c", n = 2000, conversions = 200)
        val treatment = proportionResult("t", n = 2000, conversions = 240)
        val (lift, lower, upper) = computeLiftWithCi(
            metricType = GoalMetricType.UNIQUE_CONVERSION,
            control = control,
            treatment = treatment,
            alpha = 0.05,
        )
        assertClose(expected = 20.0, actual = lift, tolerance = 1e-9, label = "point lift")
        assertClose(expected = -1.261, actual = lower, tolerance = 0.05, label = "CI lower")
        assertClose(expected = 41.261, actual = upper, tolerance = 0.05, label = "CI upper")
    }

    @Test
    fun `proportion lift CI is centered on the point lift`() {
        // For symmetric Wald CIs, (upper + lower) / 2 must equal the
        // reported point lift. If this is off, the t critical value is
        // being applied asymmetrically — a wiring bug, not a math one.
        val control = proportionResult("c", n = 5000, conversions = 250)   // 5%
        val treatment = proportionResult("t", n = 5000, conversions = 300) // 6%
        val (lift, lower, upper) = computeLiftWithCi(
            GoalMetricType.UNIQUE_CONVERSION, control, treatment, alpha = 0.05,
        )
        assertNotNull(lift); assertNotNull(lower); assertNotNull(upper)
        assertTrue(
            abs(((lower + upper) / 2.0) - lift) < 1e-9,
            "expected midpoint=$lift, got ${(lower + upper) / 2.0}",
        )
    }

    @Test
    fun `proportion lift CI widens when alpha shrinks (Bonferroni invariant)`() {
        // The whole point of parameterizing alpha is so the CI bounds
        // widen in step with the multiple-testing correction. If they
        // don't, the report can claim "ship — significant!" while
        // visually showing a CI that crosses zero.
        val control = proportionResult("c", n = 2000, conversions = 200)
        val treatment = proportionResult("t", n = 2000, conversions = 240)
        val (_, lower05, upper05) = computeLiftWithCi(
            GoalMetricType.UNIQUE_CONVERSION, control, treatment, alpha = 0.05,
        )
        val (_, lower01, upper01) = computeLiftWithCi(
            GoalMetricType.UNIQUE_CONVERSION, control, treatment, alpha = 0.01,
        )
        assertNotNull(lower05); assertNotNull(upper05)
        assertNotNull(lower01); assertNotNull(upper01)
        assertTrue(lower01 < lower05, "tighter alpha should push lower bound lower; got $lower01 vs $lower05")
        assertTrue(upper01 > upper05, "tighter alpha should push upper bound higher; got $upper01 vs $upper05")
    }

    @Test
    fun `zero control rate returns null bounds`() {
        // Lift is undefined when the denominator is zero — must return
        // null rather than divide-by-zero or report Infinity.
        val control = proportionResult("c", n = 1000, conversions = 0)
        val treatment = proportionResult("t", n = 1000, conversions = 50)
        val (lift, lower, upper) = computeLiftWithCi(
            GoalMetricType.UNIQUE_CONVERSION, control, treatment, alpha = 0.05,
        )
        assertNull(lift); assertNull(lower); assertNull(upper)
    }

    @Test
    fun `proportion lift keeps point estimate when samples are too small for bounds`() {
        val control = proportionResult("c", n = 1, conversions = 1)
        val treatment = proportionResult("t", n = 1, conversions = 1)

        val (lift, lower, upper) = computeLiftWithCi(
            GoalMetricType.UNIQUE_CONVERSION, control, treatment, alpha = 0.05,
        )

        assertNotNull(lift)
        assertNull(lower)
        assertNull(upper)
    }

    @Test
    fun `proportion lift evaluates each insufficient-data guard independently`() {
        val validControl = proportionResult("c", n = 100, conversions = 20)
        val validTreatment = proportionResult("t", n = 100, conversions = 30)

        val treatmentTooSmall = computeLiftWithCi(
            GoalMetricType.UNIQUE_CONVERSION,
            validControl,
            proportionResult("t", n = 1, conversions = 1),
            alpha = 0.05,
        )
        assertNull(treatmentTooSmall.second)

        val zeroTreatment = computeLiftWithCi(
            GoalMetricType.UNIQUE_CONVERSION,
            validControl,
            proportionResult("t", n = 100, conversions = 0),
            alpha = 0.05,
        )
        assertNull(zeroTreatment.second)

        val nonFinite = computeLiftWithCi(
            GoalMetricType.UNIQUE_CONVERSION,
            validControl,
            validTreatment.copy(conversionRate = Double.NaN),
            alpha = 0.05,
        )
        assertNull(nonFinite.second)
    }

    // -----------------------------------------------------------------
    // EVENT_COUNT (mean) CI
    // -----------------------------------------------------------------

    @Test
    fun `event count lift CI matches hand-derived delta-method bounds`() {
        // mC=2.0, vC=4.0, nC=500; mT=2.4, vT=5.0, nT=500.
        // Delta method on r = mT/mC. Var(meanX) = vX/nX.
        //   Var(mC) = 4/500 = 0.008; Var(mT) = 5/500 = 0.010
        //   r = 1.2
        //   Var(r) ≈ r^2 * ( Var(mT)/mT^2 + Var(mC)/mC^2 )
        //         = 1.44 * ( 0.010/5.76 + 0.008/4 )
        //         = 1.44 * ( 0.00173611 + 0.002 )
        //         ≈ 0.00538
        //   SE(r) ≈ 0.07335
        //   Welch df ≈ 985.86, t₀.₉₇₅(986) ≈ 1.9624
        //   half-width ≈ 1.9624 * 0.07335 ≈ 0.14394
        //   CI ≈ [+5.606%, +34.394%]
        val control = meanResult("c", n = 500, mean = 2.0, variance = 4.0)
        val treatment = meanResult("t", n = 500, mean = 2.4, variance = 5.0)
        val (lift, lower, upper) = computeLiftWithCi(
            GoalMetricType.EVENT_COUNT, control, treatment, alpha = 0.05,
        )
        assertClose(expected = 20.0, actual = lift, tolerance = 1e-9, label = "point lift")
        assertClose(expected = 5.606, actual = lower, tolerance = 0.05, label = "CI lower")
        assertClose(expected = 34.394, actual = upper, tolerance = 0.05, label = "CI upper")
    }

    @Test
    fun `event count lift CI is centered on the point lift`() {
        val control = meanResult("c", n = 800, mean = 1.5, variance = 2.25)
        val treatment = meanResult("t", n = 800, mean = 1.65, variance = 2.40)
        val (lift, lower, upper) = computeLiftWithCi(
            GoalMetricType.EVENT_COUNT, control, treatment, alpha = 0.05,
        )
        assertNotNull(lift); assertNotNull(lower); assertNotNull(upper)
        assertTrue(
            abs(((lower + upper) / 2.0) - lift) < 1e-9,
            "expected midpoint=$lift, got ${(lower + upper) / 2.0}",
        )
    }

    @Test
    fun `event count zero control mean returns null bounds`() {
        val control = meanResult("c", n = 500, mean = 0.0, variance = 1.0)
        val treatment = meanResult("t", n = 500, mean = 0.5, variance = 1.0)
        val (lift, lower, upper) = computeLiftWithCi(
            GoalMetricType.EVENT_COUNT, control, treatment, alpha = 0.05,
        )
        assertNull(lift); assertNull(lower); assertNull(upper)
    }

    @Test
    fun `event count keeps point estimate below the Welch sample floor`() {
        val control = meanResult("c", n = 10, mean = 1.0, variance = 1.0)
        val treatment = meanResult("t", n = 10, mean = 1.5, variance = 1.0)

        val (lift, lower, upper) = computeLiftWithCi(
            GoalMetricType.EVENT_COUNT, control, treatment, alpha = 0.05,
        )

        assertNotNull(lift)
        assertNull(lower)
        assertNull(upper)
    }

    @Test
    fun `continuous lift handles missing means and each sample guard`() {
        val validControl = meanResult("c", n = 100, mean = 2.0, variance = 1.0)
        val validTreatment = meanResult("t", n = 100, mean = 3.0, variance = 1.0)

        assertNull(
            computeLiftWithCi(
                GoalMetricType.EVENT_COUNT,
                validControl.copy(mean = null),
                validTreatment,
                0.05,
            ).first,
        )
        assertNull(
            computeLiftWithCi(
                GoalMetricType.EVENT_COUNT,
                validControl,
                validTreatment.copy(mean = null),
                0.05,
            ).first,
        )
        assertNull(
            computeLiftWithCi(
                GoalMetricType.EVENT_COUNT,
                validControl,
                meanResult("t", n = 10, mean = 3.0, variance = 1.0),
                0.05,
            ).second,
        )
        assertNull(
            computeLiftWithCi(
                GoalMetricType.EVENT_COUNT,
                validControl,
                meanResult("t", n = 100, mean = 0.0, variance = 1.0),
                0.05,
            ).second,
        )
    }

    @Test
    fun `continuous lift uses adjusted variance and session observation counts`() {
        val control = meanResult("c", n = 100, mean = 2.0, variance = 100.0).copy(
            adjustedVariance = 1.0,
            observationCount = 50,
        )
        val treatment = meanResult("t", n = 100, mean = 3.0, variance = 0.0).copy(
            variance = null,
            adjustedVariance = 1.5,
            observationCount = 60,
        )

        assertNotNull(computeLiftWithCi(GoalMetricType.EVENT_COUNT, control, treatment, 0.05).second)
        assertNotNull(computeLiftWithCi(GoalMetricType.SESSION_DURATION, control, treatment, 0.05).second)
    }

    @Test
    fun `continuous lift handles a small control arm and absent variance estimates`() {
        val validTreatment = meanResult("t", n = 100, mean = 3.0, variance = 1.0)
        val smallControl = meanResult("c", n = 10, mean = 2.0, variance = 1.0)
        val smallResult = computeLiftWithCi(
            GoalMetricType.EVENT_COUNT, smallControl, validTreatment, 0.05,
        )
        assertNotNull(smallResult.first)
        assertNull(smallResult.second)

        val noVarianceControl = meanResult("c", n = 100, mean = 2.0, variance = 1.0).copy(variance = null)
        val noVarianceTreatment = validTreatment.copy(variance = null)
        val noVarianceResult = computeLiftWithCi(
            GoalMetricType.EVENT_COUNT, noVarianceControl, noVarianceTreatment, 0.05,
        )
        assertNotNull(noVarianceResult.first)
        assertNotNull(noVarianceResult.second)
    }

    @Test
    fun `continuous lift drops non-finite confidence bounds`() {
        val control = meanResult("c", n = 100, mean = 2.0, variance = Double.POSITIVE_INFINITY)
        val treatment = meanResult("t", n = 100, mean = 3.0, variance = 1.0)
        val result = computeLiftWithCi(GoalMetricType.EVENT_COUNT, control, treatment, 0.05)
        assertNotNull(result.first)
        assertNull(result.second)
        assertNull(result.third)
    }

    @Test
    fun `Welch–Satterthwaite df matches textbook formula`() {
        // Worked example from a standard stats textbook (Welch 1947, NIST
        // engineering stats handbook §1.3.5.3): vC=4, nC=15; vT=9, nT=25.
        //   a = 4/15 ≈ 0.26667; b = 9/25 = 0.36
        //   num = (a + b)^2 = 0.62667^2 ≈ 0.392712
        //   den = (a^2)/14 + (b^2)/24
        //       = 0.071111/14 + 0.1296/24
        //       ≈ 0.0050794 + 0.0054
        //       ≈ 0.0104794
        //   df ≈ 37.475
        val df = welchSatterthwaiteDf(vC = 4.0, nC = 15, vT = 9.0, nT = 25)
        assertTrue(
            abs(df - 37.475) < 0.01,
            "expected Welch–Satterthwaite df ≈ 37.475, got $df",
        )
    }

    @Test
    fun `Welch–Satterthwaite df is symmetric in the two arms`() {
        // The formula is symmetric in (vC,nC) ↔ (vT,nT). If a refactor
        // ever introduces asymmetry, the t critical value depends on
        // which arm is "control" — and since which arm is control is a
        // sort-by-key convention, results would silently swing on
        // unrelated edits.
        val ab = welchSatterthwaiteDf(vC = 2.0, nC = 100, vT = 5.0, nT = 200)
        val ba = welchSatterthwaiteDf(vC = 5.0, nC = 200, vT = 2.0, nT = 100)
        assertTrue(abs(ab - ba) < 1e-12, "Welch df must be symmetric, got $ab vs $ba")
    }

    @Test
    fun `Welch–Satterthwaite falls back when both variances are zero`() {
        assertTrue(abs(welchSatterthwaiteDf(0.0, 10, 0.0, 20) - 28.0) < 1e-12)
    }

    @Test
    fun `Welch–Satterthwaite covers small and non-finite fallbacks`() {
        assertTrue(abs(welchSatterthwaiteDf(1.0, 1, 1.0, 10) - 9.0) < 1e-12)
        assertTrue(abs(welchSatterthwaiteDf(1.0, 10, 1.0, 1) - 9.0) < 1e-12)
        assertTrue(welchSatterthwaiteDf(Double.POSITIVE_INFINITY, 10, 1.0, 10).isFinite())
        assertTrue(
            welchSatterthwaiteDf(Double.MAX_VALUE, 10, -Double.MAX_VALUE, 10).isFinite(),
        )
    }

    @Test
    fun `coverage comparison treats zero impression arms as zero coverage`() {
        assertTrue(coverageDifferenceExceeds(10, 10, 0, 0))
        assertTrue(coverageDifferenceExceeds(0, 0, 10, 10))
        assertTrue(!coverageDifferenceExceeds(0, 0, 0, 0))
    }
}
