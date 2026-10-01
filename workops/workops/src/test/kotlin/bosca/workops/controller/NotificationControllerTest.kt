package bosca.workops.controller

import bosca.profile.model.Profile
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.PermissionAction
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.serialization.UUID
import bosca.workops.model.bql.SavedFilter
import bosca.workops.model.notification.Notification
import bosca.workops.model.notification.NotificationChannel
import bosca.workops.model.notification.NotificationPreference
import bosca.workops.model.notification.NotificationScheme
import bosca.workops.model.notification.NotificationSubscription
import bosca.workops.model.notification.TaskWatcher
import bosca.workops.model.task.Task
import bosca.workops.service.NotificationInboxService
import bosca.workops.service.NotificationPreferenceService
import bosca.workops.service.NotificationSchemeService
import bosca.workops.service.NotificationSubscriptionService
import bosca.workops.service.SavedFilterService
import bosca.workops.service.TaskPermissionEvaluator
import bosca.workops.service.TaskService
import bosca.workops.service.TaskWatcherService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Permission-focused tests for NotificationsMutationController.
 *
 * Pre-fix:
 *   - watch/unwatch never checked VIEW on the task (subscribed to private tasks)
 *   - subscribe took any savedFilterId, didn't check ownership
 *
 * Post-fix:
 *   - watch verifies VIEW on the task
 *   - subscribe verifies the saved filter is owned by the caller (or admin)
 */
class NotificationControllerTest {

    private val inbox = mockk<NotificationInboxService>(relaxed = true)
    private val schemeService = mockk<NotificationSchemeService>(relaxed = true)
    private val preferenceService = mockk<NotificationPreferenceService>(relaxed = true)
    private val watcherService = mockk<TaskWatcherService>(relaxed = true)
    private val subscriptionService = mockk<NotificationSubscriptionService>(relaxed = true)
    private val taskService = mockk<TaskService>()
    private val taskPermissions = mockk<TaskPermissionEvaluator>()
    private val savedFilterService = mockk<SavedFilterService>()
    private val groupEvaluator = mockk<GroupEvaluator>(relaxed = true)
    private val profileService = mockk<bosca.profile.profile.service.ProfileService>(relaxed = true)

    private val principalId = UUID.random()
    private val profileId = UUID.random()
    private val otherProfileId = UUID.random()

    private fun authenticated(profile: UUID? = profileId): AuthenticationContext {
        val principal = Principal(id = principalId, primaryProfileId = profile)
        val groups = listOf(Group(id = UUID.random(), name = "users", description = "", type = GroupType.SYSTEM))
        return ImpersonatedAuthenticationContext(principal, groups)
    }

    private fun controller() = NotificationsMutationController(
        inbox = inbox,
        preferenceService = preferenceService,
        watcherService = watcherService,
        subscriptionService = subscriptionService,
        taskService = taskService,
        taskPermissions = taskPermissions,
        savedFilterService = savedFilterService,
        groupEvaluator = groupEvaluator,
        profileService = profileService,
    )

    private fun queryController() = NotificationsQueryController(
        inbox = inbox,
        schemeService = schemeService,
        preferenceService = preferenceService,
        watcherService = watcherService,
        subscriptionService = subscriptionService,
        taskService = taskService,
        taskPermissions = taskPermissions,
        profileService = profileService,
    )

