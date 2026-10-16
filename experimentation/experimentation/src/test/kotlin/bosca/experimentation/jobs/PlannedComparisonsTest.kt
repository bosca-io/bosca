package bosca.experimentation.jobs

import bosca.experimentation.model.*
import bosca.serialization.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.*

class PlannedComparisonsTest {
    private val id = UUID.random()
    private val experiment = Experiment(id = id, featureFlagId = UUID.random(), controlVariationKey = "None", name = "Placements")
    private val goal = ConversionGoal(experimentId = id, name = "Navigation")
    private fun binary(arm: String, n: Long, k: Long) = ExperimentResult(experimentId = id, variationKey = arm,
        goalId = goal.id, impressions = n, conversions = k, conversionRate = if (n > 0) k.toDouble() / n else 0.0)
    private fun compare(rows: List<ExperimentResult>, g: ConversionGoal = goal, e: Experiment = experiment, block: String? = null) =
        plannedComparisons(e, listOf(g), rows, .05 / 3, AnalysisThresholds(), block)

    @Test fun `all planned pairs include treatment against treatment with corrected intervals`() {
        val comparisons = compare(listOf(binary("None", 1000, 100), binary("Mid", 1000, 300), binary("Bottom", 1000, 110)))
        assertEquals(setOf("Bottom:Mid", "Bottom:None", "Mid:None"), comparisons.map { "${it.leftVariationKey}:${it.rightVariationKey}" }.toSet())
        val mid = comparisons.single { it.leftVariationKey == "Mid" }
        assertEquals(.2, assertNotNull(mid.difference), 1e-9)
        assertEquals("CONCLUSIVE", mid.conclusion)
        assertTrue(assertNotNull(mid.interval).lower > 0)
        assertEquals(1 - .05 / 3, mid.threshold)
        assertEquals("1 − p", mid.evidenceLabel)
        assertEquals("INCONCLUSIVE", comparisons.single { it.rightVariationKey == "None" && it.leftVariationKey == "Bottom" }.conclusion)
        assertTrue(compare(listOf(binary("Mid", 1000, 300), binary("None", 1000, 100)), block = "SRM failed").all { it.conclusion == "DATA_QUALITY_BLOCKED" && it.reason == "SRM failed" })
    }

    @Test fun `the whole family uses the control practical margin regardless of arm names or CUPED`() {
        val continuous = goal.copy(metricType = GoalMetricType.EVENT_COUNT)
        for (keys in listOf(listOf("A", "B", "C"), listOf("C", "B", "A"))) {
            for (adjusted in listOf(false, true)) {
                val rows = keys.zip(listOf(100.0, 105.1, 110.2)).map { (key, mean) ->
                    binary(key, 10_000, 0).copy(
                        mean = if (adjusted) 1000.0 else mean, variance = 1.0,
                        adjustedMean = if (adjusted) mean else null,
                        adjustedVariance = if (adjusted) 1.0 else null,
                    )
                }
                val comparisons = plannedComparisons(experiment.copy(controlVariationKey = keys.first()),
                    listOf(continuous), rows, .05 / 3, AnalysisThresholds(minPracticalLiftPercent = 5.0), null)
                assertEquals(3, comparisons.size)
                assertTrue(comparisons.all { it.conclusion == "CONCLUSIVE" }, comparisons.toString())
            }
        }
    }

    @Test fun `a missing control estimate cannot establish a practical conclusion`() {
        val rows = listOf(binary("Mid", 1000, 300), binary("Bottom", 1000, 100))
        val comparison = compare(rows).single()
        assertEquals("INSUFFICIENT_DATA", comparison.conclusion)
        assertTrue(comparison.reason.contains("control estimate"))
    }

