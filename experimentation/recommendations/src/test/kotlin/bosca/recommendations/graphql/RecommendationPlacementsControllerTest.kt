@file:OptIn(ExperimentalUuidApi::class)

package bosca.recommendations.graphql

import bosca.recommendations.model.RecommendationPlacement
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
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi

class RecommendationPlacementsControllerTest {

    private val placementService = mockk<RecommendationPlacementService>(relaxed = true)
    private val groupEvaluator = mockk<GroupEvaluator>(relaxed = true)
    private val controller = RecommendationPlacementsController(placementService, groupEvaluator)
    private val auth = mockk<AuthenticationContext>()

    // --- all ---

    @Test
    fun `all verifies admin group then delegates to service`() = runTest {
        val placements = listOf(
            RecommendationPlacement(name = "Home Feed", slug = "home-feed"),
        )
        coEvery { placementService.getAll() } returns placements

        val result = controller.all(auth)

        coVerify { groupEvaluator.verifyHasAdminGroup(auth) }
        coVerify { placementService.getAll() }
        assertEquals(1, result.size)
        assertEquals("Home Feed", result[0].name)
    }

    @Test
    fun `all returns empty list when no placements exist`() = runTest {
        coEvery { placementService.getAll() } returns emptyList()

        val result = controller.all(auth)

        coVerify { groupEvaluator.verifyHasAdminGroup(auth) }
        assertEquals(0, result.size)
    }

    // --- placement ---

    @Test
    fun `placement verifies admin group then delegates to service`() = runTest {
        val id = UUID.random()
        val placement = RecommendationPlacement(id = id, name = "Sidebar", slug = "sidebar")
        coEvery { placementService.getById(id) } returns placement

        val result = controller.placement(auth, id)

        coVerify { groupEvaluator.verifyHasAdminGroup(auth) }
        coVerify { placementService.getById(id) }
        assertEquals("Sidebar", result?.name)
    }

    @Test
    fun `placement returns null when not found`() = runTest {
        val id = UUID.random()
        coEvery { placementService.getById(id) } returns null

        val result = controller.placement(auth, id)

        coVerify { groupEvaluator.verifyHasAdminGroup(auth) }
        assertNull(result)
    }

    // --- placementBySlug ---

    @Test
    fun `placementBySlug verifies admin group then delegates to service`() = runTest {
        val placement = RecommendationPlacement(name = "Footer", slug = "footer")
        coEvery { placementService.getBySlug("footer") } returns placement

        val result = controller.placementBySlug(auth, "footer")

        coVerify { groupEvaluator.verifyHasAdminGroup(auth) }
        coVerify { placementService.getBySlug("footer") }
        assertEquals("Footer", result?.name)
    }

    @Test
    fun `placementBySlug returns null when slug not found`() = runTest {
        coEvery { placementService.getBySlug("missing") } returns null

        val result = controller.placementBySlug(auth, "missing")

        coVerify { groupEvaluator.verifyHasAdminGroup(auth) }
        assertNull(result)
    }
}
