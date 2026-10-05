package bosca.workops.controller

import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.PermissionAction
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.serialization.UUID
import bosca.workops.model.links.LinkCategory
import bosca.workops.model.links.TaskLink
import bosca.workops.model.links.TaskLinkInput
import bosca.workops.model.links.TaskLinkType
import bosca.workops.model.task.Task
import bosca.workops.service.TaskLinkService
import bosca.workops.service.TaskPermissionEvaluator
import bosca.workops.service.TaskService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Permission-focused tests for LinkController.
 *
 * Previous behavior:
 *   - forTask returned all links without VIEW on the task (link graph leak)
 *   - link() only verified EDIT on the source task, leaking target task existence
 *
 * Post-fix behavior:
 *   - forTask requires VIEW on task; returns empty if denied
 *   - link() requires EDIT on source AND VIEW on target
 */
class LinkControllerTest {

    private val service = mockk<TaskLinkService>(relaxed = true)
    private val taskService = mockk<TaskService>()
    private val taskPermissions = mockk<TaskPermissionEvaluator>()

    private val principalId = UUID.random()
    private val profileId = UUID.random()

    private fun authenticated(): AuthenticationContext {
        val principal = Principal(id = principalId, primaryProfileId = profileId)
        val groups = listOf(Group(id = UUID.random(), name = "users", description = "", type = GroupType.SYSTEM))
        return ImpersonatedAuthenticationContext(principal, groups)
    }

    private fun queryController() = TaskLinkQueryController(
        service = service,
        taskService = taskService,
        taskPermissions = taskPermissions,
    )

    private fun mutationController() = TaskLinkMutationController(
        service = service,
        taskService = taskService,
        taskPermissions = taskPermissions,
    )

    private fun typeController() = TaskLinkTypeController(
        taskLinkService = service,
        taskService = taskService,
        taskPermissions = taskPermissions,
    )

    @Test
    fun `task link fields and link type are resolved`() = runTest {
        val linkType = sampleLinkType()
        val link = sampleLink(linkTypeId = linkType.id)
        coEvery { service.getLinkType(linkType.id) } returns linkType

        val controller = typeController()
        assertEquals(link.id, controller.id(link))
        assertEquals(link.createdAt, controller.createdAt(link))
        assertEquals(link.createdByPrincipalId, controller.createdByPrincipalId(link))
        assertEquals(linkType, controller.linkType(link))
    }

    @Test
    fun `linkType errors when referenced type is missing`() = runTest {
        val link = sampleLink()
        coEvery { service.getLinkType(link.linkTypeId) } returns null

        assertFailsWith<IllegalStateException> {
            typeController().linkType(link)
        }
    }

    @Test
    fun `sourceTask hides missing and unauthorized tasks and returns visible task`() = runTest {
        val authentication = authenticated()
        val link = sampleLink()
        val source = sampleTask(id = link.sourceTaskId)

        coEvery { taskService.getById(link.sourceTaskId) } returns null
        assertNull(typeController().sourceTask(authentication, link))

        coEvery { taskService.getById(link.sourceTaskId) } returns source
        coEvery { taskPermissions.isAllowed(authentication, source, PermissionAction.VIEW) } returns false
        assertNull(typeController().sourceTask(authentication, link))

        coEvery { taskPermissions.isAllowed(authentication, source, PermissionAction.VIEW) } returns true
        assertEquals(source, typeController().sourceTask(authentication, link))
    }

    @Test
    fun `targetTask hides missing and unauthorized tasks and returns visible task`() = runTest {
        val authentication = authenticated()
        val link = sampleLink()
        val target = sampleTask(id = link.targetTaskId)

        coEvery { taskService.getById(link.targetTaskId) } returns null
        assertNull(typeController().targetTask(authentication, link))

        coEvery { taskService.getById(link.targetTaskId) } returns target
        coEvery { taskPermissions.isAllowed(authentication, target, PermissionAction.VIEW) } returns false
        assertNull(typeController().targetTask(authentication, link))

        coEvery { taskPermissions.isAllowed(authentication, target, PermissionAction.VIEW) } returns true
        assertEquals(target, typeController().targetTask(authentication, link))
    }

    @Test
    fun `linkTypes returns configured types`() = runTest {
        val linkTypes = listOf(sampleLinkType())
        coEvery { service.listLinkTypes() } returns linkTypes

        assertEquals(linkTypes, queryController().linkTypes(authenticated()))
    }

