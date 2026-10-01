package bosca.content.state.graphql

import bosca.content.state.model.State
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
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StatesControllerCoverageTest {

    private val service = mockk<StateService>()
    private val permissionEvaluator = mockk<GroupEvaluator>()

    private val controller = WorkflowStatesController(service, permissionEvaluator)
    private val authentication = mockk<AuthenticationContext>()

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    private fun createState(
        id: String = "draft",
        name: String = "Draft",
        description: String = "Initial state",
        type: WorkflowStateType = WorkflowStateType.DRAFT,
        jobName: String? = null
    ) = State(
        id = id,
        name = name,
        description = description,
        type = type,
        configuration = JsonObject(emptyMap()),
        jobName = jobName
    )

    @Test
    fun `WorkflowStates object is accessible`() {
        assertSame(WorkflowStates, WorkflowStates)
    }

    @Test
    fun `all verifies editor group and returns all states`() = runTest {
        val states = listOf(
            createState(id = "draft", type = WorkflowStateType.DRAFT),
            createState(id = "published", type = WorkflowStateType.PUBLISHED)
        )
        every { permissionEvaluator.verifyHasEditorGroup(authentication) } returns Unit
        coEvery { service.getAll() } returns states

        val result = controller.all(authentication)

        assertEquals(states, result)
        verify(exactly = 1) { permissionEvaluator.verifyHasEditorGroup(authentication) }
        coVerify(exactly = 1) { service.getAll() }
    }

    @Test
    fun `all returns empty list when service has no states`() = runTest {
        every { permissionEvaluator.verifyHasEditorGroup(authentication) } returns Unit
        coEvery { service.getAll() } returns emptyList()

        val result = controller.all(authentication)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `all propagates unauthorized and never calls service`() = runTest {
        every { permissionEvaluator.verifyHasEditorGroup(authentication) } throws SecurityException("Unauthorized access")

        assertFailsWith<SecurityException> {
            controller.all(authentication)
        }
        coVerify(exactly = 0) { service.getAll() }
    }

    @Test
    fun `state verifies editor group and returns state when found`() = runTest {
        val state = createState(id = "published", type = WorkflowStateType.PUBLISHED)
        every { permissionEvaluator.verifyHasEditorGroup(authentication) } returns Unit
        coEvery { service.get("published") } returns state

        val result = controller.state(authentication, "published")

        assertEquals(state, result)
        verify(exactly = 1) { permissionEvaluator.verifyHasEditorGroup(authentication) }
        coVerify(exactly = 1) { service.get("published") }
    }

    @Test
    fun `state returns null when service returns null`() = runTest {
        every { permissionEvaluator.verifyHasEditorGroup(authentication) } returns Unit
        coEvery { service.get("missing") } returns null

        val result = controller.state(authentication, "missing")

        assertNull(result)
        coVerify(exactly = 1) { service.get("missing") }
    }

    @Test
    fun `state propagates unauthorized and never calls service`() = runTest {
        every { permissionEvaluator.verifyHasEditorGroup(authentication) } throws SecurityException("Unauthorized access")

        assertFailsWith<SecurityException> {
            controller.state(authentication, "draft")
        }
        coVerify(exactly = 0) { service.get(any()) }
    }
}
