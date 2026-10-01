package bosca.category.graphql

import bosca.category.model.Category
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

class CategoriesControllerCoverageTest {

    private val service = mockk<CategoryService>()
    private val groupEvaluator = mockk<GroupEvaluator>()

    private val controller = CategoriesController(service, groupEvaluator)
    private val authentication = mockk<AuthenticationContext>()

    @AfterTest
    fun tearDown() {
        clearAllMocks()
        unmockkAll()
    }

    @Test
    fun `all verifies editor group and returns categories`() = runTest {
        val categories = listOf(
            Category(id = UUID.random(), name = "News"),
            Category(id = UUID.random(), name = "Sports"),
        )

        every { groupEvaluator.verifyHasEditorGroup(authentication) } just Runs
        coEvery { service.getAll() } returns categories

        val result = controller.all(authentication)

        assertEquals(categories, result)
        verify(exactly = 1) { groupEvaluator.verifyHasEditorGroup(authentication) }
        coVerify(exactly = 1) { service.getAll() }
    }

    @Test
    fun `all returns empty list when service has none`() = runTest {
        every { groupEvaluator.verifyHasEditorGroup(authentication) } just Runs
        coEvery { service.getAll() } returns emptyList()

        val result = controller.all(authentication)

        assertTrue(result.isEmpty())
        verify(exactly = 1) { groupEvaluator.verifyHasEditorGroup(authentication) }
        coVerify(exactly = 1) { service.getAll() }
    }

    @Test
    fun `all throws when editor group check fails and does not call service`() = runTest {
        every { groupEvaluator.verifyHasEditorGroup(authentication) } throws SecurityException("Unauthorized access")

        assertFailsWith<SecurityException> {
            controller.all(authentication)
        }

        coVerify(exactly = 0) { service.getAll() }
    }

    @Test
    fun `Categories object is referenceable`() {
        assertEquals(Categories, Categories)
    }
}
