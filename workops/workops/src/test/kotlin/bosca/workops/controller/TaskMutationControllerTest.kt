package bosca.workops.controller

import bosca.profile.model.Profile
import bosca.profile.profile.service.ProfileService
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.PermissionAction
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.serialization.UUID
import bosca.workops.model.project.Project
import bosca.workops.model.task.CreateTaskInput
import bosca.workops.model.task.Task
import bosca.workops.model.task.UpdateTaskInput
import bosca.workops.repository.TaskPermissionRepository
import bosca.workops.service.ProjectPermissionEvaluator
import bosca.workops.service.ProjectService
import bosca.workops.service.TaskAffectedProjectService
import bosca.workops.service.TaskPermissionEvaluator
import bosca.workops.service.TaskService
import bosca.workops.service.TaskWatcherService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TaskMutationControllerTest {

    private val service = mockk<TaskService>(relaxed = true)
    private val projectService = mockk<ProjectService>(relaxed = true)
    private val profileService = mockk<ProfileService>(relaxed = true)
    private val permissionEvaluator = mockk<TaskPermissionEvaluator>(relaxed = true)
    private val projectPermissionEvaluator = mockk<ProjectPermissionEvaluator>(relaxed = true)
    private val watcherService = mockk<TaskWatcherService>(relaxed = true)
    private val permissionRepo = mockk<TaskPermissionRepository>(relaxed = true)
    private val affectedProjectService = mockk<TaskAffectedProjectService>(relaxed = true)

    private val principalId = UUID.random()
    private val profileId = UUID.random()

    private fun authenticated(primaryProfileId: UUID? = profileId): AuthenticationContext =
        ImpersonatedAuthenticationContext(
            Principal(id = principalId, primaryProfileId = primaryProfileId),
            listOf(Group(UUID.random(), "users", "", GroupType.SYSTEM)),
        )

    private fun controller() = TaskMutationController(
        service = service,
        projectService = projectService,
        profileService = profileService,
        permissionEvaluator = permissionEvaluator,
        projectPermissionEvaluator = projectPermissionEvaluator,
        watcherService = watcherService,
        permissionRepo = permissionRepo,
        affectedProjectService = affectedProjectService,
    )

    @Test
    fun `create attributes reporter and watches reporter plus assignee`() = runTest {
        val project = sampleProject()
        val assigneeId = UUID.random()
        val taskId = UUID.random()
        val input = CreateTaskInput(projectId = project.id, summary = "Ship release", assigneeProfileId = assigneeId)
        val task = sampleTask(id = taskId, assigneeProfileId = assigneeId)
        coEvery { projectService.getById(project.id) } returns project
        coEvery { service.create(input, principalId, profileId, profileId) } returns task

        assertEquals(task, controller().create(authenticated(), input))

        coVerify(exactly = 1) { projectPermissionEvaluator.verifyAllowed(any(), project, PermissionAction.EDIT) }
        coVerify(exactly = 1) { watcherService.add(taskId, profileId) }
        coVerify(exactly = 1) { watcherService.add(taskId, assigneeId) }
    }

    @Test
    fun `create resolves reporter from principal profiles and skips absent assignee watcher`() = runTest {
        val project = sampleProject()
        val input = CreateTaskInput(projectId = project.id, summary = "Ship release")
        val profile = mockk<Profile>()
        val taskId = UUID.random()
        val task = sampleTask(taskId)
        every { profile.id } returns profileId
        coEvery { projectService.getById(project.id) } returns project
        coEvery { profileService.getByPrincipal(principalId) } returns listOf(profile)
        coEvery { service.create(input, principalId, profileId, profileId) } returns task

        assertEquals(task, controller().create(authenticated(primaryProfileId = null), input))

        coVerify(exactly = 1) { watcherService.add(taskId, profileId) }
        coVerify(exactly = 1) { watcherService.add(any(), any()) }
    }

    @Test
    fun `update adds a watcher only when a non null assignee changes`() = runTest {
        val originalAssigneeId = UUID.random()
        val replacementAssigneeId = UUID.random()
        val changedId = UUID.random()
        val unchangedId = UUID.random()
        val clearedId = UUID.random()
        val changed = sampleTask(changedId, originalAssigneeId)
        val unchanged = sampleTask(unchangedId, originalAssigneeId)
        val cleared = sampleTask(clearedId, originalAssigneeId)
        val changedResult = sampleTask(changedId, replacementAssigneeId)
        val unchangedResult = sampleTask(unchangedId, originalAssigneeId)
        val clearedResult = sampleTask(clearedId, null)
        val input = UpdateTaskInput(summary = "Updated", expectedVersion = 1)
        coEvery { service.getById(changedId) } returns changed
        coEvery { service.getById(unchangedId) } returns unchanged
        coEvery { service.getById(clearedId) } returns cleared
        coEvery { service.update(changedId, input, principalId, profileId) } returns changedResult
        coEvery { service.update(unchangedId, input, principalId, profileId) } returns unchangedResult
        coEvery { service.update(clearedId, input, principalId, profileId) } returns clearedResult

        assertEquals(changedResult, controller().update(authenticated(), changedId, input))
        assertEquals(unchangedResult, controller().update(authenticated(), unchangedId, input))
        assertEquals(clearedResult, controller().update(authenticated(), clearedId, input))

        coVerify(exactly = 1) { watcherService.add(changedId, replacementAssigneeId) }
        coVerify(exactly = 1) { watcherService.add(any(), any()) }
    }

    @Test
    fun `delete restore transition and document creation carry actor identity`() = runTest {
        val task = sampleTask()
        val transitionId = UUID.random()
        val resolutionId = UUID.random()
        val capturedPermissions = slot<Set<PermissionAction>>()
        coEvery { service.getById(task.id) } returns task
        coEvery { service.softDelete(task.id, 2, principalId, profileId) } returns task
        coEvery { service.restore(task.id, 3, principalId, profileId) } returns task
        coEvery { permissionEvaluator.isAllowed(any<AuthenticationContext>(), task, any()) } answers {
            thirdArg<PermissionAction>() in setOf(PermissionAction.EDIT, PermissionAction.EXECUTE)
        }
        coEvery {
            service.transition(
                task.id,
                transitionId,
                4,
                principalId,
                profileId,
                capture(capturedPermissions),
                resolutionId,
                "Ready",
            )
        } returns task
        coEvery { service.createDocument(task.id, 5, principalId, profileId) } returns task

        assertEquals(task, controller().softDelete(authenticated(), task.id, 2))
        assertEquals(task, controller().restore(authenticated(), task.id, 3))
        assertEquals(
            task,
            controller().transition(authenticated(), task.id, transitionId, 4, resolutionId, "Ready"),
        )
        assertEquals(task, controller().createDocument(authenticated(), task.id, 5))
        assertEquals(setOf(PermissionAction.EDIT, PermissionAction.EXECUTE), capturedPermissions.captured)

        coVerify(exactly = 2) { permissionEvaluator.verifyAllowed(any(), task, PermissionAction.DELETE) }
        coVerify(exactly = 2) { permissionEvaluator.verifyAllowed(any(), task, PermissionAction.EDIT) }
    }

    @Test
    fun `task permissions and affected projects verify access before repository changes`() = runTest {
        val taskId = UUID.random()
        val task = sampleTask(taskId)
        val groupId = UUID.random()
        val projectId = UUID.random()
        coEvery { service.getById(taskId) } returns task

        assertTrue(controller().addPermission(authenticated(), taskId, groupId, PermissionAction.VIEW))
        assertTrue(controller().removePermission(authenticated(), taskId, groupId, PermissionAction.VIEW))
        assertTrue(controller().addAffectedProject(authenticated(), taskId, projectId))
        assertTrue(controller().removeAffectedProject(authenticated(), taskId, projectId))

        coVerify(exactly = 1) { permissionRepo.add(taskId, groupId, PermissionAction.VIEW) }
        coVerify(exactly = 1) { permissionRepo.delete(taskId, groupId, PermissionAction.VIEW) }
        coVerify(exactly = 1) { affectedProjectService.add(taskId, projectId) }
        coVerify(exactly = 1) { affectedProjectService.remove(taskId, projectId) }
        coVerify(exactly = 2) { permissionEvaluator.verifyAllowed(any(), task, PermissionAction.MANAGE) }
        coVerify(exactly = 2) { permissionEvaluator.verifyAllowed(any(), task, PermissionAction.EDIT) }
    }

    @Test
    fun `task mutations fail before side effects when required context is absent`() = runTest {
        val missingId = UUID.random()
        val missingProjectInput = CreateTaskInput(projectId = missingId, summary = "Missing")
        coEvery { projectService.getById(missingId) } returns null
        coEvery { service.getById(missingId) } returns null
        assertFailsWith<IllegalStateException> { controller().create(authenticated(), missingProjectInput) }
        assertFailsWith<IllegalStateException> {
            controller().update(authenticated(), missingId, UpdateTaskInput(expectedVersion = 0))
        }
        assertFailsWith<IllegalStateException> { controller().softDelete(authenticated(), missingId, 0) }
        assertFailsWith<IllegalStateException> { controller().restore(authenticated(), missingId, 0) }
        assertFailsWith<IllegalStateException> {
            controller().transition(authenticated(), missingId, UUID.random(), 0)
        }
        assertFailsWith<IllegalStateException> { controller().createDocument(authenticated(), missingId, 0) }
        assertFailsWith<IllegalStateException> {
            controller().addPermission(authenticated(), missingId, UUID.random(), PermissionAction.VIEW)
        }
        assertFailsWith<IllegalStateException> {
            controller().removePermission(authenticated(), missingId, UUID.random(), PermissionAction.VIEW)
        }
        assertFailsWith<IllegalStateException> {
            controller().addAffectedProject(authenticated(), missingId, UUID.random())
        }
        assertFailsWith<IllegalStateException> {
            controller().removeAffectedProject(authenticated(), missingId, UUID.random())
        }

        val project = sampleProject()
        coEvery { projectService.getById(project.id) } returns project
        assertFailsWith<IllegalStateException> {
            controller().create(
                AuthenticationContext(null, null),
                CreateTaskInput(projectId = project.id, summary = "Unauthenticated"),
            )
        }

        val task = sampleTask()
        coEvery { service.getById(task.id) } returns task
        assertFailsWith<IllegalStateException> {
            controller().update(AuthenticationContext(null, null), task.id, UpdateTaskInput(expectedVersion = 0))
        }
        assertFailsWith<IllegalStateException> {
            controller().softDelete(AuthenticationContext(null, null), task.id, 0)
        }
        assertFailsWith<IllegalStateException> {
            controller().restore(AuthenticationContext(null, null), task.id, 0)
        }
        assertFailsWith<IllegalStateException> {
            controller().transition(AuthenticationContext(null, null), task.id, UUID.random(), 0)
        }
        assertFailsWith<IllegalStateException> {
            controller().createDocument(AuthenticationContext(null, null), task.id, 0)
        }

        coEvery { profileService.getByPrincipal(principalId) } returns emptyList()
        assertFailsWith<IllegalStateException> {
            controller().create(
                authenticated(primaryProfileId = null),
                CreateTaskInput(projectId = project.id, summary = "No profile"),
            )
        }
    }

    private fun sampleProject(): Project = Project(
        id = UUID.random(),
        programId = UUID.random(),
        key = "PROJECT",
        name = "Project",
        ownerProfileId = profileId,
    )

    private fun sampleTask(
        id: UUID = UUID.random(),
        assigneeProfileId: UUID? = null,
    ): Task {
        val taskId = id
        val assignedProfileId = assigneeProfileId
        return mockk {
            every { this@mockk.id } returns taskId
            every { this@mockk.assigneeProfileId } returns assignedProfileId
        }
    }
}