    @Test
    fun `notification queries return inbox configuration watchers and subscriptions`() = runTest {
        val authentication = authenticated()
        val task = sampleTask()
        val notification = Notification(profileId = profileId, event = "TASK_UPDATED", body = "Updated")
        val scheme = NotificationScheme(name = "Default")
        val preference = NotificationPreference(profileId)
        val watcher = TaskWatcher(task.id, profileId)
        val subscription = NotificationSubscription(UUID.random(), profileId, "0 9 * * *", "UTC")
        coEvery { inbox.list(profileId, 1, 2) } returns listOf(notification)
        coEvery { inbox.listUnread(profileId, 3, 4) } returns listOf(notification)
        coEvery { inbox.unreadCount(profileId) } returns 5
        coEvery { schemeService.list() } returns listOf(scheme)
        coEvery { schemeService.getById(scheme.id) } returns scheme
        coEvery { preferenceService.get(profileId) } returns preference
        coEvery { taskService.getById(task.id) } returns task
        coEvery { taskPermissions.isAllowed(authentication, task, PermissionAction.VIEW) } returns true
        coEvery { watcherService.list(task.id) } returns listOf(watcher)
        coEvery { subscriptionService.listForProfile(profileId) } returns listOf(subscription)

        val controller = queryController()
        assertEquals(listOf(notification), controller.mine(authentication, 1, 2))
        assertEquals(listOf(notification), controller.unread(authentication, 3, 4))
        assertEquals(5, controller.unreadCount(authentication))
        assertEquals(listOf(scheme), controller.schemes(authentication))
        assertSame(scheme, controller.scheme(authentication, scheme.id))
        assertSame(preference, controller.myPreferences(authentication))
        assertEquals(listOf(watcher), controller.watchers(authentication, task.id))
        assertEquals(listOf(subscription), controller.mySubscriptions(authentication))
    }

    @Test
    fun `notification queries resolve fallback profiles and fail closed without one`() = runTest {
        val fallbackAuthentication = authenticated(profile = null)
        val fallbackProfile = mockk<Profile>()
        every { fallbackProfile.id } returns profileId
        coEvery { profileService.getByPrincipal(principalId) } returns listOf(fallbackProfile)
        coEvery { inbox.list(profileId, 0, 10) } returns emptyList()
        assertTrue(queryController().mine(fallbackAuthentication, 0, 10).isEmpty())

        coEvery { profileService.getByPrincipal(principalId) } returns emptyList()
        val controller = queryController()
        assertTrue(controller.mine(fallbackAuthentication, 0, 10).isEmpty())
        assertTrue(controller.unread(fallbackAuthentication, 0, 10).isEmpty())
        assertEquals(0, controller.unreadCount(fallbackAuthentication))
        assertNull(controller.myPreferences(fallbackAuthentication))
        assertTrue(controller.mySubscriptions(fallbackAuthentication).isEmpty())

        val unauthenticated = AuthenticationContext(null, null)
        assertTrue(controller.mine(unauthenticated, 0, 10).isEmpty())
    }

    @Test
    fun `notification watcher queries reject missing and invisible tasks`() = runTest {
        val task = sampleTask()
        val missingId = UUID.random()
        val authentication = authenticated()
        coEvery { taskService.getById(missingId) } returns null
        coEvery { taskService.getById(task.id) } returns task
        coEvery { taskPermissions.isAllowed(authentication, task, PermissionAction.VIEW) } returns false

        assertTrue(queryController().watchers(authentication, missingId).isEmpty())
        assertTrue(queryController().watchers(authentication, task.id).isEmpty())
    }

