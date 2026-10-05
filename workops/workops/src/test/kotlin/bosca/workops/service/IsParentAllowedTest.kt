package bosca.workops.service

import bosca.di.asProvider
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.workops.model.project.Portfolio
import bosca.workops.model.project.Program
import bosca.workops.model.project.Project
import bosca.workops.model.task.Task
import bosca.workops.repository.PortfolioRepository
import bosca.workops.repository.ProgramRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Focused tests for the `isParentAllowed` parent-walk implementations on
 * the workops services that participate in the permission hierarchy. The
 * full cascade is also covered indirectly by PermissionEvaluatorTest;
 * these tests verify each service correctly resolves its parent and
 * delegates to the parent evaluator.
 */
class IsParentAllowedTest {

    private val profileId = UUID.random()
    private val principalId = UUID.random()

    // --- TaskService → Project ---

    @Test
    fun `TaskServiceImpl isParentAllowed delegates to projectPermissionEvaluator`() = runTest {
        val projectService = mockk<ProjectService>()
        val projectPermissionEvaluator = mockk<ProjectPermissionEvaluator>()
        val task = Task(
            id = UUID.random(), key = "P-1", projectId = UUID.random(),
            taskTypeId = UUID.random(), statusId = UUID.random(), priorityId = UUID.random(),
            summary = "t", reporterProfileId = profileId,
            createdByPrincipalId = principalId, modifiedByPrincipalId = principalId,
        )
        val project = Project(
            id = task.projectId, programId = UUID.random(),
            key = "P", name = "Project", ownerProfileId = profileId,
        )
        coEvery { projectService.getById(task.projectId) } returns project
        coEvery {
            projectPermissionEvaluator.isAllowed(any<AuthenticationContext>(), project, PermissionAction.VIEW)
        } returns true

        val service = buildTaskService(projectService, projectPermissionEvaluator)
        assertTrue(service.isParentAllowed(null, task, PermissionAction.VIEW))
    }

    @Test
    fun `TaskServiceImpl isParentAllowed returns false when project not found`() = runTest {
        val projectService = mockk<ProjectService>()
        val projectPermissionEvaluator = mockk<ProjectPermissionEvaluator>()
        val task = Task(
            id = UUID.random(), key = "P-1", projectId = UUID.random(),
            taskTypeId = UUID.random(), statusId = UUID.random(), priorityId = UUID.random(),
            summary = "t", reporterProfileId = profileId,
            createdByPrincipalId = principalId, modifiedByPrincipalId = principalId,
        )
        coEvery { projectService.getById(task.projectId) } returns null

        val service = buildTaskService(projectService, projectPermissionEvaluator)
        assertFalse(service.isParentAllowed(null, task, PermissionAction.VIEW))
    }

    // --- ProjectService → Program ---

    @Test
    fun `ProjectServiceImpl isParentAllowed delegates to programPermissionEvaluator`() = runTest {
        val programRepository = mockk<ProgramRepository>()
        val programPermissionEvaluator = mockk<ProgramPermissionEvaluator>()
        val project = Project(
            id = UUID.random(), programId = UUID.random(),
            key = "P", name = "Project", ownerProfileId = profileId,
        )
        val program = Program(
            id = project.programId, portfolioId = UUID.random(),
            key = "PROG", name = "Program", ownerProfileId = profileId,
        )
        coEvery { programRepository.getById(project.programId) } returns program
        coEvery {
            programPermissionEvaluator.isAllowed(any<AuthenticationContext>(), program, PermissionAction.EDIT)
        } returns true

        val service = buildProjectService(programRepository, programPermissionEvaluator)
        assertTrue(service.isParentAllowed(null, project, PermissionAction.EDIT))
    }

    @Test
    fun `ProjectServiceImpl isParentAllowed returns false when program not found`() = runTest {
        val programRepository = mockk<ProgramRepository>()
        val programPermissionEvaluator = mockk<ProgramPermissionEvaluator>()
        val project = Project(
            id = UUID.random(), programId = UUID.random(),
            key = "P", name = "Project", ownerProfileId = profileId,
        )
        coEvery { programRepository.getById(project.programId) } returns null

        val service = buildProjectService(programRepository, programPermissionEvaluator)
        assertFalse(service.isParentAllowed(null, project, PermissionAction.VIEW))
    }

