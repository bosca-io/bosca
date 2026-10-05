package bosca.workops.controller

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.PermissionAction
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.serialization.UUID
import bosca.workops.model.project.Program
import bosca.workops.model.project.Project
import bosca.workops.model.spec.Spec
import bosca.workops.service.ProgramPermissionEvaluator
import bosca.workops.service.ProgramService
import bosca.workops.service.ProjectPermissionEvaluator
import bosca.workops.service.ProjectService
import bosca.workops.service.SpecPermissionEvaluator
import bosca.workops.service.SpecService
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
 * Permission-focused tests for SpecQueryController list endpoints.
 *
 * Verifies that byProject, byProgram, byOwner, and children all run
 * results through filterAllowed (i.e., respect spec-level permissions
 * with cascade) and that byProject and byProgram require VIEW on the
 * parent project/program.
 */
class SpecControllerTest {

    private val specService = mockk<SpecService>()
    private val projectService = mockk<ProjectService>()
    private val programService = mockk<ProgramService>()
    private val permissionEvaluator = mockk<SpecPermissionEvaluator>()
    private val projectPermissionEvaluator = mockk<ProjectPermissionEvaluator>()
    private val programPermissionEvaluator = mockk<ProgramPermissionEvaluator>()

    private val principalId = UUID.random()
    private val profileId = UUID.random()

    private fun authenticated(): AuthenticationContext {
        val principal = Principal(id = principalId, primaryProfileId = profileId)
        val groups = listOf(Group(id = UUID.random(), name = "users", description = "", type = GroupType.SYSTEM))
        return ImpersonatedAuthenticationContext(principal, groups)
    }

    private fun controller() = SpecQueryController(
        specService = specService,
        projectService = projectService,
        programService = programService,
        permissionEvaluator = permissionEvaluator,
        projectPermissionEvaluator = projectPermissionEvaluator,
        programPermissionEvaluator = programPermissionEvaluator,
    )

    @Test
    fun `spec lookup returns only visible specs`() = runTest {
        val missingId = UUID.random()
        val visible = sampleSpec()
        val hidden = sampleSpec()
        val authentication = authenticated()
        coEvery { specService.getById(missingId) } returns null
        coEvery { specService.getById(visible.id) } returns visible
        coEvery { specService.getById(hidden.id) } returns hidden
        coEvery { permissionEvaluator.isAllowed(authentication, visible, PermissionAction.VIEW) } returns true
        coEvery { permissionEvaluator.isAllowed(authentication, hidden, PermissionAction.VIEW) } returns false

        assertNull(controller().spec(authentication, missingId))
        assertEquals(visible, controller().spec(authentication, visible.id))
        assertNull(controller().spec(authentication, hidden.id))
    }

    @Test
    fun `specByKey returns only visible specs`() = runTest {
        val visible = sampleSpec()
        val hidden = sampleSpec()
        val authentication = authenticated()
        coEvery { specService.getByKey("missing") } returns null
        coEvery { specService.getByKey(visible.key) } returns visible
        coEvery { specService.getByKey(hidden.key) } returns hidden
        coEvery { permissionEvaluator.isAllowed(authentication, visible, PermissionAction.VIEW) } returns true
        coEvery { permissionEvaluator.isAllowed(authentication, hidden, PermissionAction.VIEW) } returns false

        assertNull(controller().specByKey(authentication, "missing"))
        assertEquals(visible, controller().specByKey(authentication, visible.key))
        assertNull(controller().specByKey(authentication, hidden.key))
    }

    // --- byProject ---

    @Test
    fun `byProject returns empty when project not found`() = runTest {
        val projectId = UUID.random()
        coEvery { projectService.getById(projectId) } returns null

        val result = controller().byProject(authenticated(), projectId, 0, 50)

        assertTrue(result.isEmpty())
        coVerify(exactly = 0) { specService.listByProject(any(), any(), any()) }
    }

