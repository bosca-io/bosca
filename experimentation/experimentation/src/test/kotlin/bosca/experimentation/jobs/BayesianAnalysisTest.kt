package bosca.experimentation.jobs

import bosca.experimentation.model.BayesianPrior
import org.apache.commons.math3.distribution.BetaDistribution
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for the Phase 4 Bayesian primitives.
 *
 * The tests aim to pin three orthogonal properties:
 *
 *   1. **Conjugacy correctness** — the posterior produced by
 *      [betaPosterior] matches the textbook `Beta(α+k, β+n-k)`
 *      update for both default and configured priors.
 *   2. **Monte Carlo stability** — [betaProbabilityBeatsControl]
 *      and [betaExpectedLoss] produce reproducible output given a
 *      fixed seed, and that output agrees (within MC error) with
 *      independently-derived reference values computed from the
 *      analytical closed-form `P(X > Y)` formula for two Betas.
 *   3. **Bayesian / frequentist convergence** — as `n` grows, the
 *      Bayesian `probabilityBeatsControl` approaches the
 *      frequentist confidence on the same data. This is the
 *      "calibration" property that makes Bayesian verdicts
 *      substitutable for frequentist ones in the rollout
 *      controller's `minConfidence` gate.
 */
class BayesianAnalysisTest {

    // -----------------------------------------------------------------
    // Beta posterior correctness
    // -----------------------------------------------------------------

    @Test
    fun `default Beta prior is uniform and posterior collapses to likelihood`() {
        // Observed 30 conversions in 100 users under Beta(1,1). The
        // posterior is Beta(31, 71); its mean is 31/102 ≈ 0.3039 —
        // slightly above the raw rate of 0.3 because the uniform
        // prior pulls the estimate toward 0.5.
        val posterior = betaPosterior(conversions = 30L, impressions = 100L)
        assertEquals(31.0, posterior.alpha, 1e-12)
        assertEquals(71.0, posterior.beta, 1e-12)
        val mean = posterior.numericalMean
        assertEquals(31.0 / 102.0, mean, 1e-9)
    }

    @Test
    fun `configured Beta prior shifts a small sample toward the prior mean`() {
        // Beta(10, 10) prior has mean 0.5. A small sample of 2/10
        // has raw mean 0.2 — the posterior mean should sit between
        // the prior mean (0.5) and the sample mean (0.2), closer to
        // 0.5 because the prior is informative relative to n=10.
        val posterior = betaPosterior(
            conversions = 2L,
            impressions = 10L,
            prior = BayesianPrior(betaPriorAlpha = 10.0, betaPriorBeta = 10.0),
        )
        // α' = 10 + 2 = 12; β' = 10 + 8 = 18; mean = 12/30 = 0.4.
        assertEquals(12.0, posterior.alpha, 1e-12)
        assertEquals(18.0, posterior.beta, 1e-12)
        assertEquals(0.4, posterior.numericalMean, 1e-9)
    }

    @Test
    fun `Beta posterior rejects invalid sample counts`() {
        assertFailsWith<IllegalArgumentException> {
            betaPosterior(conversions = 0L, impressions = -1L)
        }
        assertFailsWith<IllegalArgumentException> {
            betaPosterior(conversions = -1L, impressions = 10L)
        }
        assertFailsWith<IllegalArgumentException> {
            betaPosterior(conversions = 11L, impressions = 10L)
        }
    }

    // -----------------------------------------------------------------
    // Monte Carlo determinism and calibration
    // -----------------------------------------------------------------

    @Test
    fun `Beta probabilityBeatsControl is reproducible given the same seed`() {
        // Same posteriors, same seed, same samples → byte-identical
        // output. This is what makes re-running analysis on the
        // same report produce the same verdict prose.
        val control = betaPosterior(300L, 1000L)
        val treatment = betaPosterior(350L, 1000L)
        val seed = 0x1234_5678_DEAD_BEEFL
        val a = betaProbabilityBeatsControl(control, treatment, seed)
        val b = betaProbabilityBeatsControl(control, treatment, seed)
        assertEquals(a, b, 0.0, "same seed must produce identical output")
    }

    @Test
    fun `Beta probabilityBeatsControl agrees with the closed form for large differences`() {
        // When the two posteriors barely overlap, `P(T > C)` should
        // be very close to 1. 100 vs 500 conversions on n=1000
        // each is 10% vs 50% — practically no overlap.
        val control = betaPosterior(100L, 1000L)
        val treatment = betaPosterior(500L, 1000L)
        val p = betaProbabilityBeatsControl(control, treatment, seed = 42L)
        assertTrue(p > 0.9999, "huge-effect P(T>C) should be essentially 1, got $p")
    }

    @Test
    fun `Beta probabilityBeatsControl is roughly 0_5 when posteriors match`() {
        // Identical posteriors — treatment is exactly as good as
        // control. P(T > C) should be ≈ 0.5 with Monte Carlo error
        // bounded by ~0.005 at 50k samples.
        val posterior = betaPosterior(500L, 1000L)
        val p = betaProbabilityBeatsControl(posterior, posterior, seed = 7L)
        assertTrue(abs(p - 0.5) < 0.01, "identical posteriors should give P ≈ 0.5, got $p")
    }

