@file:OptIn(ExperimentalUuidApi::class)

package bosca.recommendations.graphql

import bosca.recommendations.model.RecommendationStrategy
import bosca.recommendations.model.RecommendationStrategyStatus
import bosca.recommendations.model.RecommendationStrategyType
import bosca.recommendations.service.RecommendationStrategyService
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi

class RecommendationStrategiesControllerTest {

    private val strategyService = mockk<RecommendationStrategyService>(relaxed = true)
    private val groupEvaluator = mockk<GroupEvaluator>(relaxed = true)
    private val controller = RecommendationStrategiesController(strategyService, groupEvaluator)

    private fun authenticatedContext(principalId: UUID = UUID.random()): AuthenticationContext {
        val principal = mockk<AuthenticatedPrincipal>()
        every { principal.id } returns principalId
        val auth = mockk<AuthenticationContext>()
        every { auth.principal() } returns principal
        return auth
    }

    // --- all ---

    @Test
    fun `all calls verifyHasAdminGroup then delegates to service`() = runTest {
        val auth = authenticatedContext()
        val strategies = listOf(
            RecommendationStrategy(name = "Trending", type = RecommendationStrategyType.TRENDING)
        )
        coEvery { strategyService.getAll(0, 10) } returns strategies

        val result = controller.all(auth, 0, 10)

        assertEquals(1, result.size)
        assertEquals("Trending", result[0].name)
        coVerify { groupEvaluator.verifyHasAdminGroup(auth) }
        coVerify { strategyService.getAll(0, 10) }
    }

    @Test
    fun `all coerces negative offset to zero`() = runTest {
        val auth = authenticatedContext()
        coEvery { strategyService.getAll(0, 10) } returns emptyList()

        controller.all(auth, -5, 10)

        coVerify { strategyService.getAll(0, 10) }
    }

    @Test
    fun `all coerces limit above 100 to 100`() = runTest {
        val auth = authenticatedContext()
        coEvery { strategyService.getAll(0, 100) } returns emptyList()

        controller.all(auth, 0, 200)

        coVerify { strategyService.getAll(0, 100) }
    }

    @Test
    fun `all coerces limit below 1 to 1`() = runTest {
        val auth = authenticatedContext()
        coEvery { strategyService.getAll(0, 1) } returns emptyList()

        controller.all(auth, 0, 0)

        coVerify { strategyService.getAll(0, 1) }
    }

    // --- strategy ---

    @Test
    fun `strategy calls verifyHasAdminGroup then delegates to service`() = runTest {
        val auth = authenticatedContext()
        val id = UUID.random()
        val strategy = RecommendationStrategy(
            id = id,
            name = "Trending",
            type = RecommendationStrategyType.TRENDING,
            status = RecommendationStrategyStatus.ACTIVE,
        )
        coEvery { strategyService.getById(id) } returns strategy

        val result = controller.strategy(auth, id)

        assertEquals(strategy, result)
        coVerify { groupEvaluator.verifyHasAdminGroup(auth) }
        coVerify { strategyService.getById(id) }
    }

    @Test
    fun `strategy returns null when not found`() = runTest {
        val auth = authenticatedContext()
        val id = UUID.random()
        coEvery { strategyService.getById(id) } returns null

        val result = controller.strategy(auth, id)

        assertNull(result)
        coVerify { groupEvaluator.verifyHasAdminGroup(auth) }
    }
}
