package bosca.workops.controller

import bosca.profile.model.Profile
import bosca.profile.profile.service.ProfileService
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.PermissionAction
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.serialization.UUID
import bosca.workops.model.project.Project
import bosca.workops.model.spec.CreateSpecContextInput
import bosca.workops.model.spec.CreateSpecInput
import bosca.workops.model.spec.GenerationSource
import bosca.workops.model.spec.Spec
import bosca.workops.model.spec.SpecContext
import bosca.workops.model.spec.SpecContextType
import bosca.workops.model.spec.SpecTaskGeneration
import bosca.workops.model.spec.UpdateSpecInput
import bosca.workops.service.ProjectPermissionEvaluator
import bosca.workops.service.ProjectService
import bosca.workops.service.SpecContextService
import bosca.workops.service.SpecGitSyncService
import bosca.workops.service.SpecPermissionEvaluator
import bosca.workops.service.SpecService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SpecMutationControllerTest {

    private val specService = mockk<SpecService>(relaxed = true)
    private val contextService = mockk<SpecContextService>(relaxed = true)
    private val gitSyncService = mockk<SpecGitSyncService>(relaxed = true)
    private val projectService = mockk<ProjectService>(relaxed = true)
    private val profileService = mockk<ProfileService>(relaxed = true)
    private val permissionEvaluator = mockk<SpecPermissionEvaluator>(relaxed = true)
    private val projectPermissionEvaluator = mockk<ProjectPermissionEvaluator>(relaxed = true)

    private val principalId = UUID.random()
    private val profileId = UUID.random()

    private fun authenticated(primaryProfileId: UUID? = profileId): AuthenticationContext =
        ImpersonatedAuthenticationContext(
            Principal(id = principalId, primaryProfileId = primaryProfileId),
            listOf(Group(UUID.random(), "users", "", GroupType.SYSTEM)),
        )

    private fun controller() = SpecMutationController(
        specService = specService,
        contextService = contextService,
        gitSyncService = gitSyncService,
        projectService = projectService,
        profileService = profileService,
        permissionEvaluator = permissionEvaluator,
        projectPermissionEvaluator = projectPermissionEvaluator,
    )

    @Test
    fun `create verifies its optional project and attributes owner to the primary profile`() = runTest {
        val project = sampleProject()
        val input = CreateSpecInput(
            name = "Release automation",
            programId = project.programId,
            projectId = project.id,
        )
        val spec = sampleSpec()
        coEvery { projectService.getById(project.id) } returns project
        coEvery { specService.create(input, principalId, profileId, profileId) } returns spec

        assertEquals(spec, controller().create(authenticated(), input))

        coVerify(exactly = 1) { projectPermissionEvaluator.verifyAllowed(any(), project, PermissionAction.EDIT) }
        coVerify(exactly = 1) { specService.create(input, principalId, profileId, profileId) }
    }

    @Test
    fun `create without a project resolves the owner from principal profiles`() = runTest {
        val input = CreateSpecInput(name = "Platform specification")
        val spec = sampleSpec()
        val profile = mockk<Profile>()
        every { profile.id } returns profileId
        coEvery { profileService.getByPrincipal(principalId) } returns listOf(profile)
        coEvery { specService.create(input, principalId, profileId, profileId) } returns spec

        assertEquals(spec, controller().create(authenticated(primaryProfileId = null), input))

        coVerify(exactly = 0) { projectService.getById(any()) }
    }

    @Test
    fun `spec lifecycle context generation transition and git sync carry actor identity`() = runTest {
        val spec = sampleSpec()
        val specId = spec.id
        val input = UpdateSpecInput(sortOrder = 4, expectedVersion = 1)
        val contextInput = CreateSpecContextInput(
            contextType = SpecContextType.GIT_RESOURCE,
            targetId = "repository:path",
            label = "Source",
        )
        val context = mockk<SpecContext>()
        val generation = mockk<SpecTaskGeneration>()
        val contextId = UUID.random()
        val transitionId = UUID.random()
        val resolutionId = UUID.random()
        val sessionId = UUID.random()
        coEvery { specService.getById(specId) } returns spec
        coEvery { specService.update(specId, input, principalId, profileId) } returns spec
        coEvery { specService.softDelete(specId, 2, principalId, profileId) } returns spec
        coEvery { specService.restore(specId, 3, principalId, profileId) } returns spec
        coEvery { contextService.add(specId, contextInput, principalId, profileId) } returns context
        coEvery { specService.generateTasks(specId, 9, GenerationSource.CLAUDE_CODE, principalId, profileId, sessionId) } returns generation
        coEvery { specService.transition(specId, transitionId, 4, principalId, profileId, resolutionId) } returns spec
        coEvery { gitSyncService.pushToGit(spec, "# Spec", principalId, "Bosca", "bosca@example.com") } returns "abc123"
        coEvery { gitSyncService.pullFromGit(specId, "def456", principalId, profileId) } returns spec

        assertEquals(spec, controller().update(authenticated(), specId, input))
        assertEquals(spec, controller().softDelete(authenticated(), specId, 2))
        assertEquals(spec, controller().restore(authenticated(), specId, 3))
        assertEquals(context, controller().addContext(authenticated(), specId, contextInput))
        assertTrue(controller().removeContext(authenticated(), specId, contextId))
        assertEquals(
            generation,
            controller().generateTasks(authenticated(), specId, 9, GenerationSource.CLAUDE_CODE, sessionId),
        )
        assertEquals(
            spec,
            controller().transition(authenticated(), specId, transitionId, 4, resolutionId),
        )
        assertEquals(
            "abc123",
            controller().pushToGit(authenticated(), specId, "# Spec", "Bosca", "bosca@example.com"),
        )
        assertEquals(spec, controller().pullFromGit(authenticated(), specId, "def456"))

        coVerify(exactly = 1) { contextService.remove(specId, contextId, principalId, profileId) }
        coVerify(exactly = 2) { permissionEvaluator.verifyAllowed(any(), spec, PermissionAction.DELETE) }
        coVerify(exactly = 7) { permissionEvaluator.verifyAllowed(any(), spec, PermissionAction.EDIT) }
    }

    @Test
    fun `spec mutations fail closed before side effects when required context is absent`() = runTest {
        val missingId = UUID.random()
        coEvery { projectService.getById(missingId) } returns null
        coEvery { specService.getById(missingId) } returns null
        assertFailsWith<IllegalStateException> {
            controller().create(authenticated(), CreateSpecInput(name = "Missing", projectId = missingId))
        }
        assertFailsWith<IllegalStateException> {
            controller().update(authenticated(), missingId, UpdateSpecInput(expectedVersion = 0))
        }
        assertFailsWith<IllegalStateException> { controller().softDelete(authenticated(), missingId, 0) }
        assertFailsWith<IllegalStateException> { controller().restore(authenticated(), missingId, 0) }
        assertFailsWith<IllegalStateException> {
            controller().addContext(
                authenticated(), missingId, CreateSpecContextInput(SpecContextType.SPEC, missingId.toString()),
            )
        }
        assertFailsWith<IllegalStateException> {
            controller().removeContext(authenticated(), missingId, UUID.random())
        }
        assertFailsWith<IllegalStateException> {
            controller().generateTasks(authenticated(), missingId, 1, GenerationSource.MANUAL)
        }
        assertFailsWith<IllegalStateException> {
            controller().transition(authenticated(), missingId, UUID.random(), 0)
        }
        assertFailsWith<IllegalStateException> {
            controller().pushToGit(authenticated(), missingId, "# Spec", "Bosca", "bosca@example.com")
        }
        assertFailsWith<IllegalStateException> { controller().pullFromGit(authenticated(), missingId, "abc123") }

        val spec = sampleSpec()
        coEvery { specService.getById(spec.id) } returns spec
        assertFailsWith<IllegalStateException> {
            controller().update(
                AuthenticationContext(null, null), spec.id, UpdateSpecInput(expectedVersion = 0),
            )
        }
        val unauthenticated = AuthenticationContext(null, null)
        assertFailsWith<IllegalStateException> {
            controller().create(unauthenticated, CreateSpecInput(name = "Unauthenticated"))
        }
        assertFailsWith<IllegalStateException> { controller().softDelete(unauthenticated, spec.id, 0) }
        assertFailsWith<IllegalStateException> { controller().restore(unauthenticated, spec.id, 0) }
        assertFailsWith<IllegalStateException> {
            controller().addContext(
                unauthenticated,
                spec.id,
                CreateSpecContextInput(SpecContextType.SPEC, spec.id.toString()),
            )
        }
        assertFailsWith<IllegalStateException> {
            controller().removeContext(unauthenticated, spec.id, UUID.random())
        }
        assertFailsWith<IllegalStateException> {
            controller().generateTasks(unauthenticated, spec.id, 1, GenerationSource.MANUAL)
        }
        assertFailsWith<IllegalStateException> {
            controller().transition(unauthenticated, spec.id, UUID.random(), 0)
        }
        assertFailsWith<IllegalStateException> {
            controller().pushToGit(unauthenticated, spec.id, "# Spec", "Bosca", "bosca@example.com")
        }
        assertFailsWith<IllegalStateException> { controller().pullFromGit(unauthenticated, spec.id, "abc123") }

        val project = sampleProject()
        coEvery { projectService.getById(project.id) } returns project
        coEvery { profileService.getByPrincipal(principalId) } returns emptyList()
        assertFailsWith<IllegalStateException> {
            controller().create(
                authenticated(primaryProfileId = null),
                CreateSpecInput(name = "No profile", projectId = project.id),
            )
        }
    }

    private fun sampleProject(): Project = Project(
        id = UUID.random(),
        programId = UUID.random(),
        key = "PROJECT",
        name = "Project",
        ownerProfileId = profileId,
    )

    private fun sampleSpec(): Spec = mockk {
        val specId = UUID.random()
        every { this@mockk.id } returns specId
    }
}
