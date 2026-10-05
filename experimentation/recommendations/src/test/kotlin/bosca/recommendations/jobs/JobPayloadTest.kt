@file:OptIn(ExperimentalUuidApi::class)

package bosca.recommendations.jobs

import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi

class JobPayloadTest {

    // --- EvaluateStrategyJob ---

    @Test
    fun `EvaluateStrategyJob stores strategyId`() {
        val id = UUID.random()
        val job = EvaluateStrategyJob(strategyId = id)
        assertEquals(id, job.strategyId)
    }

    @Test
    fun `EvaluateStrategyJob implements IJobDefinition`() {
        val job = EvaluateStrategyJob(strategyId = UUID.random())
        assertIs<IJobDefinition>(job)
    }

    // --- TrainModelJob ---

    @Test
    fun `TrainModelJob defaults to null configuration`() {
        val job = TrainModelJob()
        assertNull(job.configuration)
        assertNull(job.kubernetesDispatchId)
    }

    @Test
    fun `TrainModelJob stores configuration override`() {
        val config = JsonObject(mapOf("epochs" to JsonPrimitive(50)))
        val dispatchId = UUID.random()
        val job = TrainModelJob(configuration = config, kubernetesDispatchId = dispatchId)
        assertEquals(config, job.configuration)
        assertEquals(dispatchId, job.kubernetesDispatchId)
    }

    @Test
    fun `TrainModelJob implements IJobDefinition`() {
        val job = TrainModelJob()
        assertIs<IJobDefinition>(job)
    }

    // --- RemoveExpiredRecommendationsJob ---

    @Test
    fun `RemoveExpiredRecommendationsJob can be instantiated`() {
        val job = RemoveExpiredRecommendationsJob()
        assertIs<IJobDefinition>(job)
    }

    @Test
    fun `RemoveExpiredRecommendationsJob implements IJobDefinition`() {
        val job = RemoveExpiredRecommendationsJob()
        assertIs<IJobDefinition>(job)
    }

    @Test
    fun `RecomputeRecommendationContextsJob implements IJobDefinition`() {
        assertIs<IJobDefinition>(RecomputeRecommendationContextsJob())
    }

    @Test
    fun `BackfillRecommendationEmbeddingsJob defaults to missing-only batches`() {
        val job = BackfillRecommendationEmbeddingsJob()
        assertIs<IJobDefinition>(job)
        assertEquals(false, job.overwriteExisting)
        assertEquals(100, job.batchSize)
    }
}
