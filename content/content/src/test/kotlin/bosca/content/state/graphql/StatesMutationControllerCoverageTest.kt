package bosca.content.state.graphql

import bosca.content.state.model.State
import bosca.content.state.model.StateInput
import bosca.content.state.model.WorkflowStateType
import bosca.content.state.service.StateService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityException
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StatesMutationControllerCoverageTest {

    private val service = mockk<StateService>()
    private val groupEvaluator = mockk<GroupEvaluator>()

    private val controller = WorkflowStatesMutationController(service, groupEvaluator)
    private val authentication = mockk<AuthenticationContext>()

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    private fun createInput(
        id: String = "draft",
        name: String = "Draft",
        description: String = "Initial state",
        type: WorkflowStateType = WorkflowStateType.PROCESSING,
        configuration: JsonObject = JsonObject(emptyMap()),
        jobName: String? = null
    ) = StateInput(
        id = id,
        name = name,
        description = description,
        type = type,
        configuration = configuration,
        jobName = jobName
    )

    private fun createState(
        id: String = "draft",
        name: String = "Draft",
        description: String = "Initial state",
        type: WorkflowStateType = WorkflowStateType.PROCESSING,
        configuration: JsonObject = JsonObject(emptyMap()),
        jobName: String? = null
    ) = State(
        id = id,
        name = name,
        description = description,
        type = type,
        configuration = configuration,
        jobName = jobName
    )

    @Test
    fun `add verifies manager group and returns created state`() = runTest {
        val input = createInput(id = "draft")
        val created = createState(id = "draft")
        every { groupEvaluator.verifyHasManagerGroup(authentication) } returns Unit
        coEvery { service.add(input) } returns created

        val result = controller.add(authentication, input)

        assertEquals(created, result)
        coVerify(exactly = 1) { service.add(input) }
        verify(exactly = 1) { groupEvaluator.verifyHasManagerGroup(authentication) }
    }

    @Test
    fun `add throws when manager group verification fails and service is not called`() = runTest {
        val input = createInput()
        every { groupEvaluator.verifyHasManagerGroup(authentication) } throws SecurityException("Unauthorized access")

        assertFailsWith<SecurityException> {
            controller.add(authentication, input)
        }

        coVerify(exactly = 0) { service.add(any()) }
    }

    @Test
    fun `edit verifies manager group and returns edited state using input id`() = runTest {
        val input = createInput(id = "review")
        val edited = createState(id = "review", name = "Review")
        every { groupEvaluator.verifyHasManagerGroup(authentication) } returns Unit
        coEvery { service.edit("review", input) } returns edited

        val result = controller.edit(authentication, input)

        assertEquals(edited, result)
        coVerify(exactly = 1) { service.edit("review", input) }
    }

    @Test
    fun `edit throws when manager group verification fails and service is not called`() = runTest {
        val input = createInput(id = "review")
        every { groupEvaluator.verifyHasManagerGroup(authentication) } throws SecurityException("Unauthorized access")

        assertFailsWith<SecurityException> {
            controller.edit(authentication, input)
        }

        coVerify(exactly = 0) { service.edit(any(), any()) }
    }

    @Test
    fun `delete verifies manager group deletes and returns true`() = runTest {
        every { groupEvaluator.verifyHasManagerGroup(authentication) } returns Unit
        coEvery { service.delete("draft") } returns Unit

        val result = controller.delete(authentication, "draft")

        assertTrue(result)
        coVerify(exactly = 1) { service.delete("draft") }
    }

    @Test
    fun `delete throws when manager group verification fails and service is not called`() = runTest {
        every { groupEvaluator.verifyHasManagerGroup(authentication) } throws SecurityException("Unauthorized access")

        assertFailsWith<SecurityException> {
            controller.delete(authentication, "draft")
        }

        coVerify(exactly = 0) { service.delete(any()) }
    }
}
