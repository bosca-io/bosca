package bosca.content.tools.graphql

import bosca.content.tools.model.TemplateAttributeTool
import bosca.content.tools.service.TemplateAttributeToolService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityException
import bosca.serialization.UUID
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TemplateAttributeToolsControllerCoverageTest {

    private val toolService = mockk<TemplateAttributeToolService>()
    private val groupEvaluator = mockk<GroupEvaluator>()

    private val controller = TemplateAttributeToolsController(toolService, groupEvaluator)
    private val authentication = mockk<AuthenticationContext>()

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    private fun tool(id: UUID) = TemplateAttributeTool(
        id = id,
        key = "key",
        name = "name",
        query = "query"
    )

    @Test
    fun `all verifies admin group and returns all tools`() = runTest {
        val tools = listOf(tool(UUID.random()), tool(UUID.random()))
        every { groupEvaluator.verifyHasAdminGroup(authentication) } just Runs
        coEvery { toolService.getAll() } returns tools

        val result = controller.all(authentication)

        assertEquals(tools, result)
        coVerify(exactly = 1) { toolService.getAll() }
    }

    @Test
    fun `all returns empty list when no tools exist`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(authentication) } just Runs
        coEvery { toolService.getAll() } returns emptyList()

        val result = controller.all(authentication)

        assertEquals(emptyList(), result)
        coVerify(exactly = 1) { toolService.getAll() }
    }

    @Test
    fun `all throws when admin group verification fails and does not call service`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(authentication) } throws SecurityException("Unauthorized access")

        assertFailsWith<SecurityException> {
            controller.all(authentication)
        }

        coVerify(exactly = 0) { toolService.getAll() }
    }

    @Test
    fun `get verifies admin group and returns tool when found`() = runTest {
        val id = UUID.random()
        val found = tool(id)
        every { groupEvaluator.verifyHasAdminGroup(authentication) } just Runs
        coEvery { toolService.get(id) } returns found

        val result = controller.get(authentication, id)

        assertEquals(found, result)
        coVerify(exactly = 1) { toolService.get(id) }
    }

    @Test
    fun `get returns null when tool not found`() = runTest {
        val id = UUID.random()
        every { groupEvaluator.verifyHasAdminGroup(authentication) } just Runs
        coEvery { toolService.get(id) } returns null

        val result = controller.get(authentication, id)

        assertNull(result)
        coVerify(exactly = 1) { toolService.get(id) }
    }

    @Test
    fun `get throws when admin group verification fails and does not call service`() = runTest {
        val id = UUID.random()
        every { groupEvaluator.verifyHasAdminGroup(authentication) } throws SecurityException("Unauthorized access")

        assertFailsWith<SecurityException> {
            controller.get(authentication, id)
        }

        coVerify(exactly = 0) { toolService.get(any()) }
    }
}
