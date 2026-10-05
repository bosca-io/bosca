package bosca.workops.controller

import bosca.profile.model.Profile
import bosca.profile.profile.service.ProfileService
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.PermissionAction
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.serialization.UUID
import bosca.workops.model.audit.RequirementHistoryEntry
import bosca.workops.model.requirement.CreateRequirementInput
import bosca.workops.model.requirement.Requirement
import bosca.workops.model.requirement.RequirementParent
import bosca.workops.model.requirement.UpdateRequirementInput
import bosca.workops.model.spec.Spec
import bosca.workops.model.task.Priority
import bosca.workops.model.task.Task
import bosca.workops.model.workflow.Status
import bosca.workops.model.workflow.StatusCategory
import bosca.workops.service.PriorityService
import bosca.workops.service.RequirementPermissionEvaluator
import bosca.workops.service.RequirementService
import bosca.workops.service.SpecPermissionEvaluator
import bosca.workops.service.SpecService
import bosca.workops.service.StatusService
import bosca.workops.service.TaskPermissionEvaluator
import bosca.workops.service.TaskService
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

class RequirementControllerTest {

    private val requirementService = mockk<RequirementService>(relaxed = true)
    private val permissionEvaluator = mockk<RequirementPermissionEvaluator>(relaxed = true)
    private val specService = mockk<SpecService>(relaxed = true)
    private val taskService = mockk<TaskService>(relaxed = true)
    private val specPermissions = mockk<SpecPermissionEvaluator>(relaxed = true)
    private val taskPermissions = mockk<TaskPermissionEvaluator>(relaxed = true)
    private val statusService = mockk<StatusService>(relaxed = true)
    private val priorityService = mockk<PriorityService>(relaxed = true)
    private val profileService = mockk<ProfileService>(relaxed = true)
    private val profilePermissions = mockk<ProfilePermissionEvaluator>(relaxed = true)

    private val principalId = UUID.random()
    private val profileId = UUID.random()

    private fun authenticated(primaryProfileId: UUID? = profileId): AuthenticationContext =
        ImpersonatedAuthenticationContext(
            Principal(principalId, primaryProfileId = primaryProfileId),
            listOf(Group(UUID.random(), "users", "", GroupType.SYSTEM)),
        )

    private fun queryController() = RequirementQueryController(
        requirementService,
        permissionEvaluator,
        specService,
        taskService,
        specPermissions,
        taskPermissions,
    )

    private fun mutationController() = RequirementMutationController(
        requirementService,
        permissionEvaluator,
        specService,
        taskService,
        specPermissions,
        taskPermissions,
        profileService,
    )

    @Test
    fun `requirement fields resolve workflow values relations and history`() = runTest {
        val task = sampleTask()
        val requirement = sampleRequirement(taskId = task.id, assigneeProfileId = profileId)
        val status = Status(
            id = requirement.statusId,
            name = "Ready",
            category = StatusCategory.TODO,
            colorHex = "#123456",
        )
        val priority = Priority(
            id = requirement.priorityId,
            name = "High",
            iconKey = "high",
            colorHex = "#654321",
            displayOrder = 1,
        )
        val assignee = mockk<Profile>()
        val history = listOf(mockk<RequirementHistoryEntry>())
        val authentication = authenticated()
        coEvery { statusService.getById(requirement.statusId) } returns status
        coEvery { priorityService.getById(requirement.priorityId) } returns priority
        coEvery { taskService.getById(task.id) } returns task
        coEvery { taskPermissions.isAllowed(authentication, task, PermissionAction.VIEW) } returns true
        coEvery { profileService.getById(profileId) } returns assignee
        coEvery { profilePermissions.isAllowed(authentication, assignee, PermissionAction.VIEW) } returns true
        coEvery { requirementService.listHistory(requirement.id, 2, 3) } returns history

        val controller = RequirementTypeFieldController(
            statusService,
            priorityService,
            requirementService,
            taskService,
            taskPermissions,
            profileService,
            profilePermissions,
        )
        assertEquals(requirement.id, controller.id(requirement))
        assertEquals(requirement.key, controller.key(requirement))
        assertEquals(requirement.metadataId, controller.metadataId(requirement))
        assertEquals(requirement.parentType, controller.parentType(requirement))
        assertEquals(requirement.parentId, controller.parentId(requirement))
        assertEquals(requirement.assigneeProfileId, controller.assigneeProfileId(requirement))
        assertEquals(requirement.taskId, controller.taskId(requirement))
        assertEquals(requirement.sortOrder, controller.sortOrder(requirement))
        assertEquals(requirement.labelIds, controller.labelIds(requirement))
        assertEquals(requirement.deletedAt, controller.deletedAt(requirement))
        assertEquals(requirement.createdAt, controller.createdAt(requirement))
        assertEquals(requirement.modifiedAt, controller.modifiedAt(requirement))
        assertEquals(requirement.createdByPrincipalId, controller.createdByPrincipalId(requirement))
        assertEquals(requirement.modifiedByPrincipalId, controller.modifiedByPrincipalId(requirement))
        assertEquals(requirement.version, controller.version(requirement))
        assertSame(status, controller.status(requirement))
        assertSame(priority, controller.priority(requirement))
        assertSame(task, controller.task(authentication, requirement))
        assertSame(assignee, controller.assignee(authentication, requirement))
        assertEquals(history, controller.history(requirement, 2, 3))
    }

