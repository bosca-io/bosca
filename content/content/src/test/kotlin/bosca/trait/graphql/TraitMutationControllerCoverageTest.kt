package bosca.trait.graphql

import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.trait.model.Trait
import bosca.trait.model.TraitInput
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

class TraitMutationControllerCoverageTest {

    private val service = mockk<TraitService>()
    private val groupEvaluator = mockk<GroupEvaluator>()

    private val controller = TraitMutationController(service, groupEvaluator)
    private val authentication = mockk<AuthenticationContext>()

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    private fun input(id: String = "trait-1") = TraitInput(
        id = id,
        name = "Trait",
        description = "A trait",
        deleteWorkflowId = null,
        workflowIds = listOf("wf-1"),
        contentTypes = listOf("text/plain")
    )

    private fun trait(id: String = "trait-1") = Trait(
        id = id,
        name = "Trait",
        description = "A trait",
        deleteWorkflowId = null
    )

    @Test
    fun `TraitsMutation object is referenceable`() {
        assertEquals(TraitsMutation, TraitsMutation)
    }

    @Test
    fun `add verifies admin group then delegates to service`() = runTest {
        val traitInput = input()
        val expected = trait()
        every { groupEvaluator.verifyHasAdminGroup(authentication) } returns Unit
        coEvery { service.add(traitInput) } returns expected

        val result = controller.add(authentication, traitInput)

        assertEquals(expected, result)
        verify { groupEvaluator.verifyHasAdminGroup(authentication) }
        coVerify { service.add(traitInput) }
    }

    @Test
    fun `add throws and skips service when not admin`() = runTest {
        val traitInput = input()
        every { groupEvaluator.verifyHasAdminGroup(authentication) } throws SecurityException("Unauthorized access")

        assertFailsWith<SecurityException> {
            controller.add(authentication, traitInput)
        }

        coVerify(exactly = 0) { service.add(any()) }
    }

    @Test
    fun `edit verifies admin group then delegates to service`() = runTest {
        val traitInput = input("trait-2")
        val expected = trait("trait-2")
        every { groupEvaluator.verifyHasAdminGroup(authentication) } returns Unit
        coEvery { service.edit(traitInput) } returns expected

        val result = controller.edit(authentication, traitInput)

        assertEquals(expected, result)
        verify { groupEvaluator.verifyHasAdminGroup(authentication) }
        coVerify { service.edit(traitInput) }
    }

    @Test
    fun `edit throws and skips service when not admin`() = runTest {
        val traitInput = input("trait-2")
        every { groupEvaluator.verifyHasAdminGroup(authentication) } throws SecurityException("Unauthorized access")

        assertFailsWith<SecurityException> {
            controller.edit(authentication, traitInput)
        }

        coVerify(exactly = 0) { service.edit(any()) }
    }

    @Test
    fun `delete verifies admin group deletes and returns true`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(authentication) } returns Unit
        coEvery { service.delete("trait-3") } returns Unit

        val result = controller.delete(authentication, "trait-3")

        assertTrue(result)
        verify { groupEvaluator.verifyHasAdminGroup(authentication) }
        coVerify { service.delete("trait-3") }
    }

    @Test
    fun `delete throws and skips service when not admin`() = runTest {
        every { groupEvaluator.verifyHasAdminGroup(authentication) } throws SecurityException("Unauthorized access")

        assertFailsWith<SecurityException> {
            controller.delete(authentication, "trait-3")
        }

        coVerify(exactly = 0) { service.delete(any()) }
    }
}
