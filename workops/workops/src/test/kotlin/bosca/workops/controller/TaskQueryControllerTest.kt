package bosca.workops.controller

import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.workops.model.project.Project
import bosca.workops.model.task.Priority
import bosca.workops.model.task.Resolution
import bosca.workops.model.task.Task
import bosca.workops.model.task.TaskType
import bosca.workops.model.task.TaskTypeScheme
import bosca.workops.service.PriorityService
import bosca.workops.service.ProjectPermissionEvaluator
import bosca.workops.service.ProjectService
import bosca.workops.service.ResolutionService
import bosca.workops.service.TaskPermissionEvaluator
import bosca.workops.service.TaskService
import bosca.workops.service.TaskTypeSchemeService
import bosca.workops.service.TaskTypeService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TaskQueryControllerTest {

    private val taskService = mockk<TaskService>(relaxed = true)
    private val taskTypeService = mockk<TaskTypeService>(relaxed = true)
    private val taskTypeSchemeService = mockk<TaskTypeSchemeService>(relaxed = true)
    private val priorityService = mockk<PriorityService>(relaxed = true)
    private val resolutionService = mockk<ResolutionService>(relaxed = true)
    private val projectService = mockk<ProjectService>(relaxed = true)
    private val taskPermissions = mockk<TaskPermissionEvaluator>(relaxed = true)
    private val projectPermissions = mockk<ProjectPermissionEvaluator>(relaxed = true)
    private val groupEvaluator = mockk<GroupEvaluator>(relaxed = true)
    private val authentication = mockk<AuthenticationContext>(relaxed = true)
    private val principalId = UUID.random()
    private val profileId = UUID.random()

    private fun controller() = TaskQueryController(
        taskService,
        taskTypeService,
        taskTypeSchemeService,
        priorityService,
        resolutionService,
        projectService,
        taskPermissions,
        projectPermissions,
        groupEvaluator,
    )

    @Test
    fun `task lookups return only visible tasks and hide missing records`() = runTest {
        val visible = sampleTask("GIT-1")
        val denied = sampleTask("GIT-2")
        val missingId = UUID.random()
        coEvery { taskService.getById(missingId) } returns null
        coEvery { taskService.getById(visible.id) } returns visible
        coEvery { taskService.getById(denied.id) } returns denied
        coEvery { taskService.getByKey("MISSING") } returns null
        coEvery { taskService.getByKey(visible.key) } returns visible
        coEvery { taskService.getByKey(denied.key) } returns denied
        coEvery { taskPermissions.isAllowed(authentication, visible, PermissionAction.VIEW) } returns true
        coEvery { taskPermissions.isAllowed(authentication, denied, PermissionAction.VIEW) } returns false

        val controller = controller()
        assertNull(controller.task(authentication, missingId))
        assertSame(visible, controller.task(authentication, visible.id))
        assertNull(controller.task(authentication, denied.id))
        assertNull(controller.taskByKey(authentication, "MISSING"))
        assertSame(visible, controller.taskByKey(authentication, visible.key))
        assertNull(controller.taskByKey(authentication, denied.key))
    }

    @Test
    fun `project task lists verify view permission and handle missing projects`() = runTest {
        val project = sampleProject()
        val missingProjectId = UUID.random()
        val directTask = sampleTask("GIT-1").copy(projectId = project.id)
        val affectedTask = sampleTask("OTHER-1")
        coEvery { projectService.getById(missingProjectId) } returns null
        coEvery { projectService.getById(project.id) } returns project
        coEvery { taskService.listByProject(project.id, 2, 3) } returns listOf(directTask)
        coEvery { taskService.listByAffectedProject(project.id, 4, 5) } returns listOf(affectedTask)

        val controller = controller()
        assertTrue(controller.byProject(authentication, missingProjectId, 0, 10).isEmpty())
        assertTrue(controller.byAffectedProject(authentication, missingProjectId, 0, 10).isEmpty())
        assertEquals(listOf(directTask), controller.byProject(authentication, project.id, 2, 3))
        assertEquals(listOf(affectedTask), controller.byAffectedProject(authentication, project.id, 4, 5))
        coVerify(exactly = 2) {
            projectPermissions.verifyAllowed(authentication, project, PermissionAction.VIEW)
        }
    }

    @Test
    fun `task reference data queries delegate to owning services`() = runTest {
        val taskType = mockk<TaskType>()
        val scheme = mockk<TaskTypeScheme>()
        val priority = mockk<Priority>()
        val resolution = mockk<Resolution>()
        coEvery { taskTypeService.list() } returns listOf(taskType)
        coEvery { taskTypeSchemeService.list() } returns listOf(scheme)
        coEvery { priorityService.list() } returns listOf(priority)
        coEvery { resolutionService.list() } returns listOf(resolution)

        val controller = controller()
        assertEquals(listOf(taskType), controller.taskTypes())
        assertEquals(listOf(scheme), controller.taskTypeSchemes())
        assertEquals(listOf(priority), controller.priorities())
        assertEquals(listOf(resolution), controller.resolutions())
    }

    private fun sampleTask(key: String) = Task(
        id = UUID.random(),
        key = key,
        projectId = UUID.random(),
        taskTypeId = UUID.random(),
        statusId = UUID.random(),
        priorityId = UUID.random(),
        summary = "Task",
        reporterProfileId = profileId,
        createdByPrincipalId = principalId,
        modifiedByPrincipalId = principalId,
    )

    private fun sampleProject() = Project(
        id = UUID.random(),
        programId = UUID.random(),
        key = "GIT",
        name = "Git",
        ownerProfileId = profileId,
    )
}