    @Test
    fun `requirement fields fail closed for missing or hidden relations`() = runTest {
        val task = sampleTask()
        val authentication = authenticated()
        val controller = RequirementTypeFieldController(
            statusService,
            priorityService,
            requirementService,
            taskService,
            taskPermissions,
            profileService,
            profilePermissions,
        )
        val unlinked = sampleRequirement()
        assertNull(controller.task(authentication, unlinked))
        assertNull(controller.assignee(authentication, unlinked))

        val linked = sampleRequirement(taskId = task.id, assigneeProfileId = profileId)
        coEvery { statusService.getById(linked.statusId) } returns null
        coEvery { priorityService.getById(linked.priorityId) } returns null
        coEvery { taskService.getById(task.id) } returns null
        assertFailsWith<IllegalStateException> { controller.status(linked) }
        assertFailsWith<IllegalStateException> { controller.priority(linked) }
        assertNull(controller.task(authentication, linked))

        val assignee = mockk<Profile>()
        coEvery { taskService.getById(task.id) } returns task
        coEvery { taskPermissions.isAllowed(authentication, task, PermissionAction.VIEW) } returns false
        coEvery { profileService.getById(profileId) } returns assignee
        coEvery { profilePermissions.isAllowed(authentication, assignee, PermissionAction.VIEW) } returns false
        assertNull(controller.task(authentication, linked))
        assertNull(controller.assignee(authentication, linked))
    }

    @Test
    fun `requirement queries enforce entity and parent visibility`() = runTest {
        val requirement = sampleRequirement()
        val task = sampleTask()
        val spec = sampleSpec()
        val authentication = authenticated()
        coEvery { requirementService.getById(requirement.id) } returns requirement
        coEvery { requirementService.getByKey(requirement.key) } returns requirement
        coEvery { permissionEvaluator.isAllowed(authentication, requirement, PermissionAction.VIEW) } returns true
        coEvery { specService.getById(spec.id) } returns spec
        coEvery { taskService.getById(task.id) } returns task
        coEvery { requirementService.listByParent(RequirementParent.SPEC, spec.id, 1, 2) } returns listOf(requirement)
        coEvery { requirementService.listByParent(RequirementParent.TASK, task.id, 3, 4) } returns listOf(requirement)

        val controller = queryController()
        assertSame(requirement, controller.requirement(authentication, requirement.id))
        assertSame(requirement, controller.requirementByKey(authentication, requirement.key))
        assertEquals(listOf(requirement), controller.bySpec(authentication, spec.id, 1, 2))
        assertEquals(listOf(requirement), controller.byTask(authentication, task.id, 3, 4))
        coVerify { specPermissions.verifyAllowed(authentication, spec, PermissionAction.VIEW) }
        coVerify { taskPermissions.verifyAllowed(authentication, task, PermissionAction.VIEW) }

        coEvery { permissionEvaluator.isAllowed(authentication, requirement, PermissionAction.VIEW) } returns false
        assertNull(controller.requirement(authentication, requirement.id))
        assertNull(controller.requirementByKey(authentication, requirement.key))
    }

