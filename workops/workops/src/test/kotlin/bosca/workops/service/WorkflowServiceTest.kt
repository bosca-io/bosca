package bosca.workops.service

import bosca.serialization.UUID
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.project.Project
import bosca.workops.model.spec.Spec
import bosca.workops.model.task.Task
import bosca.workops.model.workflow.WILDCARD_FROM_STATE
import bosca.workops.model.workflow.Workflow
import bosca.workops.model.workflow.WorkflowScheme
import bosca.workops.model.workflow.WorkflowState
import bosca.workops.model.workflow.WorkflowTransition
import bosca.workops.repository.WorkflowRepository
import bosca.workops.repository.WorkflowSchemeRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Unit tests for [WorkflowServiceImpl.resolveWorkflowForSpec] — the
 * resolution path behind `WorkOpsSpec.transitions`. Specs carry their
 * `workflowId` directly, so resolution is workflow + current-state
 * lookup, without the scheme/per-task-type indirection tasks use.
 */
class WorkflowServiceTest {

    private val workflowRepository = mockk<WorkflowRepository>()
    private val schemeRepository = mockk<WorkflowSchemeRepository>()
    private val projectService = mockk<ProjectService>()
    private val workflowQueryRepository = mockk<WorkflowQueryRepository>()

    private val service = WorkflowServiceImpl(
        workflowRepository,
        schemeRepository,
        projectService,
        workflowQueryRepository,
    )

    private val workflowId = UUID.random()
    private val statusId = UUID.random()
    private val stateId = UUID.random()

    private fun sampleSpec() = Spec(
        id = UUID.random(),
        key = "SPEC-1",
        metadataId = UUID.random(),
        statusId = statusId,
        workflowId = workflowId,
        ownerProfileId = UUID.random(),
        createdByPrincipalId = UUID.random(),
        modifiedByPrincipalId = UUID.random(),
    )

    private fun sampleWorkflow() = Workflow(id = workflowId, name = "Default")

    private fun sampleTask(
        projectId: UUID = UUID.random(),
        taskTypeId: UUID = UUID.random(),
    ) = Task(
        id = UUID.random(),
        key = "TASK-1",
        projectId = projectId,
        taskTypeId = taskTypeId,
        statusId = statusId,
        priorityId = UUID.random(),
        summary = "Task",
        reporterProfileId = UUID.random(),
        createdByPrincipalId = UUID.random(),
        modifiedByPrincipalId = UUID.random(),
    )

    private fun project(id: UUID, schemeId: UUID?) = Project(
        id = id,
        programId = UUID.random(),
        key = "PROJ",
        name = "Project",
        ownerProfileId = UUID.random(),
        defaultWorkflowSchemeId = schemeId,
    )

    private fun scheme(
        id: UUID,
        defaultWorkflowId: UUID,
        overrides: kotlinx.serialization.json.JsonElement = JsonObject(emptyMap()),
    ) = WorkflowScheme(
        id = id,
        name = "Scheme",
        defaultWorkflowId = defaultWorkflowId,
        perTaskTypeWorkflowIds = overrides,
    )

    private fun sampleState(id: UUID = stateId, status: UUID = statusId) =
        WorkflowState(id = id, workflowId = workflowId, statusId = status, displayOrder = 0)

    private fun transition(name: String, fromStateIds: List<String>, toStateId: UUID = UUID.random()) =
        WorkflowTransition(
            id = UUID.random(),
            workflowId = workflowId,
            name = name,
            fromStateIds = fromStateIds,
            toStateId = toStateId,
        )

    @Test
    fun `resolveWorkflowForSpec returns workflow current state and transitions`() = runTest {
        val spec = sampleSpec()
        val workflow = sampleWorkflow()
        val state = sampleState()
        val transitions = listOf(
            transition("Start Progress", listOf(stateId.toString())),
            transition("Reopen", listOf(WILDCARD_FROM_STATE)),
        )
        coEvery { workflowRepository.getWorkflowById(workflowId) } returns workflow
        coEvery { workflowRepository.getStateByStatus(workflowId, statusId) } returns state
        coEvery { workflowRepository.listTransitions(workflowId) } returns transitions

        val resolution = service.resolveWorkflowForSpec(spec)

        assertEquals(workflow, resolution.workflow)
        assertEquals(state, resolution.currentState)
        assertEquals(transitions, resolution.transitions)
    }

    @Test
    fun `resolveWorkflowForSpec throws when workflow missing`() = runTest {
        coEvery { workflowRepository.getWorkflowById(workflowId) } returns null

        assertFailsWith<WorkOpsNotFoundException> {
            service.resolveWorkflowForSpec(sampleSpec())
        }
    }

    @Test
    fun `resolveWorkflowForSpec throws when no state matches the spec status`() = runTest {
        coEvery { workflowRepository.getWorkflowById(workflowId) } returns sampleWorkflow()
        coEvery { workflowRepository.getStateByStatus(workflowId, statusId) } returns null

        assertFailsWith<WorkOpsNotFoundException> {
            service.resolveWorkflowForSpec(sampleSpec())
        }
    }

