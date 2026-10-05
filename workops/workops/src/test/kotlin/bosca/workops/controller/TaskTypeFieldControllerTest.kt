package bosca.workops.controller

import bosca.profile.model.Profile
import bosca.profile.profile.service.ProfileService
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.PermissionAction
import bosca.security.model.Principal
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.workops.model.project.Project
import bosca.workops.model.release.TaskAffectedProject
import bosca.workops.model.task.Task
import bosca.workops.model.task.TaskTypeScheme
import bosca.workops.model.workflow.WorkflowState
import bosca.workops.model.workflow.WorkflowTransition
import bosca.workops.service.*
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TaskTypeFieldControllerTest {

    private val projectService = mockk<ProjectService>()
    private val taskTypeService = mockk<TaskTypeService>()
    private val statusService = mockk<StatusService>()
    private val priorityService = mockk<PriorityService>()
    private val resolutionService = mockk<ResolutionService>()
    private val taskService = mockk<TaskService>()
    private val workflowService = mockk<WorkflowService>()
    private val taskLinkService = mockk<TaskLinkService>()
    private val taskCommentService = mockk<TaskCommentService>()
    private val customFieldService = mockk<TaskCustomFieldService>()
    private val profileService = mockk<ProfileService>()
    private val profilePermissions = mockk<ProfilePermissionEvaluator>()
    private val permissionEvaluator = mockk<TaskPermissionEvaluator>()
    private val milestoneService = mockk<MilestoneService>()
    private val affectedProjectService = mockk<TaskAffectedProjectService>()
    private val requirementService = mockk<RequirementService>()

    private val principalId = UUID.random()
    private val profileId = UUID.random()
    private val authentication = ImpersonatedAuthenticationContext(
        Principal(id = principalId, primaryProfileId = profileId),
        listOf(Group(UUID.random(), "users", "", GroupType.SYSTEM)),
    )

    private fun controller() = TaskTypeFieldController(
        projectService,
        taskTypeService,
        statusService,
        priorityService,
        resolutionService,
        taskService,
        workflowService,
        taskLinkService,
        taskCommentService,
        customFieldService,
        profileService,
        profilePermissions,
        permissionEvaluator,
        milestoneService,
        affectedProjectService,
        requirementService,
    )

    @Test
    fun `related fields resolve through services and enforce visibility`() = runTest {
        val task = task(withRelationships = true)
        val project = project(task.projectId)
        val affectedProject = project(UUID.random())
        val missingAffectedProjectId = UUID.random()
        val reporter = mockk<Profile>()
        val assignee = mockk<Profile>()
        val taskType = mockk<bosca.workops.model.task.TaskType>()
        val status = mockk<bosca.workops.model.workflow.Status>()
        val priority = mockk<bosca.workops.model.task.Priority>()
        val resolution = mockk<bosca.workops.model.task.Resolution>()
        val parent = task().copy(id = task.parentTaskId!!)
        val epic = task().copy(id = task.epicTaskId!!)
        val milestone = mockk<bosca.workops.model.milestone.Milestone>()
        val requirement = mockk<bosca.workops.model.requirement.Requirement>()
        val links = listOf(mockk<bosca.workops.model.links.TaskLink>())
        val history = listOf(mockk<bosca.workops.model.audit.TaskHistoryEntry>())
        val comments = listOf(mockk<bosca.workops.model.comment.TaskComment>())
        val permissions = listOf(mockk<bosca.security.model.EntityPermission>())
        val filteredFields = JsonObject(mapOf("visible" to JsonPrimitive(true)))

        coEvery { projectService.getById(task.projectId) } returns project
        coEvery { projectService.getById(affectedProject.id) } returns affectedProject
        coEvery { projectService.getById(missingAffectedProjectId) } returns null
        coEvery { taskTypeService.getById(task.taskTypeId) } returns taskType
        coEvery { statusService.getById(task.statusId) } returns status
        coEvery { priorityService.getById(task.priorityId) } returns priority
        coEvery { resolutionService.getById(task.resolutionId!!) } returns resolution
        coEvery { taskService.getById(task.parentTaskId!!) } returns parent
        coEvery { taskService.getById(task.epicTaskId!!) } returns epic
        coEvery { milestoneService.getById(task.milestoneId!!) } returns milestone
        coEvery { profileService.getById(task.reporterProfileId) } returns reporter
        coEvery { profileService.getById(task.assigneeProfileId!!) } returns assignee
        coEvery { profilePermissions.isAllowed(authentication, any<Profile>(), PermissionAction.VIEW) } returns true
        coEvery { taskService.listHistory(task.id, 2, 3) } returns history
        coEvery { taskLinkService.listForTask(task.id) } returns links
        coEvery { permissionEvaluator.isAllowed(authentication, task, PermissionAction.MANAGE) } returns true
        coEvery { permissionEvaluator.verifyAllowed(authentication, task, PermissionAction.MANAGE) } returns Unit
        coEvery { taskCommentService.list(task.id, profileId, true, 4, 5) } returns comments
        coEvery { customFieldService.filterForRead(project, task, true) } returns filteredFields
        coEvery { taskService.getPermissions(task) } returns permissions
        coEvery { requirementService.getByTaskId(task.id) } returns requirement
        coEvery { affectedProjectService.listForTask(task.id) } returns listOf(
            TaskAffectedProject(task.id, affectedProject.id),
            TaskAffectedProject(task.id, missingAffectedProjectId),
        )

        val currentState = WorkflowState(workflowId = UUID.random(), statusId = task.statusId, displayOrder = 0)
        val current = WorkflowTransition(
            workflowId = currentState.workflowId,
            name = "Start",
            fromStateIds = listOf(currentState.id.toString()),
            toStateId = UUID.random(),
        )
        val other = current.copy(id = UUID.random(), name = "Other", fromStateIds = listOf(UUID.random().toString()))
        coEvery { workflowService.resolveWorkflowForTask(task) } returns
            WorkflowResolution(mockk(), currentState, listOf(current, other))

        val controller = controller()
        assertSame(milestone, controller.milestone(task))
        assertSame(reporter, controller.reporter(authentication, task))
        assertSame(assignee, controller.assignee(authentication, task))
        assertSame(project, controller.project(task))
        assertSame(taskType, controller.taskType(task))
        assertSame(status, controller.status(task))
        assertSame(priority, controller.priority(task))
        assertSame(resolution, controller.resolution(task))
        assertSame(parent, controller.parentTask(task))
        assertSame(epic, controller.epicTask(task))
        assertEquals(history, controller.history(task, 2, 3))
        assertEquals(listOf(current), controller.transitions(task, true))
        assertEquals(listOf(current, other), controller.transitions(task, false))
        assertEquals(links, controller.links(task))
        assertEquals(comments, controller.comments(task, authentication, 4, 5))
        assertEquals(filteredFields, controller.customFields(task, authentication))
        assertEquals(permissions, controller.permissions(authentication, task))
        assertEquals(listOf(affectedProject), controller.affectedProjects(task))
        assertEquals(task.customFieldValues, controller.customFieldValuesRaw(task, authentication))
        assertSame(requirement, controller.requirement(task))
        coVerify { permissionEvaluator.verifyAllowed(authentication, task, PermissionAction.MANAGE) }
    }

    @Test
    fun `nullable and denied fields fail closed`() = runTest {
        val task = task()
        val reporter = mockk<Profile>()
        coEvery { profileService.getById(task.reporterProfileId) } returns reporter
        coEvery { profilePermissions.isAllowed(authentication, reporter, PermissionAction.VIEW) } returns false
        coEvery { projectService.getById(task.projectId) } returns null
        coEvery { taskTypeService.getById(task.taskTypeId) } returns null
        coEvery { statusService.getById(task.statusId) } returns null
        coEvery { priorityService.getById(task.priorityId) } returns null
        coEvery { permissionEvaluator.isAllowed(authentication, task, PermissionAction.MANAGE) } returns false
        coEvery { taskCommentService.list(task.id, null, false, 0, 10) } returns emptyList()
        coEvery { taskService.getPermissions(task) } returns emptyList()
        coEvery { affectedProjectService.listForTask(task.id) } returns emptyList()

        val controller = controller()
        assertNull(controller.milestone(task))
        assertNull(controller.reporter(authentication, task))
        assertNull(controller.assignee(authentication, task))
        assertNull(controller.resolution(task))
        assertNull(controller.parentTask(task))
        assertNull(controller.epicTask(task))
        assertTrue(controller.comments(task, null, 0, 10).isEmpty())
        assertEquals(task.customFieldValues, controller.customFields(task, null))
        assertTrue(controller.permissions(authentication, task).isEmpty())
        assertTrue(controller.affectedProjects(task).isEmpty())
        assertFailsWith<IllegalStateException> { controller.project(task) }
        assertFailsWith<IllegalStateException> { controller.taskType(task) }
        assertFailsWith<IllegalStateException> { controller.status(task) }
        assertFailsWith<IllegalStateException> { controller.priority(task) }
        coVerify(exactly = 0) { taskService.getPermissions(task) }
    }

    @Test
    fun `comment viewing profile falls back from principal identity and handles an empty authentication`() = runTest {
        val task = task()
        val fallbackProfile = mockk<Profile> {
            every { id } returns profileId
        }
        val principalWithoutProfile = Principal(id = principalId, primaryProfileId = null)
        val fallbackAuthentication = ImpersonatedAuthenticationContext(principalWithoutProfile, emptyList())
        coEvery { profileService.getByPrincipal(principalId) } returns listOf(fallbackProfile)
        coEvery { permissionEvaluator.isAllowed(fallbackAuthentication, task, PermissionAction.MANAGE) } returns false
        coEvery { taskCommentService.list(task.id, profileId, false, 0, 10) } returns emptyList()

        val emptyAuthentication = AuthenticationContext(null, null)
        coEvery { permissionEvaluator.isAllowed(emptyAuthentication, task, PermissionAction.MANAGE) } returns false
        coEvery { taskCommentService.list(task.id, null, false, 0, 10) } returns emptyList()

        assertTrue(controller().comments(task, fallbackAuthentication, 0, 10).isEmpty())
        assertTrue(controller().comments(task, emptyAuthentication, 0, 10).isEmpty())

        coEvery { profileService.getByPrincipal(principalId) } returns emptyList()
        coEvery { taskCommentService.list(task.id, null, false, 0, 10) } returns emptyList()
        assertTrue(controller().comments(task, fallbackAuthentication, 0, 10).isEmpty())
    }

    @Test
    fun `assignee visibility and custom field management fail closed independently`() = runTest {
        val task = task(withRelationships = true)
        val assignee = mockk<Profile>()
        val project = project(task.projectId)
        coEvery { profileService.getById(task.assigneeProfileId!!) } returns assignee
        coEvery { profilePermissions.isAllowed(authentication, assignee, PermissionAction.VIEW) } returns false
        coEvery { projectService.getById(task.projectId) } returns project
        coEvery { customFieldService.filterForRead(project, task, false) } returns task.customFieldValues

        assertNull(controller().assignee(authentication, task))
        assertEquals(task.customFieldValues, controller().customFields(task, null))
    }

    @Test
    fun `task type scheme resolves its default and ordered types`() = runTest {
        val defaultId = UUID.random()
        val otherId = UUID.random()
        val scheme = TaskTypeScheme(name = "Default", taskTypeIds = listOf(defaultId, otherId), defaultTaskTypeId = defaultId)
        val defaultType = mockk<bosca.workops.model.task.TaskType>()
        val types = listOf(defaultType, mockk())
        coEvery { taskTypeService.getById(defaultId) } returns defaultType
        coEvery { taskTypeService.getByIds(scheme.taskTypeIds) } returns types
        val controller = TaskTypeSchemeFieldController(taskTypeService)

        assertSame(defaultType, controller.defaultTaskType(scheme))
        assertEquals(types, controller.taskTypes(scheme))

        coEvery { taskTypeService.getById(defaultId) } returns null
        assertFailsWith<IllegalStateException> { controller.defaultTaskType(scheme) }
    }

    private fun task(withRelationships: Boolean = false): Task {
        val resolutionId = UUID.random().takeIf { withRelationships }
        return Task(
            id = UUID.random(),
            key = "GIT-42",
            projectId = UUID.random(),
            taskTypeId = UUID.random(),
            statusId = UUID.random(),
            priorityId = UUID.random(),
            summary = "Task",
            reporterProfileId = UUID.random(),
            assigneeProfileId = UUID.random().takeIf { withRelationships },
            parentTaskId = UUID.random().takeIf { withRelationships },
            epicTaskId = UUID.random().takeIf { withRelationships },
            milestoneId = UUID.random().takeIf { withRelationships },
            resolutionId = resolutionId,
            resolutionAt = bosca.serialization.OffsetDateTime.now().takeIf { resolutionId != null },
            customFieldValues = JsonObject(mapOf("raw" to JsonPrimitive(true))),
            createdByPrincipalId = principalId,
            modifiedByPrincipalId = principalId,
        )
    }

    private fun project(id: UUID) = Project(
        id = id,
        programId = UUID.random(),
        key = "P",
        name = "Project",
        ownerProfileId = profileId,
    )
}
