package bosca.workops.controller

import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.workops.model.workflow.Status
import bosca.workops.model.workflow.StatusCategory
import bosca.workops.model.workflow.WILDCARD_FROM_STATE
import bosca.workops.model.workflow.Workflow
import bosca.workops.model.workflow.WorkflowScheme
import bosca.workops.model.workflow.WorkflowState
import bosca.workops.model.workflow.WorkflowTransition
import bosca.workops.service.StatusService
import bosca.workops.service.WorkflowService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WorkflowControllerTest {

    private val workflowService = mockk<WorkflowService>(relaxed = true)
    private val statusService = mockk<StatusService>(relaxed = true)
    private val authentication = mockk<AuthenticationContext>(relaxed = true)

    @Test
    fun `workflow type resolves states and transitions`() = runTest {
        val workflow = sampleWorkflow()
        val state = sampleState(workflow.id)
        val transition = sampleTransition(workflow.id, state.id.toString(), state.id)
        coEvery { workflowService.listStates(workflow.id) } returns listOf(state)
        coEvery { workflowService.listTransitions(workflow.id) } returns listOf(transition)

        val controller = WorkflowTypeController(workflowService)
        assertEquals(workflow.id, controller.id(workflow))
        assertEquals(workflow.name, controller.name(workflow))
        assertEquals(workflow.description, controller.description(workflow))
        assertEquals(workflow.initialStateId, controller.initialStateId(workflow))
        assertEquals(workflow.version, controller.version(workflow))
        assertEquals(listOf(state), controller.states(workflow))
        assertEquals(listOf(transition), controller.transitions(workflow))
    }

    @Test
    fun `workflow state resolves status and name and rejects missing status`() = runTest {
        val state = sampleState(UUID.random())
        val status = sampleStatus(state.statusId)
        coEvery { statusService.getById(state.statusId) } returns status

        val controller = WorkflowStateTypeController(statusService)
        assertSame(status, controller.status(state))
        assertEquals(status.name, controller.name(state))

        coEvery { statusService.getById(state.statusId) } returns null
        assertFailsWith<IllegalStateException> { controller.status(state) }
        assertFailsWith<IllegalStateException> { controller.name(state) }
    }

    @Test
    fun `workflow transition resolves wildcard specific missing and target states`() = runTest {
        val workflowId = UUID.random()
        val firstState = sampleState(workflowId)
        val targetState = sampleState(workflowId)
        val missingStateId = UUID.random()
        val specific = sampleTransition(
            workflowId,
            firstState.id.toString(),
            targetState.id,
            additionalFromStateId = missingStateId.toString(),
        )
        val wildcard = sampleTransition(workflowId, WILDCARD_FROM_STATE, targetState.id)
        val missing = sampleTransition(workflowId, missingStateId.toString(), targetState.id)
        coEvery { workflowService.listStates(workflowId) } returns listOf(firstState, targetState)
        coEvery { workflowService.getState(targetState.id) } returns targetState

        val controller = WorkflowTransitionTypeController(workflowService)
        assertEquals(listOf(firstState), controller.fromStates(specific))
        assertSame(firstState, controller.fromState(specific))
        assertTrue(controller.fromStates(wildcard).isEmpty())
        assertNull(controller.fromState(wildcard))
        assertNull(controller.fromState(missing))
        assertSame(targetState, controller.toState(specific))

        coEvery { workflowService.getState(targetState.id) } returns null
        assertFailsWith<IllegalStateException> { controller.toState(specific) }
    }

    @Test
    fun `workflow queries delegate every collection and lookup`() = runTest {
        val workflow = sampleWorkflow()
        val scheme = WorkflowScheme(
            id = UUID.random(),
            name = "Default",
            defaultWorkflowId = workflow.id,
        )
        coEvery { workflowService.listWorkflows() } returns listOf(workflow)
        coEvery { workflowService.getWorkflow(workflow.id) } returns workflow
        coEvery { workflowService.listSchemes() } returns listOf(scheme)
        coEvery { workflowService.getScheme(scheme.id) } returns scheme

        val controller = WorkflowQueryController(workflowService)
        assertEquals(listOf(workflow), controller.all(authentication))
        assertSame(workflow, controller.workflow(authentication, workflow.id))
        assertEquals(listOf(scheme), controller.schemes(authentication))
        assertSame(scheme, controller.scheme(authentication, scheme.id))
    }

    @Test
    fun `status queries delegate collection and lookup`() = runTest {
        val status = sampleStatus(UUID.random())
        coEvery { statusService.list() } returns listOf(status)
        coEvery { statusService.getById(status.id) } returns status

        val controller = StatusQueryController(statusService)
        assertEquals(listOf(status), controller.all(authentication))
        assertSame(status, controller.status(authentication, status.id))
    }

    private fun sampleWorkflow() = Workflow(
        id = UUID.random(),
        name = "Delivery",
        description = "Delivery workflow",
        initialStateId = UUID.random(),
        version = 2,
    )

    private fun sampleState(workflowId: UUID) = WorkflowState(
        id = UUID.random(),
        workflowId = workflowId,
        statusId = UUID.random(),
        displayOrder = 1,
        slaPolicyId = UUID.random(),
    )

    private fun sampleTransition(
        workflowId: UUID,
        fromStateId: String,
        toStateId: UUID,
        additionalFromStateId: String? = null,
    ) = WorkflowTransition(
        id = UUID.random(),
        workflowId = workflowId,
        name = "Advance",
        description = "Move forward",
        fromStateIds = listOfNotNull(fromStateId, additionalFromStateId),
        toStateId = toStateId,
        screenId = UUID.random(),
    )

    private fun sampleStatus(id: UUID) = Status(
        id = id,
        name = "In Progress",
        description = "Work underway",
        category = StatusCategory.IN_PROGRESS,
        colorHex = "#0055ff",
        version = 2,
    )
}