    @Test
    fun `requirement queries return empty for missing records and parents`() = runTest {
        val missingId = UUID.random()
        val authentication = authenticated()
        coEvery { requirementService.getById(missingId) } returns null
        coEvery { requirementService.getByKey("MISSING") } returns null
        coEvery { specService.getById(missingId) } returns null
        coEvery { taskService.getById(missingId) } returns null

        val controller = queryController()
        assertNull(controller.requirement(authentication, missingId))
        assertNull(controller.requirementByKey(authentication, "MISSING"))
        assertTrue(controller.bySpec(authentication, missingId, 0, 10).isEmpty())
        assertTrue(controller.byTask(authentication, missingId, 0, 10).isEmpty())
    }

    @Test
    fun `requirement mutations cover both parent types and actor attribution`() = runTest {
        val spec = sampleSpec()
        val task = sampleTask()
        val specInput = CreateRequirementInput(
            name = "Spec requirement",
            parentType = RequirementParent.SPEC,
            parentId = spec.id,
        )
        val taskInput = CreateRequirementInput(
            name = "Task requirement",
            parentType = RequirementParent.TASK,
            parentId = task.id,
        )
        val specRequirement = sampleRequirement(parentType = RequirementParent.SPEC, parentId = spec.id)
        val taskRequirement = sampleRequirement(parentType = RequirementParent.TASK, parentId = task.id)
        val update = UpdateRequirementInput(sortOrder = 4, expectedVersion = 1)
        val fallbackProfile = mockk<Profile>()
        val primaryAuthentication = authenticated()
        val fallbackAuthentication = authenticated(primaryProfileId = null)
        every { fallbackProfile.id } returns profileId
        coEvery { specService.getById(spec.id) } returns spec
        coEvery { taskService.getById(task.id) } returns task
        coEvery { profileService.getByPrincipal(principalId) } returns listOf(fallbackProfile)
        coEvery { requirementService.create(specInput, principalId, profileId) } returns specRequirement
        coEvery { requirementService.create(taskInput, principalId, profileId) } returns taskRequirement
        coEvery { requirementService.getById(specRequirement.id) } returns specRequirement
        coEvery { requirementService.update(specRequirement.id, update, principalId, profileId) } returns
            specRequirement.copy(sortOrder = 4)
        coEvery { requirementService.softDelete(specRequirement.id, 1, principalId, profileId) } returns specRequirement
        coEvery { requirementService.restore(specRequirement.id, 2, principalId, profileId) } returns specRequirement
        coEvery {
            requirementService.moveToParent(
                specRequirement.id,
                RequirementParent.TASK,
                task.id,
                3,
                principalId,
                profileId,
            )
        } returns taskRequirement
        coEvery {
            requirementService.moveToParent(
                specRequirement.id,
                RequirementParent.SPEC,
                spec.id,
                4,
                principalId,
                profileId,
            )
        } returns specRequirement

        val controller = mutationController()
        assertSame(specRequirement, controller.create(primaryAuthentication, specInput))
        assertSame(taskRequirement, controller.create(fallbackAuthentication, taskInput))
        assertEquals(4, controller.update(primaryAuthentication, specRequirement.id, update).sortOrder)
        assertSame(specRequirement, controller.softDelete(primaryAuthentication, specRequirement.id, 1))
        assertSame(specRequirement, controller.restore(primaryAuthentication, specRequirement.id, 2))
        assertSame(
            taskRequirement,
            controller.moveToParent(
                primaryAuthentication,
                specRequirement.id,
                RequirementParent.TASK,
                task.id,
                3,
            ),
        )
        assertSame(
            specRequirement,
            controller.moveToParent(
                primaryAuthentication,
                specRequirement.id,
                RequirementParent.SPEC,
                spec.id,
                4,
            ),
        )
    }