    @Test
    fun `notification mutations update inbox preferences watches and subscriptions`() = runTest {
        val authentication = authenticated()
        val notificationId = UUID.random()
        val task = sampleTask()
        val savedFilterId = UUID.random()
        val filter = sampleFilter(savedFilterId, profileId)
        val preference = NotificationPreference(profileId)
        val subscription = NotificationSubscription(savedFilterId, profileId, "0 9 * * *", "UTC")
        val mutedTaskId = UUID.random()
        val mutedProjectId = UUID.random()
        val input = NotificationPreferenceUpdateInput(
            eventChannels = JsonObject(
                mapOf(
                    "TASK_UPDATED" to JsonArray(
                        listOf(
                            JsonPrimitive("IN_APP"),
                            JsonPrimitive("EMAIL"),
                            JsonPrimitive("INVALID"),
                            JsonObject(emptyMap()),
                        ),
                    ),
                    "TASK_CREATED" to JsonPrimitive("not-an-array"),
                ),
            ),
            watchAuthored = false,
            watchCommented = true,
            dailyDigest = true,
            dndStartLocal = "22:00",
            dndEndLocal = "07:00",
            mutedTaskIds = listOf(mutedTaskId),
            mutedProjectIds = listOf(mutedProjectId),
        )
        val captured = slot<bosca.workops.service.NotificationPreferenceInput>()
        coEvery { preferenceService.upsert(profileId, capture(captured)) } returns preference
        coEvery { taskService.getById(task.id) } returns task
        coEvery { taskPermissions.verifyAllowed(authentication, task, PermissionAction.VIEW) } returns Unit
        coEvery { savedFilterService.getById(savedFilterId) } returns filter
        coEvery {
            subscriptionService.upsert(savedFilterId, profileId, "0 9 * * *", "UTC")
        } returns subscription

        val controller = controller()
        assertTrue(controller.markRead(authentication, notificationId))
        assertTrue(controller.markAllRead(authentication))
        assertSame(preference, controller.updatePreferences(authentication, input))
        assertTrue(controller.watch(authentication, task.id))
        assertTrue(controller.unwatch(authentication, task.id))
        assertSame(subscription, controller.subscribe(authentication, savedFilterId, "0 9 * * *", "UTC"))
        assertTrue(controller.unsubscribe(authentication, savedFilterId))

        assertEquals(setOf(NotificationChannel.IN_APP, NotificationChannel.EMAIL), captured.captured.eventChannels["TASK_UPDATED"])
        assertEquals(emptySet(), captured.captured.eventChannels["TASK_CREATED"])
        assertFalse(captured.captured.watchAuthored)
        assertTrue(captured.captured.watchCommented)
        assertTrue(captured.captured.dailyDigest)
        assertEquals(listOf(mutedTaskId), captured.captured.mutedTaskIds)
        assertEquals(listOf(mutedProjectId), captured.captured.mutedProjectIds)
        coVerify { inbox.markRead(notificationId, profileId) }
        coVerify { inbox.markAllRead(profileId) }
        coVerify { watcherService.remove(task.id, profileId) }
        coVerify { subscriptionService.delete(savedFilterId, profileId) }
    }

    @Test
    fun `notification mutations fail closed without a profile and accept non object channels`() = runTest {
        val noProfile = authenticated(profile = null)
        val task = sampleTask()
        coEvery { profileService.getByPrincipal(principalId) } returns emptyList()
        coEvery { taskService.getById(task.id) } returns task
        coEvery { taskPermissions.verifyAllowed(noProfile, task, PermissionAction.VIEW) } returns Unit
        val input = NotificationPreferenceUpdateInput(
            eventChannels = JsonPrimitive("not-an-object"),
            watchAuthored = true,
            watchCommented = true,
            dailyDigest = false,
            dndStartLocal = null,
            dndEndLocal = null,
            mutedTaskIds = emptyList(),
            mutedProjectIds = emptyList(),
        )
        val controller = controller()

        assertFalse(controller.markRead(noProfile, UUID.random()))
        assertFalse(controller.markAllRead(noProfile))
        assertNull(controller.updatePreferences(noProfile, input))
        assertFalse(controller.watch(noProfile, task.id))
        assertFalse(controller.unwatch(noProfile, task.id))
        assertFailsWith<IllegalStateException> {
            controller.subscribe(noProfile, UUID.random(), "0 9 * * *", "UTC")
        }
        assertFailsWith<IllegalStateException> { controller.unsubscribe(noProfile, UUID.random()) }

        val fallbackProfile = mockk<Profile>()
        every { fallbackProfile.id } returns profileId
        coEvery { profileService.getByPrincipal(principalId) } returns listOf(fallbackProfile)
        coEvery { preferenceService.upsert(profileId, match { it.eventChannels.isEmpty() }) } returns
            NotificationPreference(profileId)
        assertEquals(profileId, controller.updatePreferences(noProfile, input)?.profileId)
    }

    // --- watch ---

    @Test
    fun `watch returns false when task does not exist`() = runTest {
        val taskId = UUID.random()
        coEvery { taskService.getById(taskId) } returns null

        assertFalse(controller().watch(authenticated(), taskId))
        coVerify(exactly = 0) { watcherService.add(any(), any()) }
    }