    // --- forTask: requires VIEW on task ---

    @Test
    fun `forTask returns empty when task does not exist`() = runTest {
        val taskId = UUID.random()
        coEvery { taskService.getById(taskId) } returns null

        assertTrue(queryController().forTask(authenticated(), taskId).isEmpty())
        coVerify(exactly = 0) { service.listForTask(any()) }
    }

    @Test
    fun `forTask returns empty when caller lacks VIEW on task`() = runTest {
        val task = sampleTask()
        coEvery { taskService.getById(task.id) } returns task
        coEvery { taskPermissions.isAllowed(any<AuthenticationContext>(), task, PermissionAction.VIEW) } returns false

        assertTrue(queryController().forTask(authenticated(), task.id).isEmpty())
        coVerify(exactly = 0) { service.listForTask(any()) }
    }

    @Test
    fun `forTask returns links when caller has VIEW`() = runTest {
        val task = sampleTask()
        coEvery { taskService.getById(task.id) } returns task
        coEvery { taskPermissions.isAllowed(any<AuthenticationContext>(), task, PermissionAction.VIEW) } returns true
        coEvery { service.listForTask(task.id) } returns emptyList()

        assertEquals(emptyList(), queryController().forTask(authenticated(), task.id))
    }

    // --- link: requires EDIT on source AND VIEW on target ---

    @Test
    fun `link errors when source task is not found`() = runTest {
        val input = sampleLinkInput()
        coEvery { taskService.getById(input.sourceTaskId) } returns null

        assertFailsWith<IllegalStateException> {
            mutationController().link(authenticated(), input)
        }
        coVerify(exactly = 0) { service.link(any(), any()) }
    }

    @Test
    fun `link throws when caller lacks EDIT on source task`() = runTest {
        val source = sampleTask()
        val target = sampleTask()
        coEvery { taskService.getById(source.id) } returns source
        coEvery {
            taskPermissions.verifyAllowed(any(), source, PermissionAction.EDIT)
        } throws SecurityException("denied")

        assertFailsWith<SecurityException> {
            mutationController().link(authenticated(), TaskLinkInput(linkTypeId = UUID.random(), sourceTaskId = source.id, targetTaskId = target.id))
        }
        coVerify(exactly = 0) { service.link(any(), any()) }
    }

    @Test
    fun `link throws when caller has EDIT on source but lacks VIEW on target`() = runTest {
        val source = sampleTask()
        val target = sampleTask()
        coEvery { taskService.getById(source.id) } returns source
        coEvery { taskService.getById(target.id) } returns target
        coEvery { taskPermissions.verifyAllowed(any(), source, PermissionAction.EDIT) } returns Unit
        coEvery {
            taskPermissions.verifyAllowed(any(), target, PermissionAction.VIEW)
        } throws SecurityException("denied")

        assertFailsWith<SecurityException> {
            mutationController().link(authenticated(), TaskLinkInput(linkTypeId = UUID.random(), sourceTaskId = source.id, targetTaskId = target.id))
        }
        coVerify(exactly = 0) { service.link(any(), any()) }
    }

    @Test
    fun `link errors when target task not found`() = runTest {
        val source = sampleTask()
        val targetId = UUID.random()
        coEvery { taskService.getById(source.id) } returns source
        coEvery { taskService.getById(targetId) } returns null
        coEvery { taskPermissions.verifyAllowed(any(), source, PermissionAction.EDIT) } returns Unit

        assertFailsWith<IllegalStateException> {
            mutationController().link(authenticated(), TaskLinkInput(linkTypeId = UUID.random(), sourceTaskId = source.id, targetTaskId = targetId))
        }
    }

    @Test
    fun `link requires an authenticated principal after permission checks`() = runTest {
        val source = sampleTask()
        val target = sampleTask()
        val input = sampleLinkInput(sourceTaskId = source.id, targetTaskId = target.id)
        val unauthenticated = mockk<AuthenticationContext>()
        coEvery { taskService.getById(source.id) } returns source
        coEvery { taskService.getById(target.id) } returns target
        coEvery { taskPermissions.verifyAllowed(unauthenticated, source, PermissionAction.EDIT) } returns Unit
        coEvery { taskPermissions.verifyAllowed(unauthenticated, target, PermissionAction.VIEW) } returns Unit
        coEvery { unauthenticated.principal() } returns null

        assertFailsWith<IllegalStateException> {
            mutationController().link(unauthenticated, input)
        }
        coVerify(exactly = 0) { service.link(any(), any()) }
    }