    @Test
    fun `requirement mutations reject missing resources and unauthenticated callers`() = runTest {
        val requirement = sampleRequirement()
        val spec = sampleSpec()
        val task = sampleTask()
        val missingId = UUID.random()
        val authentication = authenticated()
        val unauthenticated = AuthenticationContext(null, null)
        val update = UpdateRequirementInput(expectedVersion = 0)
        val missingSpecInput = CreateRequirementInput(parentType = RequirementParent.SPEC, parentId = missingId)
        val missingTaskInput = CreateRequirementInput(parentType = RequirementParent.TASK, parentId = missingId)
        val specInput = CreateRequirementInput(parentType = RequirementParent.SPEC, parentId = spec.id)
        val taskInput = CreateRequirementInput(parentType = RequirementParent.TASK, parentId = task.id)
        coEvery { requirementService.getById(missingId) } returns null
        coEvery { requirementService.getById(requirement.id) } returns requirement
        coEvery { specService.getById(missingId) } returns null
        coEvery { taskService.getById(missingId) } returns null
        coEvery { specService.getById(spec.id) } returns spec
        coEvery { taskService.getById(task.id) } returns task

        val controller = mutationController()
        assertFailsWith<IllegalStateException> { controller.create(authentication, missingSpecInput) }
        assertFailsWith<IllegalStateException> { controller.create(authentication, missingTaskInput) }
        assertFailsWith<IllegalStateException> { controller.update(authentication, missingId, update) }
        assertFailsWith<IllegalStateException> { controller.softDelete(authentication, missingId, 0) }
        assertFailsWith<IllegalStateException> { controller.restore(authentication, missingId, 0) }
        assertFailsWith<IllegalStateException> {
            controller.moveToParent(authentication, missingId, RequirementParent.SPEC, spec.id, 0)
        }
        assertFailsWith<IllegalStateException> {
            controller.moveToParent(authentication, requirement.id, RequirementParent.SPEC, missingId, 0)
        }
        assertFailsWith<IllegalStateException> {
            controller.moveToParent(authentication, requirement.id, RequirementParent.TASK, missingId, 0)
        }

        assertFailsWith<IllegalStateException> { controller.create(unauthenticated, specInput) }
        assertFailsWith<IllegalStateException> { controller.create(unauthenticated, taskInput) }
        assertFailsWith<IllegalStateException> { controller.update(unauthenticated, requirement.id, update) }
        assertFailsWith<IllegalStateException> { controller.softDelete(unauthenticated, requirement.id, 0) }
        assertFailsWith<IllegalStateException> { controller.restore(unauthenticated, requirement.id, 0) }
        assertFailsWith<IllegalStateException> {
            controller.moveToParent(unauthenticated, requirement.id, RequirementParent.SPEC, spec.id, 0)
        }
        assertFailsWith<IllegalStateException> {
            controller.moveToParent(unauthenticated, requirement.id, RequirementParent.TASK, task.id, 0)
        }
    }

    @Test
    fun `requirement mutation services receive null when the principal has no profile`() = runTest {
        val requirement = sampleRequirement()
        val authentication = authenticated(primaryProfileId = null)
        val update = UpdateRequirementInput(expectedVersion = 0)
        coEvery { requirementService.getById(requirement.id) } returns requirement
        coEvery { profileService.getByPrincipal(principalId) } returns emptyList()
        coEvery { requirementService.update(requirement.id, update, principalId, null) } returns requirement

        assertSame(requirement, mutationController().update(authentication, requirement.id, update))
    }

    private fun sampleRequirement(
        parentType: RequirementParent = RequirementParent.SPEC,
        parentId: UUID = UUID.random(),
        taskId: UUID? = null,
        assigneeProfileId: UUID? = null,
    ) = Requirement(
        id = UUID.random(),
        key = "GIT-REQ-1",
        metadataId = UUID.random(),
        parentType = parentType,
        parentId = parentId,
        statusId = UUID.random(),
        workflowId = UUID.random(),
        priorityId = UUID.random(),
        taskId = taskId,
        assigneeProfileId = assigneeProfileId,
        createdByPrincipalId = principalId,
        modifiedByPrincipalId = principalId,
    )

    private fun sampleSpec() = Spec(
        id = UUID.random(),
        key = "GIT-SPEC-6",
        metadataId = UUID.random(),
        statusId = UUID.random(),
        workflowId = UUID.random(),
        ownerProfileId = profileId,
        createdByPrincipalId = principalId,
        modifiedByPrincipalId = principalId,
    )

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
}
