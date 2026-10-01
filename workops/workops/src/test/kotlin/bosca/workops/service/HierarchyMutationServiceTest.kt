package bosca.workops.service

import bosca.graphql.Batch
import bosca.security.model.EntityPermission
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.PermissionAction
import bosca.security.service.SecurityService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.workops.model.OptimisticLockFailedException
import bosca.workops.model.WorkOpsArchivedException
import bosca.workops.model.WorkOpsConflictException
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.WorkOpsValidationException
import bosca.workops.model.permission.PortfolioPermission
import bosca.workops.model.permission.ProgramPermission
import bosca.workops.model.permission.ProjectPermission
import bosca.workops.model.project.Portfolio
import bosca.workops.model.project.PortfolioInput
import bosca.workops.model.project.Program
import bosca.workops.model.project.ProgramInput
import bosca.workops.model.project.Project
import bosca.workops.model.project.ProjectInput
import bosca.workops.repository.PortfolioPermissionRepository
import bosca.workops.repository.PortfolioRepository
import bosca.workops.repository.ProgramPermissionRepository
import bosca.workops.repository.ProgramRepository
import bosca.workops.repository.ProjectKeyCounterRepository
import bosca.workops.repository.ProjectPermissionRepository
import bosca.workops.repository.ProjectRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HierarchyMutationServiceTest {

    @Test
    fun `hierarchy permission batches preserve present rows and fill missing entities`() = runTest {
        val present = UUID.random()
        val absent = UUID.random()
        val groupId = UUID.random()

        val projectPermissions = mockk<ProjectPermissionRepository>()
        val projectPermission = ProjectPermission(present, groupId, PermissionAction.VIEW)
        coEvery { projectPermissions.getByProjectIds(listOf(present, absent)) } returns listOf(projectPermission)
        val projectService = ProjectServiceImpl(
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), projectPermissions,
            mockk(relaxed = true), mockk(relaxed = true),
        )
        val projectBatch = Batch<UUID, List<EntityPermission>>(listOf(present, absent))
        projectService.addPermissionsToBatch(projectBatch)
        assertEquals(listOf(projectPermission), projectBatch.getData(present))
        assertEquals(emptyList(), projectBatch.getData(absent))

        val programPermissions = mockk<ProgramPermissionRepository>()
        val programPermission = ProgramPermission(present, groupId, PermissionAction.EDIT)
        coEvery { programPermissions.getByProgramIds(listOf(present, absent)) } returns listOf(programPermission)
        val programService = ProgramServiceImpl(
            mockk(relaxed = true), mockk(relaxed = true), programPermissions,
            mockk(relaxed = true), mockk(relaxed = true),
        )
        val programBatch = Batch<UUID, List<EntityPermission>>(listOf(present, absent))
        programService.addPermissionsToBatch(programBatch)
        assertEquals(listOf(programPermission), programBatch.getData(present))
        assertEquals(emptyList(), programBatch.getData(absent))

        val portfolioPermissions = mockk<PortfolioPermissionRepository>()
        val portfolioPermission = PortfolioPermission(present, groupId, PermissionAction.MANAGE)
        coEvery { portfolioPermissions.getByPortfolioIds(listOf(present, absent)) } returns listOf(portfolioPermission)
        val portfolioService = PortfolioServiceImpl(
            mockk(relaxed = true), portfolioPermissions, mockk(relaxed = true),
        )
        val portfolioBatch = Batch<UUID, List<EntityPermission>>(listOf(present, absent))
        portfolioService.addPermissionsToBatch(portfolioBatch)
        assertEquals(listOf(portfolioPermission), portfolioBatch.getData(present))
        assertEquals(emptyList(), portfolioBatch.getData(absent))
    }

    @BeforeTest
    fun setupTransactions() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction(any<suspend () -> Any?>()) } coAnswers {
            firstArg<suspend () -> Any?>().invoke()
        }
    }

    @AfterTest
    fun clearTransactions() {
        unmockkStatic("bosca.db.ConnectionManagerKt")
    }

    @Test
    fun `project creation validates parents conflicts defaults and inherited permissions`() = runTest {
        val repository = mockk<ProjectRepository>(relaxed = true)
        val programs = mockk<ProgramRepository>()
        val counters = mockk<ProjectKeyCounterRepository>(relaxed = true)
        val permissions = mockk<ProjectPermissionRepository>(relaxed = true)
        val programPermissions = mockk<ProgramPermissionRepository>()
        val service = ProjectServiceImpl(
            repository,
            programs,
            counters,
            permissions,
            programPermissions,
            mockk(relaxed = true),
        )
        val program = program()
        val inherited = ProgramPermission(program.id, UUID.random(), PermissionAction.EDIT)
        val captured = mutableListOf<Project>()
        coEvery { programs.getById(program.id) } returns program
        coEvery { repository.getByKey(any()) } returns null
        coEvery { programPermissions.getByProgramId(program.id) } returns listOf(inherited)
        coEvery { repository.add(capture(captured)) } coAnswers { captured.last().copy(id = UUID.random()) }

        val defaulted = service.create(ProjectInput(program.id, "APP", "Application", ownerProfileId = UUID.random()))
        val explicitTypeScheme = UUID.random()
        val authored = service.create(
            ProjectInput(
                program.id,
                "API",
                "API",
                description = "Backend",
                ownerProfileId = UUID.random(),
                defaultTaskTypeSchemeId = explicitTypeScheme,
                taskCreationFormSchemaKey = "custom.create-task",
            ),
        )

        assertEquals(ProjectServiceImpl.DEFAULT_TASK_TYPE_SCHEME_ID, defaulted.defaultTaskTypeSchemeId)
        assertEquals(ProjectServiceImpl.DEFAULT_WORKFLOW_SCHEME_ID, defaulted.defaultWorkflowSchemeId)
        assertEquals(ProjectServiceImpl.DEFAULT_FIELD_CONFIGURATION_SCHEME_ID, defaulted.defaultFieldConfigurationSchemeId)
        assertEquals("workops.create-task", defaulted.taskCreationFormSchemaKey)
        assertEquals(explicitTypeScheme, authored.defaultTaskTypeSchemeId)
        assertEquals("custom.create-task", authored.taskCreationFormSchemaKey)
        coVerify(exactly = 2) { counters.initialize(any()) }
        coVerify(exactly = 2) { permissions.add(any(), inherited.groupId, PermissionAction.EDIT) }

        assertFailsWith<WorkOpsValidationException> {
            service.create(ProjectInput(program.id, "bad-key", "Bad", ownerProfileId = UUID.random()))
        }
        val missingProgram = UUID.random()
        coEvery { programs.getById(missingProgram) } returns null
        assertFailsWith<WorkOpsNotFoundException> {
            service.create(ProjectInput(missingProgram, "MISS", "Missing", ownerProfileId = UUID.random()))
        }
        val archived = program(archived = true)
        coEvery { programs.getById(archived.id) } returns archived
        assertFailsWith<WorkOpsArchivedException> {
            service.create(ProjectInput(archived.id, "ARCH", "Archived", ownerProfileId = UUID.random()))
        }
        coEvery { repository.getByKey("APP") } returns defaulted
        assertFailsWith<WorkOpsConflictException> {
            service.create(ProjectInput(program.id, "APP", "Duplicate", ownerProfileId = UUID.random()))
        }
    }

    @Test
    fun `project reads bounds and update lifecycle surface all failure modes`() = runTest {
        val repository = mockk<ProjectRepository>()
        val programs = mockk<ProgramRepository>()
        val counters = mockk<ProjectKeyCounterRepository>()
        val permissions = mockk<ProjectPermissionRepository>()
        val evaluator = mockk<ProgramPermissionEvaluator>()
        val service = ProjectServiceImpl(
            repository,
            programs,
            counters,
            permissions,
            mockk(relaxed = true),
            evaluator,
        )
        val project = project()
        val parent = program(id = project.programId)
        val permission = bosca.workops.model.permission.ProjectPermission(project.id, UUID.random(), PermissionAction.VIEW)
        coEvery { repository.getAll() } returns listOf(project)
        coEvery { repository.getByProgram(parent.id, 0, 100) } returns listOf(project)
        coEvery { repository.getById(project.id) } returns project
        coEvery { repository.getByKey(project.key) } returns project
        coEvery { repository.getByIds(listOf(project.id)) } returns listOf(project)
        coEvery { permissions.getByProjectId(project.id) } returns listOf(permission)
        coEvery { programs.getById(parent.id) } returns parent
        coEvery { evaluator.isAllowed(null, parent, PermissionAction.EDIT) } returns true
        coEvery { counters.reserveNext(project.id) } returns 12

        assertEquals(listOf(project), service.listAll())
        assertEquals(listOf(project), service.listByProgram(parent.id, -4, 500))
        assertEquals(project, service.getById(project.id))
        assertEquals(project, service.getByKey(project.key))
        assertTrue(service.getByIds(emptyList()).isEmpty())
        assertEquals(listOf(project), service.getByIds(listOf(project.id)))
        assertEquals(listOf(permission), service.getPermissions(project))
        assertTrue(service.isParentAllowed(null, project, PermissionAction.EDIT))
        assertEquals(12, service.reserveNextTaskSequence(project.id))

        val missingParentProject = project(programId = UUID.random())
        coEvery { programs.getById(missingParentProject.programId) } returns null
        assertEquals(false, service.isParentAllowed(null, missingParentProject, PermissionAction.VIEW))
        coEvery { counters.reserveNext(UUID.NIL) } returns null
        assertFailsWith<WorkOpsNotFoundException> { service.reserveNextTaskSequence(UUID.NIL) }

        val input = ProjectInput(parent.id, project.key, "Updated", "Description", UUID.random(), null, null)
        val updated = project.copy(name = "Updated", version = 4)
        coEvery {
            repository.update(project.id, "Updated", "Description", input.ownerProfileId, null, project.taskCreationFormSchemaKey, 3)
        } returns updated
        assertEquals(updated, service.update(project.id, input, 3))

        val missingId = UUID.random()
        coEvery { repository.getById(missingId) } returns null
        assertFailsWith<WorkOpsNotFoundException> { service.update(missingId, input, 0) }
        assertFailsWith<WorkOpsValidationException> { service.update(project.id, input.copy(key = "NEW"), 3) }
        assertFailsWith<WorkOpsValidationException> {
            service.update(project.id, input.copy(programId = UUID.random()), 3)
        }
        coEvery { repository.update(project.id, any(), any(), any(), any(), any(), 99) } returns null
        assertFailsWith<OptimisticLockFailedException> { service.update(project.id, input, 99) }

        exerciseProjectArchiveLifecycle(service, repository, project)
    }

    @Test
    fun `program creation and mutation validate hierarchy and preserve idempotent archives`() = runTest {
        val repository = mockk<ProgramRepository>(relaxed = true)
        val portfolios = mockk<PortfolioRepository>()
        val permissions = mockk<ProgramPermissionRepository>(relaxed = true)
        val portfolioPermissions = mockk<PortfolioPermissionRepository>()
        val evaluator = mockk<PortfolioPermissionEvaluator>()
        val service = ProgramServiceImpl(repository, portfolios, permissions, portfolioPermissions, evaluator)
        val portfolio = portfolio()
        val inherited = PortfolioPermission(portfolio.id, UUID.random(), PermissionAction.MANAGE)
        val captured = slot<Program>()
        coEvery { portfolios.getById(portfolio.id) } returns portfolio
        coEvery { repository.getByKey(portfolio.id, "PLAT") } returns null
        coEvery { portfolioPermissions.getByPortfolioId(portfolio.id) } returns listOf(inherited)
        coEvery { repository.add(capture(captured)) } answers { captured.captured.copy(id = UUID.random()) }
        val input = ProgramInput(portfolio.id, "PLAT", "Platform", "Core", UUID.random(), null, null)
        val created = service.create(input)
        assertEquals("Platform", created.name)
        coVerify { permissions.add(created.id, inherited.groupId, inherited.action) }

        assertFailsWith<WorkOpsValidationException> {
            service.create(input.copy(key = "bad"))
        }
        val missingPortfolio = UUID.random()
        coEvery { portfolios.getById(missingPortfolio) } returns null
        assertFailsWith<WorkOpsNotFoundException> { service.create(input.copy(portfolioId = missingPortfolio)) }
        val archivedPortfolio = portfolio(archived = true)
        coEvery { portfolios.getById(archivedPortfolio.id) } returns archivedPortfolio
        assertFailsWith<WorkOpsArchivedException> { service.create(input.copy(portfolioId = archivedPortfolio.id)) }
        coEvery { repository.getByKey(portfolio.id, "PLAT") } returns created
        assertFailsWith<WorkOpsConflictException> { service.create(input) }

        coEvery { repository.getAll() } returns listOf(created)
        coEvery { repository.getByPortfolio(portfolio.id, 0, 100) } returns listOf(created)
        coEvery { repository.getById(created.id) } returns created
        coEvery { repository.getByKey(portfolio.id, created.key) } returns created
        coEvery { repository.getByIds(listOf(created.id)) } returns listOf(created)
        coEvery { permissions.getByProgramId(created.id) } returns listOf(ProgramPermission(created.id, inherited.groupId, inherited.action))
        coEvery { evaluator.isAllowed(null, portfolio, PermissionAction.VIEW) } returns true
        assertEquals(listOf(created), service.listAll())
        assertEquals(listOf(created), service.listByPortfolio(portfolio.id, -1, 101))
        assertEquals(created, service.getById(created.id))
        assertEquals(created, service.getByKey(portfolio.id, created.key))
        assertTrue(service.getByIds(emptyList()).isEmpty())
        assertEquals(listOf(created), service.getByIds(listOf(created.id)))
        assertEquals(1, service.getPermissions(created).size)
        assertTrue(service.isParentAllowed(null, created, PermissionAction.VIEW))
        val orphan = created.copy(portfolioId = UUID.random())
        coEvery { portfolios.getById(orphan.portfolioId) } returns null
        assertEquals(false, service.isParentAllowed(null, orphan, PermissionAction.VIEW))

        val update = input.copy(name = "Updated")
        val updated = created.copy(name = "Updated", version = 1)
        coEvery {
            repository.update(created.id, "Updated", "Core", input.ownerProfileId, null, null, 0)
        } returns updated
        assertEquals(updated, service.update(created.id, update, 0))
        val missingId = UUID.random()
        coEvery { repository.getById(missingId) } returns null
        assertFailsWith<WorkOpsNotFoundException> { service.update(missingId, update, 0) }
        assertFailsWith<WorkOpsValidationException> { service.update(created.id, update.copy(key = "NEW"), 0) }
        assertFailsWith<WorkOpsValidationException> {
            service.update(created.id, update.copy(portfolioId = UUID.random()), 0)
        }
        coEvery { repository.update(created.id, any(), any(), any(), any(), any(), 99) } returns null
        assertFailsWith<OptimisticLockFailedException> { service.update(created.id, update, 99) }

        exerciseProgramArchiveLifecycle(service, repository, created)
    }

    @Test
    fun `portfolio creation seeds available system groups and mutation reports exact conflicts`() = runTest {
        val repository = mockk<PortfolioRepository>()
        val permissions = mockk<PortfolioPermissionRepository>(relaxed = true)
        val security = mockk<SecurityService>()
        val service = PortfolioServiceImpl(repository, permissions, security)
        val captured = slot<Portfolio>()
        val admin = Group(UUID.random(), "administrators", "Admins", GroupType.SYSTEM)
        val editors = Group(UUID.random(), "editors", "Editors", GroupType.SYSTEM)
        coEvery { repository.getByKey("PLATFORM") } returns null
        coEvery { repository.add(capture(captured)) } answers { captured.captured.copy(id = UUID.random()) }
        coEvery { security.getGroupByName("administrators", GroupType.SYSTEM) } returns admin
        coEvery { security.getGroupByName("managers", GroupType.SYSTEM) } returns null
        coEvery { security.getGroupByName("editors", GroupType.SYSTEM) } returns editors
        val input = PortfolioInput("PLATFORM", "Platform", "Core products", UUID.random())
        val created = service.create(input)
        assertEquals("Platform", created.name)
        coVerify(exactly = 4) { permissions.add(created.id, admin.id, any()) }
        coVerify(exactly = 3) { permissions.add(created.id, editors.id, any()) }

        assertFailsWith<WorkOpsValidationException> { service.create(input.copy(key = "p")) }
        coEvery { repository.getByKey("PLATFORM") } returns created
        assertFailsWith<WorkOpsConflictException> { service.create(input) }

        val permission = PortfolioPermission(created.id, admin.id, PermissionAction.VIEW)
        coEvery { repository.getAll(0, 100) } returns listOf(created)
        coEvery { repository.getById(created.id) } returns created
        coEvery { repository.getByKey(created.key) } returns created
        coEvery { repository.getByIds(listOf(created.id)) } returns listOf(created)
        coEvery { permissions.getByPortfolioId(created.id) } returns listOf(permission)
        assertEquals(listOf(created), service.list(-4, 500))
        assertEquals(created, service.getById(created.id))
        assertEquals(created, service.getByKey(created.key))
        assertTrue(service.getByIds(emptyList()).isEmpty())
        assertEquals(listOf(created), service.getByIds(listOf(created.id)))
        assertEquals(listOf(permission), service.getPermissions(created))

        val update = input.copy(name = "Updated")
        val updated = created.copy(name = "Updated", version = 1)
        coEvery { repository.update(created.id, "Updated", input.description, input.ownerProfileId, 0) } returns updated
        assertEquals(updated, service.update(created.id, update, 0))
        val missingId = UUID.random()
        coEvery { repository.getById(missingId) } returns null
        assertFailsWith<WorkOpsNotFoundException> { service.update(missingId, update, 0) }
        assertFailsWith<WorkOpsValidationException> { service.update(created.id, update.copy(key = "RENAMED"), 0) }
        coEvery { repository.update(created.id, any(), any(), any(), 99) } returns null
        assertFailsWith<OptimisticLockFailedException> { service.update(created.id, update, 99) }

        exercisePortfolioArchiveLifecycle(service, repository, created)
    }

    private suspend fun exerciseProjectArchiveLifecycle(
        service: ProjectServiceImpl,
        repository: ProjectRepository,
        project: Project,
    ) {
        val archived = project.copy(archivedAt = OffsetDateTime.now(), version = 4)
        coEvery { repository.archive(project.id, 3) } returns archived
        assertEquals(archived, service.archive(project.id, 3))
        val alreadyArchivedId = UUID.random()
        coEvery { repository.archive(alreadyArchivedId, 3) } returns null
        coEvery { repository.getById(alreadyArchivedId) } returns archived.copy(id = alreadyArchivedId)
        assertEquals(alreadyArchivedId, service.archive(alreadyArchivedId, 3).id)
        val missingId = UUID.random()
        coEvery { repository.archive(missingId, 0) } returns null
        coEvery { repository.getById(missingId) } returns null
        assertFailsWith<WorkOpsNotFoundException> { service.archive(missingId, 0) }
        val staleId = UUID.random()
        coEvery { repository.archive(staleId, 0) } returns null
        coEvery { repository.getById(staleId) } returns project.copy(id = staleId)
        assertFailsWith<OptimisticLockFailedException> { service.archive(staleId, 0) }

        val restored = archived.copy(archivedAt = null, version = 5)
        coEvery { repository.unarchive(archived.id, 4) } returns restored
        assertEquals(restored, service.unarchive(archived.id, 4))
        val alreadyActiveId = UUID.random()
        coEvery { repository.unarchive(alreadyActiveId, 4) } returns null
        coEvery { repository.getById(alreadyActiveId) } returns restored.copy(id = alreadyActiveId)
        assertEquals(alreadyActiveId, service.unarchive(alreadyActiveId, 4).id)
        val missingRestoreId = UUID.random()
        coEvery { repository.unarchive(missingRestoreId, 0) } returns null
        coEvery { repository.getById(missingRestoreId) } returns null
        assertFailsWith<WorkOpsNotFoundException> { service.unarchive(missingRestoreId, 0) }
        val staleRestoreId = UUID.random()
        coEvery { repository.unarchive(staleRestoreId, 0) } returns null
        coEvery { repository.getById(staleRestoreId) } returns archived.copy(id = staleRestoreId)
        assertFailsWith<OptimisticLockFailedException> { service.unarchive(staleRestoreId, 0) }
    }

    private suspend fun exerciseProgramArchiveLifecycle(
        service: ProgramServiceImpl,
        repository: ProgramRepository,
        program: Program,
    ) {
        val archived = program.copy(archivedAt = OffsetDateTime.now(), version = 1)
        coEvery { repository.archive(program.id, 0) } returns archived
        assertEquals(archived, service.archive(program.id, 0))
        val alreadyArchivedId = UUID.random()
        coEvery { repository.archive(alreadyArchivedId, 0) } returns null
        coEvery { repository.getById(alreadyArchivedId) } returns archived.copy(id = alreadyArchivedId)
        assertEquals(alreadyArchivedId, service.archive(alreadyArchivedId, 0).id)
        val missingId = UUID.random()
        coEvery { repository.archive(missingId, 0) } returns null
        coEvery { repository.getById(missingId) } returns null
        assertFailsWith<WorkOpsNotFoundException> { service.archive(missingId, 0) }
        val staleId = UUID.random()
        coEvery { repository.archive(staleId, 0) } returns null
        coEvery { repository.getById(staleId) } returns program.copy(id = staleId)
        assertFailsWith<OptimisticLockFailedException> { service.archive(staleId, 0) }

        val restored = archived.copy(archivedAt = null, version = 2)
        coEvery { repository.unarchive(archived.id, 1) } returns restored
        assertEquals(restored, service.unarchive(archived.id, 1))
        val alreadyActiveId = UUID.random()
        coEvery { repository.unarchive(alreadyActiveId, 1) } returns null
        coEvery { repository.getById(alreadyActiveId) } returns restored.copy(id = alreadyActiveId)
        assertEquals(alreadyActiveId, service.unarchive(alreadyActiveId, 1).id)
        val missingRestoreId = UUID.random()
        coEvery { repository.unarchive(missingRestoreId, 0) } returns null
        coEvery { repository.getById(missingRestoreId) } returns null
        assertFailsWith<WorkOpsNotFoundException> { service.unarchive(missingRestoreId, 0) }
        val staleRestoreId = UUID.random()
        coEvery { repository.unarchive(staleRestoreId, 0) } returns null
        coEvery { repository.getById(staleRestoreId) } returns archived.copy(id = staleRestoreId)
        assertFailsWith<OptimisticLockFailedException> { service.unarchive(staleRestoreId, 0) }
    }

    private suspend fun exercisePortfolioArchiveLifecycle(
        service: PortfolioServiceImpl,
        repository: PortfolioRepository,
        portfolio: Portfolio,
    ) {
        val archived = portfolio.copy(archivedAt = OffsetDateTime.now(), version = 1)
        coEvery { repository.archive(portfolio.id, 0) } returns archived
        assertEquals(archived, service.archive(portfolio.id, 0))
        val alreadyArchivedId = UUID.random()
        coEvery { repository.archive(alreadyArchivedId, 0) } returns null
        coEvery { repository.getById(alreadyArchivedId) } returns archived.copy(id = alreadyArchivedId)
        assertEquals(alreadyArchivedId, service.archive(alreadyArchivedId, 0).id)
        val missingId = UUID.random()
        coEvery { repository.archive(missingId, 0) } returns null
        coEvery { repository.getById(missingId) } returns null
        assertFailsWith<WorkOpsNotFoundException> { service.archive(missingId, 0) }
        val staleId = UUID.random()
        coEvery { repository.archive(staleId, 0) } returns null
        coEvery { repository.getById(staleId) } returns portfolio.copy(id = staleId)
        assertFailsWith<OptimisticLockFailedException> { service.archive(staleId, 0) }

        val restored = archived.copy(archivedAt = null, version = 2)
        coEvery { repository.unarchive(archived.id, 1) } returns restored
        assertEquals(restored, service.unarchive(archived.id, 1))
        val alreadyActiveId = UUID.random()
        coEvery { repository.unarchive(alreadyActiveId, 1) } returns null
        coEvery { repository.getById(alreadyActiveId) } returns restored.copy(id = alreadyActiveId)
        assertEquals(alreadyActiveId, service.unarchive(alreadyActiveId, 1).id)
        val missingRestoreId = UUID.random()
        coEvery { repository.unarchive(missingRestoreId, 0) } returns null
        coEvery { repository.getById(missingRestoreId) } returns null
        assertFailsWith<WorkOpsNotFoundException> { service.unarchive(missingRestoreId, 0) }
        val staleRestoreId = UUID.random()
        coEvery { repository.unarchive(staleRestoreId, 0) } returns null
        coEvery { repository.getById(staleRestoreId) } returns archived.copy(id = staleRestoreId)
        assertFailsWith<OptimisticLockFailedException> { service.unarchive(staleRestoreId, 0) }
    }

    private fun project(
        id: UUID = UUID.random(),
        programId: UUID = UUID.random(),
    ) = Project(
        id = id,
        programId = programId,
        key = "APP",
        name = "Application",
        ownerProfileId = UUID.random(),
        version = 3,
    )

    private fun program(
        id: UUID = UUID.random(),
        archived: Boolean = false,
    ) = Program(
        id = id,
        portfolioId = UUID.random(),
        key = "PLAT",
        name = "Platform",
        ownerProfileId = UUID.random(),
        archivedAt = if (archived) OffsetDateTime.now() else null,
    )

    private fun portfolio(archived: Boolean = false) = Portfolio(
        id = UUID.random(),
        key = "PLATFORM",
        name = "Platform",
        ownerProfileId = UUID.random(),
        archivedAt = if (archived) OffsetDateTime.now() else null,
    )
}
