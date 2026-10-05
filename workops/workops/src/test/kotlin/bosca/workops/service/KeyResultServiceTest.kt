package bosca.workops.service

import bosca.serialization.UUID
import bosca.workops.model.okr.ConfidenceLevel
import bosca.workops.model.okr.KeyResult
import bosca.workops.model.okr.KeyResultMetric
import bosca.workops.repository.KeyResultRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class KeyResultServiceTest {

    @Test
    fun `create writes every metric discriminator and recompute preserves unavailable values`() = runTest {
        val repository = mockk<KeyResultRepository>()
        val objectiveId = UUID.random()
        val created = KeyResult(
            id = UUID.random(),
            objectiveId = objectiveId,
            title = "Created",
            metricType = "Boolean",
        )
        coEvery { repository.listForObjective(objectiveId) } returns listOf(created)
        coEvery {
            repository.add(objectiveId, "Metric", "description", any(), any(), ConfidenceLevel.HIGH.name)
        } returns created
        val service = KeyResultServiceImpl(repository, KeyResultEvaluator(null), Json)
        val metrics = listOf(
            KeyResultMetric.TaskCompletion(UUID.random()) to "TaskCompletion",
            KeyResultMetric.MetricEvent("deploy", "count") to "MetricEvent",
            KeyResultMetric.Numeric(0.0, 10.0) to "Numeric",
            KeyResultMetric.Boolean(true) to "Boolean",
            KeyResultMetric.Percentage(75) to "Percentage",
        )

        assertEquals(listOf(created), service.list(objectiveId))
        for ((metric, type) in metrics) {
            assertSame(
                created,
                service.create(objectiveId, "Metric", "description", metric, ConfidenceLevel.HIGH),
            )
            coVerify(exactly = 1) {
                repository.add(objectiveId, "Metric", "description", type, any(), ConfidenceLevel.HIGH.name)
            }
        }

        val missingId = UUID.random()
        val malformed = created.copy(id = UUID.random(), metric = JsonPrimitive("invalid"))
        val numeric = created.copy(
            id = UUID.random(),
            metricType = "Numeric",
            metric = Json.encodeToJsonElement(KeyResultMetric.serializer(), KeyResultMetric.Numeric(1.0, 2.0)),
        )
        val achieved = created.copy(
            id = UUID.random(),
            metricType = "Boolean",
            metric = Json.encodeToJsonElement(KeyResultMetric.serializer(), KeyResultMetric.Boolean(true)),
        )
        val computed = achieved.copy(currentValue = 100.0, version = 1)
        coEvery { repository.getById(missingId) } returns null
        coEvery { repository.getById(malformed.id) } returns malformed
        coEvery { repository.getById(numeric.id) } returns numeric
        coEvery { repository.getById(achieved.id) } returns achieved
        coEvery { repository.setComputedValue(achieved.id, 100.0) } returns computed

        assertNull(service.recompute(missingId))
        assertSame(malformed, service.recompute(malformed.id))
        assertSame(numeric, service.recompute(numeric.id))
        assertEquals(computed, service.recompute(achieved.id))
    }
}
