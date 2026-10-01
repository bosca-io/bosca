package bosca.experimentation.jobs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Validates the `init`-block guards on [AnalysisThresholds], ensuring that
 * out-of-range parameters are rejected early and that boundary values are
 * accepted without error.
 */
class AnalysisThresholdsTest {

    @Test
    fun `baseAlpha below 0 throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            AnalysisThresholds(baseAlpha = -0.01)
        }
    }

    @Test
    fun `baseAlpha above 1 throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            AnalysisThresholds(baseAlpha = 1.01)
        }
    }

    @Test
    fun `minPracticalLiftPercent negative throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            AnalysisThresholds(minPracticalLiftPercent = -0.1)
        }
    }

    @Test
    fun `guardrailMinRegressionPercent negative throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            AnalysisThresholds(guardrailMinRegressionPercent = -0.1)
        }
    }

    @Test
    fun `valid thresholds at boundary values succeed`() {
        // Lower boundaries — all zeros should be accepted
        val lower = AnalysisThresholds(
            baseAlpha = 0.0,
            minPracticalLiftPercent = 0.0,
            guardrailMinRegressionPercent = 0.0,
        )
        assertEquals(0.0, lower.baseAlpha)
        assertEquals(0.0, lower.minPracticalLiftPercent)
        assertEquals(0.0, lower.guardrailMinRegressionPercent)

        // Upper boundary for alpha — 1.0 should be accepted
        val upper = AnalysisThresholds(
            baseAlpha = 1.0,
            minPracticalLiftPercent = 0.0,
            guardrailMinRegressionPercent = 0.0,
        )
        assertEquals(1.0, upper.baseAlpha)
    }

    @Test
    fun `default thresholds use module constants`() {
        val defaults = AnalysisThresholds()
        assertEquals(BASE_ALPHA, defaults.baseAlpha)
        assertEquals(MIN_PRACTICAL_LIFT_PERCENT, defaults.minPracticalLiftPercent)
        assertEquals(GUARDRAIL_MIN_REGRESSION_PERCENT_DEFAULT, defaults.guardrailMinRegressionPercent)
    }
}
