package bosca.workops.service

import bosca.serialization.UUID
import bosca.workops.model.okr.KeyResultMetric
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeyResultEvaluatorTest {

    @Test
    fun `evaluate handles every metric variant without inventing unavailable values`() = runTest {
        val evaluator = KeyResultEvaluator(null)

        assertNull(evaluator.evaluate(KeyResultMetric.Numeric(baseline = 10.0, target = 20.0)))
        assertEquals(100.0, evaluator.evaluate(KeyResultMetric.Boolean(achieved = true)))
        assertEquals(0.0, evaluator.evaluate(KeyResultMetric.Boolean(achieved = false)))
        assertEquals(75.0, evaluator.evaluate(KeyResultMetric.Percentage(target = 75)))
        assertNull(evaluator.evaluate(KeyResultMetric.TaskCompletion(UUID.random())))
        assertNull(evaluator.evaluate(KeyResultMetric.MetricEvent("deploy", "count")))
    }
}
