package bosca.workops.controller

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.profile.model.Profile
import bosca.profile.profile.service.ProfileService
import bosca.security.model.Principal
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.serialization.UUID
import bosca.workops.model.spec.*
import bosca.workops.model.requirement.Requirement
import bosca.workops.model.task.Task
import bosca.workops.service.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SpecPlanControllerTest {
    private val planService = mockk<SpecPlanService>(relaxed = true)
    private val specService = mockk<SpecService>(relaxed = true)
    private val requirementService = mockk<RequirementService>(relaxed = true)
    private val taskService = mockk<TaskService>(relaxed = true)
    private val projectService = mockk<ProjectService>(relaxed = true)
    private val metadataService = mockk<MetadataService>(relaxed = true)
    private val projectPermissions = mockk<ProjectPermissionEvaluator>(relaxed = true)
    private val specPermissions = mockk<SpecPermissionEvaluator>(relaxed = true)
    private val requirementPermissions = mockk<RequirementPermissionEvaluator>(relaxed = true)
    private val taskPermissions = mockk<TaskPermissionEvaluator>(relaxed = true)
    private val metadataPermissions = mockk<MetadataPermissionEvaluator>(relaxed = true)
    private val profiles = mockk<ProfileService>(relaxed = true)
    private val principalId = UUID.random()
    private val profileId = UUID.random()
    private val id = UUID.random()
    private val content = RequirementContentInput("Deliverable", "Body", listOf("Pass"))
    private fun auth(profile: UUID? = profileId) = ImpersonatedAuthenticationContext(Principal(principalId, primaryProfileId = profile), emptyList())
    private fun plans() = SpecPlanMutationController(planService, specService, projectService, projectPermissions, specPermissions, requirementPermissions, profiles)
    private fun requirements() = RequirementContentMutationController(requirementService, taskService, metadataService, requirementPermissions, taskPermissions, metadataPermissions, profiles)

    @Test
    fun `plan creation checks project access and replayed spec access`() = runTest {
        val input = CreateSpecPlanInput(UUID.random(), id, "Plan", "Scope", listOf(content))
        val spec = mockk<Spec>(relaxed = true)
        coEvery { planService.create(input, principalId, profileId) } returns spec
        assertSame(spec, plans().createPlan(auth(), input))
        coVerify { projectPermissions.verifyAllowed(any(), any(), PermissionAction.EDIT) }
        coVerify { specPermissions.verifyAllowed(any(), spec, PermissionAction.EDIT) }
        coEvery { projectPermissions.verifyAllowed(any(), any(), any()) } throws IllegalStateException("Denied")
        assertFailsWith<IllegalStateException> { plans().createPlan(auth(), input) }
        coVerify(exactly = 1) { planService.create(any(), any(), any()) }
    }

    @Test
    fun `plan creation requires authentication project and profile and supports profile fallback`() = runTest {
        val input = CreateSpecPlanInput(UUID.random(), id, "Plan", "Scope", listOf(content))
        val anonymous = mockk<AuthenticationContext> { coEvery { principal() } returns null }
        assertFailsWith<IllegalStateException> { plans().createPlan(anonymous, input) }
        coEvery { profiles.getByPrincipal(principalId) } returns emptyList()
        assertFailsWith<IllegalStateException> { plans().createPlan(auth(null), input) }
        coEvery { profiles.getByPrincipal(principalId) } returns listOf(mockk<Profile> { every { id } returns profileId })
        plans().createPlan(auth(null), input)
        coVerify { planService.create(input, principalId, profileId) }
        coEvery { projectService.getById(id) } returns null
        assertFailsWith<IllegalStateException> { plans().createPlan(auth(), input) }
    }

    @Test
    fun `requirement addition authorizes current spec and replayed requirement`() = runTest {
        val requestId = UUID.random()
        val anonymous = mockk<AuthenticationContext> { coEvery { principal() } returns null }
        assertFailsWith<IllegalStateException> { plans().addPlanRequirement(anonymous, id, requestId, content) }
        plans().addPlanRequirement(auth(), id, requestId, content)
        coVerify { specPermissions.verifyAllowed(any(), any(), PermissionAction.EDIT) }
        coVerify { requirementPermissions.verifyAllowed(any(), any(), PermissionAction.EDIT) }
        coVerify { planService.addRequirement(id, requestId, content, principalId, profileId) }
        coEvery { specPermissions.verifyAllowed(any(), any(), any()) } throws IllegalStateException("Denied")
        assertFailsWith<IllegalStateException> { plans().addPlanRequirement(auth(), id, requestId, content) }
        coVerify(exactly = 1) { planService.addRequirement(any(), any(), any(), any(), any()) }
        coEvery { specService.getById(id) } returns null
        assertFailsWith<IllegalStateException> { plans().addPlanRequirement(auth(), id, requestId, content) }
    }

    @Test
    fun `content writes check requirement task and document permissions before mutation`() = runTest {
        val input = SetRequirementContentInput(content, 2, 3, 1)
        val requirement = mockk<Requirement> { every { taskId } returns this@SpecPlanControllerTest.id; every { metadataId } returns this@SpecPlanControllerTest.id }
        val task = mockk<Task>()
        val metadata = mockk<Metadata>()
        coEvery { requirementService.getById(id) } returns requirement
        coEvery { taskService.getById(id) } returns task
        coEvery { metadataService.getById(id) } returns metadata
        requirements().setContent(auth(), id, input)
        coVerify { requirementPermissions.verifyAllowed(any(), requirement, PermissionAction.EDIT) }
        coVerify { taskPermissions.verifyAllowed(any(), task, PermissionAction.EDIT) }
        coVerify { metadataPermissions.verifyAllowed(any(), metadata, PermissionAction.EDIT) }
        coEvery { metadataPermissions.verifyAllowed(any(), any(), any()) } throws IllegalStateException("Denied")
        assertFailsWith<IllegalStateException> { requirements().setContent(auth(), id, input) }
        coEvery { taskPermissions.verifyAllowed(any(), any(), any()) } throws IllegalStateException("Denied")
        assertFailsWith<IllegalStateException> { requirements().setContent(auth(), id, input) }
        coEvery { requirementPermissions.verifyAllowed(any(), any(), any()) } throws IllegalStateException("Denied")
        assertFailsWith<IllegalStateException> { requirements().setContent(auth(), id, input) }
        coVerify(exactly = 1) { requirementService.setContent(id, input, principalId, profileId) }
    }

    @Test
    fun `content writes reject missing entities and anonymous identities and allow absent profile`() = runTest {
        val input = SetRequirementContentInput(content, 2, 3, 1)
        val requirement = mockk<Requirement> { every { taskId } returns this@SpecPlanControllerTest.id; every { metadataId } returns this@SpecPlanControllerTest.id }
        coEvery { requirementService.getById(id) } returns null
        assertFailsWith<IllegalStateException> { requirements().setContent(auth(), id, input) }
        coEvery { requirementService.getById(id) } returns requirement
        every { requirement.taskId } returns null
        assertFailsWith<IllegalStateException> { requirements().setContent(auth(), id, input) }
        every { requirement.taskId } returns id
        coEvery { taskService.getById(id) } returns null
        assertFailsWith<IllegalStateException> { requirements().setContent(auth(), id, input) }
        coEvery { taskService.getById(id) } returns mockk(relaxed = true)
        coEvery { metadataService.getById(id) } returns null
        assertFailsWith<IllegalStateException> { requirements().setContent(auth(), id, input) }
        coEvery { metadataService.getById(id) } returns mockk(relaxed = true)
        val anonymous = mockk<AuthenticationContext> { coEvery { principal() } returns null }
        assertFailsWith<IllegalStateException> { requirements().setContent(anonymous, id, input) }
        coEvery { profiles.getByPrincipal(principalId) } returns emptyList()
        requirements().setContent(auth(null), id, input)
        coVerify { requirementService.setContent(id, input, principalId, null) }
        coEvery { profiles.getByPrincipal(principalId) } returns listOf(mockk<Profile> { every { id } returns profileId })
        requirements().setContent(auth(null), id, input)
        coVerify { requirementService.setContent(id, input, principalId, profileId) }
    }
}
