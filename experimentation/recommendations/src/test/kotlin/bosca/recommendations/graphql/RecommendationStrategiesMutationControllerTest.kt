@file:OptIn(ExperimentalUuidApi::class)

package bosca.recommendations.graphql

import bosca.recommendations.model.RecommendationStrategy
import bosca.recommendations.model.RecommendationStrategyInput
import bosca.recommendations.model.RecommendationStrategyType
import bosca.recommendations.service.RecommendationExperimentService
import bosca.recommendations.service.RecommendationStrategyService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

class RecommendationStrategiesMutationControllerTest {

    private val strategyService = mockk<RecommendationStrategyService>(relaxed = true)
    private val experimentService = mockk<RecommendationExperimentService>(relaxed = true)
    private val groupEvaluator = mockk<GroupEvaluator>(relaxed = true)
    private val controller = RecommendationStrategiesMutationController(strategyService, experimentService, groupEvaluator)
    private val auth = mockk<AuthenticationContext>()

    // --- add ---

    @Test
    fun `add verifies admin group then delegates to service`() = runTest {
        val input = RecommendationStrategyInput(name = "Trending", type = RecommendationStrategyType.TRENDING)
        val created = RecommendationStrategy(name = "Trending", type = RecommendationStrategyType.TRENDING)
        coEvery { strategyService.add(input) } returns created

        val result = controller.add(auth, input)

        coVerify { groupEvaluator.verifyHasAdminGroup(auth) }
        coVerify { strategyService.add(input) }
        assertEquals("Trending", result.name)
    }

    // --- edit ---

    @Test
    fun `edit verifies admin group then delegates to service`() = runTest {
        val id = UUID.random()
        val input = RecommendationStrategyInput(name = "Updated", type = RecommendationStrategyType.CO_ENGAGEMENT)
        val updated = RecommendationStrategy(id = id, name = "Updated", type = RecommendationStrategyType.CO_ENGAGEMENT)
        coEvery { strategyService.edit(id, input) } returns updated

        val result = controller.edit(auth, id, input)

        coVerify { groupEvaluator.verifyHasAdminGroup(auth) }
        coVerify { strategyService.edit(id, input) }
        assertEquals("Updated", result.name)
    }

    // --- delete ---

    @Test
    fun `delete verifies admin group and returns true`() = runTest {
        val id = UUID.random()

        val result = controller.delete(auth, id)

        coVerify { groupEvaluator.verifyHasAdminGroup(auth) }
        coVerify { strategyService.delete(id) }
        assertTrue(result)
    }

    // --- evaluate ---

    @Test
    fun `evaluate verifies admin group then delegates to service`() = runTest {
        val strategyId = UUID.random()
        val strategy = RecommendationStrategy(id = strategyId, name = "Eval Target", type = RecommendationStrategyType.TRENDING)
        coEvery { strategyService.evaluate(strategyId) } returns strategy

        val result = controller.evaluate(auth, strategyId)

        coVerify { groupEvaluator.verifyHasAdminGroup(auth) }
        coVerify { strategyService.evaluate(strategyId) }
        assertEquals("Eval Target", result.name)
        assertEquals(strategyId, result.id)
    }

    // --- experiment provisioning + training ---

    @Test
    fun `provisionEngineExperiment verifies admin then delegates`() = runTest {
        coEvery { experimentService.provisionEngineExperiment() } returns mockk(relaxed = true)
        controller.provisionEngineExperiment(auth)
        coVerify { groupEvaluator.verifyHasAdminGroup(auth) }
        coVerify { experimentService.provisionEngineExperiment() }
    }

    @Test
    fun `provisionModelExperiment verifies admin then delegates with the version pair`() = runTest {
        coEvery { experimentService.provisionModelExperiment(3, 4) } returns mockk(relaxed = true)
        controller.provisionModelExperiment(auth, 3, 4)
        coVerify { groupEvaluator.verifyHasAdminGroup(auth) }
        coVerify { experimentService.provisionModelExperiment(3, 4) }
    }

    @Test
    @OptIn(bosca.di.annotation.InternalDI::class)
    fun `trainModel verifies admin then enqueues a training job`() = runTest {
        bosca.di.ProviderRegistry.clear()
        bosca.di.provides<kotlinx.serialization.json.Json>(singleton = true) { kotlinx.serialization.json.Json { ignoreUnknownKeys = true } }
        bosca.di.provides<bosca.sharedqueue.jobs.JobQueue>(name = "recommendationsQueue", singleton = true) { mockk(relaxed = true) }

        val result = controller.trainModel(auth, null)

        assertTrue(result)
        coVerify { groupEvaluator.verifyHasAdminGroup(auth) }
        bosca.di.ProviderRegistry.clear()
    }

    @Test
    @OptIn(bosca.di.annotation.InternalDI::class)
    fun `backfillSemanticData verifies admin then enqueues a backfill job`() = runTest {
        bosca.di.ProviderRegistry.clear()
        bosca.di.provides<kotlinx.serialization.json.Json>(singleton = true) { kotlinx.serialization.json.Json { ignoreUnknownKeys = true } }
        bosca.di.provides<bosca.sharedqueue.jobs.JobQueue>(name = "recommendationsQueue", singleton = true) { mockk(relaxed = true) }

        val result = controller.backfillSemanticData(auth, overwriteExisting = true)

        assertTrue(result)
        coVerify { groupEvaluator.verifyHasAdminGroup(auth) }
        bosca.di.ProviderRegistry.clear()
    }
}
