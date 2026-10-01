package bosca.content.tools.graphql

import bosca.content.tools.model.TemplateAttributeTool
import bosca.content.tools.model.TemplateAttributeToolInput
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
import kotlin.test.assertTrue

class TemplateAttributeToolsMutationControllerCoverageTest {

    private val toolService = mockk<TemplateAttributeToolService>()
    private val groupEvaluator = mockk<GroupEvaluator>()

    private val controller = TemplateAttributeToolsMutationController(toolService, groupEvaluator)
    private val authentication = mockk<AuthenticationContext>()

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    private fun input() = TemplateAttributeToolInput(
        key = "key",
        name = "name",
        query = "query"
    )

    private fun tool(id: UUID) = TemplateAttributeTool(
        id = id,
        key = "key",
        name = "name",
        query = "query"
    )

    @Test
    fun `add verifies admin group and returns created tool`() = runTest {
        val created = tool(UUID.random())
        val toolInput = input()
        every { groupEvaluator.verifyHasAdminGroup(authentication) } just Runs
        coEvery { toolService.add(toolInput) } returns created

        val result = controller.add(authentication, toolInput)

        assertEquals(created, result)
        coVerify(exactly = 1) { toolService.add(toolInput) }
    }

    @Test
    fun `add throws when admin group verification fails and does not call service`() = runTest {
        val toolInput = input()
        every { groupEvaluator.verifyHasAdminGroup(authentication) } throws SecurityException("Unauthorized access")

        assertFailsWith<SecurityException> {
            controller.add(authentication, toolInput)
        }

        coVerify(exactly = 0) { toolService.add(any()) }
    }

    @Test
    fun `edit verifies admin group and returns edited tool`() = runTest {
        val id = UUID.random()
        val edited = tool(id)
        val toolInput = input()
        every { groupEvaluator.verifyHasAdminGroup(authentication) } just Runs
        coEvery { toolService.edit(id, toolInput) } returns edited

        val result = controller.edit(authentication, id, toolInput)

        assertEquals(edited, result)
        coVerify(exactly = 1) { toolService.edit(id, toolInput) }
    }

    @Test
    fun `edit throws when admin group verification fails and does not call service`() = runTest {
        val id = UUID.random()
        val toolInput = input()
        every { groupEvaluator.verifyHasAdminGroup(authentication) } throws SecurityException("Unauthorized access")

        assertFailsWith<SecurityException> {
            controller.edit(authentication, id, toolInput)
        }

        coVerify(exactly = 0) { toolService.edit(any(), any()) }
    }

    @Test
    fun `delete verifies admin group deletes tool and returns true`() = runTest {
        val id = UUID.random()
        every { groupEvaluator.verifyHasAdminGroup(authentication) } just Runs
        coEvery { toolService.delete(id) } just Runs

        val result = controller.delete(authentication, id)

        assertTrue(result)
        coVerify(exactly = 1) { toolService.delete(id) }
    }

    @Test
    fun `delete throws when admin group verification fails and does not call service`() = runTest {
        val id = UUID.random()
        every { groupEvaluator.verifyHasAdminGroup(authentication) } throws SecurityException("Unauthorized access")

        assertFailsWith<SecurityException> {
            controller.delete(authentication, id)
        }

        coVerify(exactly = 0) { toolService.delete(any()) }
    }
}