    @Test
    fun `link creates task link for authenticated principal`() = runTest {
        val authentication = authenticated()
        val source = sampleTask()
        val target = sampleTask()
        val input = sampleLinkInput(sourceTaskId = source.id, targetTaskId = target.id)
        val link = sampleLink(
            linkTypeId = input.linkTypeId,
            sourceTaskId = source.id,
            targetTaskId = target.id,
        )
        coEvery { taskService.getById(source.id) } returns source
        coEvery { taskService.getById(target.id) } returns target
        coEvery { taskPermissions.verifyAllowed(authentication, source, PermissionAction.EDIT) } returns Unit
        coEvery { taskPermissions.verifyAllowed(authentication, target, PermissionAction.VIEW) } returns Unit
        coEvery { service.link(input, principalId) } returns link

        assertEquals(link, mutationController().link(authentication, input))
        coVerify(exactly = 1) { service.link(input, principalId) }
    }

    @Test
    fun `unlink errors when link is not found`() = runTest {
        val linkId = UUID.random()
        coEvery { service.getById(linkId) } returns null

        assertFailsWith<IllegalStateException> {
            mutationController().unlink(authenticated(), linkId)
        }
        coVerify(exactly = 0) { service.unlink(any()) }
    }

    @Test
    fun `unlink errors when source task is not found`() = runTest {
        val link = sampleLink()
        coEvery { service.getById(link.id) } returns link
        coEvery { taskService.getById(link.sourceTaskId) } returns null

        assertFailsWith<IllegalStateException> {
            mutationController().unlink(authenticated(), link.id)
        }
        coVerify(exactly = 0) { service.unlink(any()) }
    }

    @Test
    fun `unlink requires edit permission on source task`() = runTest {
        val authentication = authenticated()
        val link = sampleLink()
        val source = sampleTask(id = link.sourceTaskId)
        coEvery { service.getById(link.id) } returns link
        coEvery { taskService.getById(link.sourceTaskId) } returns source
        coEvery {
            taskPermissions.verifyAllowed(authentication, source, PermissionAction.EDIT)
        } throws SecurityException("denied")

        assertFailsWith<SecurityException> {
            mutationController().unlink(authentication, link.id)
        }
        coVerify(exactly = 0) { service.unlink(any()) }
    }

    @Test
    fun `unlink removes visible link`() = runTest {
        val authentication = authenticated()
        val link = sampleLink()
        val source = sampleTask(id = link.sourceTaskId)
        coEvery { service.getById(link.id) } returns link
        coEvery { taskService.getById(link.sourceTaskId) } returns source
        coEvery { taskPermissions.verifyAllowed(authentication, source, PermissionAction.EDIT) } returns Unit
        coEvery { service.unlink(link.id) } returns Unit

        assertTrue(mutationController().unlink(authentication, link.id))
        coVerify(exactly = 1) { service.unlink(link.id) }
    }

    // --- helpers ---

    private fun sampleTask(id: UUID = UUID.random()): Task = Task(
        id = id, key = "P-${UUID.random()}", projectId = UUID.random(),
        taskTypeId = UUID.random(), statusId = UUID.random(), priorityId = UUID.random(),
        summary = "task", reporterProfileId = profileId,
        createdByPrincipalId = principalId, modifiedByPrincipalId = principalId,
    )

    private fun sampleLinkType() = TaskLinkType(
        id = UUID.random(),
        name = "blocks",
        inwardLabel = "is blocked by",
        outwardLabel = "blocks",
        category = LinkCategory.BLOCKS,
    )

    private fun sampleLinkInput(
        sourceTaskId: UUID = UUID.random(),
        targetTaskId: UUID = UUID.random(),
    ) = TaskLinkInput(
        linkTypeId = UUID.random(),
        sourceTaskId = sourceTaskId,
        targetTaskId = targetTaskId,
    )

    private fun sampleLink(
        linkTypeId: UUID = UUID.random(),
        sourceTaskId: UUID = UUID.random(),
        targetTaskId: UUID = UUID.random(),
    ) = TaskLink(
        id = UUID.random(),
        linkTypeId = linkTypeId,
        sourceTaskId = sourceTaskId,
        targetTaskId = targetTaskId,
        createdByPrincipalId = principalId,
    )
}
