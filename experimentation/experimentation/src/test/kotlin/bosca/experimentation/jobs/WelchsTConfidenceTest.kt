package bosca.experimentation.jobs

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies [welchsTConfidence] against fixtures whose expected values come
 * from `scipy.stats.ttest_ind_from_stats(equal_var=False)`.
 *
 * The aggregator delegates to Apache Commons Math's [org.apache.commons.math3.stat.inference.TTest],
 * which uses the true Student-t distribution with Welch–Satterthwaite degrees
 * of freedom — the same primitive scipy uses internally — so tolerances can
 * be tight. Any drift here means an arithmetic regression in the wrapper
 * (wrong `StatisticalSummaryValues` ordering, p/(1-p) flipped, etc.), not a
 * distributional approximation difference.
 */
class WelchsTConfidenceTest {

    private fun assertClose(expected: Double, actual: Double?, tolerance: Double, label: String) {
        assertNotNull(actual, "$label: expected non-null confidence")
        assertTrue(
            abs(expected - actual) <= tolerance,
            "$label: expected $expected ± $tolerance, got $actual"
        )
    }

    @Test
    fun `clearly different means produce high confidence`() {
        // Treatment users average twice as many events with similar variance.
        // scipy: ttest_ind_from_stats(mean1=1.0, std1=1.0, nobs1=1000,
        //                             mean2=2.0, std2=1.0, nobs2=1000,
        //                             equal_var=False) → p ≈ 4.3e-94, conf ≈ 1.0
        val confidence = welchsTConfidence(
            controlMean = 1.0,
            controlVariance = 1.0,
            controlN = 1000,
            treatmentMean = 2.0,
            treatmentVariance = 1.0,
            treatmentN = 1000,
        )
        assertNotNull(confidence)
        assertTrue(confidence > 0.999, "expected near-1.0 confidence, got $confidence")
    }

    @Test
    fun `identical samples produce zero confidence`() {
        // mean1 == mean2, t-statistic is 0, p = 1.0, confidence = 0.
        val confidence = welchsTConfidence(
            controlMean = 1.5,
            controlVariance = 0.25,
            controlN = 500,
            treatmentMean = 1.5,
            treatmentVariance = 0.25,
            treatmentN = 500,
        )
        assertNotNull(confidence)
        assertEquals(0.0, confidence, 1e-9)
    }

    @Test
    fun `moderate effect with moderate sample matches scipy`() {
        // scipy: ttest_ind_from_stats(mean1=10.0, std1=3.0, nobs1=200,
        //                             mean2=10.6, std2=3.2, nobs2=200,
        //                             equal_var=False) → p ≈ 0.054
        // → confidence ≈ 0.946.
        val confidence = welchsTConfidence(
            controlMean = 10.0,
            controlVariance = 9.0,        // 3.0²
            controlN = 200,
            treatmentMean = 10.6,
            treatmentVariance = 10.24,    // 3.2²
            treatmentN = 200,
        )
        assertClose(expected = 0.946, actual = confidence, tolerance = 0.01, label = "moderate effect")
    }

    @Test
    fun `negative effect (treatment worse) still yields a confidence in zero to one`() {
        // The two-tailed test treats direction symmetrically; the absolute
        // confidence should match the positive-direction case.
        val pos = welchsTConfidence(
            controlMean = 1.0, controlVariance = 1.0, controlN = 500,
            treatmentMean = 1.5, treatmentVariance = 1.0, treatmentN = 500,
        )
        val neg = welchsTConfidence(
            controlMean = 1.5, controlVariance = 1.0, controlN = 500,
            treatmentMean = 1.0, treatmentVariance = 1.0, treatmentN = 500,
        )
        assertNotNull(pos)
        assertNotNull(neg)
        assertEquals(pos, neg, 1e-9, "two-tailed Welch's t must be symmetric in direction")
    }

    @Test
    fun `null when sample size below 2`() {
        assertNull(welchsTConfidence(0.0, 1.0, 1, 1.0, 1.0, 100))
        assertNull(welchsTConfidence(0.0, 1.0, 100, 1.0, 1.0, 1))
    }

    @Test
    fun `null when both variances are zero`() {
        // Zero standard error means the t-statistic is undefined; we should
        // return null rather than crash or produce a misleading confidence.
        assertNull(
            welchsTConfidence(
                controlMean = 5.0, controlVariance = 0.0, controlN = 100,
                treatmentMean = 5.0, treatmentVariance = 0.0, treatmentN = 100,
            )
        )
    }
}
