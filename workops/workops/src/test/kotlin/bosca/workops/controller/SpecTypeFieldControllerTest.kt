package bosca.workops.controller

import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
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
import bosca.workops.model.project.Project
import bosca.workops.model.spec.Spec
import bosca.workops.model.workflow.Status
import bosca.workops.model.workflow.WorkflowState
import bosca.workops.model.workflow.WorkflowTransition
import bosca.workops.repository.SpecTaskGenerationRepository
import bosca.workops.service.ProjectService
import bosca.workops.service.RequirementService
import bosca.workops.service.SpecCommentService
import bosca.workops.service.SpecContextService
import bosca.workops.service.SpecService
import bosca.workops.service.StatusService
import bosca.workops.service.WorkflowResolution
import bosca.workops.service.WorkflowService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class SpecTypeFieldControllerTest {

    private val projectService = mockk<ProjectService>()
    private val statusService = mockk<StatusService>()
    private val specService = mockk<SpecService>()
    private val requirementService = mockk<RequirementService>()
    private val contextService = mockk<SpecContextService>()
    private val commentService = mockk<SpecCommentService>()
    private val generationRepository = mockk<SpecTaskGenerationRepository>()
    private val profileService = mockk<ProfileService>()
    private val profilePermissions = mockk<ProfilePermissionEvaluator>()
    private val workflowService = mockk<WorkflowService>()
    private val metadataService = mockk<MetadataService>()
    private val metadataPermissions = mockk<MetadataPermissionEvaluator>()

    private val principalId = UUID.random()
    private val profileId = UUID.random()

    private fun controller() = SpecTypeFieldController(
        projectService = projectService,
        statusService = statusService,
        specService = specService,
        requirementService = requirementService,
        contextService = contextService,
        commentService = commentService,
        generationRepository = generationRepository,
        profileService = profileService,
        profilePermissions = profilePermissions,
        workflowService = workflowService,
        metadataService = metadataService,
        metadataPermissions = metadataPermissions,
    )

    private fun authenticated(
        primaryProfileId: UUID? = profileId,
        id: UUID = principalId,
    ): AuthenticationContext = ImpersonatedAuthenticationContext(
        Principal(id = id, primaryProfileId = primaryProfileId),
        listOf(Group(UUID.random(), "users", "", GroupType.SYSTEM)),
    )

    @Test
    fun `spec fields relations and collections are resolved`() = runTest {
        val parent = sampleSpec()
        val project = sampleProject()
        val spec = sampleSpec(projectId = project.id).copy(parentSpecId = parent.id)
        val status = mockk<Status>()
        val owner = mockk<Profile>()
        val authentication = authenticated()
        val currentState = WorkflowState(
            workflowId = spec.workflowId,
            statusId = spec.statusId,
            displayOrder = 0,
        )
        val currentTransition = WorkflowTransition(
            workflowId = spec.workflowId,
            name = "Start",
            fromStateIds = listOf(currentState.id.toString()),
            toStateId = UUID.random(),
        )
        val otherTransition = currentTransition.copy(
            id = UUID.random(),
            name = "Other",
            fromStateIds = listOf(UUID.random().toString()),
        )
        val transitions = listOf(currentTransition, otherTransition)

        coEvery { specService.getById(parent.id) } returns parent
        coEvery { projectService.getById(project.id) } returns project
        coEvery { statusService.getById(spec.statusId) } returns status
        coEvery { profileService.getById(spec.ownerProfileId) } returns owner
        coEvery { profilePermissions.isAllowed(authentication, owner, PermissionAction.VIEW) } returns true
        coEvery { workflowService.resolveWorkflowForSpec(spec) } returns
                WorkflowResolution(mockk(), currentState, transitions)
        coEvery { specService.listChildren(spec.id, 1, 2) } returns emptyList()
        coEvery { specService.listHistory(spec.id, 3, 4) } returns emptyList()
        coEvery { requirementService.listByParent(any(), spec.id, 5, 6) } returns emptyList()
        coEvery { requirementService.countByParent(any(), spec.id) } returns 7
        coEvery { contextService.listBySpec(spec.id) } returns emptyList()
        coEvery { generationRepository.listBySpec(spec.id, 8, 9) } returns emptyList()

        val controller = controller()
        assertEquals(spec.id, controller.id(spec))
        assertEquals(spec.key, controller.key(spec))
        assertEquals(spec.metadataId, controller.metadataId(spec))
        assertEquals(spec.programId, controller.programId(spec))
        assertEquals(spec.projectId, controller.projectId(spec))
        assertEquals(spec.ownerProfileId, controller.ownerProfileId(spec))
        assertEquals(spec.parentSpecId, controller.parentSpecId(spec))
        assertEquals(spec.sortOrder, controller.sortOrder(spec))
        assertEquals(spec.childCount, controller.childCount(spec))
        assertEquals(spec.childDoneCount, controller.childDoneCount(spec))
        assertEquals(spec.gitRepositoryId, controller.gitRepositoryId(spec))
        assertEquals(spec.gitPath, controller.gitPath(spec))
        assertEquals(spec.watcherProfileIds, controller.watcherProfileIds(spec))
        assertEquals(spec.labelIds, controller.labelIds(spec))
        assertEquals(spec.deletedAt, controller.deletedAt(spec))
        assertEquals(spec.createdAt, controller.createdAt(spec))
        assertEquals(spec.modifiedAt, controller.modifiedAt(spec))
        assertEquals(spec.createdByPrincipalId, controller.createdByPrincipalId(spec))
        assertEquals(spec.modifiedByPrincipalId, controller.modifiedByPrincipalId(spec))
        assertEquals(spec.version, controller.version(spec))
        assertSame(parent, controller.parentSpec(spec))
        assertNull(controller.parentSpec(spec.copy(parentSpecId = null)))
        assertSame(project, controller.project(spec))
        assertNull(controller.project(spec.copy(projectId = null)))
        assertSame(status, controller.status(spec))
        assertSame(owner, controller.owner(authentication, spec))
        assertEquals(listOf(currentTransition), controller.transitions(spec, currentOnly = true))
        assertEquals(transitions, controller.transitions(spec, currentOnly = false))
        assertEquals(emptyList(), controller.children(spec, 1, 2))
        assertEquals(emptyList(), controller.history(spec, 3, 4))
        assertEquals(emptyList(), controller.requirements(spec, 5, 6))
        assertEquals(7, controller.requirementCount(spec))
        assertEquals(emptyList(), controller.contexts(spec))
        assertEquals(emptyList(), controller.taskGenerations(spec, 8, 9))

        coEvery { profilePermissions.isAllowed(authentication, owner, PermissionAction.VIEW) } returns false
        assertNull(controller.owner(authentication, spec))
        coEvery { statusService.getById(spec.statusId) } returns null
        assertFailsWith<IllegalStateException> { controller.status(spec) }
    }

    @Test
    fun `comments select primary fallback and public visibility`() = runTest {
        val spec = sampleSpec()
        val primaryAuthentication = authenticated()
        val fallbackAuthentication = authenticated(primaryProfileId = null)
        val noProfilePrincipalId = UUID.random()
        val noProfileAuthentication = authenticated(primaryProfileId = null, id = noProfilePrincipalId)
        val anonymousAuthentication = AuthenticationContext(null, null)
        val fallbackProfile = mockk<Profile>()
        every { fallbackProfile.id } returns profileId
        coEvery { profileService.getByPrincipal(principalId) } returns listOf(fallbackProfile)
        coEvery { profileService.getByPrincipal(noProfilePrincipalId) } returns emptyList()
        coEvery { commentService.listForProfile(spec.id, profileId, 0, 1) } returns emptyList()
        coEvery { commentService.listForProfile(spec.id, profileId, 2, 3) } returns emptyList()
        coEvery { commentService.listPublic(spec.id, 4, 5) } returns emptyList()
        coEvery { commentService.listPublic(spec.id, 6, 7) } returns emptyList()
        coEvery { commentService.listPublic(spec.id, 8, 9) } returns emptyList()

        val controller = controller()
        assertEquals(emptyList(), controller.comments(spec, primaryAuthentication, 0, 1))
        assertEquals(emptyList(), controller.comments(spec, fallbackAuthentication, 2, 3))
        assertEquals(emptyList(), controller.comments(spec, noProfileAuthentication, 4, 5))
        assertEquals(emptyList(), controller.comments(spec, anonymousAuthentication, 6, 7))
        assertEquals(emptyList(), controller.comments(spec, null, 8, 9))
    }

    private fun sampleProject() = Project(
        id = UUID.random(),
        programId = UUID.random(),
        key = "PROJ",
        name = "Project",
        ownerProfileId = profileId,
    )

    private fun sampleSpec(projectId: UUID? = UUID.random()) = Spec(
        id = UUID.random(),
        key = "PROJ-SPEC-${UUID.random().toString().take(4)}",
        metadataId = UUID.random(),
        projectId = projectId,
        statusId = UUID.random(),
        workflowId = UUID.random(),
        ownerProfileId = profileId,
        createdByPrincipalId = principalId,
        modifiedByPrincipalId = principalId,
    )
}
