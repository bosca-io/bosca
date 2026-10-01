package bosca.workops.controller

import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.serialization.UUID
import bosca.workops.model.links.CreateTaskLinkTypeInput
import bosca.workops.model.links.LinkCategory
import bosca.workops.model.links.TaskLinkType
import bosca.workops.model.links.UpdateTaskLinkTypeInput
import bosca.workops.model.task.CreatePriorityInput
import bosca.workops.model.task.CreateResolutionInput
import bosca.workops.model.task.CreateTaskTypeInput
import bosca.workops.model.task.Priority
import bosca.workops.model.task.Resolution
import bosca.workops.model.task.TaskHierarchyLevel
import bosca.workops.model.task.TaskType
import bosca.workops.model.task.UpdatePriorityInput
import bosca.workops.model.task.UpdateResolutionInput
import bosca.workops.model.task.UpdateTaskTypeInput
import bosca.workops.model.workflow.CreateStatusInput
import bosca.workops.model.workflow.Status
import bosca.workops.model.workflow.StatusCategory
import bosca.workops.model.workflow.UpdateStatusInput
import bosca.workops.service.PriorityService
import bosca.workops.service.ResolutionService
import bosca.workops.service.StatusService
import bosca.workops.service.TaskLinkService
import bosca.workops.service.TaskTypeService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LookupMutationControllerTest {

    private val taskTypeService = mockk<TaskTypeService>(relaxed = true)
    private val priorityService = mockk<PriorityService>(relaxed = true)
    private val resolutionService = mockk<ResolutionService>(relaxed = true)
    private val statusService = mockk<StatusService>(relaxed = true)
    private val taskLinkService = mockk<TaskLinkService>(relaxed = true)
    private val principalId = UUID.random()

    @Test
    fun `task type and priority mutations delegate complete admin CRUD`() = runTest {
        val authentication = authentication("sa")
        val taskTypeId = UUID.random()
        val createTaskType = CreateTaskTypeInput(
            name = "Incident",
            description = "Production incident",
            iconKey = "alert",
            colorHex = "#ff0000",
            hierarchyLevel = TaskHierarchyLevel.STANDARD,
        )
        val updateTaskType = UpdateTaskTypeInput(
            name = "Major Incident",
            description = "Major production incident",
            iconKey = "alert",
            colorHex = "#cc0000",
            hierarchyLevel = TaskHierarchyLevel.STANDARD,
            expectedVersion = 1,
        )
        val taskType = TaskType(
            id = taskTypeId,
            name = createTaskType.name,
            description = createTaskType.description,
            iconKey = createTaskType.iconKey,
            colorHex = createTaskType.colorHex,
            hierarchyLevel = createTaskType.hierarchyLevel,
        )
        coEvery { taskTypeService.create(createTaskType) } returns taskType
        coEvery { taskTypeService.update(taskTypeId, updateTaskType) } returns taskType.copy(
            name = updateTaskType.name,
            version = 2,
        )

        val taskTypes = TaskTypeMutationController(taskTypeService)
        assertSame(taskType, taskTypes.create(authentication, createTaskType))
        assertEquals(2L, taskTypes.update(authentication, taskTypeId, updateTaskType).version)
        assertTrue(taskTypes.delete(authentication, taskTypeId))
        coVerify(exactly = 1) { taskTypeService.delete(taskTypeId) }

        val priorityId = UUID.random()
        val createPriority = CreatePriorityInput("Sev 0", "Immediate", "alert", "#ff0000", 0)
        val updatePriority = UpdatePriorityInput("Critical", "Urgent", "alert", "#cc0000", 1, 1)
        val priority = Priority(priorityId, "Sev 0", "Immediate", "alert", "#ff0000", 0)
        coEvery { priorityService.create(createPriority) } returns priority
        coEvery { priorityService.update(priorityId, updatePriority) } returns priority.copy(
            name = updatePriority.name,
            version = 2,
        )

        val priorities = PriorityMutationController(priorityService)
        assertSame(priority, priorities.create(authentication, createPriority))
        assertEquals(2L, priorities.update(authentication, priorityId, updatePriority).version)
        assertTrue(priorities.delete(authentication, priorityId))
        coVerify(exactly = 1) { priorityService.delete(priorityId) }
    }

    @Test
    fun `resolution status and link type mutations delegate complete admin CRUD`() = runTest {
        val authentication = authentication("administrators")
        val resolutionId = UUID.random()
        val createResolution = CreateResolutionInput("Deferred", "Deferred intentionally", 10)
        val updateResolution = UpdateResolutionInput("Later", "Deferred", 11, 1)
        val resolution = Resolution(resolutionId, "Deferred", "Deferred intentionally", 10)
        coEvery { resolutionService.create(createResolution) } returns resolution
        coEvery { resolutionService.update(resolutionId, updateResolution) } returns resolution.copy(
            name = updateResolution.name,
            version = 2,
        )

        val resolutions = ResolutionMutationController(resolutionService)
        assertSame(resolution, resolutions.create(authentication, createResolution))
        assertEquals(2L, resolutions.update(authentication, resolutionId, updateResolution).version)
        assertTrue(resolutions.delete(authentication, resolutionId))
        coVerify(exactly = 1) { resolutionService.delete(resolutionId) }

        val statusId = UUID.random()
        val createStatus = CreateStatusInput("Blocked", "Waiting", StatusCategory.IN_PROGRESS, "#ffaa00")
        val updateStatus = UpdateStatusInput("Waiting", "Blocked", StatusCategory.IN_PROGRESS, "#ff9900", 1)
        val status = Status(statusId, "Blocked", "Waiting", StatusCategory.IN_PROGRESS, "#ffaa00")
        coEvery { statusService.create(createStatus) } returns status
        coEvery { statusService.update(statusId, updateStatus) } returns status.copy(
            name = updateStatus.name,
            version = 2,
        )

        val statuses = StatusMutationController(statusService)
        assertSame(status, statuses.create(authentication, createStatus))
        assertEquals(2L, statuses.update(authentication, statusId, updateStatus).version)
        assertTrue(statuses.delete(authentication, statusId))
        coVerify(exactly = 1) { statusService.delete(statusId) }

        val linkTypeId = UUID.random()
        val createLinkType = CreateTaskLinkTypeInput("Depends", "is required by", "requires", LinkCategory.CUSTOM)
        val updateLinkType = UpdateTaskLinkTypeInput("Requires", "is required by", "requires", LinkCategory.CUSTOM, 1)
        val linkType = TaskLinkType(linkTypeId, "Depends", "is required by", "requires", LinkCategory.CUSTOM)
        coEvery { taskLinkService.createLinkType(createLinkType) } returns linkType
        coEvery { taskLinkService.updateLinkType(linkTypeId, updateLinkType) } returns linkType.copy(
            name = updateLinkType.name,
            version = 2,
        )

        val linkTypes = TaskLinkTypeMutationController(taskLinkService)
        assertSame(linkType, linkTypes.create(authentication, createLinkType))
        assertEquals(2L, linkTypes.update(authentication, linkTypeId, updateLinkType).version)
        assertTrue(linkTypes.delete(authentication, linkTypeId))
        coVerify(exactly = 1) { taskLinkService.deleteLinkType(linkTypeId) }
    }

    @Test
    fun `lookup mutations require sa or administrators membership`() = runTest {
        val input = CreateTaskTypeInput("Incident", iconKey = "alert", colorHex = "#ff0000")
        val taskType = TaskType(
            id = UUID.random(),
            name = input.name,
            iconKey = input.iconKey,
            colorHex = input.colorHex,
        )
        coEvery { taskTypeService.create(input) } returns taskType
        val controller = TaskTypeMutationController(taskTypeService)

        assertSame(taskType, controller.create(authentication("sa"), input))
        assertSame(taskType, controller.create(authentication("administrators"), input))
        assertFailsWith<IllegalStateException> { controller.create(authentication("users"), input) }
        assertFailsWith<IllegalStateException> {
            controller.create(AuthenticationContext(null, null), input)
        }
        coVerify(exactly = 2) { taskTypeService.create(input) }
    }

    private fun authentication(groupName: String): AuthenticationContext =
        ImpersonatedAuthenticationContext(
            Principal(principalId),
            listOf(Group(UUID.random(), groupName, "", GroupType.SYSTEM)),
        )
}
