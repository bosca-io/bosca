@file:OptIn(ExperimentalUuidApi::class)

package bosca.recommendations.graphql

import bosca.recommendations.model.RecommendationPlacement
import bosca.recommendations.model.RecommendationPlacementInput
import bosca.recommendations.service.RecommendationPlacementService
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

class RecommendationPlacementsMutationControllerTest {

    private val placementService = mockk<RecommendationPlacementService>(relaxed = true)
    private val groupEvaluator = mockk<GroupEvaluator>(relaxed = true)
    private val controller = RecommendationPlacementsMutationController(placementService, groupEvaluator)
    private val auth = mockk<AuthenticationContext>()

    // --- add ---

    @Test
    fun `add verifies admin group then delegates to service`() = runTest {
        val input = RecommendationPlacementInput(name = "Home Feed", slug = "home-feed")
        val strategyIds = listOf(UUID.random())
        val created = RecommendationPlacement(name = "Home Feed", slug = "home-feed")
        coEvery { placementService.add(input, strategyIds) } returns created

        val result = controller.add(auth, input, strategyIds)

        coVerify { groupEvaluator.verifyHasAdminGroup(auth) }
        coVerify { placementService.add(input, strategyIds) }
        assertEquals("Home Feed", result.name)
    }

    // --- edit ---

    @Test
    fun `edit verifies admin group then delegates to service`() = runTest {
        val id = UUID.random()
        val input = RecommendationPlacementInput(name = "Updated", slug = "updated")
        val strategyIds = listOf(UUID.random())
        val updated = RecommendationPlacement(id = id, name = "Updated", slug = "updated")
        coEvery { placementService.edit(id, input, strategyIds) } returns updated

        val result = controller.edit(auth, id, input, strategyIds)

        coVerify { groupEvaluator.verifyHasAdminGroup(auth) }
        coVerify { placementService.edit(id, input, strategyIds) }
        assertEquals("Updated", result.name)
    }

    // --- delete ---

    @Test
    fun `delete verifies admin group and returns true`() = runTest {
        val id = UUID.random()

        val result = controller.delete(auth, id)

        coVerify { groupEvaluator.verifyHasAdminGroup(auth) }
        coVerify { placementService.delete(id) }
        assertTrue(result)
    }
}