    // --- ProgramService → Portfolio ---

    @Test
    fun `ProgramServiceImpl isParentAllowed delegates to portfolioPermissionEvaluator`() = runTest {
        val portfolioRepository = mockk<PortfolioRepository>()
        val portfolioPermissionEvaluator = mockk<PortfolioPermissionEvaluator>()
        val program = Program(
            id = UUID.random(), portfolioId = UUID.random(),
            key = "PROG", name = "Program", ownerProfileId = profileId,
        )
        val portfolio = Portfolio(
            id = program.portfolioId, key = "PORT", name = "Portfolio",
            ownerProfileId = profileId,
        )
        coEvery { portfolioRepository.getById(program.portfolioId) } returns portfolio
        coEvery {
            portfolioPermissionEvaluator.isAllowed(any<AuthenticationContext>(), portfolio, PermissionAction.MANAGE)
        } returns true

        val service = buildProgramService(portfolioRepository, portfolioPermissionEvaluator)
        assertTrue(service.isParentAllowed(null, program, PermissionAction.MANAGE))
    }

    @Test
    fun `ProgramServiceImpl isParentAllowed returns false when portfolio not found`() = runTest {
        val portfolioRepository = mockk<PortfolioRepository>()
        val portfolioPermissionEvaluator = mockk<PortfolioPermissionEvaluator>()
        val program = Program(
            id = UUID.random(), portfolioId = UUID.random(),
            key = "PROG", name = "Program", ownerProfileId = profileId,
        )
        coEvery { portfolioRepository.getById(program.portfolioId) } returns null

        val service = buildProgramService(portfolioRepository, portfolioPermissionEvaluator)
        assertFalse(service.isParentAllowed(null, program, PermissionAction.VIEW))
    }

    // --- builders ---

    private fun buildTaskService(
        projectService: ProjectService,
        projectPermissionEvaluator: ProjectPermissionEvaluator,
    ): TaskServiceImpl = TaskServiceImpl(
        taskRepository = mockk(relaxed = true),
        taskHistoryRepository = mockk(relaxed = true),
        projectService = projectService,
        programService = mockk(relaxed = true),
        taskTypeService = mockk(relaxed = true),
        taskTypeSchemeService = mockk(relaxed = true),
        statusService = mockk(relaxed = true),
        priorityService = mockk(relaxed = true),
        workflowService = mockk(relaxed = true),
        workflowEvaluator = mockk(relaxed = true),
        customFieldService = mockk(relaxed = true),
        taskPermissionRepository = mockk(relaxed = true),
        affectedProjectService = mockk(relaxed = true),
        requirementServiceProvider = mockk<RequirementService>(relaxed = true).asProvider(),
        metadataService = mockk(relaxed = true),
        sprintService = mockk(relaxed = true),
        projectPermissionEvaluator = projectPermissionEvaluator,
        json = Json,
    )

    private fun buildProjectService(
        programRepository: ProgramRepository,
        programPermissionEvaluator: ProgramPermissionEvaluator,
    ): ProjectServiceImpl = ProjectServiceImpl(
        repository = mockk(relaxed = true),
        programRepository = programRepository,
        keyCounterRepository = mockk(relaxed = true),
        permissionRepository = mockk(relaxed = true),
        programPermissionRepository = mockk(relaxed = true),
        programPermissionEvaluator = programPermissionEvaluator,
    )

    private fun buildProgramService(
        portfolioRepository: PortfolioRepository,
        portfolioPermissionEvaluator: PortfolioPermissionEvaluator,
    ): ProgramServiceImpl = ProgramServiceImpl(
        repository = mockk(relaxed = true),
        portfolioRepository = portfolioRepository,
        permissionRepository = mockk(relaxed = true),
        portfolioPermissionRepository = mockk(relaxed = true),
        portfolioPermissionEvaluator = portfolioPermissionEvaluator,
    )

}
