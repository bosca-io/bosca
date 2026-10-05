@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.workops.service

import bosca.comments.model.CommentStatus
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.WorkOpsValidationException
import bosca.workops.model.comment.TaskComment
import bosca.workops.model.comment.TaskCommentInput
import bosca.workops.model.notification.NotificationDeliveryRequested
import bosca.workops.model.notification.dispatch as dispatchNotification
import bosca.workops.model.project.Program
import bosca.workops.model.project.Project
import bosca.workops.model.task.Task
import bosca.workops.model.task.TaskCommented
import bosca.workops.model.task.dispatch
import bosca.workops.repository.TaskCommentRepository
import bosca.workops.repository.TaskHistoryRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TaskCommentServiceTest {
    private val repository = mockk<TaskCommentRepository>()
    private val taskService = mockk<TaskService>()
    private val history = mockk<TaskHistoryRepository>(relaxed = true)
    private val projectService = mockk<ProjectService>()
    private val programService = mockk<ProgramService>()
    private val automation = mockk<AutomationDispatcher>(relaxed = true)
    private val profiles = mockk<ProfileService>()
    private val json = Json
    private val service = TaskCommentServiceImpl(
        repository,
        taskService,
        history,
        projectService,
        programService,
        automation,
        profiles,
        json,
    )

    private val taskId = UUID.random()
    private val principalId = UUID.random()
    private val profileId = UUID.random()
    private val task = task()

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction(any<suspend () -> Any?>()) } coAnswers {
            firstArg<suspend () -> Any?>().invoke()
        }
        mockkStatic("bosca.workops.model.task.TaskCommentedExtKt")
        coEvery { any<TaskCommented>().dispatch() } just Runs
        mockkStatic("bosca.workops.model.notification.NotificationDeliveryRequestedExtKt")
        coEvery { any<NotificationDeliveryRequested>().dispatchNotification() } just Runs
    }

    @AfterTest
    fun teardown() {
        unmockkStatic("bosca.workops.model.notification.NotificationDeliveryRequestedExtKt")
        unmockkStatic("bosca.workops.model.task.TaskCommentedExtKt")
        unmockkStatic("bosca.db.ConnectionManagerKt")
    }

    @Test
    fun `add validates reply persists audit resolves mentions and fires hierarchy automation`() = runTest {
        val parent = comment(7)
        val saved = comment(42)
        val knownProfileId = UUID.random()
        val knownProfile = mockk<Profile> { every { id } returns knownProfileId }
        val project = Project(
            id = task.projectId,
            programId = UUID.random(),
            key = "WORK",
            name = "Work",
            ownerProfileId = UUID.random(),
        )
        val program = Program(
            id = project.programId,
            portfolioId = UUID.random(),
            key = "PROGRAM",
            name = "Program",
            ownerProfileId = UUID.random(),
        )
        coEvery { taskService.getById(taskId) } returns task
        coEvery { repository.getById(7) } returns parent
        coEvery { repository.add(7, taskId, profileId, null, ProfileVisibility.USER, any(), null, null) } returns 42
        coEvery { repository.getById(42) } returns saved
        coEvery { profiles.getBySlug("known") } returns knownProfile
        coEvery { profiles.getBySlug("missing") } returns null
        coEvery { projectService.getById(task.projectId) } returns project
        coEvery { programService.getById(project.programId) } returns program

        val result = service.add(
            taskId,
            TaskCommentInput(parentId = 7, content = "Hello @Known, @missing and @known"),
            principalId,
            profileId,
            null,
        )

        assertSame(saved, result)
        coVerify(exactly = 1) {
            any<TaskCommented>().dispatch()
        }
        coVerify(exactly = 1) {
            automation.fireTaskCommented(task, task.projectId, project.programId, program.portfolioId)
        }
        verifyHistory("comment", "added:42", profileId)
    }

    @Test
    fun `add rejects blank missing task invalid parent and vanished insert`() = runTest {
        assertFailsWith<WorkOpsValidationException> {
            service.add(taskId, TaskCommentInput(content = " \n"), principalId, profileId, null)
        }

        coEvery { taskService.getById(taskId) } returns null
        assertFailsWith<WorkOpsNotFoundException> {
            service.add(taskId, TaskCommentInput(content = "comment"), principalId, profileId, null)
        }

        coEvery { taskService.getById(taskId) } returns task
        coEvery { repository.getById(7) } returns null
        assertFailsWith<WorkOpsNotFoundException> {
            service.add(taskId, TaskCommentInput(parentId = 7, content = "reply"), principalId, profileId, null)
        }

        coEvery { repository.getById(7) } returns comment(7).copy(taskId = UUID.random())
        assertFailsWith<WorkOpsValidationException> {
            service.add(taskId, TaskCommentInput(parentId = 7, content = "reply"), principalId, profileId, null)
        }

        coEvery { repository.add(null, taskId, profileId, null, ProfileVisibility.USER, "comment", null, null) } returns 99
        coEvery { repository.getById(99) } returns null
        assertFailsWith<IllegalStateException> {
            service.add(taskId, TaskCommentInput(content = "comment"), principalId, profileId, null)
        }
    }

    @Test
    fun `comment automation handles absent hierarchy ordinary failure and cancellation`() = runTest {
        stubAdd(1)
        coEvery { projectService.getById(task.projectId) } returns null
        service.add(taskId, TaskCommentInput(content = "one"), principalId, profileId, null)
        coVerify(exactly = 0) { automation.fireTaskCommented(any(), any(), any(), any()) }

        stubAdd(2)
        val project = Project(
            id = task.projectId,
            programId = UUID.random(),
            key = "WORK",
            name = "Work",
            ownerProfileId = UUID.random(),
        )
        coEvery { projectService.getById(task.projectId) } returns project
        coEvery { programService.getById(project.programId) } returns null
        service.add(taskId, TaskCommentInput(content = "two"), principalId, profileId, null)
        coVerify(exactly = 1) { automation.fireTaskCommented(task, task.projectId, project.programId, null) }

        stubAdd(3)
        coEvery { automation.fireTaskCommented(any(), any(), any(), any()) } throws IllegalStateException("automation")
        service.add(taskId, TaskCommentInput(content = "three"), principalId, profileId, null)

        stubAdd(4)
        coEvery { automation.fireTaskCommented(any(), any(), any(), any()) } throws CancellationException("cancelled")
        assertFailsWith<CancellationException> {
            service.add(taskId, TaskCommentInput(content = "four"), principalId, profileId, null)
        }
    }

    @Test
    fun `read count and reply operations route every visibility mode with bounded pages`() = runTest {
        val expected = listOf(comment(1))
        coEvery { repository.getManager(taskId, 1) } returns expected.single()
        coEvery { repository.getForProfile(taskId, 1, profileId) } returns expected.single()
        coEvery { repository.getPublic(taskId, 1) } returns expected.single()
        coEvery { repository.listManager(taskId, 0, 100) } returns expected
        coEvery { repository.listForProfile(taskId, profileId, 0, 1) } returns expected
        coEvery { repository.listPublic(taskId, 2, 3) } returns expected
        coEvery { repository.countManager(taskId) } returns 1
        coEvery { repository.countForProfile(taskId, profileId) } returns 2
        coEvery { repository.countPublic(taskId) } returns 3
        coEvery { repository.listRepliesManager(taskId, 1, 0, 100) } returns expected
        coEvery { repository.listRepliesForProfile(taskId, profileId, 1, 0, 1) } returns expected
        coEvery { repository.listRepliesPublic(taskId, 1, 2, 3) } returns expected

        assertSame(expected.single(), service.get(taskId, 1, null, true))
        assertSame(expected.single(), service.get(taskId, 1, profileId, false))
        assertSame(expected.single(), service.get(taskId, 1, null, false))
        assertEquals(expected, service.list(taskId, null, true, -1, 999))
        assertEquals(expected, service.list(taskId, profileId, false, -1, 0))
        assertEquals(expected, service.list(taskId, null, false, 2, 3))
        assertEquals(1, service.count(taskId, null, true))
        assertEquals(2, service.count(taskId, profileId, false))
        assertEquals(3, service.count(taskId, null, false))
        assertEquals(expected, service.listReplies(taskId, 1, null, true, -1, 999))
        assertEquals(expected, service.listReplies(taskId, 1, profileId, false, -1, 0))
        assertEquals(expected, service.listReplies(taskId, 1, null, false, 2, 3))
    }

    @Test
    fun `likes and unlikes distinguish missing zero positive and inconsistent reaction rows`() = runTest {
        coEvery { repository.incrementLikes(taskId, 1) } returns null
        assertEquals(-1, service.like(taskId, 1, profileId))
        coEvery { repository.incrementLikes(taskId, 2) } returns 0
        assertEquals(0, service.like(taskId, 2, profileId))
        coEvery { repository.incrementLikes(taskId, 3) } returns 2
        coEvery { repository.addLikeRow(3, profileId) } just Runs
        assertEquals(2, service.like(taskId, 3, profileId))
        coVerify(exactly = 1) { repository.addLikeRow(3, profileId) }

        coEvery { repository.decrementLikes(taskId, 4) } returns null
        assertEquals(-1, service.unlike(taskId, 4, profileId))
        coEvery { repository.decrementLikes(taskId, 5) } returns 0
        coEvery { repository.deleteLikeRow(5, profileId) } returns 5
        assertEquals(0, service.unlike(taskId, 5, profileId))
        coEvery { repository.decrementLikes(taskId, 6) } returns 0
        coEvery { repository.deleteLikeRow(6, profileId) } returns null
        assertFailsWith<IllegalStateException> { service.unlike(taskId, 6, profileId) }
    }

    @Test
    fun `moderation deletion and author deletion validate entities audit and dispatch`() = runTest {
        val existing = comment(1).copy(content = "Review @known")
        val mentionedId = UUID.random()
        coEvery { taskService.getById(taskId) } returns task
        coEvery { repository.getManager(taskId, 1) } returns existing
        coEvery { repository.setStatus(taskId, 1, CommentStatus.APPROVED) } just Runs
        coEvery { profiles.getBySlug("known") } returns mockk { every { id } returns mentionedId }
        service.setStatus(taskId, 1, CommentStatus.APPROVED, principalId, null)
        verifyHistory("comment_status", "1:APPROVED", null)

        coEvery { repository.softDelete(taskId, 2) } just Runs
        service.delete(taskId, 2, principalId, profileId)
        coEvery { repository.softDeleteByProfile(taskId, 3, profileId) } just Runs
        service.deleteByAuthor(taskId, 3, profileId, principalId)
        coVerify(exactly = 3) { any<NotificationDeliveryRequested>().dispatchNotification() }

        val missingId = UUID.random()
        coEvery { taskService.getById(missingId) } returns null
        assertFailsWith<WorkOpsNotFoundException> {
            service.setStatus(missingId, 1, CommentStatus.BLOCKED, principalId, null)
        }
        assertFailsWith<WorkOpsNotFoundException> { service.delete(missingId, 1, principalId, null) }
        assertFailsWith<WorkOpsNotFoundException> { service.deleteByAuthor(missingId, 1, profileId, principalId) }

        coEvery { taskService.getById(taskId) } returns task
        coEvery { repository.getManager(taskId, 99) } returns null
        assertFailsWith<WorkOpsNotFoundException> {
            service.setStatus(taskId, 99, CommentStatus.BLOCKED, principalId, null)
        }
    }

    private suspend fun stubAdd(id: Long) {
        coEvery { taskService.getById(taskId) } returns task
        coEvery { repository.add(null, taskId, profileId, null, ProfileVisibility.USER, any(), null, null) } returns id
        coEvery { repository.getById(id) } returns comment(id)
        coEvery { profiles.getBySlug(any()) } returns null
    }

    private fun task() = Task(
        id = taskId,
        key = "WORK-1",
        projectId = UUID.random(),
        taskTypeId = UUID.random(),
        statusId = UUID.random(),
        priorityId = UUID.random(),
        summary = "Work",
        reporterProfileId = profileId,
        createdByPrincipalId = principalId,
        modifiedByPrincipalId = principalId,
    )

    private fun comment(id: Long) = TaskComment(
        id = id,
        taskId = taskId,
        profileId = profileId,
        created = OffsetDateTime.now(),
        modified = OffsetDateTime.now(),
        status = CommentStatus.PENDING,
        content = "comment",
    )

    private suspend fun verifyHistory(fieldKey: String, value: String, changedByProfileId: UUID?) {
        coVerify(atLeast = 1) {
            history.add(
                taskId,
                any(),
                principalId,
                changedByProfileId,
                match {
                    val change = (it as JsonArray).single() as JsonObject
                    change["fieldKey"]?.jsonPrimitive?.content == fieldKey &&
                        change["toValue"]?.jsonPrimitive?.content == value
                },
            )
        }
    }
}
