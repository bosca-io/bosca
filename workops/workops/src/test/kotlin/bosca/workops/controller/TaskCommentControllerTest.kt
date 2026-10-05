package bosca.workops.controller

import bosca.comments.model.CommentStatus
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.PermissionAction
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.workops.model.comment.TaskComment
import bosca.workops.model.comment.TaskCommentInput
import bosca.workops.model.notification.NotificationPreference
import bosca.workops.model.task.Task
import bosca.workops.service.NotificationPreferenceService
import bosca.workops.service.ProjectService
import bosca.workops.service.TaskCommentService
import bosca.workops.service.TaskPermissionEvaluator
import bosca.workops.service.TaskService
import bosca.workops.service.TaskWatcherService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TaskCommentControllerTest {

    private val service = mockk<TaskCommentService>(relaxed = true)
    private val taskService = mockk<TaskService>(relaxed = true)
    private val projectService = mockk<ProjectService>(relaxed = true)
    private val permissionEvaluator = mockk<TaskPermissionEvaluator>(relaxed = true)
    private val watcherService = mockk<TaskWatcherService>(relaxed = true)
    private val profileService = mockk<ProfileService>(relaxed = true)
    private val profilePermissions = mockk<ProfilePermissionEvaluator>(relaxed = true)
    private val preferenceService = mockk<NotificationPreferenceService>(relaxed = true)

    private val principalId = UUID.random()
    private val profileId = UUID.random()

    private fun authenticated(primaryProfileId: UUID? = profileId): AuthenticationContext =
        ImpersonatedAuthenticationContext(
            Principal(principalId, primaryProfileId = primaryProfileId),
            listOf(Group(UUID.random(), "users", "", GroupType.SYSTEM)),
        )

    private fun queryController() = TaskCommentQueryController(
        service,
        taskService,
        permissionEvaluator,
        profileService,
    )

    private fun mutationController() = TaskCommentMutationController(
        service,
        taskService,
        projectService,
        permissionEvaluator,
        watcherService,
        profileService,
        preferenceService,
    )

    @Test
    fun `comment fields resolve profiles and visible replies`() = runTest {
        val task = sampleTask()
        val comment = sampleComment(task.id)
        val reply = sampleComment(task.id, id = 2, parentId = comment.id)
        val profile = mockk<Profile>()
        val authentication = authenticated()
        coEvery { profileService.getById(profileId) } returns profile
        coEvery { profilePermissions.isAllowed(authentication, profile, PermissionAction.VIEW) } returns true
        coEvery { taskService.getById(task.id) } returns task
        coEvery { permissionEvaluator.isAllowed(authentication, task, PermissionAction.VIEW) } returns true
        coEvery { permissionEvaluator.isAllowed(authentication, task, PermissionAction.MANAGE) } returns true
        coEvery { service.listReplies(task.id, comment.id, profileId, true, 3, 4) } returns listOf(reply)

        val controller = TaskCommentTypeController(
            service,
            taskService,
            permissionEvaluator,
            profileService,
            profilePermissions,
        )
        assertEquals(comment.id, controller.id(comment))
        assertEquals(comment.parentId, controller.parentId(comment))
        assertEquals(comment.taskId, controller.taskId(comment))
        assertEquals(comment.profileId, controller.profileId(comment))
        assertEquals(comment.impersonatorId, controller.impersonatorId(comment))
        assertSame(profile, controller.profile(authentication, comment))
        assertEquals(comment.visibility, controller.visibility(comment))
        assertEquals(comment.created, controller.created(comment))
        assertEquals(comment.modified, controller.modified(comment))
        assertEquals(comment.status, controller.status(comment))
        assertEquals(comment.content, controller.content(comment))
        assertEquals(comment.likes, controller.likes(comment))
        assertEquals(comment.deleted, controller.deleted(comment))
        assertEquals(listOf(reply), controller.replies(comment, authentication, 3, 4))

        coEvery { profilePermissions.isAllowed(authentication, profile, PermissionAction.VIEW) } returns false
        assertNull(controller.profile(authentication, comment))
    }

    @Test
    fun `comment replies fail closed and resolve absent viewing profiles`() = runTest {
        val task = sampleTask()
        val comment = sampleComment(task.id)
        val authentication = authenticated()
        val fallbackAuthentication = authenticated(primaryProfileId = null)
        val unauthenticated = AuthenticationContext(null, null)
        val fallbackProfile = mockk<Profile>()
        every { fallbackProfile.id } returns profileId
        val controller = TaskCommentTypeController(
            service,
            taskService,
            permissionEvaluator,
            profileService,
            profilePermissions,
        )

        coEvery { taskService.getById(task.id) } returns null
        assertTrue(controller.replies(comment, authentication, 0, 10).isEmpty())

        coEvery { taskService.getById(task.id) } returns task
        assertTrue(controller.replies(comment, null, 0, 10).isEmpty())

        coEvery { permissionEvaluator.isAllowed(authentication, task, PermissionAction.VIEW) } returns false
        assertTrue(controller.replies(comment, authentication, 0, 10).isEmpty())

        coEvery { permissionEvaluator.isAllowed(unauthenticated, task, PermissionAction.VIEW) } returns true
        coEvery { permissionEvaluator.isAllowed(unauthenticated, task, PermissionAction.MANAGE) } returns false
        coEvery { service.listReplies(task.id, comment.id, null, false, 0, 10) } returns emptyList()
        assertTrue(controller.replies(comment, unauthenticated, 0, 10).isEmpty())

        coEvery { permissionEvaluator.isAllowed(fallbackAuthentication, task, PermissionAction.VIEW) } returns true
        coEvery { permissionEvaluator.isAllowed(fallbackAuthentication, task, PermissionAction.MANAGE) } returns false
        coEvery { profileService.getByPrincipal(principalId) } returns listOf(fallbackProfile)
        coEvery { service.listReplies(task.id, comment.id, profileId, false, 1, 2) } returns emptyList()
        assertTrue(controller.replies(comment, fallbackAuthentication, 1, 2).isEmpty())
    }

    @Test
    fun `task comment queries return profile scoped results`() = runTest {
        val task = sampleTask()
        val comment = sampleComment(task.id)
        val reply = sampleComment(task.id, id = 2, parentId = comment.id)
        val fallbackProfile = mockk<Profile>()
        val primaryAuthentication = authenticated()
        val fallbackAuthentication = authenticated(primaryProfileId = null)
        every { fallbackProfile.id } returns profileId
        coEvery { taskService.getById(task.id) } returns task
        coEvery { permissionEvaluator.isAllowed(any<AuthenticationContext>(), task, PermissionAction.VIEW) } returns true
        coEvery { permissionEvaluator.isAllowed(primaryAuthentication, task, PermissionAction.MANAGE) } returns true
        coEvery { permissionEvaluator.isAllowed(fallbackAuthentication, task, PermissionAction.MANAGE) } returns false
        coEvery { profileService.getByPrincipal(principalId) } returns listOf(fallbackProfile)
        coEvery { service.list(task.id, profileId, true, 1, 2) } returns listOf(comment)
        coEvery { service.count(task.id, profileId, false) } returns 3
        coEvery { service.listReplies(task.id, comment.id, profileId, true, 4, 5) } returns listOf(reply)
        coEvery { service.get(task.id, comment.id, profileId, true) } returns comment

        val controller = queryController()
        assertEquals(listOf(comment), controller.forTask(primaryAuthentication, task.id, 1, 2))
        assertEquals(3, controller.countForTask(fallbackAuthentication, task.id))
        assertEquals(listOf(reply), controller.repliesFor(primaryAuthentication, task.id, comment.id, 4, 5))
        assertEquals(comment, controller.comment(primaryAuthentication, task.id, comment.id))
    }

    @Test
    fun `task comment queries fail closed for missing or invisible tasks`() = runTest {
        val task = sampleTask()
        val missingId = UUID.random()
        val authentication = authenticated()
        coEvery { taskService.getById(missingId) } returns null
        coEvery { taskService.getById(task.id) } returns task
        coEvery { permissionEvaluator.isAllowed(authentication, task, PermissionAction.VIEW) } returns false

        val controller = queryController()
        assertTrue(controller.forTask(authentication, missingId, 0, 10).isEmpty())
        assertEquals(0, controller.countForTask(authentication, missingId))
        assertTrue(controller.repliesFor(authentication, missingId, 1, 0, 10).isEmpty())
        assertNull(controller.comment(authentication, missingId, 1))
        assertTrue(controller.forTask(authentication, task.id, 0, 10).isEmpty())
        assertEquals(0, controller.countForTask(authentication, task.id))
        assertTrue(controller.repliesFor(authentication, task.id, 1, 0, 10).isEmpty())
        assertNull(controller.comment(authentication, task.id, 1))
    }

    @Test
    fun `task comment queries pass null viewing profile for unauthenticated context`() = runTest {
        val task = sampleTask()
        val authentication = AuthenticationContext(null, null)
        coEvery { taskService.getById(task.id) } returns task
        coEvery { permissionEvaluator.isAllowed(authentication, task, PermissionAction.VIEW) } returns true
        coEvery { permissionEvaluator.isAllowed(authentication, task, PermissionAction.MANAGE) } returns false
        coEvery { service.list(task.id, null, false, 0, 10) } returns emptyList()

        assertTrue(queryController().forTask(authentication, task.id, 0, 10).isEmpty())
    }

    @Test
    fun `comment mutations attribute actors watch commenters and deduplicate mentions`() = runTest {
        val task = sampleTask()
        val authentication = authenticated()
        val mentionId = UUID.random()
        val mentionedProfile = Profile(
            id = mentionId,
            type = ProfileType.GENERIC,
            name = "Ada Lovelace",
            visibility = ProfileVisibility.PUBLIC,
        )
        val input = TaskCommentInput(content = "Ask @ada-lovelace, then remind @ada-lovelace")
        val comment = sampleComment(task.id)
        coEvery { taskService.getById(task.id) } returns task
        coEvery { service.add(task.id, input, principalId, profileId) } returns comment
        coEvery { preferenceService.get(profileId) } returns null
        coEvery { profileService.getBySlug("ada-lovelace") } returns mentionedProfile
        coEvery { service.like(task.id, comment.id, profileId) } returns 1
        coEvery { service.unlike(task.id, comment.id, profileId) } returns 0

        val controller = mutationController()
        assertEquals(comment, controller.add(authentication, task.id, input))
        assertEquals(1, controller.like(authentication, task.id, comment.id))
        assertEquals(0, controller.unlike(authentication, task.id, comment.id))
        assertTrue(controller.setStatus(authentication, task.id, comment.id, CommentStatus.APPROVED))
        assertTrue(controller.delete(authentication, task.id, comment.id))

        coVerify(exactly = 1) { permissionEvaluator.verifyAllowed(authentication, task, PermissionAction.EDIT) }
        coVerify(exactly = 2) { permissionEvaluator.verifyAllowed(authentication, task, PermissionAction.VIEW) }
        coVerify(exactly = 2) { permissionEvaluator.verifyAllowed(authentication, task, PermissionAction.MANAGE) }
        coVerify(exactly = 1) { watcherService.add(task.id, profileId) }
        coVerify(exactly = 1) { watcherService.add(task.id, mentionId) }
        coVerify(exactly = 1) {
            service.setStatus(task.id, comment.id, CommentStatus.APPROVED, principalId, profileId)
        }
        coVerify(exactly = 1) { service.delete(task.id, comment.id, principalId, profileId) }
    }

    @Test
    fun `comment add resolves fallback profile and honors disabled auto watch`() = runTest {
        val task = sampleTask()
        val authentication = authenticated(primaryProfileId = null)
        val fallbackProfile = mockk<Profile>()
        val input = TaskCommentInput(content = "No mentions")
        val comment = sampleComment(task.id)
        every { fallbackProfile.id } returns profileId
        coEvery { taskService.getById(task.id) } returns task
        coEvery { profileService.getByPrincipal(principalId) } returns listOf(fallbackProfile)
        coEvery { preferenceService.get(profileId) } returns
            NotificationPreference(profileId = profileId, watchCommented = false)
        coEvery { service.add(task.id, input, principalId, profileId) } returns comment

        assertEquals(comment, mutationController().add(authentication, task.id, input))

        coVerify(exactly = 0) { watcherService.add(any(), any()) }
    }

    @Test
    fun `comment mutations require profiles only where the service contract does`() = runTest {
        val task = sampleTask()
        val authentication = authenticated(primaryProfileId = null)
        coEvery { taskService.getById(task.id) } returns task
        coEvery { profileService.getByPrincipal(principalId) } returns emptyList()

        val controller = mutationController()
        assertFailsWith<IllegalStateException> {
            controller.add(authentication, task.id, TaskCommentInput(content = "No profile"))
        }
        assertFailsWith<IllegalStateException> { controller.like(authentication, task.id, 1) }
        assertFailsWith<IllegalStateException> { controller.unlike(authentication, task.id, 1) }
        assertTrue(controller.setStatus(authentication, task.id, 1, CommentStatus.PENDING))
        assertTrue(controller.delete(authentication, task.id, 1))

        coVerify(exactly = 1) { service.setStatus(task.id, 1, CommentStatus.PENDING, principalId, null) }
        coVerify(exactly = 1) { service.delete(task.id, 1, principalId, null) }
    }

    @Test
    fun `comment mutations reject missing tasks and unauthenticated callers`() = runTest {
        val task = sampleTask()
        val missingId = UUID.random()
        val authenticated = authenticated()
        val unauthenticated = AuthenticationContext(null, null)
        val input = TaskCommentInput(content = "Comment")
        coEvery { taskService.getById(missingId) } returns null
        coEvery { taskService.getById(task.id) } returns task

        val controller = mutationController()
        assertFailsWith<IllegalStateException> { controller.add(authenticated, missingId, input) }
        assertFailsWith<IllegalStateException> { controller.like(authenticated, missingId, 1) }
        assertFailsWith<IllegalStateException> { controller.unlike(authenticated, missingId, 1) }
        assertFailsWith<IllegalStateException> {
            controller.setStatus(authenticated, missingId, 1, CommentStatus.APPROVED)
        }
        assertFailsWith<IllegalStateException> { controller.delete(authenticated, missingId, 1) }

        assertFailsWith<IllegalStateException> { controller.add(unauthenticated, task.id, input) }
        assertFailsWith<IllegalStateException> { controller.like(unauthenticated, task.id, 1) }
        assertFailsWith<IllegalStateException> { controller.unlike(unauthenticated, task.id, 1) }
        assertFailsWith<IllegalStateException> {
            controller.setStatus(unauthenticated, task.id, 1, CommentStatus.APPROVED)
        }
        assertFailsWith<IllegalStateException> { controller.delete(unauthenticated, task.id, 1) }
    }

    private fun sampleTask() = Task(
        id = UUID.random(),
        key = "GIT-42",
        projectId = UUID.random(),
        taskTypeId = UUID.random(),
        statusId = UUID.random(),
        priorityId = UUID.random(),
        summary = "Task",
        reporterProfileId = profileId,
        createdByPrincipalId = principalId,
        modifiedByPrincipalId = principalId,
    )

    private fun sampleComment(
        taskId: UUID,
        id: Long = 1,
        parentId: Long? = null,
    ) = TaskComment(
        id = id,
        parentId = parentId,
        taskId = taskId,
        profileId = profileId,
        visibility = ProfileVisibility.USER,
        created = OffsetDateTime.now(),
        modified = OffsetDateTime.now(),
        status = CommentStatus.APPROVED,
        content = "Comment",
    )
}