    @Test
    fun `watch throws when caller lacks VIEW on task`() = runTest {
        val task = sampleTask()
        coEvery { taskService.getById(task.id) } returns task
        coEvery {
            taskPermissions.verifyAllowed(any(), task, PermissionAction.VIEW)
        } throws SecurityException("denied")

        assertFailsWith<SecurityException> {
            controller().watch(authenticated(), task.id)
        }
        coVerify(exactly = 0) { watcherService.add(any(), any()) }
    }

    @Test
    fun `watch succeeds when caller has VIEW`() = runTest {
        val task = sampleTask()
        coEvery { taskService.getById(task.id) } returns task
        coEvery { taskPermissions.verifyAllowed(any(), task, PermissionAction.VIEW) } returns Unit

        assertTrue(controller().watch(authenticated(), task.id))
        coVerify { watcherService.add(task.id, profileId) }
    }

    // --- subscribe ---

    @Test
    fun `subscribe throws when caller is not the saved filter owner and not admin`() = runTest {
        val filterId = UUID.random()
        val filter = sampleFilter(id = filterId, ownerProfileId = otherProfileId)
        coEvery { savedFilterService.getById(filterId) } returns filter
        coEvery { groupEvaluator.hasAdminGroup(any()) } returns false
        coEvery { groupEvaluator.throwUnauthorized() } throws SecurityException("denied")

        assertFailsWith<SecurityException> {
            controller().subscribe(authenticated(), filterId, "0 9 * * *", "UTC")
        }
        coVerify(exactly = 0) { subscriptionService.upsert(any(), any(), any(), any()) }
    }

    @Test
    fun `subscribe errors when saved filter not found`() = runTest {
        val filterId = UUID.random()
        coEvery { savedFilterService.getById(filterId) } returns null

        assertFailsWith<IllegalStateException> {
            controller().subscribe(authenticated(), filterId, "0 9 * * *", "UTC")
        }
    }

    @Test
    fun `subscribe succeeds when caller owns the filter`() = runTest {
        val filterId = UUID.random()
        val filter = sampleFilter(id = filterId, ownerProfileId = profileId)
        coEvery { savedFilterService.getById(filterId) } returns filter
        coEvery {
            subscriptionService.upsert(filterId, profileId, "0 9 * * *", "UTC")
        } returns mockk(relaxed = true)

        controller().subscribe(authenticated(), filterId, "0 9 * * *", "UTC")
        coVerify { subscriptionService.upsert(filterId, profileId, "0 9 * * *", "UTC") }
    }

    @Test
    fun `subscribe succeeds when caller is admin even if not owner`() = runTest {
        val filterId = UUID.random()
        val filter = sampleFilter(id = filterId, ownerProfileId = otherProfileId)
        coEvery { savedFilterService.getById(filterId) } returns filter
        coEvery { groupEvaluator.hasAdminGroup(any()) } returns true
        coEvery {
            subscriptionService.upsert(filterId, profileId, "0 9 * * *", "UTC")
        } returns mockk(relaxed = true)

        controller().subscribe(authenticated(), filterId, "0 9 * * *", "UTC")
        coVerify { subscriptionService.upsert(filterId, profileId, "0 9 * * *", "UTC") }
    }

    // --- helpers ---

    private fun sampleTask(): Task = Task(
        id = UUID.random(), key = "P-${UUID.random()}", projectId = UUID.random(),
        taskTypeId = UUID.random(), statusId = UUID.random(), priorityId = UUID.random(),
        summary = "t", reporterProfileId = profileId,
        createdByPrincipalId = principalId, modifiedByPrincipalId = principalId,
    )

    private fun sampleFilter(id: UUID, ownerProfileId: UUID): SavedFilter {
        val now = bosca.serialization.OffsetDateTime.now()
        return SavedFilter(
            id = id, ownerProfileId = ownerProfileId, name = "F",
            description = null, bqlSource = "status = todo",
            parsedAst = JsonObject(emptyMap()),
            createdAt = now, modifiedAt = now, version = 0,
        )
    }
}
