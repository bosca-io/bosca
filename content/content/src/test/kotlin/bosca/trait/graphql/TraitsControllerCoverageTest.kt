package bosca.trait.graphql

import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.trait.model.Trait
import bosca.trait.service.TraitService
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TraitsControllerCoverageTest {

    private val service = mockk<TraitService>()
    private val groupEvaluator = mockk<GroupEvaluator>()

    private val controller = TraitsController(service, groupEvaluator)
    private val authentication = mockk<AuthenticationContext>()

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    private fun trait(id: String = "trait-1") = Trait(
        id = id,
        name = "Trait",
        description = "A trait",
        deleteWorkflowId = null
    )

    @Test
    fun `Traits object is referenceable`() {
        assertEquals(Traits, Traits)
    }

    @Test
    fun `all verifies editor group then returns all traits`() = runTest {
        val expected = listOf(trait("trait-1"), trait("trait-2"))
        every { groupEvaluator.verifyHasEditorGroup(authentication) } returns Unit
        coEvery { service.getAll() } returns expected

        val result = controller.all(authentication)

        assertEquals(expected, result)
        verify { groupEvaluator.verifyHasEditorGroup(authentication) }
        coVerify { service.getAll() }
    }

    @Test
    fun `all returns empty list when service has no traits`() = runTest {
        every { groupEvaluator.verifyHasEditorGroup(authentication) } returns Unit
        coEvery { service.getAll() } returns emptyList()

        val result = controller.all(authentication)

        assertTrue(result.isEmpty())
        verify { groupEvaluator.verifyHasEditorGroup(authentication) }
        coVerify { service.getAll() }
    }

    @Test
    fun `all throws and skips service when not editor`() = runTest {
        every { groupEvaluator.verifyHasEditorGroup(authentication) } throws SecurityException("Unauthorized access")

        assertFailsWith<SecurityException> {
            controller.all(authentication)
        }

        coVerify(exactly = 0) { service.getAll() }
    }
}
