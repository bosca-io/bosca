@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.workops.service

import bosca.db.ConnectionPool
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.pipelines.model.Pipeline
import bosca.pipelines.model.PipelineAwaitingApproval
import bosca.pipelines.service.PipelineService
import bosca.pubsub.PubSubService
import bosca.profile.attribute.model.ProfileAttribute
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.Permission
import bosca.security.model.PermissionAction
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.workops.model.notification.NotificationChannel
import bosca.workops.model.notification.NotificationDelivery
import bosca.workops.model.notification.NotificationEvent
import bosca.workops.model.notification.NotificationPreference
import bosca.workops.model.notification.NotificationRecipient
import bosca.workops.model.notification.TaskWatcher
import bosca.workops.model.notification.TypedNotificationScheme
import bosca.workops.model.permission.ProjectPermission
import bosca.workops.model.project.Project
import bosca.workops.model.requirement.Requirement
import bosca.workops.model.requirement.RequirementParent
import bosca.workops.model.spec.Spec
import bosca.workops.model.task.Task
import bosca.workops.model.workflow.Status
import bosca.workops.model.workflow.StatusCategory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationDispatcherTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `deliver exits cleanly when notification context cannot resolve`() = runTest {
        val fixture = fixture()
        try {
            val missingSpecId = UUID.random()
            val unresolvedSpecId = UUID.random()
            val unresolvedProjectId = UUID.random()
            val missingSchemeProject = project(defaultSchemeId = UUID.random())
            val missingEventProject = project(defaultSchemeId = UUID.random())
            val emptyEventProject = project(defaultSchemeId = UUID.random())
            val unresolvedSpec = mockk<bosca.workops.model.spec.Spec> {
                every { projectId } returns unresolvedProjectId
            }
            coEvery { fixture.specService.getById(missingSpecId) } returns null
            coEvery { fixture.specService.getByIdIncludingDeleted(missingSpecId) } returns null
            coEvery { fixture.specService.getById(unresolvedSpecId) } returns unresolvedSpec
            coEvery { fixture.projectService.getById(unresolvedProjectId) } returns null
            coEvery { fixture.projectService.getById(missingSchemeProject.id) } returns missingSchemeProject
            coEvery { fixture.projectService.getById(missingEventProject.id) } returns missingEventProject
            coEvery { fixture.projectService.getById(emptyEventProject.id) } returns emptyEventProject
            coEvery { fixture.schemeService.typed(missingSchemeProject.defaultNotificationSchemeId ?: error("missing scheme")) } returns null
            coEvery { fixture.schemeService.typed(missingEventProject.defaultNotificationSchemeId ?: error("missing scheme")) } returns
                    typed(emptyMap())
            coEvery { fixture.schemeService.typed(emptyEventProject.defaultNotificationSchemeId ?: error("missing scheme")) } returns
                    typed(mapOf("TASK_UPDATED" to emptyList()))

            fixture.dispatcher.deliver("TASK_UPDATED", null, null, null)
            fixture.dispatcher.deliver("TASK_UPDATED", null, null, missingSpecId)
            fixture.dispatcher.deliver("TASK_UPDATED", null, null, unresolvedSpecId)
            fixture.dispatcher.deliver("TASK_UPDATED", missingSchemeProject.id, null, null)
            fixture.dispatcher.deliver("TASK_UPDATED", missingEventProject.id, null, null)
            fixture.dispatcher.deliver("TASK_UPDATED", emptyEventProject.id, null, null)
            fixture.dispatcher.deliver(eventName = "TASK_UPDATED", sourceId = UUID.random())
            fixture.dispatcher.deliver(
                eventName = "TASK_UPDATED",
                projectId = null,
                taskId = null,
                specId = null,
                requirementId = null,
                actorProfileId = null,
                mentionedProfileIds = emptySet(),
                commentId = null,
                eventAssigneeProfileId = null,
                eventAssigneeKnown = false,
            )

            coVerify(exactly = 0) { fixture.inboxService.addOnce(any(), any(), any(), any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) {
                fixture.outboxService.enqueueOnce(any(), any(), any(), any(), any(), any(), any())
            }
        } finally {
            fixture.dispatcher.close()
        }
    }

    @Test
    fun `deliver resolves task recipients preferences and every channel`() = runTest {
        val fixture = fixture()
        try {
            val schemeId = UUID.random()
            val project = project(defaultSchemeId = schemeId)
            val reporterId = UUID.random()
            val assigneeId = UUID.random()
            val watcherId = UUID.random()
            val explicitProfileId = UUID.random()
            val task = task(project.id, reporterId, assigneeId)
            val preference = mockk<NotificationPreference>(relaxed = true)
            val recipients = listOf(
                NotificationRecipient.Reporter,
                NotificationRecipient.Assignee,
                NotificationRecipient.CurrentAssignee,
                NotificationRecipient.Watchers,
                NotificationRecipient.Profile(explicitProfileId),
                NotificationRecipient.MentionedUsers,
            )
            coEvery { fixture.projectService.getById(project.id) } returns project
            coEvery { fixture.schemeService.typed(schemeId) } returns typed(mapOf("TASK_UPDATED" to recipients))
            coEvery { fixture.taskService.getById(task.id) } returns task
            coEvery { fixture.watcherService.list(task.id) } returns listOf(
                TaskWatcher(task.id, reporterId),
                TaskWatcher(task.id, watcherId),
            )
            coEvery { fixture.preferenceService.get(reporterId) } returns null
            coEvery { fixture.preferenceService.get(assigneeId) } returns preference
            coEvery { fixture.preferenceService.get(watcherId) } returns preference
            coEvery { fixture.preferenceService.get(explicitProfileId) } returns preference
            coEvery { fixture.preferenceService.decodedChannels(preference, "TASK_UPDATED") } returnsMany listOf(
                emptySet(),
                setOf(NotificationChannel.EMAIL, NotificationChannel.WEBHOOK, NotificationChannel.SLACK),
                setOf(NotificationChannel.IN_APP, NotificationChannel.EMAIL),
            )

            fixture.dispatcher.deliver("TASK_UPDATED", project.id, task.id, null)

            coVerify(exactly = 1) {
                fixture.inboxService.addOnce(
                    any(),
                    reporterId,
                    "TASK_UPDATED",
                    task.id,
                    project.id,
                    null,
                    "task updated",
                    "/workops/tasks/${task.id}",
                )
            }
            coVerify(exactly = 1) {
                fixture.inboxService.addOnce(
                    any(),
                    explicitProfileId,
                    "TASK_UPDATED",
                    task.id,
                    project.id,
                    null,
                    "task updated",
                    "/workops/tasks/${task.id}",
                )
            }
            for (channel in listOf(NotificationChannel.WEBHOOK, NotificationChannel.SLACK)) {
                coVerify(exactly = 1) {
                    fixture.outboxService.enqueueOnce(
                        any(),
                        "TASK_UPDATED",
                        channel,
                        watcherId.toString(),
                        match {
                            val payload = json.parseToJsonElement(it).jsonObject
                            payload["event"]?.jsonPrimitive?.content == "TASK_UPDATED" &&
                                    payload["taskId"]?.jsonPrimitive?.content == task.id.toString() &&
                                    payload["projectId"]?.jsonPrimitive?.content == project.id.toString()
                        },
                        null,
                        false,
                    )
                }
            }
            coVerify(exactly = 1) {
                fixture.outboxService.enqueueOnce(
                    any(),
                    "TASK_UPDATED",
                    NotificationChannel.EMAIL,
                    watcherId.toString(),
                    match {
                        json.parseToJsonElement(it).jsonObject["entityId"]?.jsonPrimitive?.content == task.id.toString()
                    },
                    null,
                    false,
                )
            }
            coVerify(exactly = 1) {
                fixture.outboxService.enqueueOnce(
                    any(),
                    "TASK_UPDATED",
                    NotificationChannel.EMAIL,
                    explicitProfileId.toString(),
                    any(),
                    null,
                    false,
                )
            }
            coVerify(exactly = 0) {
                fixture.inboxService.addOnce(any(), assigneeId, any(), any(), any(), any(), any(), any())
            }
        } finally {
            fixture.dispatcher.close()
        }
    }

    @Test
    fun `deliver resolves groups project permission groups and profile custom fields`() = runTest {
        val fixture = fixture()
        try {
            val schemeId = UUID.random()
            val project = project(defaultSchemeId = schemeId)
            val groupId = UUID.random()
            val projectGroupId = UUID.random()
            val groupProfileId = UUID.random()
            val projectGroupProfileId = UUID.random()
            val fieldProfileId = UUID.random()
            val task = task(project.id, UUID.random(), UUID.random()).copy(
                customFieldValues = buildJsonObject {
                    put("approvers", JsonArray(listOf(JsonPrimitive(fieldProfileId.toString()))))
                },
            )
            val group = Group(groupId, "team", "", GroupType.SYSTEM)
            val projectGroup = Group(projectGroupId, "project-team", "", GroupType.SYSTEM)
            coEvery { fixture.taskService.getById(task.id) } returns task
            coEvery { fixture.projectService.getById(project.id) } returns project
            coEvery { fixture.projectService.getPermissions(project) } returns listOf(
                ProjectPermission(project.id, projectGroupId, PermissionAction.VIEW),
            )
            coEvery { fixture.schemeService.typed(schemeId) } returns typed(
                mapOf(
                    "TASK_UPDATED" to listOf(
                        NotificationRecipient.Group(groupId),
                        NotificationRecipient.ProjectRole(projectGroupId),
                        NotificationRecipient.CustomFieldValue("approvers"),
                    ),
                ),
            )
            coEvery { fixture.securityService.getGroupById(groupId) } returns group
            coEvery { fixture.securityService.getGroupById(projectGroupId) } returns projectGroup
            coEvery { fixture.securityService.getPrincipalsByGroup(group) } returns listOf(
                Principal(primaryProfileId = groupProfileId),
            )
            coEvery { fixture.securityService.getPrincipalsByGroup(projectGroup) } returns listOf(
                Principal(primaryProfileId = projectGroupProfileId),
            )
            for (profileId in listOf(groupProfileId, projectGroupProfileId, fieldProfileId)) {
                coEvery { fixture.preferenceService.get(profileId) } returns null
            }

            fixture.dispatcher.deliver("TASK_UPDATED", project.id, task.id)

            for (profileId in listOf(groupProfileId, projectGroupProfileId, fieldProfileId)) {
                coVerify(exactly = 1) {
                    fixture.inboxService.addOnce(any(), profileId, "TASK_UPDATED", task.id, project.id, null, any(), any())
                }
            }
        } finally {
            fixture.dispatcher.close()
        }
    }

    @Test
    fun `deliver honors muted task and project preferences`() = runTest {
        val fixture = fixture()
        try {
            val schemeId = UUID.random()
            val project = project(defaultSchemeId = schemeId)
            val taskMutedProfile = UUID.random()
            val projectMutedProfile = UUID.random()
            val task = task(project.id, UUID.random(), UUID.random())
            coEvery { fixture.taskService.getById(task.id) } returns task
            coEvery { fixture.projectService.getById(project.id) } returns project
            coEvery { fixture.schemeService.typed(schemeId) } returns typed(
                mapOf(
                    "TASK_UPDATED" to listOf(
                        NotificationRecipient.Profile(taskMutedProfile),
                        NotificationRecipient.Profile(projectMutedProfile),
                    ),
                ),
            )
            coEvery { fixture.preferenceService.get(taskMutedProfile) } returns NotificationPreference(
                profileId = taskMutedProfile,
                mutedTaskIds = listOf(task.id),
            )
            coEvery { fixture.preferenceService.get(projectMutedProfile) } returns NotificationPreference(
                profileId = projectMutedProfile,
                mutedProjectIds = listOf(project.id),
            )

            fixture.dispatcher.deliver("TASK_UPDATED", project.id, task.id)

            coVerify(exactly = 0) { fixture.inboxService.addOnce(any(), any(), any(), any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) {
                fixture.outboxService.enqueueOnce(any(), any(), any(), any(), any(), any(), any())
            }
        } finally {
            fixture.dispatcher.close()
        }
    }

    @Test
    fun `deleted notifications resolve tombstoned entities through manager service reads`() = runTest {
        val fixture = fixture()
        try {
            val schemeId = UUID.random()
            val project = project(defaultSchemeId = schemeId)
            val taskRecipient = UUID.random()
            val specRecipient = UUID.random()
            val deletedTask = task(project.id, taskRecipient, UUID.random())
            val deletedSpecId = UUID.random()
            val deletedSpec = mockk<bosca.workops.model.spec.Spec> {
                every { id } returns deletedSpecId
                every { projectId } returns project.id
                every { key } returns "SPEC-DELETED"
                every { ownerProfileId } returns specRecipient
                every { watcherProfileIds } returns emptyList()
            }
            coEvery { fixture.taskService.getByIdIncludingDeleted(deletedTask.id) } returns deletedTask
            coEvery { fixture.specService.getByIdIncludingDeleted(deletedSpecId) } returns deletedSpec
            coEvery { fixture.projectService.getById(project.id) } returns project
            coEvery { fixture.schemeService.typed(schemeId) } returns typed(
                mapOf(
                    "TASK_DELETED" to listOf(NotificationRecipient.Reporter),
                    "SPEC_DELETED" to listOf(NotificationRecipient.Owner),
                ),
            )
            coEvery { fixture.preferenceService.get(taskRecipient) } returns null
            coEvery { fixture.preferenceService.get(specRecipient) } returns null

            fixture.dispatcher.deliver("TASK_DELETED", project.id, deletedTask.id, commentId = 17)
            fixture.dispatcher.deliver(eventName = "SPEC_DELETED", specId = deletedSpecId, commentId = 18)

            coVerify(exactly = 1) { fixture.taskService.getByIdIncludingDeleted(deletedTask.id) }
            coVerify(exactly = 0) { fixture.taskService.getById(deletedTask.id) }
            coVerify(exactly = 1) { fixture.specService.getByIdIncludingDeleted(deletedSpecId) }
            coVerify(exactly = 0) { fixture.specService.getById(deletedSpecId) }
            coVerify(exactly = 1) {
                fixture.inboxService.addOnce(
                    any(), taskRecipient, "TASK_DELETED", deletedTask.id, project.id, null, any(), any(),
                )
            }
            coVerify(exactly = 1) {
                fixture.inboxService.addOnce(any(), specRecipient, "SPEC_DELETED", null, project.id, null, any(), any())
            }
        } finally {
            fixture.dispatcher.close()
        }
    }

    @Test
    fun `deliver uses the default scheme and includes spec context in outbox payload`() = runTest {
        val fixture = fixture()
        try {
            val project = project(defaultSchemeId = null)
            val specId = UUID.random()
            val profileId = UUID.random()
            val watcherId = UUID.random()
            val spec = mockk<bosca.workops.model.spec.Spec> {
                every { projectId } returns project.id
            }
            val preference = mockk<NotificationPreference>(relaxed = true)
            every { spec.id } returns specId
            every { spec.key } returns "SPEC-1"
            every { spec.ownerProfileId } returns UUID.random()
            every { spec.watcherProfileIds } returns listOf(watcherId)
            coEvery { fixture.specService.getById(specId) } returns spec
            coEvery { fixture.projectService.getById(project.id) } returns project
            coEvery { fixture.schemeService.typed(any()) } returns typed(
                mapOf(
                    "SPEC_UPDATED" to listOf(
                        NotificationRecipient.Profile(profileId),
                        NotificationRecipient.Watchers,
                    ),
                ),
            )
            coEvery { fixture.preferenceService.get(profileId) } returns preference
            coEvery { fixture.preferenceService.get(watcherId) } returns null
            coEvery {
                fixture.preferenceService.decodedChannels(preference, "SPEC_UPDATED")
            } returns setOf(NotificationChannel.EMAIL)

            fixture.dispatcher.deliver("SPEC_UPDATED", null, null, specId)

            coVerify(exactly = 1) {
                fixture.outboxService.enqueueOnce(
                    any(),
                    "SPEC_UPDATED",
                    NotificationChannel.EMAIL,
                    profileId.toString(),
                    match {
                        val payload = json.parseToJsonElement(it).jsonObject
                        payload["entityType"]?.jsonPrimitive?.content == "SPEC" &&
                            payload["entityId"]?.jsonPrimitive?.content == specId.toString() &&
                            payload["projectName"]?.jsonPrimitive?.content == project.name
                    },
                    null,
                    false,
                )
            }
            coVerify(exactly = 1) {
                fixture.inboxService.addOnce(
                    any(), watcherId, "SPEC_UPDATED", null, project.id, null, any(), "/workops/specs/$specId",
                )
            }
        } finally {
            fixture.dispatcher.close()
        }
    }

    @Test
    fun `comment delivery resolves mentions and content through services and excludes the actor`() = runTest {
        val fixture = fixture()
        try {
            val schemeId = UUID.random()
            val project = project(defaultSchemeId = schemeId)
            val actorId = UUID.random()
            val watcherId = UUID.random()
            val mentionedId = UUID.random()
            val task = task(project.id, actorId, UUID.random())
            val commentId = 42L
            val preference = mockk<NotificationPreference>(relaxed = true)
            val comment = mockk<bosca.workops.model.comment.TaskComment> {
                every { content } returns "Please route this through services."
            }
            val actor = mockk<bosca.profile.model.Profile> {
                every { name } returns "Maya Rivera"
            }
            coEvery { fixture.taskService.getById(task.id) } returns task
            coEvery { fixture.projectService.getById(project.id) } returns project
            coEvery { fixture.schemeService.typed(schemeId) } returns typed(
                mapOf(
                    "TASK_COMMENTED" to listOf(
                        NotificationRecipient.Watchers,
                        NotificationRecipient.MentionedUsers,
                    ),
                ),
            )
            coEvery { fixture.watcherService.list(task.id) } returns listOf(
                TaskWatcher(task.id, actorId),
                TaskWatcher(task.id, watcherId),
            )
            coEvery { fixture.taskCommentService.get(task.id, commentId, watcherId, false) } returns comment
            coEvery { fixture.taskCommentService.get(task.id, commentId, mentionedId, false) } returns comment
            coEvery { fixture.profileService.getById(actorId) } returns actor
            coEvery { fixture.preferenceService.get(watcherId) } returns preference
            coEvery { fixture.preferenceService.get(mentionedId) } returns preference
            coEvery {
                fixture.preferenceService.decodedChannels(preference, "TASK_COMMENTED")
            } returns setOf(NotificationChannel.EMAIL)

            fixture.dispatcher.deliver(
                eventName = "TASK_COMMENTED",
                projectId = project.id,
                taskId = task.id,
                actorProfileId = actorId,
                mentionedProfileIds = setOf(mentionedId),
                commentId = commentId,
            )

            coVerify(exactly = 1) { fixture.taskService.getById(task.id) }
            coVerify(exactly = 1) { fixture.projectService.getById(project.id) }
            coVerify(exactly = 1) { fixture.taskCommentService.get(task.id, commentId, watcherId, false) }
            coVerify(exactly = 1) { fixture.taskCommentService.get(task.id, commentId, mentionedId, false) }
            coVerify(exactly = 1) { fixture.profileService.getById(actorId) }
            coVerify(exactly = 0) { fixture.preferenceService.get(actorId) }
            for (recipientId in listOf(watcherId, mentionedId)) {
                coVerify(exactly = 1) {
                    fixture.outboxService.enqueueOnce(
                        any(),
                        "TASK_COMMENTED",
                        NotificationChannel.EMAIL,
                        recipientId.toString(),
                        match {
                            val payload = json.parseToJsonElement(it).jsonObject
                            payload["body"]?.jsonPrimitive?.content == "Please route this through services." &&
                                payload["actorName"]?.jsonPrimitive?.content == "Maya Rivera"
                        },
                        null,
                        false,
                    )
                }
            }
        } finally {
            fixture.dispatcher.close()
        }
    }

    @Test
    fun `comment delivery uses manager visibility for a manager recipient`() = runTest {
        val fixture = fixture()
        try {
            val schemeId = UUID.random()
            val project = project(defaultSchemeId = schemeId)
            val managerId = UUID.random()
            val task = task(project.id, UUID.random(), UUID.random())
            val commentId = 43L
            val comment = mockk<bosca.workops.model.comment.TaskComment> {
                every { content } returns "Pending user comment"
            }
            val preference = NotificationPreference(
                profileId = managerId,
                eventChannels = buildJsonObject {
                    put("TASK_COMMENTED", JsonArray(listOf(JsonPrimitive(NotificationChannel.IN_APP.name))))
                },
            )
            coEvery { fixture.taskService.getById(task.id) } returns task
            coEvery { fixture.projectService.getById(project.id) } returns project
            coEvery { fixture.schemeService.typed(schemeId) } returns typed(
                mapOf("TASK_COMMENTED" to listOf(NotificationRecipient.MentionedUsers)),
            )
            coEvery {
                fixture.taskPermissionEvaluator.isAllowed(any<AuthenticationContext>(), task, PermissionAction.MANAGE)
            } returns true
            coEvery { fixture.taskCommentService.get(task.id, commentId, managerId, true) } returns comment
            coEvery { fixture.preferenceService.get(managerId) } returns preference
            coEvery {
                fixture.preferenceService.decodedChannels(preference, "TASK_COMMENTED")
            } returns setOf(NotificationChannel.IN_APP)

            fixture.dispatcher.deliver(
                eventName = "TASK_COMMENTED",
                projectId = project.id,
                taskId = task.id,
                mentionedProfileIds = setOf(managerId),
                commentId = commentId,
            )

            coVerify(exactly = 1) { fixture.taskCommentService.get(task.id, commentId, managerId, true) }
            coVerify(exactly = 0) { fixture.taskCommentService.get(task.id, commentId, managerId, false) }
            coVerify(exactly = 1) {
                fixture.inboxService.addOnce(
                    any(), managerId, "TASK_COMMENTED", task.id, project.id, null,
                    "Pending user comment", "/workops/tasks/${task.id}",
                )
            }
            coVerify(exactly = 0) {
                fixture.outboxService.enqueueOnce(any(), any(), any(), any(), any(), any(), any())
            }
        } finally {
            fixture.dispatcher.close()
        }
    }

    @Test
    fun `email defaults on and an explicit event opt out is respected`() = runTest {
        val fixture = fixture()
        try {
            val schemeId = UUID.random()
            val project = project(defaultSchemeId = schemeId)
            val defaultId = UUID.random()
            val optedOutId = UUID.random()
            val task = task(project.id, UUID.random(), UUID.random())
            val optedOut = NotificationPreference(
                profileId = optedOutId,
                eventChannels = buildJsonObject {
                    put("TASK_UPDATED", JsonArray(emptyList()))
                },
            )
            coEvery { fixture.taskService.getById(task.id) } returns task
            coEvery { fixture.projectService.getById(project.id) } returns project
            coEvery { fixture.schemeService.typed(schemeId) } returns typed(
                mapOf(
                    "TASK_UPDATED" to listOf(
                        NotificationRecipient.Profile(defaultId),
                        NotificationRecipient.Profile(optedOutId),
                    ),
                ),
            )
            coEvery { fixture.preferenceService.get(defaultId) } returns null
            coEvery { fixture.preferenceService.get(optedOutId) } returns optedOut
            coEvery {
                fixture.preferenceService.decodedChannels(optedOut, "TASK_UPDATED")
            } returns emptySet()

            fixture.dispatcher.deliver(
                eventName = "TASK_UPDATED",
                projectId = project.id,
                taskId = task.id,
            )

            coVerify(exactly = 1) {
                fixture.inboxService.addOnce(
                    any(), defaultId, "TASK_UPDATED", task.id, project.id, null,
                    "task updated", "/workops/tasks/${task.id}",
                )
            }
            coVerify(exactly = 1) {
                fixture.outboxService.enqueueOnce(
                    any(), "TASK_UPDATED", NotificationChannel.EMAIL, defaultId.toString(), any(), null, false,
                )
            }
            coVerify(exactly = 0) {
                fixture.inboxService.addOnce(any(), optedOutId, any(), any(), any(), any(), any(), any())
            }
            coVerify(exactly = 0) {
                fixture.outboxService.enqueueOnce(any(), any(), any(), optedOutId.toString(), any(), any(), any())
            }
        } finally {
            fixture.dispatcher.close()
        }
    }

    @Test
    fun `comment delivery skips recipients without entity or comment visibility`() = runTest {
        val fixture = fixture()
        try {
            val schemeId = UUID.random()
            val project = project(defaultSchemeId = schemeId)
            val deniedId = UUID.random()
            val hiddenCommentId = UUID.random()
            val task = task(project.id, UUID.random(), UUID.random())
            val commentId = 43L
            coEvery { fixture.taskService.getById(task.id) } returns task
            coEvery { fixture.projectService.getById(project.id) } returns project
            coEvery { fixture.schemeService.typed(schemeId) } returns typed(
                mapOf(
                    "TASK_COMMENTED" to listOf(
                        NotificationRecipient.Profile(deniedId),
                        NotificationRecipient.Profile(hiddenCommentId),
                    ),
                ),
            )
            coEvery {
                fixture.taskPermissionEvaluator.isAllowed(any<AuthenticationContext>(), task, PermissionAction.VIEW)
            } returnsMany listOf(false, true)
            coEvery {
                fixture.taskCommentService.get(task.id, commentId, hiddenCommentId, false)
            } returns null

            fixture.dispatcher.deliver(
                eventName = "TASK_COMMENTED",
                projectId = project.id,
                taskId = task.id,
                commentId = commentId,
            )

            coVerify(exactly = 0) { fixture.taskCommentService.get(task.id, commentId, deniedId, false) }
            coVerify(exactly = 1) { fixture.taskCommentService.get(task.id, commentId, hiddenCommentId, false) }
            coVerify(exactly = 0) { fixture.inboxService.addOnce(any(), any(), any(), any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) {
                fixture.outboxService.enqueueOnce(any(), any(), any(), any(), any(), any(), any())
            }
        } finally {
            fixture.dispatcher.close()
        }
    }

    @Test
    fun `deliver attempts later profiles then retries ordinary failures and preserves cancellation`() = runTest {
        val fixture = fixture()
        try {
            val schemeId = UUID.random()
            val project = project(defaultSchemeId = schemeId)
            val failedProfileId = UUID.random()
            val followingProfileId = UUID.random()
            val task = task(project.id, UUID.random(), UUID.random())
            coEvery { fixture.taskService.getById(task.id) } returns task
            coEvery { fixture.projectService.getById(project.id) } returns project
            coEvery { fixture.schemeService.typed(schemeId) } returns typed(
                mapOf(
                    "TASK_UPDATED" to listOf(
                        NotificationRecipient.Profile(failedProfileId),
                        NotificationRecipient.Profile(followingProfileId),
                    )
                ),
            )
            coEvery { fixture.preferenceService.get(failedProfileId) } throws IllegalStateException("failed")
            coEvery { fixture.preferenceService.get(followingProfileId) } returns null

            assertFailsWith<IllegalStateException> {
                fixture.dispatcher.deliver("TASK_UPDATED", project.id, task.id, null)
            }
            coVerify(exactly = 1) {
                fixture.inboxService.addOnce(any(), followingProfileId, any(), any(), any(), any(), any(), any())
            }

            coEvery { fixture.preferenceService.get(failedProfileId) } throws CancellationException("cancelled")
            assertFailsWith<CancellationException> {
                fixture.dispatcher.deliver("TASK_UPDATED", project.id, task.id, null)
            }
        } finally {
            fixture.dispatcher.close()
        }
    }

    @Test
    fun `durable task update uses event-time assignee and emits assignment event`() = runTest {
        val fixture = fixture()
        try {
            val schemeId = UUID.random()
            val project = project(defaultSchemeId = schemeId)
            val eventAssigneeId = UUID.random()
            val laterAssigneeId = UUID.random()
            val task = task(project.id, UUID.random(), laterAssigneeId)
            coEvery { fixture.taskService.getById(task.id) } returns task
            coEvery { fixture.projectService.getById(project.id) } returns project
            coEvery { fixture.schemeService.typed(schemeId) } returns typed(
                mapOf(
                    "TASK_UPDATED" to listOf(NotificationRecipient.CurrentAssignee),
                    "TASK_ASSIGNED" to listOf(NotificationRecipient.CurrentAssignee),
                ),
            )
            coEvery { fixture.preferenceService.get(eventAssigneeId) } returns null

            fixture.dispatcher.deliver(
                NotificationDelivery(
                    event = NotificationEvent.TASK_UPDATED,
                    taskId = task.id,
                    projectId = project.id,
                    assigneeProfileId = eventAssigneeId,
                    assigneeChanged = true,
                ),
            )

            coVerify(exactly = 1) {
                fixture.inboxService.addOnce(
                    any(), eventAssigneeId, "TASK_UPDATED", task.id, project.id, null, any(), any(),
                )
            }
            coVerify(exactly = 1) {
                fixture.inboxService.addOnce(
                    any(), eventAssigneeId, "TASK_ASSIGNED", task.id, project.id, null, any(), any(),
                )
            }
            coVerify(exactly = 0) {
                fixture.inboxService.addOnce(any(), laterAssigneeId, any(), any(), any(), any(), any(), any())
            }
        } finally {
            fixture.dispatcher.close()
        }
    }

    @Test
    fun `durable task transitions emit resolved closed and reopened events`() = runTest {
        val fixture = fixture()
        try {
            val schemeId = UUID.random()
            val project = project(defaultSchemeId = schemeId)
            val recipientId = UUID.random()
            val task = task(project.id, UUID.random(), UUID.random())
            val todo = status(StatusCategory.TODO)
            val inProgress = status(StatusCategory.IN_PROGRESS)
            val done = status(StatusCategory.DONE)
            val cancelled = status(StatusCategory.CANCELLED)
            listOf(todo, inProgress, done, cancelled).forEach { status ->
                coEvery { fixture.statusService.getById(status.id) } returns status
            }
            coEvery { fixture.taskService.getById(task.id) } returns task
            coEvery { fixture.projectService.getById(project.id) } returns project
            coEvery { fixture.schemeService.typed(schemeId) } returns typed(
                mapOf(
                    "TASK_TRANSITIONED" to emptyList(),
                    "TASK_RESOLVED" to listOf(NotificationRecipient.Profile(recipientId)),
                    "TASK_CLOSED" to listOf(NotificationRecipient.Profile(recipientId)),
                    "TASK_REOPENED" to listOf(NotificationRecipient.Profile(recipientId)),
                ),
            )
            coEvery { fixture.preferenceService.get(recipientId) } returns null

            suspend fun transition(from: Status, to: Status) {
                fixture.dispatcher.deliver(
                    NotificationDelivery(
                        event = NotificationEvent.TASK_TRANSITIONED,
                        taskId = task.id,
                        projectId = project.id,
                        fromStatusId = from.id,
                        toStatusId = to.id,
                    ),
                )
            }
            transition(todo, done)
            transition(inProgress, cancelled)
            transition(done, inProgress)
            transition(cancelled, done)
            transition(done, cancelled)
            transition(cancelled, cancelled)
            transition(done, done)

            for ((event, count) in mapOf("TASK_RESOLVED" to 2, "TASK_CLOSED" to 2, "TASK_REOPENED" to 1)) {
                coVerify(exactly = count) {
                    fixture.inboxService.addOnce(any(), recipientId, event, task.id, project.id, null, any(), any())
                }
            }
        } finally {
            fixture.dispatcher.close()
        }
    }

    @Test
    fun `durable delivery emits no synthetic event without a complete semantic transition or changed assignee`() = runTest {
        val fixture = fixture()
        try {
            val schemeId = UUID.random()
            val project = project(defaultSchemeId = schemeId)
            val task = task(project.id, UUID.random(), UUID.random())
            val missingFromId = UUID.random()
            val missingToId = UUID.random()
            val todo = status(StatusCategory.TODO)
            val inProgress = status(StatusCategory.IN_PROGRESS)
            coEvery { fixture.taskService.getById(task.id) } returns task
            coEvery { fixture.projectService.getById(project.id) } returns project
            coEvery { fixture.schemeService.typed(schemeId) } returns typed(
                mapOf(
                    NotificationEvent.TASK_CREATED.name to emptyList(),
                    NotificationEvent.TASK_UPDATED.name to emptyList(),
                    NotificationEvent.TASK_TRANSITIONED.name to emptyList(),
                ),
            )
            coEvery { fixture.statusService.getById(missingFromId) } returns null
            coEvery { fixture.statusService.getById(missingToId) } returns null
            coEvery { fixture.statusService.getById(todo.id) } returns todo
            coEvery { fixture.statusService.getById(inProgress.id) } returns inProgress

            fixture.dispatcher.deliver(
                NotificationDelivery(
                    event = NotificationEvent.TASK_CREATED,
                    projectId = project.id,
                    taskId = task.id,
                ),
            )
            fixture.dispatcher.deliver(
                NotificationDelivery(
                    event = NotificationEvent.TASK_UPDATED,
                    projectId = project.id,
                    taskId = task.id,
                    assigneeProfileId = UUID.random(),
                    assigneeChanged = false,
                ),
            )
            fixture.dispatcher.deliver(
                NotificationDelivery(
                    event = NotificationEvent.TASK_UPDATED,
                    projectId = project.id,
                    taskId = task.id,
                    assigneeProfileId = null,
                    assigneeChanged = true,
                ),
            )

            suspend fun transition(from: UUID?, to: UUID?) {
                fixture.dispatcher.deliver(
                    NotificationDelivery(
                        event = NotificationEvent.TASK_TRANSITIONED,
                        projectId = project.id,
                        taskId = task.id,
                        fromStatusId = from,
                        toStatusId = to,
                    ),
                )
            }
            transition(null, inProgress.id)
            transition(missingFromId, inProgress.id)
            transition(todo.id, null)
            transition(todo.id, missingToId)
            transition(todo.id, inProgress.id)

            coVerify(exactly = 0) {
                fixture.inboxService.addOnce(any(), any(), any(), any(), any(), any(), any(), any())
            }
            coVerify(exactly = 0) {
                fixture.schemeService.typed(match { it != schemeId })
            }
        } finally {
            fixture.dispatcher.close()
        }
    }

    @Test
    fun `daily digest stages external channels for aggregation and preserves in app`() = runTest {
        val fixture = fixture()
        try {
            val schemeId = UUID.random()
            val project = project(defaultSchemeId = schemeId)
            val recipientId = UUID.random()
            val task = task(project.id, UUID.random(), UUID.random())
            val preference = NotificationPreference(profileId = recipientId, dailyDigest = true)
            coEvery { fixture.taskService.getById(task.id) } returns task
            coEvery { fixture.projectService.getById(project.id) } returns project
            coEvery { fixture.schemeService.typed(schemeId) } returns typed(
                mapOf("TASK_UPDATED" to listOf(NotificationRecipient.Profile(recipientId))),
            )
            coEvery { fixture.preferenceService.get(recipientId) } returns preference
            coEvery { fixture.preferenceService.decodedChannels(preference, "TASK_UPDATED") } returns setOf(
                NotificationChannel.IN_APP,
                NotificationChannel.EMAIL,
                NotificationChannel.WEBHOOK,
            )

            fixture.dispatcher.deliver("TASK_UPDATED", project.id, task.id)

            coVerify(exactly = 1) {
                fixture.inboxService.addOnce(any(), recipientId, "TASK_UPDATED", task.id, project.id, null, any(), any())
            }
            for (channel in listOf(NotificationChannel.EMAIL, NotificationChannel.WEBHOOK)) {
                coVerify(exactly = 1) {
                    fixture.outboxService.enqueueOnce(
                        any(), "TASK_UPDATED", channel, recipientId.toString(), any(), any(), true,
                    )
                }
            }
        } finally {
            fixture.dispatcher.close()
        }
    }

    @Test
    fun `DND suppresses immediate external channels in the profile timezone`() = runTest {
        val fixture = fixture()
        try {
            val schemeId = UUID.random()
            val project = project(defaultSchemeId = schemeId)
            val recipientId = UUID.random()
            val task = task(project.id, UUID.random(), UUID.random())
            val preference = NotificationPreference(
                profileId = recipientId,
                dndStartLocal = "00:00",
                dndEndLocal = "00:00",
            )
            val timezoneAttribute = mockk<ProfileAttribute> {
                every { typeId } returns "bosca.profiles.timezone"
                every { attributes } returns buildJsonObject { put("value", "UTC") }
            }
            coEvery { fixture.taskService.getById(task.id) } returns task
            coEvery { fixture.projectService.getById(project.id) } returns project
            coEvery { fixture.schemeService.typed(schemeId) } returns typed(
                mapOf("TASK_UPDATED" to listOf(NotificationRecipient.Profile(recipientId))),
            )
            coEvery { fixture.preferenceService.get(recipientId) } returns preference
            coEvery { fixture.preferenceService.decodedChannels(preference, "TASK_UPDATED") } returns setOf(
                NotificationChannel.IN_APP,
                NotificationChannel.EMAIL,
            )
            coEvery { fixture.profileService.getAttributes(recipientId) } returns listOf(timezoneAttribute)

            fixture.dispatcher.deliver("TASK_UPDATED", project.id, task.id)

            coVerify(exactly = 1) {
                fixture.inboxService.addOnce(any(), recipientId, "TASK_UPDATED", task.id, project.id, null, any(), any())
            }
            coVerify(exactly = 0) {
                fixture.outboxService.enqueueOnce(any(), any(), any(), any(), any(), any(), any())
            }
        } finally {
            fixture.dispatcher.close()
        }
    }

    @Test
    fun `external scheduling tolerates timezone and DND configuration variants`() = runTest {
        val fixture = fixture()
        try {
            val schemeId = UUID.random()
            val project = project(defaultSchemeId = schemeId)
            val task = task(project.id, UUID.random(), UUID.random())
            val legacyDigestId = UUID.random()
            val invalidZoneDigestId = UUID.random()
            val invalidStartId = UUID.random()
            val invalidEndId = UUID.random()
            val outsideWindowId = UUID.random()
            val activeWindowId = UUID.random()
            val partialWindowId = UUID.random()
            val recipients = listOf(
                legacyDigestId,
                invalidZoneDigestId,
                invalidStartId,
                invalidEndId,
                outsideWindowId,
                activeWindowId,
                partialWindowId,
            )
            val now = LocalTime.now(ZoneOffset.UTC).withSecond(0).withNano(0)
            fun local(time: LocalTime) = time.toString()
            fun timezoneAttribute(valueKey: String, value: String) = mockk<ProfileAttribute> {
                every { typeId } returns "bosca.profiles.timezone"
                every { attributes } returns buildJsonObject { put(valueKey, value) }
            }
            coEvery { fixture.taskService.getById(task.id) } returns task
            coEvery { fixture.projectService.getById(project.id) } returns project
            coEvery { fixture.schemeService.typed(schemeId) } returns typed(
                mapOf("TASK_UPDATED" to recipients.map { NotificationRecipient.Profile(it) }),
            )
            val preferences = mapOf(
                legacyDigestId to NotificationPreference(profileId = legacyDigestId, dailyDigest = true),
                invalidZoneDigestId to NotificationPreference(profileId = invalidZoneDigestId, dailyDigest = true),
                invalidStartId to NotificationPreference(
                    profileId = invalidStartId,
                    dndStartLocal = "invalid",
                    dndEndLocal = "23:59",
                ),
                invalidEndId to NotificationPreference(
                    profileId = invalidEndId,
                    dndStartLocal = "00:00",
                    dndEndLocal = "invalid",
                ),
                outsideWindowId to NotificationPreference(
                    profileId = outsideWindowId,
                    dndStartLocal = local(now.plusHours(5)),
                    dndEndLocal = local(now.plusHours(6)),
                ),
                activeWindowId to NotificationPreference(
                    profileId = activeWindowId,
                    dndStartLocal = local(now.minusMinutes(5)),
                    dndEndLocal = local(now.plusMinutes(5)),
                ),
                partialWindowId to NotificationPreference(
                    profileId = partialWindowId,
                    dndStartLocal = "00:00",
                    dndEndLocal = null,
                ),
            )
            preferences.forEach { (profileId, preference) ->
                coEvery { fixture.preferenceService.get(profileId) } returns preference
                coEvery { fixture.preferenceService.decodedChannels(preference, "TASK_UPDATED") } returns
                    setOf(NotificationChannel.EMAIL)
            }
            coEvery { fixture.profileService.getAttributes(legacyDigestId) } returns
                listOf(timezoneAttribute("timezone", "UTC"))
            coEvery { fixture.profileService.getAttributes(invalidZoneDigestId) } returns
                listOf(timezoneAttribute("value", "Mars/Olympus"))
            for (profileId in listOf(invalidStartId, invalidEndId, outsideWindowId, activeWindowId)) {
                coEvery { fixture.profileService.getAttributes(profileId) } returns emptyList()
            }

            fixture.dispatcher.deliver("TASK_UPDATED", project.id, task.id)

            for (profileId in listOf(legacyDigestId, invalidZoneDigestId)) {
                val availableAt = slot<java.time.OffsetDateTime?>()
                coVerify(exactly = 1) {
                    fixture.outboxService.enqueueOnce(
                        any(), "TASK_UPDATED", NotificationChannel.EMAIL, profileId.toString(), any(),
                        captureNullable(availableAt), true,
                    )
                }
                assertTrue(availableAt.captured != null)
            }
            for (profileId in listOf(invalidStartId, invalidEndId, outsideWindowId, partialWindowId)) {
                coVerify(exactly = 1) {
                    fixture.outboxService.enqueueOnce(
                        any(), "TASK_UPDATED", NotificationChannel.EMAIL, profileId.toString(), any(), null, false,
                    )
                }
            }
            val dndAvailableAt = slot<java.time.OffsetDateTime?>()
            coVerify(exactly = 1) {
                fixture.outboxService.enqueueOnce(
                    any(), "TASK_UPDATED", NotificationChannel.EMAIL, activeWindowId.toString(), any(),
                    captureNullable(dndAvailableAt), false,
                )
            }
            assertTrue(dndAvailableAt.captured != null)
        } finally {
            fixture.dispatcher.close()
        }
    }

    @Test
    fun `channel failure does not suppress later channels`() = runTest {
        val fixture = fixture()
        try {
            val schemeId = UUID.random()
            val project = project(defaultSchemeId = schemeId)
            val recipientId = UUID.random()
            val task = task(project.id, UUID.random(), UUID.random())
            val preference = NotificationPreference(profileId = recipientId)
            coEvery { fixture.taskService.getById(task.id) } returns task
            coEvery { fixture.projectService.getById(project.id) } returns project
            coEvery { fixture.schemeService.typed(schemeId) } returns typed(
                mapOf("TASK_UPDATED" to listOf(NotificationRecipient.Profile(recipientId))),
            )
            coEvery { fixture.preferenceService.get(recipientId) } returns preference
            coEvery { fixture.preferenceService.decodedChannels(preference, "TASK_UPDATED") } returns linkedSetOf(
                NotificationChannel.EMAIL,
                NotificationChannel.WEBHOOK,
            )
            coEvery {
                fixture.outboxService.enqueueOnce(
                    any(), "TASK_UPDATED", NotificationChannel.EMAIL, recipientId.toString(), any(), null, false,
                )
            } throws IllegalStateException("email failed")

            assertFailsWith<IllegalStateException> {
                fixture.dispatcher.deliver("TASK_UPDATED", project.id, task.id)
            }

            coVerify(exactly = 1) {
                fixture.outboxService.enqueueOnce(
                    any(), "TASK_UPDATED", NotificationChannel.WEBHOOK, recipientId.toString(), any(), null, false,
                )
            }
        } finally {
            fixture.dispatcher.close()
        }
    }

    @Test
    fun `DND windows handle ordinary and overnight ranges`() {
        val zone = ZoneId.of("UTC")
        val noon = Instant.parse("2026-07-31T12:00:00Z")
        val late = Instant.parse("2026-07-31T23:00:00Z")

        assertTrue(isInDndWindow(LocalTime.of(9, 0), LocalTime.of(17, 0), zone, noon))
        assertFalse(isInDndWindow(LocalTime.of(9, 0), LocalTime.of(17, 0), zone, late))
        assertTrue(isInDndWindow(LocalTime.of(22, 0), LocalTime.of(7, 0), zone, late))
        assertTrue(
            isInDndWindow(
                LocalTime.of(22, 0),
                LocalTime.of(7, 0),
                zone,
                Instant.parse("2026-07-31T02:00:00Z"),
            ),
        )
        assertFalse(isInDndWindow(LocalTime.of(22, 0), LocalTime.of(7, 0), zone, noon))
        assertTrue(isInDndWindow(LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, zone, noon))
    }

    @Test
    fun `task comment mention parser deduplicates canonical profile handles`() {
        val parsed = parseProfileMentions(
            "Hi @Ada-Lovelace and again @ada-lovelace, not ada@example.com or @@invalid",
        )

        assertTrue(parsed == setOf("ada-lovelace"))
    }

    @Test
    fun `approval delivery resolves execute groups deduplicates profiles and isolates failures`() = runTest {
        val fixture = fixture()
        try {
            val event = PipelineAwaitingApproval(UUID.random(), UUID.random(), "gate", "")
            val pipeline = Pipeline(event.pipelineId, "Release", acceptedInputType = "input")
            val executeGroupId = UUID.random()
            val ignoredGroupId = UUID.random()
            val executeGroup = Group(executeGroupId, "approvers", "", GroupType.SYSTEM)
            val profileId = UUID.random()
            val failedProfileId = UUID.random()
            val fallbackProfileId = UUID.random()
            val fallbackPrincipal = Principal(primaryProfileId = null)
            coEvery { fixture.pipelineService.get(event.pipelineId) } returns null
            fixture.dispatcher.deliverApprovalRequest(event)

            coEvery { fixture.pipelineService.get(event.pipelineId) } returns pipeline
            coEvery { fixture.pipelineService.getPermissions(pipeline) } returns listOf(
                Permission(ignoredGroupId, PermissionAction.VIEW),
            )
            fixture.dispatcher.deliverApprovalRequest(event)

            coEvery { fixture.pipelineService.getPermissions(pipeline) } returns listOf(
                Permission(executeGroupId, PermissionAction.EXECUTE),
                Permission(executeGroupId, PermissionAction.EXECUTE),
            )
            coEvery { fixture.securityService.getGroupById(executeGroupId) } returns executeGroup
            coEvery { fixture.securityService.getPrincipalsByGroup(executeGroup) } throws IllegalStateException("group failed")
            assertFailsWith<IllegalStateException> {
                fixture.dispatcher.deliverApprovalRequest(event)
            }

            coEvery { fixture.securityService.getPrincipalsByGroup(executeGroup) } returns listOf(
                Principal(primaryProfileId = profileId),
                Principal(primaryProfileId = profileId),
                Principal(primaryProfileId = failedProfileId),
                Principal(primaryProfileId = null),
                fallbackPrincipal,
            )
            coEvery { fixture.profileService.getPrimaryProfile(fallbackPrincipal) } returns Profile(
                fallbackProfileId,
                ProfileType.GENERIC,
                fallbackPrincipal.id,
                name = "Fallback approver",
                visibility = ProfileVisibility.USER,
            )
            coEvery {
                fixture.inboxService.addOnce(any(), failedProfileId, any(), any(), any(), any(), any(), any())
            } throws IllegalStateException("inbox failed")
            assertFailsWith<IllegalStateException> {
                fixture.dispatcher.deliverApprovalRequest(event)
            }

            coVerify(exactly = 1) {
                fixture.inboxService.addOnce(
                    event.runId,
                    profileId,
                    NotificationDispatcher.PIPELINE_APPROVAL_REQUESTED,
                    null,
                    null,
                    null,
                    "'Release' is awaiting approval",
                    "/pipelines/runs?runId=${event.runId}",
                )
            }
            coVerify(exactly = 1) {
                fixture.inboxService.addOnce(
                    event.runId,
                    failedProfileId,
                    NotificationDispatcher.PIPELINE_APPROVAL_REQUESTED,
                    null,
                    null,
                    null,
                    "'Release' is awaiting approval",
                    any(),
                )
            }
        } finally {
            fixture.dispatcher.close()
        }
    }

    @Test
    fun `approval delivery uses authored prompt and preserves cancellation`() = runTest {
        val fixture = fixture()
        try {
            val event = PipelineAwaitingApproval(UUID.random(), UUID.random(), "gate", "Approve production")
            val pipeline = Pipeline(event.pipelineId, "Release", acceptedInputType = "input")
            val groupId = UUID.random()
            val group = Group(groupId, "approvers", "", GroupType.SYSTEM)
            val profileId = UUID.random()
            coEvery { fixture.pipelineService.get(event.pipelineId) } returns pipeline
            coEvery { fixture.pipelineService.getPermissions(pipeline) } returns listOf(
                Permission(groupId, PermissionAction.EXECUTE),
            )
            coEvery { fixture.securityService.getGroupById(groupId) } returns group
            coEvery { fixture.securityService.getPrincipalsByGroup(group) } returns listOf(
                Principal(primaryProfileId = profileId),
            )

            fixture.dispatcher.deliverApprovalRequest(event)
            coVerify(exactly = 1) {
                fixture.inboxService.addOnce(event.runId, profileId, any(), null, null, null, "Approve production", any())
            }

            coEvery {
                fixture.inboxService.addOnce(any(), profileId, any(), any(), any(), any(), any(), any())
            } throws CancellationException("cancelled")
            assertFailsWith<CancellationException> {
                fixture.dispatcher.deliverApprovalRequest(event)
            }

            coEvery { fixture.securityService.getPrincipalsByGroup(group) } throws CancellationException("cancelled")
            assertFailsWith<CancellationException> {
                fixture.dispatcher.deliverApprovalRequest(event)
            }
        } finally {
            fixture.dispatcher.close()
        }
    }

    @Test
    fun `requirement and project notifications resolve their own entity context`() = runTest {
        val fixture = fixture()
        try {
            val schemeId = UUID.random()
            val project = project(schemeId)
            val recipientId = UUID.random()
            val parent = task(project.id, UUID.random(), UUID.random())
            val requirement = Requirement(
                id = UUID.random(),
                key = "NTF-REQ-1",
                metadataId = UUID.random(),
                parentType = RequirementParent.TASK,
                parentId = parent.id,
                statusId = UUID.random(),
                workflowId = UUID.random(),
                priorityId = UUID.random(),
                createdByPrincipalId = UUID.random(),
                modifiedByPrincipalId = UUID.random(),
            )
            coEvery { fixture.requirementService.getById(requirement.id) } returns requirement
            coEvery { fixture.taskService.getById(parent.id) } returns parent
            coEvery { fixture.projectService.getById(project.id) } returns project
            coEvery { fixture.schemeService.typed(schemeId) } returns typed(
                mapOf(
                    "REQUIREMENT_UPDATED" to listOf(NotificationRecipient.Profile(recipientId)),
                    "PROJECT_UPDATED" to listOf(NotificationRecipient.Profile(recipientId)),
                ),
            )
            coEvery { fixture.preferenceService.get(recipientId) } returns NotificationPreference(profileId = recipientId)
            coEvery { fixture.preferenceService.decodedChannels(any(), any()) } returns setOf(NotificationChannel.EMAIL)

            fixture.dispatcher.deliver(eventName = "REQUIREMENT_UPDATED", requirementId = requirement.id)
            fixture.dispatcher.deliver(eventName = "PROJECT_UPDATED", projectId = project.id)

            coVerify(exactly = 1) {
                fixture.outboxService.enqueueOnce(
                    any(),
                    "REQUIREMENT_UPDATED",
                    NotificationChannel.EMAIL,
                    recipientId.toString(),
                    match {
                        val payload = json.parseToJsonElement(it).jsonObject
                        payload["entityType"]?.jsonPrimitive?.content == "REQUIREMENT" &&
                            payload["entityId"]?.jsonPrimitive?.content == requirement.id.toString()
                    },
                    null,
                    false,
                )
            }
            coVerify(exactly = 1) {
                fixture.outboxService.enqueueOnce(
                    any(),
                    "PROJECT_UPDATED",
                    NotificationChannel.EMAIL,
                    recipientId.toString(),
                    match {
                        val payload = json.parseToJsonElement(it).jsonObject
                        payload["entityType"]?.jsonPrimitive?.content == "PROJECT" &&
                            payload["entityId"]?.jsonPrimitive?.content == project.id.toString()
                    },
                    null,
                    false,
                )
            }
        } finally {
            fixture.dispatcher.close()
        }
    }

    @Test
    fun `delivery falls back to tombstones and requirement parents for entity and project context`() = runTest {
        val fixture = fixture()
        try {
            val schemeId = UUID.random()
            val project = project(schemeId)
            val taskReporterId = UUID.random()
            val specOwnerId = UUID.random()
            val requirementAssigneeId = UUID.random()
            val parentTaskWatcherId = UUID.random()
            val parentSpecOwnerId = UUID.random()
            val parentSpecWatcherId = UUID.random()
            val tombstonedTask = task(project.id, taskReporterId, UUID.random())
            val tombstonedSpec = Spec(
                id = UUID.random(),
                key = "NTF-SPEC-DELETED",
                metadataId = UUID.random(),
                projectId = project.id,
                statusId = UUID.random(),
                workflowId = UUID.random(),
                ownerProfileId = specOwnerId,
                createdByPrincipalId = UUID.random(),
                modifiedByPrincipalId = UUID.random(),
            )
            val parentTask = task(project.id, UUID.random(), UUID.random()).copy(
                watcherProfileIds = listOf(parentTaskWatcherId),
            )
            val taskRequirement = Requirement(
                id = UUID.random(),
                key = "NTF-REQ-TASK",
                metadataId = UUID.random(),
                parentType = RequirementParent.TASK,
                parentId = parentTask.id,
                statusId = UUID.random(),
                workflowId = UUID.random(),
                priorityId = UUID.random(),
                assigneeProfileId = requirementAssigneeId,
                createdByPrincipalId = UUID.random(),
                modifiedByPrincipalId = UUID.random(),
            )
            val parentSpec = tombstonedSpec.copy(
                id = UUID.random(),
                key = "NTF-PARENT-SPEC",
                ownerProfileId = parentSpecOwnerId,
                watcherProfileIds = listOf(parentSpecWatcherId),
            )
            val specRequirement = taskRequirement.copy(
                id = UUID.random(),
                key = "NTF-REQ-SPEC",
                parentType = RequirementParent.SPEC,
                parentId = parentSpec.id,
                assigneeProfileId = null,
            )
            coEvery { fixture.taskService.getById(tombstonedTask.id) } returns null
            coEvery { fixture.taskService.getByIdIncludingDeleted(tombstonedTask.id) } returns tombstonedTask
            coEvery { fixture.specService.getById(tombstonedSpec.id) } returns null
            coEvery { fixture.specService.getByIdIncludingDeleted(tombstonedSpec.id) } returns tombstonedSpec
            coEvery { fixture.requirementService.getById(taskRequirement.id) } returns null
            coEvery { fixture.requirementService.getByIdIncludingDeleted(taskRequirement.id) } returns taskRequirement
            coEvery { fixture.requirementService.getById(specRequirement.id) } returns null
            coEvery { fixture.requirementService.getByIdIncludingDeleted(specRequirement.id) } returns specRequirement
            coEvery { fixture.taskService.getById(parentTask.id) } returns null
            coEvery { fixture.taskService.getByIdIncludingDeleted(parentTask.id) } returns parentTask
            coEvery { fixture.specService.getById(parentSpec.id) } returns null
            coEvery { fixture.specService.getByIdIncludingDeleted(parentSpec.id) } returns parentSpec
            coEvery { fixture.watcherService.list(parentTask.id) } returns emptyList()
            coEvery { fixture.projectService.getById(project.id) } returns project
            coEvery { fixture.schemeService.typed(schemeId) } returns typed(
                mapOf(
                    "TASK_FALLBACK" to listOf(NotificationRecipient.Reporter),
                    "SPEC_FALLBACK" to listOf(NotificationRecipient.Owner),
                    "REQ_TASK_FALLBACK" to listOf(NotificationRecipient.Assignee, NotificationRecipient.Watchers),
                    "REQ_SPEC_FALLBACK" to listOf(NotificationRecipient.Owner, NotificationRecipient.Watchers),
                ),
            )
            val recipientIds = listOf(
                taskReporterId,
                specOwnerId,
                requirementAssigneeId,
                parentTaskWatcherId,
                parentSpecOwnerId,
                parentSpecWatcherId,
            )
            recipientIds.forEach { coEvery { fixture.preferenceService.get(it) } returns null }

            fixture.dispatcher.deliver(eventName = "TASK_FALLBACK", taskId = tombstonedTask.id)
            fixture.dispatcher.deliver(eventName = "SPEC_FALLBACK", specId = tombstonedSpec.id)
            fixture.dispatcher.deliver(eventName = "REQ_TASK_FALLBACK", requirementId = taskRequirement.id)
            fixture.dispatcher.deliver(eventName = "REQ_SPEC_FALLBACK", requirementId = specRequirement.id)

            recipientIds.forEach { profileId ->
                coVerify(exactly = 1) { fixture.inboxService.addOnce(any(), profileId, any(), any(), project.id, null, any(), any()) }
            }
        } finally {
            fixture.dispatcher.close()
        }
    }

    @Test
    fun `custom field and group recipients ignore malformed ids nested values and unrelated project roles`() = runTest {
        val fixture = fixture()
        try {
            val schemeId = UUID.random()
            val project = project(schemeId)
            val primitiveId = UUID.random()
            val nestedId = UUID.random()
            val deepId = UUID.random()
            val groupProfileId = UUID.random()
            val groupId = UUID.random()
            val unrelatedRoleId = UUID.random()
            val principal = Principal(id = UUID.random(), primaryProfileId = null)
            val unresolvedPrincipal = Principal(id = UUID.random(), primaryProfileId = null)
            val group = Group(groupId, "fallback-profile", "", GroupType.SYSTEM)
            val task = task(project.id, UUID.random(), UUID.random()).copy(
                customFieldValues = buildJsonObject {
                    put("primitive", primitiveId.toString())
                    put("nested", JsonArray(listOf(
                        JsonPrimitive(nestedId.toString()),
                        JsonArray(listOf(JsonPrimitive("invalid"), JsonPrimitive(deepId.toString()))),
                        buildJsonObject { put("ignored", true) },
                    )))
                    put("nullValue", JsonNull)
                },
            )
            coEvery { fixture.taskService.getById(task.id) } returns task
            coEvery { fixture.projectService.getById(project.id) } returns project
            coEvery { fixture.projectService.getPermissions(project) } returns emptyList()
            coEvery { fixture.schemeService.typed(schemeId) } returns typed(
                mapOf(
                    "TASK_CUSTOM" to listOf(
                        NotificationRecipient.CustomFieldValue("primitive"),
                        NotificationRecipient.CustomFieldValue("nested"),
                        NotificationRecipient.CustomFieldValue("nullValue"),
                        NotificationRecipient.CustomFieldValue("missing"),
                        NotificationRecipient.Group(groupId),
                        NotificationRecipient.ProjectRole(unrelatedRoleId),
                    ),
                ),
            )
            coEvery { fixture.securityService.getGroupById(groupId) } returns group
            coEvery { fixture.securityService.getPrincipalsByGroup(group) } returns listOf(principal, unresolvedPrincipal)
            coEvery { fixture.profileService.getPrimaryProfile(principal) } returns Profile(
                groupProfileId,
                ProfileType.GENERIC,
                principal.id,
                name = "Fallback",
                visibility = ProfileVisibility.USER,
            )
            coEvery { fixture.profileService.getPrimaryProfile(unresolvedPrincipal) } returns null
            val resolved = listOf(primitiveId, nestedId, deepId, groupProfileId)
            resolved.forEach { coEvery { fixture.preferenceService.get(it) } returns null }

            fixture.dispatcher.deliver("TASK_CUSTOM", taskId = task.id)

            resolved.forEach { profileId ->
                coVerify(exactly = 1) { fixture.inboxService.addOnce(any(), profileId, "TASK_CUSTOM", task.id, project.id, null, any(), any()) }
            }
        } finally {
            fixture.dispatcher.close()
        }
    }

    @Test
    fun `project-only events ignore recipients that require a task or spec`() = runTest {
        val fixture = fixture()
        try {
            val schemeId = UUID.random()
            val project = project(schemeId)
            coEvery { fixture.projectService.getById(project.id) } returns project
            coEvery { fixture.schemeService.typed(schemeId) } returns typed(
                mapOf(
                    "PROJECT_CUSTOM" to listOf(
                        NotificationRecipient.Assignee,
                        NotificationRecipient.CurrentAssignee,
                        NotificationRecipient.Watchers,
                        NotificationRecipient.CustomFieldValue("approver"),
                        NotificationRecipient.Owner,
                    ),
                ),
            )
            coEvery { fixture.preferenceService.get(project.ownerProfileId) } returns null

            fixture.dispatcher.deliver("PROJECT_CUSTOM", projectId = project.id)

            coVerify(exactly = 1) {
                fixture.inboxService.addOnce(
                    any(), project.ownerProfileId, "PROJECT_CUSTOM", null, project.id, null, any(), any(),
                )
            }
        } finally {
            fixture.dispatcher.close()
        }
    }

    @Test
    fun `current assignee falls back to the requirement when the event has no assignee snapshot`() = runTest {
        val fixture = fixture()
        try {
            val schemeId = UUID.random()
            val project = project(schemeId)
            val parent = task(project.id, UUID.random(), UUID.random())
            val assigneeId = UUID.random()
            val requirement = Requirement(
                id = UUID.random(),
                key = "NTF-REQ-ASSIGNEE",
                metadataId = UUID.random(),
                parentType = RequirementParent.TASK,
                parentId = parent.id,
                statusId = UUID.random(),
                workflowId = UUID.random(),
                priorityId = UUID.random(),
                assigneeProfileId = assigneeId,
                createdByPrincipalId = UUID.random(),
                modifiedByPrincipalId = UUID.random(),
            )
            coEvery { fixture.requirementService.getById(requirement.id) } returns requirement
            coEvery { fixture.taskService.getById(parent.id) } returns parent
            coEvery { fixture.projectService.getById(project.id) } returns project
            coEvery { fixture.schemeService.typed(schemeId) } returns typed(
                mapOf("REQUIREMENT_ASSIGNEE" to listOf(NotificationRecipient.CurrentAssignee)),
            )
            coEvery { fixture.preferenceService.get(assigneeId) } returns null

            fixture.dispatcher.deliver("REQUIREMENT_ASSIGNEE", requirementId = requirement.id)

            coVerify(exactly = 1) {
                fixture.inboxService.addOnce(
                    any(), assigneeId, "REQUIREMENT_ASSIGNEE", null, project.id, null, any(), any(),
                )
            }
        } finally {
            fixture.dispatcher.close()
        }
    }

    @Test
    fun `delivery skips unresolved profiles and profiles without principals`() = runTest {
        val fixture = fixture()
        try {
            val schemeId = UUID.random()
            val project = project(schemeId)
            val task = task(project.id, UUID.random(), UUID.random())
            val missingId = UUID.random()
            val noPrincipalId = UUID.random()
            coEvery { fixture.taskService.getById(task.id) } returns task
            coEvery { fixture.projectService.getById(project.id) } returns project
            coEvery { fixture.schemeService.typed(schemeId) } returns typed(
                mapOf("TASK_UPDATED" to listOf(NotificationRecipient.Profile(missingId), NotificationRecipient.Profile(noPrincipalId))),
            )
            coEvery { fixture.profileService.getAllByIds(any()) } returns listOf(
                Profile(noPrincipalId, ProfileType.GENERIC, null, name = "No principal", visibility = ProfileVisibility.USER),
            )

            fixture.dispatcher.deliver("TASK_UPDATED", taskId = task.id)

            coVerify(exactly = 0) { fixture.preferenceService.get(any()) }
            coVerify(exactly = 0) { fixture.inboxService.addOnce(any(), any(), any(), any(), any(), any(), any(), any()) }
        } finally {
            fixture.dispatcher.close()
        }
    }

    @Test
    fun `multiple recipient failures are aggregated as suppressed causes`() = runTest {
        val fixture = fixture()
        try {
            val schemeId = UUID.random()
            val project = project(schemeId)
            val task = task(project.id, UUID.random(), UUID.random())
            val firstId = UUID.random()
            val secondId = UUID.random()
            coEvery { fixture.taskService.getById(task.id) } returns task
            coEvery { fixture.projectService.getById(project.id) } returns project
            coEvery { fixture.schemeService.typed(schemeId) } returns typed(
                mapOf("TASK_UPDATED" to listOf(NotificationRecipient.Profile(firstId), NotificationRecipient.Profile(secondId))),
            )
            coEvery { fixture.preferenceService.get(firstId) } throws IllegalStateException("first")
            coEvery { fixture.preferenceService.get(secondId) } throws IllegalArgumentException("second")

            val failure = assertFailsWith<IllegalStateException> {
                fixture.dispatcher.deliver("TASK_UPDATED", taskId = task.id)
            }

            assertEquals(1, failure.suppressed.size)
            assertTrue(failure.suppressed.single() is IllegalArgumentException)
        } finally {
            fixture.dispatcher.close()
        }
    }

    @Test
    fun `spec and requirement comments use manager-aware visible content`() = runTest {
        val fixture = fixture()
        try {
            val schemeId = UUID.random()
            val project = project(schemeId)
            val recipientId = UUID.random()
            val spec = Spec(
                id = UUID.random(),
                key = "NTF-SPEC-1",
                metadataId = UUID.random(),
                projectId = project.id,
                statusId = UUID.random(),
                workflowId = UUID.random(),
                ownerProfileId = UUID.random(),
                createdByPrincipalId = UUID.random(),
                modifiedByPrincipalId = UUID.random(),
            )
            val requirement = Requirement(
                id = UUID.random(),
                key = "NTF-REQ-1",
                metadataId = UUID.random(),
                parentType = RequirementParent.SPEC,
                parentId = spec.id,
                statusId = UUID.random(),
                workflowId = UUID.random(),
                priorityId = UUID.random(),
                createdByPrincipalId = UUID.random(),
                modifiedByPrincipalId = UUID.random(),
            )
            coEvery { fixture.specService.getById(spec.id) } returns spec
            coEvery { fixture.requirementService.getById(requirement.id) } returns requirement
            coEvery { fixture.projectService.getById(project.id) } returns project
            coEvery { fixture.schemeService.typed(schemeId) } returns typed(
                mapOf(
                    "SPEC_COMMENTED" to listOf(NotificationRecipient.Profile(recipientId)),
                    "REQUIREMENT_COMMENTED" to listOf(NotificationRecipient.Profile(recipientId)),
                ),
            )
            coEvery { fixture.preferenceService.get(recipientId) } returns null
            coEvery {
                fixture.specPermissionEvaluator.isAllowed(any<AuthenticationContext>(), spec, PermissionAction.MANAGE)
            } returnsMany listOf(true, false)
            coEvery {
                fixture.requirementPermissionEvaluator.isAllowed(
                    any<AuthenticationContext>(),
                    requirement,
                    PermissionAction.MANAGE,
                )
            } returnsMany listOf(true, false)
            coEvery { fixture.specCommentService.getManager(spec.id, 1) } returns mockk {
                every { content } returns "manager spec comment"
            }
            coEvery { fixture.specCommentService.getForProfile(spec.id, 2, recipientId) } returns mockk {
                every { content } returns "visible spec comment"
            }
            coEvery { fixture.requirementCommentService.getManager(requirement.id, 3) } returns mockk {
                every { content } returns "manager requirement comment"
            }
            coEvery {
                fixture.requirementCommentService.getForProfile(requirement.id, 4, recipientId)
            } returns mockk { every { content } returns "visible requirement comment" }

            fixture.dispatcher.deliver(eventName = "SPEC_COMMENTED", specId = spec.id, commentId = 1)
            fixture.dispatcher.deliver(eventName = "SPEC_COMMENTED", specId = spec.id, commentId = 2)
            fixture.dispatcher.deliver(eventName = "REQUIREMENT_COMMENTED", requirementId = requirement.id, commentId = 3)
            fixture.dispatcher.deliver(eventName = "REQUIREMENT_COMMENTED", requirementId = requirement.id, commentId = 4)

            for (body in listOf(
                "manager spec comment",
                "visible spec comment",
                "manager requirement comment",
                "visible requirement comment",
            )) {
                coVerify(exactly = 1) {
                    fixture.inboxService.addOnce(any(), recipientId, any(), null, project.id, null, body, any())
                }
            }
        } finally {
            fixture.dispatcher.close()
        }
    }

    @Test
    fun `requirement child notifications use every parent and project recipient fallback`() = runTest {
        val fixture = fixture()
        try {
            val schemeId = UUID.random()
            val project = project(schemeId)
            val assigneeId = UUID.random()
            val watcherId = UUID.random()
            val groupProfileId = UUID.random()
            val groupId = UUID.random()
            val parentTask = task(project.id, UUID.random(), UUID.random()).copy(
                watcherProfileIds = listOf(watcherId),
                customFieldValues = buildJsonObject { put("approver", JsonPrimitive(assigneeId.toString())) },
            )
            val requirement = Requirement(
                id = UUID.random(),
                key = "NTF-REQ-PARENT",
                metadataId = UUID.random(),
                parentType = RequirementParent.TASK,
                parentId = parentTask.id,
                statusId = UUID.random(),
                workflowId = UUID.random(),
                priorityId = UUID.random(),
                assigneeProfileId = assigneeId,
                createdByPrincipalId = UUID.random(),
                modifiedByPrincipalId = UUID.random(),
            )
            val group = Group(groupId, "fallback profiles", "", GroupType.SYSTEM)
            val groupPrincipal = Principal(id = UUID.random(), primaryProfileId = null)
            coEvery { fixture.requirementService.getById(requirement.id) } returns requirement
            coEvery { fixture.taskService.getById(parentTask.id) } returns parentTask
            coEvery { fixture.projectService.getById(project.id) } returns project
            coEvery { fixture.watcherService.list(parentTask.id) } returns emptyList()
            coEvery { fixture.schemeService.typed(schemeId) } returns typed(
                mapOf(
                    "REQUIREMENT_UPDATED" to listOf(
                        NotificationRecipient.Reporter,
                        NotificationRecipient.Assignee,
                        NotificationRecipient.CurrentAssignee,
                        NotificationRecipient.Owner,
                        NotificationRecipient.Watchers,
                        NotificationRecipient.CustomFieldValue("approver"),
                        NotificationRecipient.CustomFieldValue("missing"),
                        NotificationRecipient.Group(groupId),
                    ),
                ),
            )
            coEvery { fixture.securityService.getGroupById(groupId) } returns group
            coEvery { fixture.securityService.getPrincipalsByGroup(group) } returns listOf(groupPrincipal)
            coEvery { fixture.profileService.getPrimaryProfile(groupPrincipal) } returns Profile(
                groupProfileId,
                ProfileType.GENERIC,
                groupPrincipal.id,
                name = "Fallback profile",
                visibility = ProfileVisibility.USER,
            )
            coEvery { fixture.preferenceService.get(any()) } returns null

            fixture.dispatcher.deliver(
                eventName = "REQUIREMENT_UPDATED",
                requirementId = requirement.id,
                eventAssigneeKnown = true,
                eventAssigneeProfileId = null,
            )

            for (profileId in setOf(assigneeId, project.ownerProfileId, watcherId, groupProfileId)) {
                coVerify(exactly = 1) {
                    fixture.inboxService.addOnce(
                        any(), profileId, "REQUIREMENT_UPDATED", null, project.id, null,
                        "requirement updated", "/workops/requirements/${requirement.id}",
                    )
                }
            }
        } finally {
            fixture.dispatcher.close()
        }
    }

    @Test
    fun `comment delivery drops missing manager-visible bodies and actor-only recipient sets`() = runTest {
        val fixture = fixture()
        try {
            val schemeId = UUID.random()
            val project = project(schemeId)
            val recipientId = UUID.random()
            val spec = Spec(
                id = UUID.random(),
                key = "NTF-SPEC-MISSING",
                metadataId = UUID.random(),
                projectId = project.id,
                statusId = UUID.random(),
                workflowId = UUID.random(),
                ownerProfileId = UUID.random(),
                createdByPrincipalId = UUID.random(),
                modifiedByPrincipalId = UUID.random(),
            )
            val requirement = Requirement(
                id = UUID.random(),
                key = "NTF-REQ-MISSING",
                metadataId = UUID.random(),
                parentType = RequirementParent.SPEC,
                parentId = spec.id,
                statusId = UUID.random(),
                workflowId = UUID.random(),
                priorityId = UUID.random(),
                createdByPrincipalId = UUID.random(),
                modifiedByPrincipalId = UUID.random(),
            )
            coEvery { fixture.specService.getById(spec.id) } returns spec
            coEvery { fixture.requirementService.getById(requirement.id) } returns requirement
            coEvery { fixture.projectService.getById(project.id) } returns project
            coEvery { fixture.schemeService.typed(schemeId) } returns typed(
                mapOf(
                    "SPEC_COMMENTED" to listOf(NotificationRecipient.Profile(recipientId)),
                    "REQUIREMENT_COMMENTED" to listOf(NotificationRecipient.Profile(recipientId)),
                    "PROJECT_COMMENTED" to listOf(NotificationRecipient.Profile(recipientId)),
                    "PROJECT_UPDATED" to listOf(NotificationRecipient.Profile(recipientId)),
                ),
            )
            coEvery { fixture.preferenceService.get(recipientId) } returns null
            coEvery {
                fixture.specPermissionEvaluator.isAllowed(any<AuthenticationContext>(), spec, PermissionAction.MANAGE)
            } returnsMany listOf(true, false)
            coEvery { fixture.specCommentService.getManager(spec.id, 99) } returns null
            coEvery { fixture.specCommentService.getForProfile(spec.id, 98, recipientId) } returns null
            coEvery {
                fixture.requirementPermissionEvaluator.isAllowed(
                    any<AuthenticationContext>(), requirement, PermissionAction.MANAGE,
                )
            } returnsMany listOf(true, false)
            coEvery { fixture.requirementCommentService.getManager(requirement.id, 97) } returns null
            coEvery { fixture.requirementCommentService.getForProfile(requirement.id, 96, recipientId) } returns null

            fixture.dispatcher.deliver("SPEC_COMMENTED", specId = spec.id, commentId = 99)
            fixture.dispatcher.deliver("SPEC_COMMENTED", specId = spec.id, commentId = 98)
            fixture.dispatcher.deliver("REQUIREMENT_COMMENTED", requirementId = requirement.id, commentId = 97)
            fixture.dispatcher.deliver("REQUIREMENT_COMMENTED", requirementId = requirement.id, commentId = 96)
            fixture.dispatcher.deliver("PROJECT_COMMENTED", projectId = project.id, commentId = 1)
            fixture.dispatcher.deliver(
                "PROJECT_UPDATED",
                projectId = project.id,
                actorProfileId = recipientId,
            )

            coVerify(exactly = 0) {
                fixture.inboxService.addOnce(any(), any(), any(), any(), any(), any(), any(), any())
            }
        } finally {
            fixture.dispatcher.close()
        }
    }

    @Test
    fun `delivery scheduling helpers cover digest and DND boundary semantics`() {
        val zone = ZoneId.of("UTC")
        val beforeDigest = Instant.parse("2026-08-19T08:00:00Z")
        val afterDigest = Instant.parse("2026-08-19T10:00:00Z")
        assertTrue(nextDigestAt(zone, beforeDigest).toInstant() == Instant.parse("2026-08-19T09:00:00Z"))
        assertTrue(nextDigestAt(zone, afterDigest).toInstant() == Instant.parse("2026-08-20T09:00:00Z"))

        assertTrue(
            dndEndsAt(LocalTime.of(9, 0), LocalTime.of(17, 0), zone, Instant.parse("2026-08-19T12:00:00Z"))
                .toInstant() == Instant.parse("2026-08-19T17:00:00Z"),
        )
        assertTrue(
            dndEndsAt(LocalTime.of(22, 0), LocalTime.of(7, 0), zone, Instant.parse("2026-08-19T23:00:00Z"))
                .toInstant() == Instant.parse("2026-08-20T07:00:00Z"),
        )
        assertTrue(
            dndEndsAt(LocalTime.of(22, 0), LocalTime.of(7, 0), zone, Instant.parse("2026-08-19T02:00:00Z"))
                .toInstant() == Instant.parse("2026-08-19T07:00:00Z"),
        )
        assertFailsWith<IllegalArgumentException> {
            dndEndsAt(LocalTime.NOON, LocalTime.NOON, zone, beforeDigest)
        }
    }

    private fun fixture(): Fixture {
        ProviderRegistry.clear()
        val pubSubService = mockk<PubSubService>(relaxed = true)
        every {
            pubSubService.subscribe(any(), any<DeserializationStrategy<Any>>())
        } returns flow { awaitCancellation() }
        provides<PubSubService>(singleton = true) { pubSubService }
        val schemeService = mockk<NotificationSchemeService>()
        val preferenceService = mockk<NotificationPreferenceService>()
        val watcherService = mockk<TaskWatcherService>()
        val taskService = mockk<TaskService>()
        val specService = mockk<SpecService>()
        val requirementService = mockk<RequirementService>(relaxed = true)
        val taskCommentService = mockk<TaskCommentService>(relaxed = true)
        val specCommentService = mockk<SpecCommentService>(relaxed = true)
        val requirementCommentService = mockk<RequirementCommentService>(relaxed = true)
        val projectService = mockk<ProjectService>()
        val taskPermissionEvaluator = mockk<TaskPermissionEvaluator>()
        val specPermissionEvaluator = mockk<SpecPermissionEvaluator>()
        val requirementPermissionEvaluator = mockk<RequirementPermissionEvaluator>()
        val projectPermissionEvaluator = mockk<ProjectPermissionEvaluator>()
        coEvery { taskPermissionEvaluator.isAllowed(any<AuthenticationContext>(), any<Task>(), PermissionAction.VIEW) } returns true
        coEvery { taskPermissionEvaluator.isAllowed(any<AuthenticationContext>(), any<Task>(), PermissionAction.MANAGE) } returns false
        coEvery {
            specPermissionEvaluator.isAllowed(any<AuthenticationContext>(), any<bosca.workops.model.spec.Spec>(), PermissionAction.VIEW)
        } returns true
        coEvery {
            specPermissionEvaluator.isAllowed(any<AuthenticationContext>(), any<bosca.workops.model.spec.Spec>(), PermissionAction.MANAGE)
        } returns false
        coEvery {
            requirementPermissionEvaluator.isAllowed(
                any<AuthenticationContext>(), any<bosca.workops.model.requirement.Requirement>(), PermissionAction.VIEW,
            )
        } returns true
        coEvery {
            requirementPermissionEvaluator.isAllowed(
                any<AuthenticationContext>(), any<bosca.workops.model.requirement.Requirement>(), PermissionAction.MANAGE,
            )
        } returns false
        coEvery { projectPermissionEvaluator.isAllowed(any<AuthenticationContext>(), any<Project>(), PermissionAction.VIEW) } returns true
        val inboxService = mockk<NotificationInboxService>(relaxed = true)
        val outboxService = mockk<NotificationOutboxService>(relaxed = true)
        val pipelineService = mockk<PipelineService>()
        val securityService = mockk<SecurityService>()
        val profileService = mockk<ProfileService>(relaxed = true)
        coEvery { profileService.getAllByIds(any()) } answers {
            firstArg<List<UUID>>().map { id ->
                Profile(id, ProfileType.GENERIC, id, name = "Profile $id", visibility = ProfileVisibility.USER)
            }
        }
        coEvery { securityService.getPrincipalById(any()) } answers { Principal(id = firstArg()) }
        coEvery { securityService.getPrincipalGroups(any<UUID>()) } returns emptyList()
        coEvery { profileService.getPrimaryProfile(any()) } returns null
        val statusService = mockk<StatusService>()
        val dispatcher = NotificationDispatcher(
            pubSubService = pubSubService,
            schemeService = schemeService,
            preferenceService = preferenceService,
            watcherService = watcherService,
            taskService = taskService,
            specService = specService,
            requirementService = requirementService,
            taskCommentService = taskCommentService,
            specCommentService = specCommentService,
            requirementCommentService = requirementCommentService,
            projectService = projectService,
            taskPermissionEvaluator = taskPermissionEvaluator,
            specPermissionEvaluator = specPermissionEvaluator,
            requirementPermissionEvaluator = requirementPermissionEvaluator,
            projectPermissionEvaluator = projectPermissionEvaluator,
            inboxService = inboxService,
            outboxService = outboxService,
            pipelineService = pipelineService,
            securityService = securityService,
            profileService = profileService,
            statusService = statusService,
            json = json,
            connectionPool = mockk<ConnectionPool>(relaxed = true),
        )
        return Fixture(
            dispatcher = dispatcher,
            pubSubService = pubSubService,
            schemeService = schemeService,
            preferenceService = preferenceService,
            watcherService = watcherService,
            taskService = taskService,
            specService = specService,
            requirementService = requirementService,
            projectService = projectService,
            taskPermissionEvaluator = taskPermissionEvaluator,
            specPermissionEvaluator = specPermissionEvaluator,
            requirementPermissionEvaluator = requirementPermissionEvaluator,
            taskCommentService = taskCommentService,
            specCommentService = specCommentService,
            requirementCommentService = requirementCommentService,
            inboxService = inboxService,
            outboxService = outboxService,
            pipelineService = pipelineService,
            securityService = securityService,
            profileService = profileService,
            statusService = statusService,
        )
    }

    private fun project(defaultSchemeId: UUID?): Project = Project(
        id = UUID.random(),
        programId = UUID.random(),
        key = "NTF",
        name = "Notifications",
        ownerProfileId = UUID.random(),
        defaultNotificationSchemeId = defaultSchemeId,
    )

    private fun task(projectId: UUID, reporterId: UUID, assigneeId: UUID): Task = Task(
        id = UUID.random(),
        key = "NTF-1",
        projectId = projectId,
        taskTypeId = UUID.random(),
        statusId = UUID.random(),
        priorityId = UUID.random(),
        summary = "Notification task",
        reporterProfileId = reporterId,
        assigneeProfileId = assigneeId,
        createdByPrincipalId = UUID.random(),
        modifiedByPrincipalId = UUID.random(),
    )

    private fun typed(recipients: Map<String, List<NotificationRecipient>>) = TypedNotificationScheme(
        id = UUID.random(),
        name = "Scheme",
        description = null,
        recipientsByEvent = recipients,
        version = 0,
    )

    private fun status(category: StatusCategory) = Status(
        id = UUID.random(),
        name = category.name,
        category = category,
        colorHex = "#000000",
    )

    private data class Fixture(
        val dispatcher: NotificationDispatcher,
        val pubSubService: PubSubService,
        val schemeService: NotificationSchemeService,
        val preferenceService: NotificationPreferenceService,
        val watcherService: TaskWatcherService,
        val taskService: TaskService,
        val specService: SpecService,
        val requirementService: RequirementService,
        val projectService: ProjectService,
        val taskPermissionEvaluator: TaskPermissionEvaluator,
        val specPermissionEvaluator: SpecPermissionEvaluator,
        val requirementPermissionEvaluator: RequirementPermissionEvaluator,
        val taskCommentService: TaskCommentService,
        val specCommentService: SpecCommentService,
        val requirementCommentService: RequirementCommentService,
        val inboxService: NotificationInboxService,
        val outboxService: NotificationOutboxService,
        val pipelineService: PipelineService,
        val securityService: SecurityService,
        val profileService: ProfileService,
        val statusService: StatusService,
    )
}
