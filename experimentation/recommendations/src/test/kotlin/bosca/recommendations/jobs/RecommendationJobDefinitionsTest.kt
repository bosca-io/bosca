@file:OptIn(ExperimentalUuidApi::class)

package bosca.recommendations.jobs

import bosca.serialization.UUID
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.uuid.ExperimentalUuidApi

/**
 * Covers the recommendation job-definition data classes across construction, equality, and both
 * serialization modes (default-skipping vs. `encodeDefaults`), plus the required-field decode guard —
 * exercising the generated serializer/equals branches.
 */
class RecommendationJobDefinitionsTest {

    private val plain = Json { ignoreUnknownKeys = true }
    private val withDefaults = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val strict = Json { } // rejects unknown keys → exercises the decoder's unknown-field branch

    @Test
    fun `unknown fields are rejected by the strict decoder`() {
        assertFailsWith<Exception> { strict.decodeFromString(TrainModelJob.serializer(), """{"unexpected":1}""") }
        assertFailsWith<Exception> { strict.decodeFromString(EvaluateStrategyJob.serializer(), """{"strategyId":"${UUID.random()}","unexpected":1}""") }
        assertFailsWith<Exception> { strict.decodeFromString(RemoveExpiredRecommendationsJob.serializer(), """{"unexpected":1}""") }
        assertFailsWith<Exception> { strict.decodeFromString(RecomputeRecommendationContextsJob.serializer(), """{"unexpected":1}""") }
        assertFailsWith<Exception> { strict.decodeFromString(BackfillRecommendationEmbeddingsJob.serializer(), """{"unexpected":1}""") }
    }

    @Test
    fun `TrainModelJob equality and both serialization modes`() {
        val dispatchId = UUID.random()
        val a = TrainModelJob(configuration = JsonPrimitive("x"), kubernetesDispatchId = dispatchId)
        val b = TrainModelJob(configuration = JsonPrimitive("x"), kubernetesDispatchId = dispatchId)
        val defaulted = TrainModelJob()
        assertEquals(a, b)
        assertNotEquals(a, defaulted)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(a, a.copy())

        // Default-skipping omits the null configuration; encodeDefaults writes it — both serializer branches.
        assertEquals(defaulted, plain.decodeFromString(TrainModelJob.serializer(), plain.encodeToString(TrainModelJob.serializer(), defaulted)))
        assertEquals(defaulted, withDefaults.decodeFromString(TrainModelJob.serializer(), withDefaults.encodeToString(TrainModelJob.serializer(), defaulted)))
        assertEquals(a, plain.decodeFromString(TrainModelJob.serializer(), plain.encodeToString(TrainModelJob.serializer(), a)))
        assertNotEquals(a, a.copy(configuration = JsonPrimitive("y")))
        assertNotEquals(a, a.copy(kubernetesDispatchId = UUID.random()))
    }

    @Test
    fun `EvaluateStrategyJob equality, serialization and the required-field guard`() {
        val id = UUID.random()
        val a = EvaluateStrategyJob(id)
        assertEquals(a, EvaluateStrategyJob(id))
        assertNotEquals(a, EvaluateStrategyJob(UUID.random()))
        assertEquals(a.hashCode(), EvaluateStrategyJob(id).hashCode())

        val encoded = withDefaults.encodeToString(EvaluateStrategyJob.serializer(), a)
        assertEquals(a, plain.decodeFromString(EvaluateStrategyJob.serializer(), encoded))
        // Missing the required strategyId → the decode guard throws.
        assertFailsWith<MissingFieldException> { plain.decodeFromString(EvaluateStrategyJob.serializer(), "{}") }
    }

    @Test
    fun `RemoveExpiredRecommendationsJob round-trips in both modes`() {
        val job = RemoveExpiredRecommendationsJob()
        assertEquals(RemoveExpiredRecommendationsJob()::class, plain.decodeFromString(RemoveExpiredRecommendationsJob.serializer(), plain.encodeToString(RemoveExpiredRecommendationsJob.serializer(), job))::class)
        withDefaults.encodeToString(RemoveExpiredRecommendationsJob.serializer(), job)
    }

    @Test
    fun `RecomputeRecommendationContextsJob round-trips in both modes`() {
        val job = RecomputeRecommendationContextsJob()
        assertEquals(
            RecomputeRecommendationContextsJob()::class,
            plain.decodeFromString(
                RecomputeRecommendationContextsJob.serializer(),
                plain.encodeToString(RecomputeRecommendationContextsJob.serializer(), job),
            )::class,
        )
        withDefaults.encodeToString(RecomputeRecommendationContextsJob.serializer(), job)
    }

    @Test
    fun `BackfillRecommendationEmbeddingsJob preserves mode and batch size`() {
        val job = BackfillRecommendationEmbeddingsJob(overwriteExisting = true, batchSize = 25)
        assertEquals(
            job,
            plain.decodeFromString(
                BackfillRecommendationEmbeddingsJob.serializer(),
                plain.encodeToString(BackfillRecommendationEmbeddingsJob.serializer(), job),
            ),
        )
        assertEquals(
            BackfillRecommendationEmbeddingsJob(),
            withDefaults.decodeFromString(
                BackfillRecommendationEmbeddingsJob.serializer(),
                withDefaults.encodeToString(
                    BackfillRecommendationEmbeddingsJob.serializer(),
                    BackfillRecommendationEmbeddingsJob(),
                ),
            ),
        )
    }
}
