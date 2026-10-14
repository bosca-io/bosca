package bosca.experimentation.jobs

import bosca.experimentation.model.GoalMetricType
import kotlin.test.*

class GoalPopulationSummaryTest {
    @Test fun `empty denominators have no estimate and zero events retain the population`() {
        assertNull(wilsonInterval(0, 0))
        assertNull(summarizeDistribution(emptyList()))
        assertNull(populationSummary(GoalMetricType.UNIQUE_CONVERSION, 0, GoalCountStats.ZERO).estimate)
        val zero = populationSummary(GoalMetricType.EVENT_COUNT, 100, GoalCountStats.ZERO)
        assertEquals(100, zero.observationCount); assertEquals(0.0, zero.estimate)
        assertEquals(0.0, zero.distribution?.p99)
        assertEquals(0.0, zero.interval?.lower)
        assertFailsWith<IllegalArgumentException> { wilsonInterval(2, 1) }
        assertFailsWith<IllegalArgumentException> { summarizeDistribution(listOf(1.0), -1) }
    }
    @Test fun `counts from separate stores above the denominator keep raw counts without a rate`() {
        val drifted = populationSummary(GoalMetricType.UNIQUE_CONVERSION, 10, GoalCountStats(12, 12, 12.0))
        assertEquals(10, drifted.subjects); assertEquals(12, drifted.conversions)
        assertNull(drifted.estimate); assertNull(drifted.interval)
        assertNull(observedRateInterval(11, 10))
        assertEquals(wilsonInterval(3, 10), observedRateInterval(3, 10))
        val events = populationSummary(GoalMetricType.EVENT_COUNT, 2, GoalCountStats(3, 3, 3.0, values = listOf(1.0, 1.0, 1.0)))
        assertEquals(3, events.observationCount)
    }
    @Test fun `Wilson intervals reproduce the production reach and sparse attribution fixture`() {
        val reach = assertNotNull(wilsonInterval(31, 301))
        assertEquals(.0735, reach.lower, .0001); assertEquals(.1425, reach.upper, .0001)
        val destination = assertNotNull(wilsonInterval(1, 13))
        assertEquals(.0137, destination.lower, .0001); assertEquals(.3331, destination.upper, .0001)
        assertEquals(0.0, wilsonInterval(0, 16)?.lower)
        assertEquals(1.0, wilsonInterval(16, 16)?.upper)
    }
    @Test fun `continuous percentiles preserve long tails and exclude unobserved subjects`() {
        val summary = populationSummary(GoalMetricType.SESSION_DURATION, 5, GoalCountStats(0, 0, 0.0, values = listOf(300.0, 300.0, 300.0, 1900.0)))
        assertEquals(4, summary.observationCount); assertEquals(5, summary.subjects)
        val d = assertNotNull(summary.distribution)
        assertEquals(300.0, d.p50); assertEquals(700.0, d.p75); assertEquals(1420.0, d.p90, 1e-8)
        assertEquals(1660.0, d.p95, 1e-8); assertEquals(1852.0, d.p99, 1e-8); assertEquals(1900.0, d.maximum)
        assertNotNull(summary.interval)
        assertNull(populationSummary(GoalMetricType.SESSION_DURATION, 10, GoalCountStats.ZERO).distribution)
        assertNull(populationSummary(GoalMetricType.SESSION_DURATION, 10, GoalCountStats(0, 0, 0.0, values = listOf(300.0))).interval)
    }
    @Test fun `Fisher exact reproduces sparse recommendation comparisons`() {
        assertEquals(.235, fisherExactPValue(13, 31, 16, 58), .001)
        assertEquals(.448, fisherExactPValue(1, 13, 0, 16), .001)
        assertEquals(1.0, fisherExactPValue(0, 0, 0, 0))
        assertEquals(1.0, fisherExactPValue(0, 10, 0, 10))
        assertFailsWith<IllegalArgumentException> { fisherExactPValue(2, 1, 0, 0) }
    }
}