    @Test
    fun `byProject throws when caller lacks VIEW on project`() = runTest {
        val projectId = UUID.random()
        val project = sampleProject(projectId)
        coEvery { projectService.getById(projectId) } returns project
        coEvery {
            projectPermissionEvaluator.verifyAllowed(any(), project, PermissionAction.VIEW)
        } throws SecurityException("denied")

        assertFailsWith<SecurityException> {
            controller().byProject(authenticated(), projectId, 0, 50)
        }
    }

    @Test
    fun `byProject filters results through specPermissionEvaluator`() = runTest {
        val projectId = UUID.random()
        val project = sampleProject(projectId)
        val allSpecs = listOf(sampleSpec(projectId), sampleSpec(projectId), sampleSpec(projectId))
        val visibleSpecs = listOf(allSpecs[0], allSpecs[2])
        coEvery { projectService.getById(projectId) } returns project
        coEvery { projectPermissionEvaluator.verifyAllowed(any(), project, PermissionAction.VIEW) } returns Unit
        coEvery { specService.listByProject(projectId, 0, 50) } returns allSpecs
        coEvery {
            permissionEvaluator.filterAllowed(any(), allSpecs, PermissionAction.VIEW)
        } returns visibleSpecs

        assertEquals(visibleSpecs, controller().byProject(authenticated(), projectId, 0, 50))
    }

    // --- byProgram ---

    @Test
    fun `byProgram returns empty when program not found`() = runTest {
        val programId = UUID.random()
        coEvery { programService.getById(programId) } returns null

        assertTrue(controller().byProgram(authenticated(), programId, 0, 50).isEmpty())
        coVerify(exactly = 0) { specService.listByProgram(any(), any(), any()) }
    }

    @Test
    fun `byProgram throws when caller lacks VIEW on program`() = runTest {
        val programId = UUID.random()
        val program = sampleProgram(programId)
        coEvery { programService.getById(programId) } returns program
        coEvery {
            programPermissionEvaluator.verifyAllowed(any(), program, PermissionAction.VIEW)
        } throws SecurityException("denied")

        assertFailsWith<SecurityException> {
            controller().byProgram(authenticated(), programId, 0, 50)
        }
    }

    @Test
    fun `byProgram filters results through specPermissionEvaluator`() = runTest {
        val programId = UUID.random()
        val program = sampleProgram(programId)
        val allSpecs = listOf(sampleSpec(), sampleSpec())
        val visibleSpecs = listOf(allSpecs[0])
        coEvery { programService.getById(programId) } returns program
        coEvery { programPermissionEvaluator.verifyAllowed(any(), program, PermissionAction.VIEW) } returns Unit
        coEvery { specService.listByProgram(programId, 0, 50) } returns allSpecs
        coEvery {
            permissionEvaluator.filterAllowed(any(), allSpecs, PermissionAction.VIEW)
        } returns visibleSpecs

        assertEquals(visibleSpecs, controller().byProgram(authenticated(), programId, 0, 50))
    }

    // --- byOwner ---

    @Test
    fun `byOwner filters results through specPermissionEvaluator`() = runTest {
        val allSpecs = listOf(sampleSpec(), sampleSpec())
        val visibleSpecs = listOf(allSpecs[1])
        coEvery { specService.listByOwner(profileId, 0, 50) } returns allSpecs
        coEvery {
            permissionEvaluator.filterAllowed(any(), allSpecs, PermissionAction.VIEW)
        } returns visibleSpecs

        assertEquals(visibleSpecs, controller().byOwner(authenticated(), profileId, 0, 50))
    }

    // --- children ---

    @Test
    fun `children returns empty when parent spec not found`() = runTest {
        val parentId = UUID.random()
        coEvery { specService.getById(parentId) } returns null

        assertTrue(controller().children(authenticated(), parentId, 0, 50).isEmpty())
        coVerify(exactly = 0) { specService.listChildren(any(), any(), any()) }
    }

    @Test
    fun `children throws when caller lacks VIEW on parent spec`() = runTest {
        val parentId = UUID.random()
        val parent = sampleSpec(specId = parentId)
        coEvery { specService.getById(parentId) } returns parent
        coEvery {
            permissionEvaluator.verifyAllowed(any(), parent, PermissionAction.VIEW)
        } throws SecurityException("denied")

        assertFailsWith<SecurityException> {
            controller().children(authenticated(), parentId, 0, 50)
        }
    }

