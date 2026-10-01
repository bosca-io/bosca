package bosca.workops.controller

import bosca.git.model.Repository
import bosca.git.service.RepositoryAccessEvaluator
import bosca.git.service.RepositoryService
import bosca.security.model.PermissionAction
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.workops.model.environment.Environment
import bosca.workops.model.project.Program
import bosca.workops.model.project.Project
import bosca.workops.model.release.Release
import bosca.workops.model.release.ReleasePipelinePlan
import bosca.workops.model.task.Task
import bosca.workops.service.CrossProjectMoveService
import bosca.workops.service.EnvironmentPermissionEvaluator
import bosca.workops.service.EnvironmentService
import bosca.workops.service.ProgramPermissionEvaluator
import bosca.workops.service.ProgramService
import bosca.workops.service.ProjectPermissionEvaluator
import bosca.workops.service.ProjectService
import bosca.workops.service.ReleasePipelineService
import bosca.workops.service.ReleaseService
import bosca.workops.service.TaskAffectedProjectService
import bosca.workops.service.TaskPermissionEvaluator
import bosca.workops.service.TaskService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrossProjectControllerEdgeTest {

    private val releaseService = mockk<ReleaseService>()
    private val pipelines = mockk<ReleasePipelineService>()
    private val affected = mockk<TaskAffectedProjectService>(relaxed = true)
    private val moves = mockk<CrossProjectMoveService>(relaxed = true)
    private val programs = mockk<ProgramService>()
    private val projects = mockk<ProjectService>(relaxed = true)
    private val tasks = mockk<TaskService>(relaxed = true)
    private val environments = mockk<EnvironmentService>()
    private val environmentPermissions = mockk<EnvironmentPermissionEvaluator>()
    private val repositories = mockk<RepositoryService>()
    private val repositoryPermissions = mockk<RepositoryAccessEvaluator>()
    private val programPermissions = mockk<ProgramPermissionEvaluator>(relaxed = true)
    private val projectPermissions = mockk<ProjectPermissionEvaluator>(relaxed = true)
    private val taskPermissions = mockk<TaskPermissionEvaluator>(relaxed = true)
    private val authentication = mockk<AuthenticationContext>()

    private val query = CrossProjectQueryController(
        releaseService,
        pipelines,
        affected,
        programs,
        programPermissions,
        environments,
        environmentPermissions,
        repositories,
        repositoryPermissions,
        tasks,
        taskPermissions,
    )
    private val mutation = CrossProjectMutationController(
        releaseService,
        pipelines,
        affected,
        moves,
        programs,
        projects,
        tasks,
        environments,
        environmentPermissions,
        repositories,
        repositoryPermissions,
        programPermissions,
        projectPermissions,
        taskPermissions,
        Json,
    )

    @Test
    fun `release plan and action access queries handle missing context and project every grant`() = runTest {
        val release = release()
        val program = program(release.programId)
        val firstRepositoryId = UUID.random()
        val missingRepositoryId = UUID.random()
        val repository = Repository(
            id = firstRepositoryId,
            slug = "server",
            name = "Server",
            ownerId = UUID.random(),
        )
        val environment = Environment(
            id = UUID.random(),
            programId = program.id,
            key = "production",
            name = "Production",
        )
        val plan = mockk<ReleasePipelinePlan>()

        coEvery { releaseService.getById(release.id) } returns null
        assertTrue(query.releasePlans(authentication, release.id).isEmpty())
        assertNull(query.releaseActionAccess(authentication, release.id))

        coEvery { releaseService.getById(release.id) } returns release
        coEvery { programs.getById(program.id) } returns null
        assertTrue(query.releasePlans(authentication, release.id).isEmpty())
        assertNull(query.releaseActionAccess(authentication, release.id))

        coEvery { programs.getById(program.id) } returns program
        coEvery { pipelines.plansForRelease(release.id) } returns listOf(plan)
        coEvery { pipelines.repositoryIdsForRelease(release.id) } returns listOf(firstRepositoryId, missingRepositoryId)
        coEvery { repositories.findById(firstRepositoryId) } returns repository
        coEvery { repositories.findById(missingRepositoryId) } returns null
        coEvery { repositoryPermissions.isAllowed(authentication, repository, PermissionAction.EDIT) } returns false
        coEvery { repositoryPermissions.isAllowed(authentication, repository, PermissionAction.EXECUTE) } returns false
        coEvery { environments.listByProgram(program.id) } returns listOf(environment)
        coEvery {
            environmentPermissions.isAllowed(authentication, environment, PermissionAction.EXECUTE)
        } returns true
        coEvery { programPermissions.isAllowed(authentication, program, PermissionAction.MANAGE) } returns true

        assertEquals(listOf(plan), query.releasePlans(authentication, release.id))
        val access = query.releaseActionAccess(authentication, release.id) ?: error("missing access")
        assertTrue(access.canManage)
        assertEquals(listOf(ReleaseRepositoryAccess(firstRepositoryId, false, false)), access.repositories)
        assertEquals(listOf(ReleaseEnvironmentAccess("production", true)), access.environments)
        coVerify(exactly = 2) { programPermissions.verifyAllowed(authentication, program, PermissionAction.VIEW) }
    }

    @Test
    fun `patch and rollback mutations validate release ownership and delegate exact inputs`() = runTest {
        val release = release()
        val program = program(release.programId)
        val selected = listOf(UUID.random(), UUID.random())
        val patch = release(id = UUID.random())
        coEvery { releaseService.getById(release.id) } returns release
        coEvery { programs.getById(program.id) } returns program
        coEvery { pipelines.startPatchRelease(release.id, selected, authentication) } returns patch
        coEvery { pipelines.rollbackEnvironment(release.id, "production", 2, authentication) } returns listOf("server@41")

        assertEquals(
            patch,
            mutation.startPatchRelease(authentication, release.id, StartPatchReleaseInput(selected)),
        )
        assertEquals(
            listOf("server@41"),
            mutation.rollbackReleaseEnvironment(authentication, release.id, "production", 2),
        )
        coVerify(exactly = 2) { programPermissions.verifyAllowed(authentication, program, PermissionAction.MANAGE) }

        val missingRelease = UUID.random()
        coEvery { releaseService.getById(missingRelease) } returns null
        assertFailsWith<IllegalStateException> {
            mutation.startPatchRelease(authentication, missingRelease, StartPatchReleaseInput(selected))
        }
        assertFailsWith<IllegalStateException> {
            mutation.rollbackReleaseEnvironment(authentication, missingRelease, "production", 0)
        }

        coEvery { programs.getById(program.id) } returns null
        assertFailsWith<IllegalStateException> {
            mutation.startPatchRelease(authentication, release.id, StartPatchReleaseInput(selected))
        }
        assertFailsWith<IllegalStateException> {
            mutation.rollbackReleaseEnvironment(authentication, release.id, "production", 0)
        }
    }

    @Test
    fun `launch requires edit and execute on every repository and accepts only scalar inputs`() = runTest {
        val release = release()
        val program = program(release.programId)
        val firstId = UUID.random()
        val secondId = UUID.random()
        val first = Repository(id = firstId, slug = "first", name = "First", ownerId = UUID.random())
        val second = Repository(id = secondId, slug = "second", name = "Second", ownerId = UUID.random())
        coEvery { releaseService.getById(release.id) } returns release
        coEvery { programs.getById(program.id) } returns program
        coEvery { pipelines.repositoryIdsForRelease(release.id) } returns listOf(firstId, secondId)
        coEvery { repositories.findById(firstId) } returns first
        coEvery { repositories.findById(secondId) } returns second
        coEvery { repositoryPermissions.isAllowed(authentication, first, PermissionAction.EDIT) } returns true
        coEvery { repositoryPermissions.isAllowed(authentication, second, PermissionAction.EDIT) } returns true
        coEvery { repositoryPermissions.isAllowed(authentication, first, PermissionAction.EXECUTE) } returns true
        coEvery { repositoryPermissions.isAllowed(authentication, second, PermissionAction.EXECUTE) } returns true
        coEvery {
            pipelines.launch(release.id, mapOf("track" to "beta", "attempt" to "2"), authentication)
        } returns true

        assertTrue(
            mutation.launchRelease(
                authentication,
                release.id,
                buildJsonObject {
                    put("track", "beta")
                    put("attempt", 2)
                },
            ),
        )

        assertFailsWith<IllegalStateException> {
            mutation.launchRelease(
                authentication,
                release.id,
                buildJsonObject { put("nested", JsonObject(emptyMap())) },
            )
        }

        coEvery { repositories.findById(secondId) } returns null
        assertFailsWith<IllegalStateException> { mutation.launchRelease(authentication, release.id, null) }
        coEvery { repositories.findById(secondId) } returns second
        coEvery { repositoryPermissions.isAllowed(authentication, second, PermissionAction.EDIT) } returns false
        assertFailsWith<SecurityException> { mutation.launchRelease(authentication, release.id, null) }
    }

    @Test
    fun `promotion trims target resolves key then name and passes scalar parameters`() = runTest {
        val release = release()
        val program = program(release.programId)
        val environment = Environment(
            id = UUID.random(),
            programId = program.id,
            key = "production",
            name = "Production",
        )
        coEvery { releaseService.getById(release.id) } returns release
        coEvery { programs.getById(program.id) } returns program
        coEvery { pipelines.repositoryIdsForRelease(release.id) } returns emptyList()
        coEvery { environments.getByProgramAndKey(program.id, "Production") } returns null
        coEvery { environments.getByProgramAndName(program.id, "Production") } returns environment
        coEvery {
            environmentPermissions.verifyAllowed(authentication, environment, PermissionAction.EXECUTE)
        } returns Unit
        coEvery {
            pipelines.promote(release.id, "production", true, mapOf("track" to "stable"), authentication)
        } returns true

        assertTrue(
            mutation.promoteRelease(
                authentication,
                release.id,
                PromoteReleaseInput(
                    environment = " Production ",
                    allowDowngrade = true,
                    inputs = buildJsonObject { put("track", "stable") },
                ),
            ),
        )

        coEvery { environments.getByProgramAndName(program.id, "Missing") } returns null
        coEvery { environments.getByProgramAndKey(program.id, "Missing") } returns null
        assertFailsWith<IllegalStateException> {
            mutation.promoteRelease(authentication, release.id, PromoteReleaseInput("Missing"))
        }
    }

    @Test
    fun `move parses only valid mappings and requires both task and target authority`() = runTest {
        val task = task()
        val target = project()
        val principalId = UUID.random()
        val principal = mockk<AuthenticatedPrincipal> { every { id } returns principalId }
        val sourceStatus = UUID.random()
        val targetStatus = UUID.random()
        val moved = task.copy(id = UUID.random(), projectId = target.id)
        coEvery { tasks.getById(task.id) } returns task
        coEvery { projects.getById(target.id) } returns target
        coEvery { authentication.principal() } returns principal
        coEvery { moves.moveTaskToProject(task.id, any(), principalId) } returns moved

        assertEquals(
            moved.id,
            mutation.moveTaskToProject(
                authentication,
                task.id,
                MoveTaskInput(
                    targetProjectId = target.id,
                    statusMapping = JsonObject(
                        mapOf(
                            sourceStatus.toString() to JsonPrimitive(targetStatus.toString()),
                            "bad-source" to JsonPrimitive(targetStatus.toString()),
                            UUID.random().toString() to JsonObject(emptyMap()),
                            UUID.random().toString() to JsonPrimitive("bad-target"),
                        ),
                    ),
                    fieldMapping = JsonObject(
                        mapOf(
                            "priority" to JsonPrimitive("severity"),
                            "ignored" to JsonObject(emptyMap()),
                        ),
                    ),
                ),
            ),
        )
        coVerify {
            moves.moveTaskToProject(
                task.id,
                match {
                    it.targetProjectId == target.id &&
                        it.statusMapping == mapOf(sourceStatus to targetStatus) &&
                        it.fieldMapping == mapOf("priority" to "severity")
                },
                principalId,
            )
        }

        coEvery { tasks.getById(task.id) } returns null
        assertFailsWith<IllegalStateException> {
            mutation.moveTaskToProject(authentication, task.id, MoveTaskInput(target.id, JsonPrimitive("none")))
        }
        coEvery { tasks.getById(task.id) } returns task
        coEvery { projects.getById(target.id) } returns null
        assertFailsWith<IllegalStateException> {
            mutation.moveTaskToProject(authentication, task.id, MoveTaskInput(target.id, JsonPrimitive("none")))
        }
        coEvery { projects.getById(target.id) } returns target
        coEvery { authentication.principal() } returns null
        assertFailsWith<IllegalStateException> {
            mutation.moveTaskToProject(authentication, task.id, MoveTaskInput(target.id, JsonPrimitive("none")))
        }
    }

    private fun release(id: UUID = UUID.random()) = Release(
        id = id,
        programId = UUID.random(),
        name = "6.0.0",
    )

    private fun program(id: UUID) = Program(
        id = id,
        portfolioId = UUID.random(),
        key = "PLATFORM",
        name = "Platform",
        ownerProfileId = UUID.random(),
    )

    private fun project() = Project(
        id = UUID.random(),
        programId = UUID.random(),
        key = "TARGET",
        name = "Target",
        ownerProfileId = UUID.random(),
    )

    private fun task() = Task(
        id = UUID.random(),
        key = "TASK-1",
        projectId = UUID.random(),
        taskTypeId = UUID.random(),
        statusId = UUID.random(),
        priorityId = UUID.random(),
        summary = "Task",
        reporterProfileId = UUID.random(),
        createdByPrincipalId = UUID.random(),
        modifiedByPrincipalId = UUID.random(),
    )
}
