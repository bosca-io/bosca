package bosca.experimentation.jobs

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private fun Map<String, CupedResult>.arm(key: String): CupedResult =
    assertNotNull(this[key], "expected an arm result for '$key', got keys=${this.keys}")

/**
 * Tests for the CUPED adjuster. The adjuster is a pure function
 * from (per-user outcomes, per-user covariates) to (adjusted mean,
 * adjusted variance, fitted θ, applied flag). These tests pin the
 * math properties that make CUPED useful and correct:
 *
 *   1. `θ = Cov(y,x) / Var(x)` — the OLS coefficient the adjuster
 *      computes must match the closed-form single-variable
 *      regression.
 *   2. Adjusted mean equals raw mean — the centering on `x̄`
 *      preserves the overall average, so the lift operators see
 *      in the UI matches the ground-truth business metric.
 *   3. Adjusted variance is **strictly less than** raw variance
 *      when the covariate is perfectly correlated with the
 *      outcome. This is the point of CUPED — variance reduction
 *      without bias.
 *   4. Adjusted variance equals raw variance when the covariate
 *      has no relationship with the outcome (θ = 0 in
 *      expectation).
 *   5. Zero-variance covariate → fallback to unadjusted, `applied`
 *      flag set to false, no crash.
 */
class CupedAdjusterTest {

    // -----------------------------------------------------------------
    // θ computation
    // -----------------------------------------------------------------