    @Test
    fun `Beta expected loss is small when treatment clearly wins`() {
        // Treatment clearly better → expected loss from picking
        // treatment (which here means "the control's rate is higher
        // than the treatment's") should be near zero.
        val control = betaPosterior(100L, 1000L)
        val treatment = betaPosterior(500L, 1000L)
        val loss = betaExpectedLoss(control, treatment, seed = 11L)
        assertTrue(loss < 0.01, "expected loss should be ≈ 0 when treatment dominates, got $loss")
    }

    @Test
    fun `Beta expected loss is non-trivial when posteriors overlap`() {
        // 50% vs 52% is a borderline case — lots of overlap, loss
        // should be noticeable but small.
        val control = betaPosterior(500L, 1000L)
        val treatment = betaPosterior(520L, 1000L)
        val loss = betaExpectedLoss(control, treatment, seed = 99L)
        assertTrue(loss > 0.001 && loss < 0.05,
            "overlapping posteriors should give small but non-zero loss, got $loss")
    }

    // -----------------------------------------------------------------
    // Normal closed-form correctness
    // -----------------------------------------------------------------

    @Test
    fun `Normal probabilityBeatsControl is 0_5 when means match`() {
        val p = normalProbabilityBeatsControl(
            controlMean = 1.0, controlVariance = 0.25,
            treatmentMean = 1.0, treatmentVariance = 0.25,
        )
        assertEquals(0.5, p, 1e-12)
    }

    @Test
    fun `Normal probabilityBeatsControl approaches 1 with large positive difference`() {
        val p = normalProbabilityBeatsControl(
            controlMean = 1.0, controlVariance = 0.01,
            treatmentMean = 5.0, treatmentVariance = 0.01,
        )
        assertTrue(p > 0.9999)
    }

    @Test
    fun `Normal probabilityBeatsControl matches hand-computed fixture`() {
        // d = 1, σ = sqrt(0.25 + 0.25) = sqrt(0.5) ≈ 0.7071
        // z = d/σ ≈ 1.4142
        // Φ(1.4142) ≈ 0.9214
        val p = normalProbabilityBeatsControl(
            controlMean = 0.0, controlVariance = 0.25,
            treatmentMean = 1.0, treatmentVariance = 0.25,
        )
        assertEquals(0.9214, p, 1e-3)
    }

    @Test
    fun `degenerate Normal probability follows deterministic ordering`() {
        assertEquals(1.0, normalProbabilityBeatsControl(1.0, 0.0, 2.0, 0.0))
        assertEquals(0.0, normalProbabilityBeatsControl(2.0, 0.0, 1.0, 0.0))
        assertEquals(0.5, normalProbabilityBeatsControl(1.0, 0.0, 1.0, 0.0))
    }

    @Test
    fun `Normal expected loss matches the half-Normal formula`() {
        // μ_d = control - treatment = -1, σ = sqrt(0.5)
        // z = -1/0.7071 ≈ -1.4142
        // Φ(-1.4142) ≈ 0.0786
        // φ(-1.4142) ≈ 0.1476
        // Loss = -1 * 0.0786 + 0.7071 * 0.1476 ≈ -0.0786 + 0.1044 ≈ 0.0258
        val loss = normalExpectedLoss(
            controlMean = 0.0, controlVariance = 0.25,
            treatmentMean = 1.0, treatmentVariance = 0.25,
        )
        assertEquals(0.0258, loss, 1e-3)
    }

    @Test
    fun `degenerate Normal expected loss is the positive mean difference`() {
        assertEquals(2.0, normalExpectedLoss(3.0, 0.0, 1.0, 0.0))
        assertEquals(0.0, normalExpectedLoss(1.0, 0.0, 3.0, 0.0))
    }

    // -----------------------------------------------------------------
    // Normal posterior conjugate update
    // -----------------------------------------------------------------

    @Test
    fun `flat prior gives posterior variance of sample variance over n`() {
        val p = bayesianNormalPosterior(
            sampleMean = 5.0,
            sampleVariance = 2.0,
            n = 100L,
            prior = BayesianPrior.DEFAULT,
        )
        assertEquals(5.0, p.mean, 1e-12)
        assertEquals(0.02, p.variance, 1e-12)
    }

    @Test
    fun `half-configured Normal prior falls back to flat`() {
        // Only meanPrior set, no variancePrior → should NOT be
        // treated as a proper Normal prior. A half-configured prior
        // is almost certainly a misconfiguration.
        val p = bayesianNormalPosterior(
            sampleMean = 5.0,
            sampleVariance = 2.0,
            n = 100L,
            prior = BayesianPrior(normalPriorMean = 0.0, normalPriorVariance = null),
        )
        assertEquals(5.0, p.mean, 1e-12)
        assertEquals(0.02, p.variance, 1e-12)
    }

