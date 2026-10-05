package bosca.workops.controller

import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.workops.model.component.Component
import bosca.workops.model.component.ComponentAssigneeMode
import bosca.workops.model.component.CreateComponentInput
import bosca.workops.model.milestone.CreateMilestoneInput
import bosca.workops.model.milestone.Milestone
import bosca.workops.model.milestone.MilestoneState
import bosca.workops.model.project.Program
import bosca.workops.model.project.Project
import bosca.workops.model.version.CreateVersionInput
import bosca.workops.model.version.Version
import bosca.workops.service.ComponentService
import bosca.workops.service.MilestoneService
import bosca.workops.service.ProgramPermissionEvaluator
import bosca.workops.service.ProgramService
import bosca.workops.service.ProjectPermissionEvaluator
import bosca.workops.service.ProjectService
import bosca.workops.service.VersionService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AgileLookupControllerTest {

    private val versionService = mockk<VersionService>(relaxed = true)
    private val componentService = mockk<ComponentService>(relaxed = true)
    private val milestoneService = mockk<MilestoneService>(relaxed = true)
    private val projectService = mockk<ProjectService>(relaxed = true)
    private val programService = mockk<ProgramService>(relaxed = true)
    private val projectPermissions = mockk<ProjectPermissionEvaluator>(relaxed = true)
    private val programPermissions = mockk<ProgramPermissionEvaluator>(relaxed = true)
    private val authentication = mockk<AuthenticationContext>(relaxed = true)
    private val ownerProfileId = UUID.random()

    @Test
    fun `version controllers cover fields queries lifecycle and missing resources`() = runTest {
        val project = sampleProject()
        val version = sampleVersion(project.id)
        val input = CreateVersionInput(project.id, "1.0", "First release")
        val missingId = UUID.random()
        val missingProjectId = UUID.random()
        val orphan = sampleVersion(missingProjectId)
        coEvery { projectService.getById(project.id) } returns project
        coEvery { projectService.getById(missingProjectId) } returns null
        coEvery { versionService.getById(version.id) } returns version
        coEvery { versionService.getById(missingId) } returns null
        coEvery { versionService.getById(orphan.id) } returns orphan
        coEvery { versionService.listByProject(project.id) } returns listOf(version)
        coEvery { versionService.create(input) } returns version
        coEvery { versionService.release(version.id, 2) } returns version.copy(released = true, version = 3)

        val typeController = VersionTypeController(projectService)
        assertEquals(version.id, typeController.id(version))
        assertEquals(version.projectId, typeController.projectId(version))
        assertEquals(version.name, typeController.name(version))
        assertEquals(version.description, typeController.description(version))
        assertEquals(version.startDate, typeController.startDate(version))
        assertEquals(version.releaseDate, typeController.releaseDate(version))
        assertEquals(version.released, typeController.released(version))
        assertEquals(version.archived, typeController.archived(version))
        assertEquals(version.sequenceNumber, typeController.sequenceNumber(version))
        assertEquals(version.version, typeController.version(version))
        assertSame(project, typeController.project(version))
        assertFailsWith<IllegalStateException> { typeController.project(orphan) }

        val queryController = VersionQueryController(versionService, projectService, projectPermissions)
        assertNull(queryController.version(authentication, missingId))
        assertNull(queryController.version(authentication, orphan.id))
        assertSame(version, queryController.version(authentication, version.id))
        assertTrue(queryController.byProject(authentication, missingProjectId).isEmpty())
        assertEquals(listOf(version), queryController.byProject(authentication, project.id))
        coVerify(exactly = 2) {
            projectPermissions.verifyAllowed(authentication, project, PermissionAction.VIEW)
        }

        val mutationController = VersionMutationController(versionService, projectService, projectPermissions)
        assertSame(version, mutationController.create(authentication, input))
        assertEquals(3, mutationController.release(authentication, version.id, 2).version)
        assertTrue(mutationController.delete(authentication, version.id))
        coVerify(exactly = 3) {
            projectPermissions.verifyAllowed(authentication, project, PermissionAction.MANAGE)
        }
        coVerify(exactly = 1) { versionService.delete(version.id) }

        assertFailsWith<IllegalStateException> {
            mutationController.create(authentication, input.copy(projectId = missingProjectId))
        }
        assertFailsWith<IllegalStateException> { mutationController.release(authentication, missingId, 0) }
        assertFailsWith<IllegalStateException> { mutationController.delete(authentication, missingId) }
        assertFailsWith<IllegalStateException> { mutationController.release(authentication, orphan.id, 0) }
        assertFailsWith<IllegalStateException> { mutationController.delete(authentication, orphan.id) }
    }

    @Test
    fun `component controllers cover fields queries lifecycle and missing resources`() = runTest {
        val project = sampleProject()
        val component = sampleComponent(project.id)
        val input = CreateComponentInput(
            projectId = project.id,
            name = "API",
            description = "API team",
            defaultAssigneeProfileId = UUID.random(),
            leadProfileId = UUID.random(),
            assigneeMode = ComponentAssigneeMode.COMPONENT_LEAD,
        )
        val missingId = UUID.random()
        val missingProjectId = UUID.random()
        val orphan = sampleComponent(missingProjectId)
        coEvery { projectService.getById(project.id) } returns project
        coEvery { projectService.getById(missingProjectId) } returns null
        coEvery { componentService.getById(component.id) } returns component
        coEvery { componentService.getById(missingId) } returns null
        coEvery { componentService.getById(orphan.id) } returns orphan
        coEvery { componentService.listByProject(project.id) } returns listOf(component)
        coEvery { componentService.create(input) } returns component

        val typeController = ComponentTypeController(projectService)
        assertEquals(component.id, typeController.id(component))
        assertEquals(component.projectId, typeController.projectId(component))
        assertEquals(component.name, typeController.name(component))
        assertEquals(component.description, typeController.description(component))
        assertEquals(component.defaultAssigneeProfileId, typeController.defaultAssigneeProfileId(component))
        assertEquals(component.leadProfileId, typeController.leadProfileId(component))
        assertEquals(component.assigneeMode, typeController.assigneeMode(component))
        assertEquals(component.version, typeController.version(component))
        assertSame(project, typeController.project(component))
        assertFailsWith<IllegalStateException> { typeController.project(orphan) }

        val queryController = ComponentQueryController(componentService, projectService, projectPermissions)
        assertNull(queryController.component(authentication, missingId))
        assertNull(queryController.component(authentication, orphan.id))
        assertSame(component, queryController.component(authentication, component.id))
        assertTrue(queryController.byProject(authentication, missingProjectId).isEmpty())
        assertEquals(listOf(component), queryController.byProject(authentication, project.id))
        coVerify(exactly = 2) {
            projectPermissions.verifyAllowed(authentication, project, PermissionAction.VIEW)
        }

        val mutationController = ComponentMutationController(componentService, projectService, projectPermissions)
        assertSame(component, mutationController.create(authentication, input))
        assertTrue(mutationController.delete(authentication, component.id))
        coVerify(exactly = 2) {
            projectPermissions.verifyAllowed(authentication, project, PermissionAction.MANAGE)
        }
        coVerify(exactly = 1) { componentService.delete(component.id) }

        assertFailsWith<IllegalStateException> {
            mutationController.create(authentication, input.copy(projectId = missingProjectId))
        }
        assertFailsWith<IllegalStateException> { mutationController.delete(authentication, missingId) }
        assertFailsWith<IllegalStateException> { mutationController.delete(authentication, orphan.id) }
    }

    @Test
    fun `milestone controllers cover fields queries lifecycle and missing resources`() = runTest {
        val program = sampleProgram()
        val milestone = sampleMilestone(program.id)
        val input = CreateMilestoneInput(program.id, "Public beta", "Ship beta", OffsetDateTime.now())
        val missingId = UUID.random()
        val missingProgramId = UUID.random()
        val orphan = sampleMilestone(missingProgramId)
        coEvery { programService.getById(program.id) } returns program
        coEvery { programService.getById(missingProgramId) } returns null
        coEvery { milestoneService.getById(milestone.id) } returns milestone
        coEvery { milestoneService.getById(missingId) } returns null
        coEvery { milestoneService.getById(orphan.id) } returns orphan
        coEvery { milestoneService.listByProgram(program.id) } returns listOf(milestone)
        coEvery { milestoneService.create(input) } returns milestone
        coEvery { milestoneService.close(milestone.id, 2) } returns milestone.copy(
            state = MilestoneState.CLOSED,
            closedAt = OffsetDateTime.now(),
            version = 3,
        )

        val typeController = MilestoneTypeController(programService)
        assertEquals(milestone.id, typeController.id(milestone))
        assertEquals(milestone.programId, typeController.programId(milestone))
        assertEquals(milestone.name, typeController.name(milestone))
        assertEquals(milestone.description, typeController.description(milestone))
        assertEquals(milestone.targetDate, typeController.targetDate(milestone))
        assertEquals(milestone.state, typeController.state(milestone))
        assertEquals(milestone.closedAt, typeController.closedAt(milestone))
        assertEquals(milestone.version, typeController.version(milestone))
        assertSame(program, typeController.program(milestone))
        assertFailsWith<IllegalStateException> { typeController.program(orphan) }

        val queryController = MilestoneQueryController(milestoneService, programService, programPermissions)
        assertNull(queryController.milestone(authentication, missingId))
        assertNull(queryController.milestone(authentication, orphan.id))
        assertSame(milestone, queryController.milestone(authentication, milestone.id))
        assertTrue(queryController.byProgram(authentication, missingProgramId).isEmpty())
        assertEquals(listOf(milestone), queryController.byProgram(authentication, program.id))
        coVerify(exactly = 2) {
            programPermissions.verifyAllowed(authentication, program, PermissionAction.VIEW)
        }

        val mutationController = MilestoneMutationController(milestoneService, programService, programPermissions)
        assertSame(milestone, mutationController.create(authentication, input))
        assertEquals(3, mutationController.close(authentication, milestone.id, 2).version)
        coVerify(exactly = 2) {
            programPermissions.verifyAllowed(authentication, program, PermissionAction.MANAGE)
        }

        assertFailsWith<IllegalStateException> {
            mutationController.create(authentication, input.copy(programId = missingProgramId))
        }
        assertFailsWith<IllegalStateException> { mutationController.close(authentication, missingId, 0) }
        assertFailsWith<IllegalStateException> { mutationController.close(authentication, orphan.id, 0) }
    }

    private fun sampleProject() = Project(
        id = UUID.random(),
        programId = UUID.random(),
        key = "PROJECT",
        name = "Project",
        ownerProfileId = ownerProfileId,
    )

    private fun sampleProgram() = Program(
        id = UUID.random(),
        portfolioId = UUID.random(),
        key = "PROGRAM",
        name = "Program",
        ownerProfileId = ownerProfileId,
    )

    private fun sampleVersion(projectId: UUID) = Version(
        id = UUID.random(),
        projectId = projectId,
        name = "1.0",
        description = "First release",
        startDate = OffsetDateTime.now(),
        releaseDate = OffsetDateTime.now(),
        sequenceNumber = 1,
        version = 2,
    )

    private fun sampleComponent(projectId: UUID) = Component(
        id = UUID.random(),
        projectId = projectId,
        name = "API",
        description = "API team",
        defaultAssigneeProfileId = UUID.random(),
        leadProfileId = UUID.random(),
        assigneeMode = ComponentAssigneeMode.COMPONENT_LEAD,
        version = 2,
    )

    private fun sampleMilestone(programId: UUID) = Milestone(
        id = UUID.random(),
        programId = programId,
        name = "Public beta",
        description = "Ship beta",
        targetDate = OffsetDateTime.now(),
        version = 2,
    )
}
