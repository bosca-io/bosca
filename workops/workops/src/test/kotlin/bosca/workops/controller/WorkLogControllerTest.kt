package bosca.workops.controller

import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.PermissionAction
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.workops.model.task.Task
import bosca.workops.model.worklog.WorkLog
import bosca.workops.model.worklog.WorkLogInput
import bosca.workops.model.worklog.WorklogVisibility
import bosca.workops.service.TaskPermissionEvaluator
import bosca.workops.service.TaskService
import bosca.workops.service.WorkLogService
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WorkLogControllerTest {

    private val service = mockk<WorkLogService>(relaxed = true)
    private val taskService = mockk<TaskService>(relaxed = true)
    private val permissions = mockk<TaskPermissionEvaluator>(relaxed = true)
    private val profileService = mockk<ProfileService>(relaxed = true)

    private val principalId = UUID.random()
    private val profileId = UUID.random()
    private val task = task()
    private val log = worklog(task.id, profileId)
    private val input = WorkLogInput(
        timeSpent = "1h 30m",
        startedAt = OffsetDateTime.now(),
        comment = "Implementation",
        visibility = WorklogVisibility.ROLE,
    )

    private fun authentication(primaryProfileId: UUID? = profileId): AuthenticationContext =
        ImpersonatedAuthenticationContext(
            Principal(principalId, primaryProfileId = primaryProfileId),
            listOf(Group(UUID.random(), "users", "", GroupType.SYSTEM)),
        )

    @Test
    fun `type fields expose the complete worklog model`() {
        val controller = WorkLogTypeController()

        assertEquals(log.id, controller.id(log))
        assertEquals(log.taskId, controller.taskId(log))
        assertEquals(log.profileId, controller.profileId(log))
        assertEquals(log.timeSpentSeconds, controller.timeSpentSeconds(log))
        assertEquals("1h 1m 1s", controller.timeSpentShort(log))
        assertEquals(log.startedAt, controller.startedAt(log))
        assertEquals(log.comment, controller.comment(log))
        assertEquals(log.worklogVisibility, controller.visibility(log))
        assertEquals(log.createdAt, controller.createdAt(log))
        assertEquals(log.modifiedAt, controller.modifiedAt(log))
    }

    @Test
    fun `for task returns only visible task worklogs`() = runTest {
        val authentication = authentication()
        val controller = WorklogQueryController(service, taskService, permissions)
        coEvery { taskService.getById(task.id) } returns null
        assertEquals(emptyList(), controller.forTask(authentication, task.id, 2, 3))

        coEvery { taskService.getById(task.id) } returns task
        coEvery { permissions.isAllowed(authentication, task, PermissionAction.VIEW) } returns false
        assertEquals(emptyList(), controller.forTask(authentication, task.id, 2, 3))

        coEvery { permissions.isAllowed(authentication, task, PermissionAction.VIEW) } returns true
        coEvery { service.listForTask(task.id, 2, 3) } returns listOf(log)
        assertEquals(listOf(log), controller.forTask(authentication, task.id, 2, 3))
    }

    @Test
    fun `worklog query checks existence task and view permission`() = runTest {
        val authentication = authentication()
        val controller = WorklogQueryController(service, taskService, permissions)
        coEvery { service.get(log.id) } returns null
        assertNull(controller.worklog(authentication, log.id))

        coEvery { service.get(log.id) } returns log
        coEvery { taskService.getById(task.id) } returns null
        assertNull(controller.worklog(authentication, log.id))

        coEvery { taskService.getById(task.id) } returns task
        coEvery { permissions.isAllowed(authentication, task, PermissionAction.VIEW) } returns false
        assertNull(controller.worklog(authentication, log.id))

        coEvery { permissions.isAllowed(authentication, task, PermissionAction.VIEW) } returns true
        assertSame(log, controller.worklog(authentication, log.id))
    }

    @Test
    fun `log work uses the principal primary profile`() = runTest {
        val authentication = authentication()
        coEvery { taskService.getById(task.id) } returns task
        coEvery { service.log(task.id, profileId, input) } returns log

        val result = mutation().logWork(authentication, task.id, input)

        assertSame(log, result)
        coVerify(exactly = 1) { permissions.verifyAllowed(authentication, task, PermissionAction.EDIT) }
        coVerify(exactly = 0) { profileService.getByPrincipal(any()) }
    }

    @Test
    fun `log work falls back to the first principal profile`() = runTest {
        val authentication = authentication(primaryProfileId = null)
        coEvery { taskService.getById(task.id) } returns task
        coEvery { profileService.getByPrincipal(principalId) } returns listOf(profile(profileId))
        coEvery { service.log(task.id, profileId, input) } returns log

        assertSame(log, mutation().logWork(authentication, task.id, input))
    }

    @Test
    fun `log work rejects missing task principal and profile`() = runTest {
        val controller = mutation()
        val authenticated = authentication()
        coEvery { taskService.getById(task.id) } returns null
        assertFailsWith<IllegalStateException> { controller.logWork(authenticated, task.id, input) }

        coEvery { taskService.getById(task.id) } returns task
        assertFailsWith<IllegalStateException> {
            controller.logWork(AuthenticationContext(null, null), task.id, input)
        }

        val noProfile = authentication(primaryProfileId = null)
        coEvery { profileService.getByPrincipal(principalId) } returns emptyList()
        assertFailsWith<IllegalStateException> { controller.logWork(noProfile, task.id, input) }
    }

    @Test
    fun `update own worklog requires edit permission`() = runTest {
        val authentication = authentication()
        coEvery { service.get(log.id) } returns log
        coEvery { taskService.getById(task.id) } returns task
        coEvery { service.update(log.id, input) } returns log

        assertSame(log, mutation().updateWorklog(authentication, log.id, input))
        coVerify(exactly = 1) { permissions.verifyAllowed(authentication, task, PermissionAction.EDIT) }
        coVerify(exactly = 0) { permissions.verifyAllowed(authentication, task, PermissionAction.MANAGE) }
    }

    @Test
    fun `update another profile worklog requires manage permission and supports profile fallback`() = runTest {
        val authentication = authentication(primaryProfileId = null)
        val otherLog = log.copy(profileId = UUID.random())
        coEvery { service.get(otherLog.id) } returns otherLog
        coEvery { taskService.getById(task.id) } returns task
        coEvery { profileService.getByPrincipal(principalId) } returns listOf(profile(profileId))
        coEvery { service.update(otherLog.id, input) } returns otherLog

        assertSame(otherLog, mutation().updateWorklog(authentication, otherLog.id, input))
        coVerify(exactly = 1) { permissions.verifyAllowed(authentication, task, PermissionAction.MANAGE) }
    }

    @Test
    fun `update rejects missing worklog task principal and profile`() = runTest {
        val controller = mutation()
        val authenticated = authentication()
        coEvery { service.get(log.id) } returns null
        assertFailsWith<IllegalStateException> { controller.updateWorklog(authenticated, log.id, input) }

        coEvery { service.get(log.id) } returns log
        coEvery { taskService.getById(task.id) } returns null
        assertFailsWith<IllegalStateException> { controller.updateWorklog(authenticated, log.id, input) }

        coEvery { taskService.getById(task.id) } returns task
        assertFailsWith<IllegalStateException> {
            controller.updateWorklog(AuthenticationContext(null, null), log.id, input)
        }

        val noProfile = authentication(primaryProfileId = null)
        coEvery { profileService.getByPrincipal(principalId) } returns emptyList()
        assertFailsWith<IllegalStateException> { controller.updateWorklog(noProfile, log.id, input) }
    }

    @Test
    fun `delete returns false for missing worklog or task`() = runTest {
        val controller = mutation()
        val authentication = authentication()
        coEvery { service.get(log.id) } returns null
        assertFalse(controller.deleteWorklog(authentication, log.id))

        coEvery { service.get(log.id) } returns log
        coEvery { taskService.getById(task.id) } returns null
        assertFalse(controller.deleteWorklog(authentication, log.id))
    }

    @Test
    fun `delete uses edit for owner and manage for another profile`() = runTest {
        val authentication = authentication()
        coEvery { service.get(log.id) } returns log
        coEvery { taskService.getById(task.id) } returns task
        coEvery { service.delete(log.id) } returns true
        assertTrue(mutation().deleteWorklog(authentication, log.id))
        coVerify(exactly = 1) { permissions.verifyAllowed(authentication, task, PermissionAction.EDIT) }

        clearMocks(permissions, answers = false, recordedCalls = true)
        val otherLog = log.copy(profileId = UUID.random())
        coEvery { service.get(log.id) } returns otherLog
        assertTrue(mutation().deleteWorklog(authentication, log.id))
        coVerify(exactly = 1) { permissions.verifyAllowed(authentication, task, PermissionAction.MANAGE) }
    }

    @Test
    fun `delete supports profile fallback and rejects missing identity`() = runTest {
        val controller = mutation()
        coEvery { service.get(log.id) } returns log
        coEvery { taskService.getById(task.id) } returns task
        assertFailsWith<IllegalStateException> {
            controller.deleteWorklog(AuthenticationContext(null, null), log.id)
        }

        val noProfile = authentication(primaryProfileId = null)
        coEvery { profileService.getByPrincipal(principalId) } returns emptyList()
        assertFailsWith<IllegalStateException> { controller.deleteWorklog(noProfile, log.id) }

        coEvery { profileService.getByPrincipal(principalId) } returns listOf(profile(profileId))
        coEvery { service.delete(log.id) } returns true
        assertTrue(controller.deleteWorklog(noProfile, log.id))
    }

    private fun mutation() = WorklogMutationController(service, taskService, permissions, profileService)

    private fun task() = Task(
        id = UUID.random(),
        key = "GIT-1",
        projectId = UUID.random(),
        taskTypeId = UUID.random(),
        statusId = UUID.random(),
        priorityId = UUID.random(),
        summary = "Task",
        reporterProfileId = profileId,
        createdByPrincipalId = principalId,
        modifiedByPrincipalId = principalId,
    )

    private fun worklog(taskId: UUID, profileId: UUID) = WorkLog(
        id = UUID.random(),
        taskId = taskId,
        profileId = profileId,
        timeSpentSeconds = 3661,
        startedAt = OffsetDateTime.now(),
        comment = "Implementation",
        worklogVisibility = WorklogVisibility.ROLE,
    )

    private fun profile(id: UUID) = Profile(
        id = id,
        type = ProfileType.GENERIC,
        name = "Profile",
        visibility = ProfileVisibility.USER,
    )
}
