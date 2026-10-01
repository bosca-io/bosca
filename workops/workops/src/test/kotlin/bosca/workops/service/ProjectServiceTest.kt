package bosca.workops.service

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.workops.model.OptimisticLockFailedException
import bosca.workops.model.WorkOpsArchivedException
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.project.Program
import bosca.workops.model.project.Project
import bosca.workops.repository.ProgramPermissionRepository
import bosca.workops.repository.ProgramRepository
import bosca.workops.repository.ProjectKeyCounterRepository
import bosca.workops.repository.ProjectPermissionRepository
import bosca.workops.repository.ProjectRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProjectServiceTest {

    private val repository = mockk<ProjectRepository>()
    private val programRepository = mockk<ProgramRepository>()
    private val service = ProjectServiceImpl(
        repository = repository,
        programRepository = programRepository,
        keyCounterRepository = mockk<ProjectKeyCounterRepository>(),
        permissionRepository = mockk<ProjectPermissionRepository>(),
        programPermissionRepository = mockk<ProgramPermissionRepository>(),
        programPermissionEvaluator = mockk<ProgramPermissionEvaluator>(),
    )

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction(any<suspend () -> Any?>()) } coAnswers {
            firstArg<suspend () -> Any?>().invoke()
        }
    }

    @AfterTest
    fun teardown() {
        unmockkStatic("bosca.db.ConnectionManagerKt")
    }

    @Test
    fun `move changes the project parent with optimistic locking`() = runTest {
        val project = sampleProject()
        val targetProgram = sampleProgram()
        val moved = project.copy(programId = targetProgram.id, version = project.version + 1)
        coEvery { repository.getById(project.id) } returns project
        coEvery { programRepository.getById(targetProgram.id) } returns targetProgram
        coEvery { repository.move(project.id, targetProgram.id, project.version) } returns moved

        assertEquals(moved, service.move(project.id, targetProgram.id, project.version))
        coVerify(exactly = 1) { repository.move(project.id, targetProgram.id, project.version) }
    }

    @Test
    fun `move rejects missing projects and invalid destination programs`() = runTest {
        val missingProjectId = UUID.random()
        coEvery { repository.getById(missingProjectId) } returns null

        val missingProject = assertFailsWith<WorkOpsNotFoundException> {
            service.move(missingProjectId, UUID.random(), 0)
        }
        assertEquals("Project", missingProject.type)

        val project = sampleProject()
        val missingProgramId = UUID.random()
        coEvery { repository.getById(project.id) } returns project
        coEvery { programRepository.getById(missingProgramId) } returns null

        val missingProgram = assertFailsWith<WorkOpsNotFoundException> {
            service.move(project.id, missingProgramId, project.version)
        }
        assertEquals("Program", missingProgram.type)

        val archivedProgram = sampleProgram(archived = true)
        coEvery { programRepository.getById(archivedProgram.id) } returns archivedProgram

        val archived = assertFailsWith<WorkOpsArchivedException> {
            service.move(project.id, archivedProgram.id, project.version)
        }
        assertEquals(archivedProgram.id, archived.id)
        coVerify(exactly = 0) { repository.move(any(), any(), any()) }
    }

    @Test
    fun `move to the current program is idempotent only for the current version`() = runTest {
        val project = sampleProject()
        val currentProgram = sampleProgram(id = project.programId)
        coEvery { repository.getById(project.id) } returns project
        coEvery { programRepository.getById(currentProgram.id) } returns currentProgram

        assertEquals(project, service.move(project.id, currentProgram.id, project.version))
        assertFailsWith<OptimisticLockFailedException> {
            service.move(project.id, currentProgram.id, project.version - 1)
        }
        coVerify(exactly = 0) { repository.move(any(), any(), any()) }
    }

    @Test
    fun `move reports a concurrent project change`() = runTest {
        val project = sampleProject()
        val targetProgram = sampleProgram()
        coEvery { repository.getById(project.id) } returns project
        coEvery { programRepository.getById(targetProgram.id) } returns targetProgram
        coEvery { repository.move(project.id, targetProgram.id, project.version) } returns null

        val failure = assertFailsWith<OptimisticLockFailedException> {
            service.move(project.id, targetProgram.id, project.version)
        }
        assertEquals(project.id, failure.id)
    }

    private fun sampleProject() = Project(
        id = UUID.random(),
        programId = UUID.random(),
        key = "MOVE",
        name = "Move",
        ownerProfileId = UUID.random(),
        version = 3,
    )

    private fun sampleProgram(
        id: UUID = UUID.random(),
        archived: Boolean = false,
    ) = Program(
        id = id,
        portfolioId = UUID.random(),
        key = "PROGRAM",
        name = "Program",
        ownerProfileId = UUID.random(),
        archivedAt = if (archived) OffsetDateTime.now() else null,
    )
}