    @Test fun `sample requirements and missing observations prevent conclusions`() {
        for (n in listOf(0L, 1L, 29L)) {
            assertEquals("INSUFFICIENT_DATA", compare(listOf(binary("Mid", n, n), binary("None", n, 0))).single().conclusion)
        }
        // The sample target withholds ship in the overall verdict, not the safety-relevant comparison.
        assertEquals("CONCLUSIVE", compare(listOf(binary("Mid", 100, 100), binary("None", 100, 0)), e = experiment.copy(targetSampleSize = 1000)).single().conclusion)
        val continuous = goal.copy(metricType = GoalMetricType.SESSION_DURATION)
        val rows = listOf("Mid", "None").map { binary(it, 100, 0).copy(mean = null, observationCount = 0) }
        assertNull(compare(rows, continuous).single().interval)
        assertNull(compare(rows, continuous).single().difference)
        assertEquals("INSUFFICIENT_DATA", compare(rows, continuous).single().conclusion)
        assertNull(compare(rows.map { it.copy(mean = 10.0, observationCount = 100) }, continuous).single().interval)
    }

    @Test fun `binary counts above the denominator block the comparison instead of failing`() {
        for (e in listOf(experiment, experiment.copy(analysisMethod = AnalysisMethod.BAYESIAN))) {
            val c = compare(listOf(binary("Mid", 100, 120), binary("None", 100, 10)), e = e).single()
            assertEquals("DATA_QUALITY_BLOCKED", c.conclusion)
            assertEquals("Conversions exceed the outcome-eligible subjects in at least one arm.", c.reason)
            assertNull(c.interval)
            assertNull(c.evidence)
        }
    }

    @Test fun `continuous comparisons use CUPED moments and can establish practical equivalence`() {
        val continuous = goal.copy(metricType = GoalMetricType.SESSION_DURATION)
        val rows = listOf("Mid", "None").map { binary(it, 1000, 0).copy(mean = 600.0, variance = 100.0, observationCount = 1000) }
        assertEquals("EQUIVALENT", compare(rows, continuous).single().conclusion)
        val adjusted = rows.mapIndexed { i, r -> r.copy(adjustedMean = if (i == 0) 800.0 else 500.0, adjustedVariance = 25.0) }
        val c = compare(adjusted, continuous).single()
        assertEquals(300.0, c.difference)
        assertEquals("CONCLUSIVE", c.conclusion)
        val guardrail = continuous.copy(role = ConversionGoalRole.GUARDRAIL)
        assertEquals("CONCLUSIVE", compare(adjusted, guardrail).single().conclusion)
    }

    @Test fun `Bayesian pair evidence and intervals come from the same posterior family`() {
        val bayes = experiment.copy(analysisMethod = AnalysisMethod.BAYESIAN,
            bayesianPrior = Json.encodeToJsonElement(BayesianPrior.serializer(), BayesianPrior(betaPriorAlpha = 2.0, betaPriorBeta = 2.0)))
        val rows = listOf(binary("Mid", 1000, 300), binary("None", 1000, 100))
        val c = compare(rows, e = bayes).single()
        assertEquals("P(win)", c.evidenceLabel)
        assertEquals("CONCLUSIVE", c.conclusion)
        assertTrue(assertNotNull(c.evidence) > .99)
        assertTrue(assertNotNull(c.interval).lower > 0)
        assertEquals(c, compare(rows, e = bayes).single())
        assertEquals("CONCLUSIVE", compare(rows.reversed().map { it.copy(variationKey = if (it.variationKey == "None") "Mid" else "None") }, e = bayes).single().conclusion)
        assertEquals("INSUFFICIENT_DATA", compare(listOf(binary("Mid", 0, 0), binary("None", 0, 0)), e = bayes).single().conclusion)
        val continuous = goal.copy(metricType = GoalMetricType.EVENT_COUNT)
        val means = rows.mapIndexed { i, r -> r.copy(mean = if (i == 0) 5.0 else 2.0, variance = 10.0) }
        assertEquals("CONCLUSIVE", compare(means, continuous, bayes).single().conclusion)
        assertNull(compare(means.map { it.copy(variance = null) }, continuous, bayes).single().interval)
    }
}