    @Test
    fun `repository backed reads and unresolved subtask count are delegated`() = runTest {
        val scheme = scheme(UUID.random(), workflowId)
        val workflow = sampleWorkflow()
        val state = sampleState()
        val transition = transition("Start", listOf(state.id.toString()))
        val taskId = UUID.random()
        coEvery { workflowRepository.listWorkflows() } returns listOf(workflow)
        coEvery { workflowRepository.getWorkflowById(workflow.id) } returns workflow
        coEvery { schemeRepository.listAll() } returns listOf(scheme)
        coEvery { schemeRepository.getById(scheme.id) } returns scheme
        coEvery { workflowRepository.listStates(workflow.id) } returns listOf(state)
        coEvery { workflowRepository.getStateById(state.id) } returns state
        coEvery { workflowRepository.listTransitions(workflow.id) } returns listOf(transition)
        coEvery { workflowQueryRepository.countUnresolvedSubtasks(taskId) } returns 7L

        assertEquals(listOf(workflow), service.listWorkflows())
        assertEquals(workflow, service.getWorkflow(workflow.id))
        assertEquals(listOf(scheme), service.listSchemes())
        assertEquals(scheme, service.getScheme(scheme.id))
        assertEquals(listOf(state), service.listStates(workflow.id))
        assertEquals(state, service.getState(state.id))
        assertEquals(listOf(transition), service.listTransitions(workflow.id))
        assertEquals(7, service.countUnresolvedSubtasks(taskId))
    }

    @Test
    fun `resolveWorkflowForTask uses its task type override`() = runTest {
        val taskTypeId = UUID.random()
        val task = sampleTask(taskTypeId = taskTypeId)
        val schemeId = UUID.random()
        val overrideWorkflowId = UUID.random()
        val project = project(task.projectId, schemeId)
        val scheme = scheme(
            schemeId,
            workflowId,
            JsonObject(mapOf(taskTypeId.toString() to JsonPrimitive(overrideWorkflowId.toString()))),
        )
        val workflow = Workflow(overrideWorkflowId, "Override")
        val state = WorkflowState(stateId, overrideWorkflowId, statusId, 0)
        val transitions = listOf(transition("Start", listOf(stateId.toString())).copy(workflowId = overrideWorkflowId))
        coEvery { projectService.getById(task.projectId) } returns project
        coEvery { schemeRepository.getById(schemeId) } returns scheme
        coEvery { workflowRepository.getWorkflowById(overrideWorkflowId) } returns workflow
        coEvery { workflowRepository.getStateByStatus(overrideWorkflowId, statusId) } returns state
        coEvery { workflowRepository.listTransitions(overrideWorkflowId) } returns transitions

        assertEquals(WorkflowResolution(workflow, state, transitions), service.resolveWorkflowForTask(task))
    }

    @Test
    fun `resolveWorkflowForTask falls back to the default for unusable override maps`() = runTest {
        val task = sampleTask()
        val schemeId = UUID.random()
        val project = project(task.projectId, schemeId)
        val workflow = sampleWorkflow()
        val state = sampleState()
        val unusableOverrides = listOf(
            JsonArray(emptyList()),
            JsonObject(emptyMap()),
            JsonObject(mapOf(task.taskTypeId.toString() to JsonObject(emptyMap()))),
            JsonObject(mapOf(task.taskTypeId.toString() to JsonPrimitive("not-a-uuid"))),
        )
        coEvery { projectService.getById(task.projectId) } returns project
        coEvery { workflowRepository.getWorkflowById(workflowId) } returns workflow
        coEvery { workflowRepository.getStateByStatus(workflowId, statusId) } returns state
        coEvery { workflowRepository.listTransitions(workflowId) } returns emptyList()

        for (overrides in unusableOverrides) {
            coEvery { schemeRepository.getById(schemeId) } returns scheme(schemeId, workflowId, overrides)
            assertEquals(workflow, service.resolveWorkflowForTask(task).workflow)
        }
    }

    @Test
    fun `resolveWorkflowForTask reports each missing configuration layer`() = runTest {
        val task = sampleTask()
        val schemeId = UUID.random()
        val configuredProject = project(task.projectId, schemeId)

        coEvery { projectService.getById(task.projectId) } returns null
        assertFailsWith<WorkOpsNotFoundException> { service.resolveWorkflowForTask(task) }

        coEvery { projectService.getById(task.projectId) } returns project(task.projectId, null)
        assertFailsWith<WorkOpsNotFoundException> { service.resolveWorkflowForTask(task) }

        coEvery { projectService.getById(task.projectId) } returns configuredProject
        coEvery { schemeRepository.getById(schemeId) } returns null
        assertFailsWith<WorkOpsNotFoundException> { service.resolveWorkflowForTask(task) }

        coEvery { schemeRepository.getById(schemeId) } returns scheme(schemeId, workflowId)
        coEvery { workflowRepository.getWorkflowById(workflowId) } returns null
        assertFailsWith<WorkOpsNotFoundException> { service.resolveWorkflowForTask(task) }

        coEvery { workflowRepository.getWorkflowById(workflowId) } returns sampleWorkflow()
        coEvery { workflowRepository.getStateByStatus(workflowId, statusId) } returns null
        assertFailsWith<WorkOpsNotFoundException> { service.resolveWorkflowForTask(task) }
    }

    @Test
    fun `matching filters to explicit from-state and wildcard transitions`() {
        val explicit = transition("Start Progress", listOf(stateId.toString()))
        val wildcard = transition("Reopen", listOf(WILDCARD_FROM_STATE))
        val unreachable = transition("Close", listOf(UUID.random().toString()))

        val matched = listOf(explicit, wildcard, unreachable).matching(stateId)

        assertEquals(listOf(explicit, wildcard), matched)
    }
}
