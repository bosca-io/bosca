package bosca.category.graphql

import bosca.category.model.Category
import bosca.category.model.CategoryInput
import bosca.category.service.CategoryService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityException
import bosca.serialization.UUID
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CategoryMutationControllerCoverageTest {

    private val service = mockk<CategoryService>()
    private val groupEvaluator = mockk<GroupEvaluator>()

    private val controller = CategoryMutationController(service, groupEvaluator)
    private val authentication = mockk<AuthenticationContext>()

    @AfterTest
    fun tearDown() {
        clearAllMocks()
        unmockkAll()
    }

    @Test
    fun `add verifies admin group and delegates to service`() = runTest {
        val input = CategoryInput(name = "News")
        val created = Category(id = UUID.random(), name = "News")

        every { groupEvaluator.verifyHasAdminGroup(authentication) } just Runs
        coEvery { service.add(input) } returns created

        val result = controller.add(authentication, input)

        assertEquals(created, result)
        verify(exactly = 1) { groupEvaluator.verifyHasAdminGroup(authentication) }
        coVerify(exactly = 1) { service.add(input) }
    }

    @Test
    fun `add throws when admin group check fails and does not call service`() = runTest {
        val input = CategoryInput(name = "News")

        every { groupEvaluator.verifyHasAdminGroup(authentication) } throws SecurityException("Unauthorized access")

        assertFailsWith<SecurityException> {
            controller.add(authentication, input)
        }

        coVerify(exactly = 0) { service.add(any()) }
    }

    @Test
    fun `edit verifies admin group and delegates to service`() = runTest {
        val id = UUID.random()
        val input = CategoryInput(name = "Updated")
        val edited = Category(id = id, name = "Updated")

        every { groupEvaluator.verifyHasAdminGroup(authentication) } just Runs
        coEvery { service.edit(id, input) } returns edited

        val result = controller.edit(authentication, id, input)

        assertEquals(edited, result)
        verify(exactly = 1) { groupEvaluator.verifyHasAdminGroup(authentication) }
        coVerify(exactly = 1) { service.edit(id, input) }
    }

    @Test
    fun `edit throws when admin group check fails and does not call service`() = runTest {
        val id = UUID.random()
        val input = CategoryInput(name = "Updated")

        every { groupEvaluator.verifyHasAdminGroup(authentication) } throws SecurityException("Unauthorized access")

        assertFailsWith<SecurityException> {
            controller.edit(authentication, id, input)
        }

        coVerify(exactly = 0) { service.edit(any(), any()) }
    }

    @Test
    fun `delete verifies admin group deletes and returns true`() = runTest {
        val id = UUID.random()

        every { groupEvaluator.verifyHasAdminGroup(authentication) } just Runs
        coEvery { service.delete(id) } just Runs

        val result = controller.delete(authentication, id)

        assertTrue(result)
        verify(exactly = 1) { groupEvaluator.verifyHasAdminGroup(authentication) }
        coVerify(exactly = 1) { service.delete(id) }
    }

    @Test
    fun `delete throws when admin group check fails and does not call service`() = runTest {
        val id = UUID.random()

        every { groupEvaluator.verifyHasAdminGroup(authentication) } throws SecurityException("Unauthorized access")

        assertFailsWith<SecurityException> {
            controller.delete(authentication, id)
        }

        coVerify(exactly = 0) { service.delete(any()) }
    }
}