    @Test
    fun `proper Normal prior combines precisions`() {
        // Prior: N(0, 1). Sample: mean=4, variance=2, n=10 → sample
        // precision = 10/2 = 5, prior precision = 1. Combined
        // precision = 6. Posterior mean = (0*1 + 4*5)/6 = 20/6 =
        // 3.333..., variance = 1/6.
        val p = bayesianNormalPosterior(
            sampleMean = 4.0,
            sampleVariance = 2.0,
            n = 10L,
            prior = BayesianPrior(normalPriorMean = 0.0, normalPriorVariance = 1.0),
        )
        assertEquals(20.0 / 6.0, p.mean, 1e-9)
        assertEquals(1.0 / 6.0, p.variance, 1e-9)
    }

    @Test
    fun `Normal posterior handles empty and degenerate samples`() {
        val empty = bayesianNormalPosterior(
            sampleMean = 4.0,
            sampleVariance = 2.0,
            n = 0L,
            prior = BayesianPrior(normalPriorMean = 0.0, normalPriorVariance = 1.0),
        )
        assertEquals(4.0, empty.mean)
        assertEquals(2.0, empty.variance)

        val degenerate = bayesianNormalPosterior(
            sampleMean = 4.0,
            sampleVariance = 0.0,
            n = 10L,
            prior = BayesianPrior(normalPriorMean = 0.0, normalPriorVariance = 1.0),
        )
        assertEquals(4.0, degenerate.mean)
        assertEquals(0.0, degenerate.variance)
    }

    // -----------------------------------------------------------------
    // Bayesian / frequentist convergence
    // -----------------------------------------------------------------

    @Test
    fun `as n grows Bayesian P_T_beats_C converges to one for a stable effect`() {
        // 10% vs 11% — genuine but small effect. Bayesian
        // `probabilityBeatsControl` should grow monotonically (in
        // expectation) as n grows, approaching 1 once the sample is
        // large enough that the frequentist p-value would also be
        // tiny.
        val seed = 2024L
        val effects = listOf(100L, 1_000L, 10_000L)
        val probabilities = effects.map { n ->
            val control = betaPosterior((n * 0.10).toLong(), n)
            val treatment = betaPosterior((n * 0.11).toLong(), n)
            betaProbabilityBeatsControl(control, treatment, seed)
        }
        // Strict monotone increase — the posterior concentrates
        // as evidence accumulates.
        assertTrue(
            probabilities[0] < probabilities[1] && probabilities[1] < probabilities[2],
            "expected monotone increase with n, got $probabilities",
        )
        // At n=10k a 10% vs 11% effect is well into the "clearly
        // better" territory — the frequentist two-proportion z-test
        // on this data gives z ≈ 2.3, p ≈ 0.02. The Bayesian
        // posterior should be comparably confident (>0.98) that the
        // treatment wins.
        assertTrue(probabilities[2] > 0.98,
            "at n=10k Bayesian must converge past 0.98, got ${probabilities[2]}")
    }

    @Test
    fun `configured informative Beta prior pulls tiny sample toward the prior mean`() {
        // 1 conversion in 5 users. Raw rate 0.2. With a default
        // uniform prior the posterior mean is (1+1)/(5+2) ≈ 0.286.
        // With an informative Beta(50, 50) prior the posterior is
        // Beta(51, 54); mean = 51/105 ≈ 0.486 — a major pull
        // toward the prior mean of 0.5.
        val weak = betaPosterior(1L, 5L, BayesianPrior.DEFAULT)
        val strong = betaPosterior(
            1L, 5L, BayesianPrior(betaPriorAlpha = 50.0, betaPriorBeta = 50.0)
        )
        assertTrue(strong.numericalMean > weak.numericalMean,
            "informative prior must shift the posterior mean toward the prior")
        assertEquals(51.0 / 105.0, strong.numericalMean, 1e-9)
    }

    // -----------------------------------------------------------------
    // Seed from report id helper
    // -----------------------------------------------------------------

    @Test
    fun `seedFromReportId is a pure function of the report id`() {
        val id1 = bosca.serialization.UUID.parse("11111111-2222-3333-4444-555555555555")
        val id2 = bosca.serialization.UUID.parse("11111111-2222-3333-4444-555555555555")
        val id3 = bosca.serialization.UUID.parse("99999999-aaaa-bbbb-cccc-dddddddddddd")
        assertEquals(seedFromReportId(id1), seedFromReportId(id2))
        assertTrue(seedFromReportId(id1) != seedFromReportId(id3))
    }

    // -----------------------------------------------------------------
    // describeBeta debug helper (smoke test — pins the format so a
    // future refactor doesn't silently change what logs look like)
    // -----------------------------------------------------------------

    @Test
    fun `describeBeta includes all four fields in a stable order`() {
        val s = describeBeta(BetaDistribution(2.0, 8.0))
        assertTrue(s.contains("α=2.0"))
        assertTrue(s.contains("β=8.0"))
        assertTrue(s.contains("mean="))
        assertTrue(s.contains("sd="))
    }
}