    @Test
    fun `children filters results through specPermissionEvaluator`() = runTest {
        val parentId = UUID.random()
        val parent = sampleSpec(specId = parentId)
        val allSpecs = listOf(sampleSpec(), sampleSpec(), sampleSpec())
        val visibleSpecs = listOf(allSpecs[1])
        coEvery { specService.getById(parentId) } returns parent
        coEvery { permissionEvaluator.verifyAllowed(any(), parent, PermissionAction.VIEW) } returns Unit
        coEvery { specService.listChildren(parentId, 0, 50) } returns allSpecs
        coEvery {
            permissionEvaluator.filterAllowed(any(), allSpecs, PermissionAction.VIEW)
        } returns visibleSpecs

        assertEquals(visibleSpecs, controller().children(authenticated(), parentId, 0, 50))
    }

    // --- metadata field ---

    private val metadataService = mockk<MetadataService>()
    private val metadataPermissions = mockk<MetadataPermissionEvaluator>()

    private fun fieldController() = SpecTypeFieldController(
        projectService = mockk(),
        statusService = mockk(),
        specService = specService,
        requirementService = mockk(),
        contextService = mockk(),
        commentService = mockk(),
        generationRepository = mockk(),
        profileService = mockk(),
        profilePermissions = mockk(),
        workflowService = mockk(),
        metadataService = metadataService,
        metadataPermissions = metadataPermissions,
    )

    private fun sampleMetadata(id: UUID, name: String) = Metadata(
        id = id, name = name, type = MetadataType.STANDARD,
        contentType = "bosca/v-document", contentLength = null,
        languageTag = "en", workflowStateId = "draft",
    )

    @Test
    fun `metadata returns backing document when caller has VIEW`() = runTest {
        val spec = sampleSpec()
        val metadata = sampleMetadata(spec.metadataId, "My Spec")
        coEvery { metadataService.getById(spec.metadataId) } returns metadata
        coEvery { metadataPermissions.isAllowed(any<AuthenticationContext>(), metadata, PermissionAction.VIEW) } returns true

        assertEquals(metadata, fieldController().metadata(authenticated(), spec))
    }

    @Test
    fun `metadata returns null when caller lacks VIEW on the document`() = runTest {
        val spec = sampleSpec()
        val metadata = sampleMetadata(spec.metadataId, "My Spec")
        coEvery { metadataService.getById(spec.metadataId) } returns metadata
        coEvery { metadataPermissions.isAllowed(any<AuthenticationContext>(), metadata, PermissionAction.VIEW) } returns false

        assertNull(fieldController().metadata(authenticated(), spec))
    }

    @Test
    fun `metadata returns null when the backing document is missing`() = runTest {
        val spec = sampleSpec()
        coEvery { metadataService.getById(spec.metadataId) } returns null

        assertNull(fieldController().metadata(authenticated(), spec))
        coVerify(exactly = 0) { metadataPermissions.isAllowed(any<AuthenticationContext>(), any<Metadata>(), any()) }
    }

    // --- helpers ---

    private fun sampleProject(id: UUID = UUID.random()) = Project(
        id = id, programId = UUID.random(),
        key = "P", name = "Project", ownerProfileId = profileId,
    )

    private fun sampleProgram(id: UUID = UUID.random()) = Program(
        id = id, portfolioId = UUID.random(),
        key = "PROG", name = "Program", ownerProfileId = profileId,
    )

    private fun sampleSpec(projectId: UUID? = UUID.random(), specId: UUID = UUID.random()) = Spec(
        id = specId, key = "P-SPEC-${specId.toString().take(4)}",
        metadataId = UUID.random(), projectId = projectId,
        statusId = UUID.random(), workflowId = UUID.random(), ownerProfileId = profileId,
        createdByPrincipalId = principalId, modifiedByPrincipalId = principalId,
    )
}
