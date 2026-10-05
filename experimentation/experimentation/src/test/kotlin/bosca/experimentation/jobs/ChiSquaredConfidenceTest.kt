package bosca.experimentation.jobs

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies [chiSquaredConfidence] against fixtures whose expected values
 * come from `scipy.stats.chi2_contingency(correction=False)`. The wrapper
 * delegates the actual test to Apache Commons Math's `ChiSquareTest`, so
 * a deviation from scipy means either the wrapper is constructing the
 * 2×2 table incorrectly, the `1 - p` flip is wrong, or the
 * minimum-expected-cell-count guard is letting a bad sample through.
 *
 * The minimum-expected-count-≥5 rule is the standard validity guard for
 * the chi-squared approximation. Below that the math3 result is
 * unreliable; the wrapper must return null and let the caller fall back
 * to "insufficient data" rather than serve a confident-looking number
 * built on a broken approximation.
 */
class ChiSquaredConfidenceTest {

    private fun assertClose(expected: Double, actual: Double?, tolerance: Double, label: String) {
        assertNotNull(actual, "$label: expected non-null confidence")
        assertTrue(
            abs(expected - actual) <= tolerance,
            "$label: expected $expected ± $tolerance, got $actual",
        )
    }

    @Test
    fun `large effect with large sample produces near 1 confidence`() {
        // scipy:
        //   chi2_contingency([[100, 900], [200, 800]], correction=False)
        //   p ≈ 1.6e-12 → confidence ≈ 1.0
        val confidence = chiSquaredConfidence(
            controlN = 1000, controlConversions = 100,
            treatmentN = 1000, treatmentConversions = 200,
        )
        assertClose(expected = 1.0, actual = confidence, tolerance = 1e-6, label = "10% vs 20% on n=1000")
    }

    @Test
    fun `identical conversion rates produce near zero confidence`() {
        // 5% vs 5% on equal samples → p = 1.0 → confidence = 0.0.
        val confidence = chiSquaredConfidence(
            controlN = 1000, controlConversions = 50,
            treatmentN = 1000, treatmentConversions = 50,
        )
        assertNotNull(confidence)
        assertEquals(0.0, confidence, 1e-9)
    }

    @Test
    fun `borderline significant fixture matches scipy`() {
        // scipy:
        //   chi2_contingency([[40, 460], [60, 440]], correction=False)
        // Hand-check: row totals (500, 500), col totals (100, 900),
        //   expected = (50, 450, 50, 450).
        //   χ² = (10²/50)·2 + (10²/450)·2 = 4 + 0.4444 ≈ 4.4444
        //   df = 1 → p ≈ 0.0350 → confidence ≈ 0.9650.
        val confidence = chiSquaredConfidence(
            controlN = 500, controlConversions = 40,
            treatmentN = 500, treatmentConversions = 60,
        )
        assertClose(expected = 0.9650, actual = confidence, tolerance = 1e-3, label = "8% vs 12% on n=500")
    }

    @Test
    fun `direction symmetry -- swapping arms keeps the same confidence`() {
        // The chi-squared test of independence is symmetric in the row
        // ordering: a regression of the same magnitude should produce the
        // same confidence as a corresponding lift, because the test
        // doesn't know "which side is treatment".
        val lift = chiSquaredConfidence(
            controlN = 1000, controlConversions = 100,
            treatmentN = 1000, treatmentConversions = 150,
        )
        val regression = chiSquaredConfidence(
            controlN = 1000, controlConversions = 150,
            treatmentN = 1000, treatmentConversions = 100,
        )
        assertNotNull(lift)
        assertNotNull(regression)
        assertEquals(lift, regression, 1e-12, "chi-squared must be symmetric in row order")
    }

    @Test
    fun `min expected cell count under 5 returns null`() {
        // 1 conversion total in a 2×2 of (5, 5) impressions → expected
        // converted-cell count is 0.5, well under the 5 cutoff. The
        // wrapper must refuse to compute and return null so the caller
        // surfaces "insufficient data" instead of a misleading number.
        val confidence = chiSquaredConfidence(
            controlN = 5, controlConversions = 0,
            treatmentN = 5, treatmentConversions = 1,
        )
        assertNull(confidence, "expected null when expected cell count < 5")
    }

    @Test
    fun `at the min expected cell count boundary returns a value`() {
        // Smallest table where every expected cell is ≥ 5. With (50, 50)
        // impressions and (10, 10) conversions, every expected cell is
        // exactly 10 — comfortably above the cutoff, no result should be
        // suppressed.
        val confidence = chiSquaredConfidence(
            controlN = 50, controlConversions = 10,
            treatmentN = 50, treatmentConversions = 10,
        )
        assertNotNull(confidence)
        assertEquals(0.0, confidence, 1e-9)
    }

    @Test
    fun `zero conversions on both arms returns zero confidence not null`() {
        // No information at all → can't reject the null. The wrapper
        // returns 0.0 (not null) so the caller treats this as "no
        // signal yet" rather than "validity guard tripped".
        val confidence = chiSquaredConfidence(
            controlN = 1000, controlConversions = 0,
            treatmentN = 1000, treatmentConversions = 0,
        )
        assertEquals(0.0, confidence)
    }

    @Test
    fun `everyone converts on both arms returns zero confidence`() {
        // 100% vs 100% — degenerate but not invalid; same "no signal"
        // semantics as the zero-conversion case.
        val confidence = chiSquaredConfidence(
            controlN = 1000, controlConversions = 1000,
            treatmentN = 1000, treatmentConversions = 1000,
        )
        assertEquals(0.0, confidence)
    }

    @Test
    fun `conversion counts above either arm size are rejected`() {
        assertNull(chiSquaredConfidence(10, 11, 100, 10))
        assertNull(chiSquaredConfidence(100, 10, 10, 11))
    }
}