    @Test
    fun `theta matches closed form cov_y_x over var_x`() {
        // Hand-computed fixture:
        // x = [1, 2, 3, 4, 5], mean = 3, Var(x) = (4+1+0+1+4)/4 = 2.5
        // y = [2, 3, 5, 4, 6], mean = 4
        //   deviations_x = [-2, -1, 0, 1, 2]
        //   deviations_y = [-2, -1, 1, 0, 2]
        //   Cov(x,y)     = ((-2)(-2) + (-1)(-1) + 0 + 0 + 4) / 4 = 9 / 4 = 2.25
        //   θ            = 2.25 / 2.5 = 0.9
        val y = doubleArrayOf(2.0, 3.0, 5.0, 4.0, 6.0)
        val x = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0)
        val theta = computeCupedTheta(y, x)
        assertNotNull(theta)
        assertEquals(0.9, theta, 1e-9)
    }

    @Test
    fun `theta is null for zero-variance covariate`() {
        val y = doubleArrayOf(1.0, 2.0, 3.0, 4.0)
        val x = doubleArrayOf(5.0, 5.0, 5.0, 5.0)
        val theta = computeCupedTheta(y, x)
        assertEquals(null, theta,
            "zero-variance covariate must return null (caller falls back to unadjusted)")
    }

    @Test
    fun `theta validates dimensions and handles samples smaller than two`() {
        assertFailsWith<IllegalArgumentException> {
            computeCupedTheta(doubleArrayOf(1.0), doubleArrayOf())
        }
        assertEquals(0.0, computeCupedTheta(doubleArrayOf(), doubleArrayOf()))
        assertEquals(0.0, computeCupedTheta(doubleArrayOf(1.0), doubleArrayOf(2.0)))
    }

    @Test
    fun `theta of zero when outcome uncorrelated with covariate`() {
        // Anti-correlated pairs cancel out exactly.
        val y = doubleArrayOf(1.0, 2.0, 1.0, 2.0, 1.0, 2.0)
        val x = doubleArrayOf(1.0, 1.0, 2.0, 2.0, 3.0, 3.0)
        val theta = computeCupedTheta(y, x)
        assertNotNull(theta)
        assertEquals(0.0, theta, 1e-9)
    }

    // -----------------------------------------------------------------
    // Mean preservation
    // -----------------------------------------------------------------

    @Test
    fun `adjusted mean equals raw mean regardless of theta`() {
        // Because the adjustment is centered on x̄, the sum of
        // residuals equals the sum of the raw outcomes. Even
        // contrived values preserve this invariant.
        val y = doubleArrayOf(10.0, 20.0, 30.0, 40.0, 50.0)
        val x = doubleArrayOf(1.0, 3.0, 5.0, 7.0, 9.0)
        val adjusted = adjustOutcomes(y, x, theta = 2.0)
        val rawMean = y.average()
        val adjustedMean = adjusted.average()
        assertEquals(rawMean, adjustedMean, 1e-9,
            "adjustOutcomes must preserve the overall mean")
    }

    @Test
    fun `adjustOutcomes requires aligned samples`() {
        assertFailsWith<IllegalArgumentException> {
            adjustOutcomes(doubleArrayOf(1.0), doubleArrayOf(), theta = 1.0)
        }
    }

    @Test
    fun `mean and variance handle empty and singleton samples`() {
        assertEquals(0.0 to 0.0, meanAndVariance(doubleArrayOf()))
        assertEquals(4.0 to 0.0, meanAndVariance(doubleArrayOf(4.0)))
    }

    // -----------------------------------------------------------------
    // Variance reduction (the whole point of CUPED)
    // -----------------------------------------------------------------

    @Test
    fun `perfect correlation strictly reduces variance`() {
        // y = 2x + noise with the noise set to zero for total
        // determinism. θ should come out as 2 and the adjusted
        // residuals should be identically zero — so adjusted
        // variance is 0, dramatically smaller than the raw
        // variance of y.
        val y = doubleArrayOf(2.0, 4.0, 6.0, 8.0, 10.0)
        val x = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0)
        val armOut = mapOf("control" to mapOf("u1" to 2.0, "u2" to 4.0, "u3" to 6.0, "u4" to 8.0, "u5" to 10.0))
        val armCov = mapOf("control" to mapOf("u1" to 1.0, "u2" to 2.0, "u3" to 3.0, "u4" to 4.0, "u5" to 5.0))
        val result = adjustAllArms(armOut, armCov).arm("control")

        val (_, rawVariance) = meanAndVariance(y)
        assertTrue(result.applied, "covariate has variance; adjustment should apply")
        assertEquals(2.0, result.theta, 1e-9)
        assertTrue(result.adjustedVariance < rawVariance,
            "adjusted variance ${result.adjustedVariance} must be strictly less than raw variance $rawVariance")
        assertTrue(result.adjustedVariance < 1e-9,
            "perfect correlation should collapse variance to ~0, got ${result.adjustedVariance}")
    }

    @Test
    fun `no correlation leaves variance unchanged`() {
        // θ ≈ 0, so the adjustment leaves everything essentially
        // untouched. We test on the full adjustAllArms path to
        // exercise the public API.
        val armOut = mapOf("control" to mapOf(
            "u1" to 1.0, "u2" to 2.0, "u3" to 1.0, "u4" to 2.0, "u5" to 1.0, "u6" to 2.0,
        ))
        val armCov = mapOf("control" to mapOf(
            "u1" to 1.0, "u2" to 1.0, "u3" to 2.0, "u4" to 2.0, "u5" to 3.0, "u6" to 3.0,
        ))
        val result = adjustAllArms(armOut, armCov).arm("control")
        val rawValues = doubleArrayOf(1.0, 2.0, 1.0, 2.0, 1.0, 2.0)
        val (_, rawVariance) = meanAndVariance(rawValues)
        assertTrue(abs(result.theta) < 1e-9, "uncorrelated → θ ≈ 0, got ${result.theta}")
        assertEquals(rawVariance, result.adjustedVariance, 1e-9,
            "uncorrelated covariate → adjusted variance ≈ raw variance")
    }

    // -----------------------------------------------------------------
    // Zero-variance fallback
    // -----------------------------------------------------------------

    @Test
    fun `zero-variance covariate falls back to unadjusted values`() {
        val armOut = mapOf("control" to mapOf("u1" to 10.0, "u2" to 20.0, "u3" to 30.0))
        val armCov = mapOf("control" to mapOf("u1" to 5.0, "u2" to 5.0, "u3" to 5.0))
        val result = adjustAllArms(armOut, armCov).arm("control")
        assertFalse(result.applied, "applied flag must be false on fallback")
        assertEquals(20.0, result.adjustedMean, 1e-9, "fallback should report raw mean")
        val (_, rawVariance) = meanAndVariance(doubleArrayOf(10.0, 20.0, 30.0))
        assertEquals(rawVariance, result.adjustedVariance, 1e-9)
    }

    @Test
    fun `adjustArm validates aligned samples`() {
        assertFailsWith<IllegalArgumentException> {
            adjustArm(doubleArrayOf(1.0), doubleArrayOf(), 1.0, 0.0)
        }
    }

    @Test
    fun `arms present on only one side are retained with zero-filled values`() {
        val results = adjustAllArms(
            armsOutcomes = mapOf("outcomes-only" to mapOf("u1" to 3.0)),
            armsCovariates = mapOf("covariates-only" to mapOf("u2" to 2.0)),
        )

        assertEquals(setOf("outcomes-only", "covariates-only"), results.keys)
        assertTrue(results.arm("outcomes-only").adjustedMean.isFinite())
        assertTrue(results.arm("covariates-only").adjustedMean.isFinite())
        assertTrue(results.values.all { it.applied })
    }

    @Test
    fun `no arms produces an empty adjustment`() {
        assertTrue(adjustAllArms(emptyMap(), emptyMap()).isEmpty())
    }

    // -----------------------------------------------------------------
    // Missing pre-period data is treated as zero
    // -----------------------------------------------------------------

    @Test
    fun `users missing pre-period data get covariate of zero`() {
        // Two users in the outcomes map; only one has a
        // covariate. The second user should be treated as having
        // a covariate value of 0 (zero-inflated view).
        val armOut = mapOf("control" to mapOf("u1" to 5.0, "u2" to 7.0))
        val armCov = mapOf("control" to mapOf("u1" to 3.0))
        val result = adjustAllArms(armOut, armCov).arm("control")
        // Without the fill-to-zero, adjustAllArms would implicitly
        // drop u2 from the OLS and the theta would be undefined
        // on a single data point; the zero fill lets u2 keep its
        // raw outcome essentially unchanged.
        assertTrue(result.adjustedMean > 0.0)
    }

    // -----------------------------------------------------------------
    // Multi-arm end-to-end
    // -----------------------------------------------------------------

    @Test
    fun `adjustAllArms preserves lift direction for balanced covariates`() {
        // Realistic CUPED scenario: both arms have overlapping
        // covariate ranges (randomization worked), and the
        // treatment has a real +3 effect on top of the linear
        // relationship y ≈ 2x. After CUPED the noise in y
        // attributable to x should vanish, leaving the adjusted
        // treatment mean ≈ adjusted control mean + 3.
        //
        // Control: y = 2x, x in {1, 2, 3, 4}
        // Treatment: y = 2x + 3, x in {1, 2, 3, 4}
        val control = mapOf(
            "c1" to 2.0, "c2" to 4.0, "c3" to 6.0, "c4" to 8.0,
        )
        val controlCov = mapOf(
            "c1" to 1.0, "c2" to 2.0, "c3" to 3.0, "c4" to 4.0,
        )
        val treatment = mapOf(
            "t1" to 5.0, "t2" to 7.0, "t3" to 9.0, "t4" to 11.0,
        )
        val treatmentCov = mapOf(
            "t1" to 1.0, "t2" to 2.0, "t3" to 3.0, "t4" to 4.0,
        )

        val results = adjustAllArms(
            armsOutcomes = mapOf("control" to control, "treatment" to treatment),
            armsCovariates = mapOf("control" to controlCov, "treatment" to treatmentCov),
        )
        val controlResult = results.arm("control")
        val treatmentResult = results.arm("treatment")
        assertTrue(controlResult.applied)
        assertTrue(treatmentResult.applied)
        // θ ≈ 2 (the slope of y on x), treatment effect ≈ +3 adds
        // a constant to the intercept but does not change the slope.
        assertEquals(2.0, controlResult.theta, 1e-9)
        // Adjusted control mean = raw control mean = 5.0 (because
        // both arms share the same x values, the global x̄ equals
        // each arm's x̄, which means the centering collapses to
        // zero per-arm and the adjusted mean equals the raw mean).
        assertEquals(5.0, controlResult.adjustedMean, 1e-9)
        assertEquals(8.0, treatmentResult.adjustedMean, 1e-9)
        // Lift is preserved: 8 - 5 = 3.0 matches the pre-CUPED
        // lift of 3.0. That is the property CUPED guarantees for
        // properly-randomized experiments.
        val rawLift = treatment.values.average() - control.values.average()
        val adjLift = treatmentResult.adjustedMean - controlResult.adjustedMean
        assertEquals(rawLift, adjLift, 1e-9,
            "CUPED must preserve the arm lift for balanced covariates")
    }
}
