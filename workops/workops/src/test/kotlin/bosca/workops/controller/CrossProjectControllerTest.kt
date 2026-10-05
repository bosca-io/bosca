package bosca.workops.controller

import bosca.git.model.Repository
import bosca.git.service.RepositoryAccessEvaluator
import bosca.git.service.RepositoryService
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.PermissionAction
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.workops.model.artifact.ReleaseDeclaredArtifact
import bosca.workops.model.environment.Environment
import bosca.workops.model.project.Program
import bosca.workops.model.project.Project
import bosca.workops.model.release.Release
import bosca.workops.model.release.ReleaseProjectVersion
import bosca.workops.model.release.ReleaseRunView
import bosca.workops.model.release.TaskAffectedProject
import bosca.workops.model.task.Task
import bosca.workops.service.CrossProjectMoveService
import bosca.workops.service.EnvironmentService
import bosca.workops.service.EnvironmentPermissionEvaluator
import bosca.workops.service.MoveWorkOpsTaskInput
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
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CrossProjectControllerTest {

    private val releaseService = mockk<ReleaseService>(relaxed = true)
    private val releasePipelineService = mockk<ReleasePipelineService>(relaxed = true)
    private val affectedService = mockk<TaskAffectedProjectService>(relaxed = true)
    private val moveService = mockk<CrossProjectMoveService>(relaxed = true)
    private val environmentService = mockk<EnvironmentService>(relaxed = true)
    private val environmentPermissions = mockk<EnvironmentPermissionEvaluator>(relaxed = true)
    private val repositoryService = mockk<RepositoryService>(relaxed = true)
    private val repositoryPermissions = mockk<RepositoryAccessEvaluator>(relaxed = true)
    private val programService = mockk<ProgramService>(relaxed = true)
    private val projectService = mockk<ProjectService>(relaxed = true)
    private val taskService = mockk<TaskService>(relaxed = true)
    private val programPermissions = mockk<ProgramPermissionEvaluator>(relaxed = true)
    private val projectPermissions = mockk<ProjectPermissionEvaluator>(relaxed = true)
    private val taskPermissions = mockk<TaskPermissionEvaluator>(relaxed = true)
    private val json = Json

    private val principalId = UUID.random()
    private val profileId = UUID.random()

    private fun authenticated(): AuthenticationContext = ImpersonatedAuthenticationContext(
        Principal(id = principalId, primaryProfileId = profileId),
        listOf(Group(UUID.random(), "users", "", GroupType.SYSTEM)),
    )

    private fun queryController() = CrossProjectQueryController(
        releaseService = releaseService,
        releasePipelineService = releasePipelineService,
        affectedService = affectedService,
        programService = programService,
        programPermissions = programPermissions,
        environmentService = environmentService,
        environmentPermissions = environmentPermissions,
        repositoryService = repositoryService,
        repositoryPermissions = repositoryPermissions,
        taskService = taskService,
        taskPermissions = taskPermissions,
    )

    private fun mutationController() = CrossProjectMutationController(
        releaseService = releaseService,
        releasePipelineService = releasePipelineService,
        affectedService = affectedService,
        moveService = moveService,
        programService = programService,
        projectService = projectService,
        taskService = taskService,
        environmentService = environmentService,
        environmentPermissions = environmentPermissions,
        repositoryService = repositoryService,
        repositoryPermissions = repositoryPermissions,
        programPermissions = programPermissions,
        projectPermissions = projectPermissions,
        taskPermissions = taskPermissions,
        json = json,
    )

    @Test
    fun `release and affected project queries return authorized cross project state`() = runTest {
        val program = sampleProgram()
        val release = sampleRelease(program.id)
        val releaseVersion = ReleaseProjectVersion(release.id, UUID.random(), UUID.random())
        val declaredArtifact = mockk<ReleaseDeclaredArtifact>()
        val releaseRun = ReleaseRunView(
            runId = UUID.random(),
            status = "RUNNING",
            startedAt = OffsetDateTime.now(),
        )
        val task = mockk<Task>()
        val taskId = UUID.random()
        val affected = TaskAffectedProject(taskId, UUID.random())
        coEvery { programService.getById(program.id) } returns program
        coEvery { releaseService.getById(release.id) } returns release
        coEvery { releaseService.list(program.id) } returns listOf(release)
        coEvery { releaseService.listVersions(release.id) } returns listOf(releaseVersion)
        coEvery { releasePipelineService.releaseChannelArtifactTypes(release.id) } returns listOf("DOCKER")
        coEvery { releasePipelineService.releaseDeclaredArtifacts(release.id) } returns listOf(declaredArtifact)
        coEvery { releasePipelineService.dependencyViolations(release.id) } returns listOf("provider missing")
        coEvery { releasePipelineService.runsForRelease(release.id, 20) } returns listOf(releaseRun)
        coEvery { taskService.getById(taskId) } returns task
        coEvery { affectedService.listForTask(taskId) } returns listOf(affected)

        assertEquals(listOf(release), queryController().releases(authenticated(), program.id))
        assertEquals(release, queryController().release(authenticated(), release.id))
        assertEquals(listOf(releaseVersion), queryController().versionsForRelease(authenticated(), release.id))
        assertEquals(listOf("DOCKER"), queryController().releaseChannelArtifactTypes(authenticated(), release.id))
        assertEquals(listOf(declaredArtifact), queryController().releaseDeclaredArtifacts(authenticated(), release.id))
        assertEquals(listOf("provider missing"), queryController().releaseReadiness(authenticated(), release.id))
        assertEquals(listOf(releaseRun), queryController().releaseRuns(authenticated(), release.id))
        assertEquals(listOf(affected), queryController().affectedProjects(authenticated(), taskId))

        coVerify(exactly = 7) { programPermissions.verifyAllowed(any(), program, PermissionAction.VIEW) }
        coVerify(exactly = 1) { taskPermissions.verifyAllowed(any(), task, PermissionAction.VIEW) }
    }

    @Test
    fun `cross project queries fail closed when release task or program is absent`() = runTest {
        val missingId = UUID.random()
        coEvery { programService.getById(missingId) } returns null
        coEvery { releaseService.getById(missingId) } returns null
        coEvery { taskService.getById(missingId) } returns null

        assertTrue(queryController().releases(authenticated(), missingId).isEmpty())
        assertNull(queryController().release(authenticated(), missingId))
        assertTrue(queryController().versionsForRelease(authenticated(), missingId).isEmpty())
        assertTrue(queryController().releaseChannelArtifactTypes(authenticated(), missingId).isEmpty())
        assertTrue(queryController().releaseDeclaredArtifacts(authenticated(), missingId).isEmpty())
        assertTrue(queryController().releaseReadiness(authenticated(), missingId).isEmpty())
        assertTrue(queryController().releaseRuns(authenticated(), missingId).isEmpty())
        assertTrue(queryController().affectedProjects(authenticated(), missingId).isEmpty())

        val orphanRelease = sampleRelease(missingId).copy(id = UUID.random())
        coEvery { releaseService.getById(orphanRelease.id) } returns orphanRelease
        assertNull(queryController().release(authenticated(), orphanRelease.id))
        assertTrue(queryController().versionsForRelease(authenticated(), orphanRelease.id).isEmpty())
        assertTrue(queryController().releaseChannelArtifactTypes(authenticated(), orphanRelease.id).isEmpty())
        assertTrue(queryController().releaseDeclaredArtifacts(authenticated(), orphanRelease.id).isEmpty())
        assertTrue(queryController().releaseReadiness(authenticated(), orphanRelease.id).isEmpty())
        assertTrue(queryController().releaseRuns(authenticated(), orphanRelease.id).isEmpty())
    }

    @Test
    fun `release lifecycle verifies program management and delegates every transition`() = runTest {
        val program = sampleProgram()
        val release = sampleRelease(program.id)
        val updated = release.copy(name = "1.5.0", description = "Updated", version = 2)
        val input = CreateReleaseInput(
            programId = program.id,
            name = release.name,
            description = release.description,
            releaseDate = release.releaseDate,
            ownerProfileId = release.ownerProfileId,
        )
        val updateInput = UpdateReleaseInput(updated.name, updated.description, updated.releaseDate)
        val projectId = UUID.random()
        val versionId = UUID.random()
        coEvery { programService.getById(program.id) } returns program
        coEvery {
            releaseService.create(program.id, input.name, input.description, input.releaseDate, input.ownerProfileId)
        } returns release
        coEvery { releaseService.getById(release.id) } returns release
        coEvery {
            releaseService.update(release.id, updateInput.name, updateInput.description, updateInput.releaseDate, 1)
        } returns updated
        coEvery { releaseService.release(release.id, 2) } returns updated.copy(releasedAt = OffsetDateTime.now())
        coEvery { releasePipelineService.launch(release.id, any(), any()) } returns true
        coEvery { releasePipelineService.rollbackArtifacts(release.id, any()) } returns false

        assertEquals(release, mutationController().createRelease(authenticated(), input))
        assertEquals(updated, mutationController().updateRelease(authenticated(), release.id, updateInput, 1))
        assertTrue(mutationController().bundleVersion(authenticated(), release.id, projectId, versionId))
        assertTrue(mutationController().unbundleVersion(authenticated(), release.id, versionId))
        assertEquals(2, mutationController().markReleased(authenticated(), release.id, 2).version)
        assertTrue(mutationController().launchRelease(authenticated(), release.id))
        assertFalse(mutationController().rollbackReleaseArtifacts(authenticated(), release.id))
        assertTrue(mutationController().deleteRelease(authenticated(), release.id))

        coVerify(exactly = 1) { releaseService.bundle(release.id, projectId, versionId) }
        coVerify(exactly = 1) { releaseService.unbundle(release.id, versionId) }
        coVerify(exactly = 1) { releaseService.delete(release.id) }
        coVerify(exactly = 8) { programPermissions.verifyAllowed(any(), program, PermissionAction.MANAGE) }
    }

    @Test
    fun `release launch and promotion verify repository edits and execution and target execution`() = runTest {
        val program = sampleProgram()
        val release = sampleRelease(program.id)
        val repository = Repository(
            id = UUID.random(),
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
        coEvery { releaseService.getById(release.id) } returns release
        coEvery { programService.getById(program.id) } returns program
        coEvery { releasePipelineService.repositoryIdsForRelease(release.id) } returns listOf(repository.id)
        coEvery { repositoryService.findById(repository.id) } returns repository
        coEvery {
            repositoryPermissions.isAllowed(any<AuthenticationContext>(), repository, PermissionAction.EDIT)
        } returns true
        coEvery {
            repositoryPermissions.isAllowed(any<AuthenticationContext>(), repository, PermissionAction.EXECUTE)
        } returns true
        coEvery { environmentService.getByProgramAndKey(program.id, "Production") } returns null
        coEvery { environmentService.getByProgramAndName(program.id, "Production") } returns environment
        coEvery { releasePipelineService.launch(release.id, any(), any()) } returns true
        val promotedEnvironment = slot<String>()
        coEvery {
            releasePipelineService.promote(release.id, capture(promotedEnvironment), false, any(), any())
        } returns true

        assertTrue(mutationController().launchRelease(authenticated(), release.id))
        assertTrue(
            mutationController().promoteRelease(
                authenticated(),
                release.id,
                PromoteReleaseInput(environment = " Production "),
            ),
        )
        coEvery { environmentService.getByProgramAndKey(program.id, environment.key) } returns environment
        assertTrue(
            mutationController().promoteRelease(
                authenticated(),
                release.id,
                PromoteReleaseInput(
                    environment = environment.key,
                    inputs = buildJsonObject {
                        put("approved", true)
                        put("replicas", 3)
                        put("channel", "stable")
                    },
                ),
            ),
        )
        assertEquals(environment.key, promotedEnvironment.captured)
        coVerify(exactly = 1) {
            releasePipelineService.promote(
                release.id,
                environment.key,
                false,
                mapOf("approved" to "true", "replicas" to "3", "channel" to "stable"),
                any(),
            )
        }

        assertFailsWith<IllegalStateException> {
            mutationController().promoteRelease(
                authenticated(),
                release.id,
                PromoteReleaseInput(
                    environment = environment.key,
                    inputs = buildJsonObject { put("nested", buildJsonObject {}) },
                ),
            )
        }

        coVerify(exactly = 4) {
            repositoryPermissions.isAllowed(any<AuthenticationContext>(), repository, PermissionAction.EDIT)
        }
        coVerify(exactly = 4) {
            repositoryPermissions.isAllowed(any<AuthenticationContext>(), repository, PermissionAction.EXECUTE)
        }
        coVerify(exactly = 3) {
            environmentPermissions.verifyAllowed(any(), environment, PermissionAction.EXECUTE)
        }
    }

    @Test
    fun `release management and repository edit cannot start builds without execution permission`() = runTest {
        val program = sampleProgram()
        val release = sampleRelease(program.id)
        val repository = Repository(id = UUID.random(), slug = "server", name = "Server", ownerId = UUID.random())
        coEvery { releaseService.getById(release.id) } returns release
        coEvery { programService.getById(program.id) } returns program
        coEvery { releasePipelineService.repositoryIdsForRelease(release.id) } returns listOf(repository.id)
        coEvery { repositoryService.findById(repository.id) } returns repository
        coEvery { repositoryPermissions.isAllowed(any(), repository, PermissionAction.EDIT) } returns true
        coEvery { repositoryPermissions.isAllowed(any(), repository, PermissionAction.EXECUTE) } returns false

        assertFailsWith<SecurityException> { mutationController().launchRelease(authenticated(), release.id) }
        assertFailsWith<SecurityException> {
            mutationController().promoteRelease(authenticated(), release.id, PromoteReleaseInput("production"))
        }
        coVerify(exactly = 0) { releasePipelineService.launch(any(), any(), any()) }
        coVerify(exactly = 0) { releasePipelineService.promote(any(), any(), any(), any(), any()) }

        val access = queryController().releaseActionAccess(authenticated(), release.id)
        assertNotNull(access)
        assertTrue(access.repositories.single().canEdit)
        assertFalse(access.repositories.single().canExecute)
    }


    @Test
    fun `affected project changes and task move preserve only valid typed mappings`() = runTest {
        val task = mockk<Task>()
        val movedTask = mockk<Task>()
        val taskId = UUID.random()
        val movedTaskId = UUID.random()
        val project = sampleProject()
        val sourceStatusId = UUID.random()
        val targetStatusId = UUID.random()
        val captured = slot<MoveWorkOpsTaskInput>()
        coEvery { taskService.getById(taskId) } returns task
        coEvery { projectService.getById(project.id) } returns project
        coEvery { moveService.moveTaskToProject(taskId, capture(captured), principalId) } returns movedTask
        coEvery { movedTask.id } returns movedTaskId
        val input = MoveTaskInput(
            targetProjectId = project.id,
            statusMapping = JsonObject(
                mapOf(
                    sourceStatusId.toString() to JsonPrimitive(targetStatusId.toString()),
                    "not-a-uuid" to JsonPrimitive(targetStatusId.toString()),
                    UUID.random().toString() to JsonObject(emptyMap()),
                    UUID.random().toString() to JsonPrimitive("not-a-uuid"),
                ),
            ),
            fieldMapping = JsonObject(
                mapOf(
                    "old-field" to JsonPrimitive("new-field"),
                    "ignored" to JsonObject(emptyMap()),
                ),
            ),
        )

        assertTrue(mutationController().addAffectedProject(authenticated(), taskId, project.id))
        assertTrue(mutationController().removeAffectedProject(authenticated(), taskId, project.id))
        assertEquals(movedTaskId, mutationController().moveTaskToProject(authenticated(), taskId, input))
        assertEquals(project.id, captured.captured.targetProjectId)
        assertEquals(mapOf(sourceStatusId to targetStatusId), captured.captured.statusMapping)
        assertEquals(mapOf("old-field" to "new-field"), captured.captured.fieldMapping)

        coVerify(exactly = 1) { affectedService.add(taskId, project.id) }
        coVerify(exactly = 1) { affectedService.remove(taskId, project.id) }
        coVerify(exactly = 2) { taskPermissions.verifyAllowed(any(), task, PermissionAction.EDIT) }
        coVerify(exactly = 1) { taskPermissions.verifyAllowed(any(), task, PermissionAction.MANAGE) }
        coVerify(exactly = 1) { projectPermissions.verifyAllowed(any(), project, PermissionAction.MANAGE) }
    }

    @Test
    fun `non object task move mappings become empty maps`() = runTest {
        val task = mockk<Task>()
        val movedTask = mockk<Task>()
        val taskId = UUID.random()
        val movedTaskId = UUID.random()
        val project = sampleProject()
        val captured = slot<MoveWorkOpsTaskInput>()
        coEvery { taskService.getById(taskId) } returns task
        coEvery { projectService.getById(project.id) } returns project
        coEvery { moveService.moveTaskToProject(taskId, capture(captured), principalId) } returns movedTask
        coEvery { movedTask.id } returns movedTaskId

        assertEquals(
            movedTaskId,
            mutationController().moveTaskToProject(
                authenticated(),
                taskId,
                MoveTaskInput(project.id, JsonArray(emptyList()), JsonArray(emptyList())),
            ),
        )
        assertTrue(captured.captured.statusMapping.isEmpty())
        assertTrue(captured.captured.fieldMapping.isEmpty())
    }

    @Test
    fun `cross project mutations fail closed when scoped records or principal are absent`() = runTest {
        val missingId = UUID.random()
        val input = CreateReleaseInput(missingId, "1.4.0")
        coEvery { programService.getById(missingId) } returns null
        coEvery { releaseService.getById(missingId) } returns null
        coEvery { taskService.getById(missingId) } returns null
        assertFailsWith<IllegalStateException> { mutationController().createRelease(authenticated(), input) }
        assertFailsWith<IllegalStateException> {
            mutationController().updateRelease(authenticated(), missingId, UpdateReleaseInput("1.4.1"), 0)
        }
        assertFalse(mutationController().deleteRelease(authenticated(), missingId))
        assertFailsWith<IllegalStateException> {
            mutationController().bundleVersion(authenticated(), missingId, UUID.random(), UUID.random())
        }
        assertFailsWith<IllegalStateException> {
            mutationController().unbundleVersion(authenticated(), missingId, UUID.random())
        }
        assertFailsWith<IllegalStateException> { mutationController().markReleased(authenticated(), missingId, 0) }
        assertFailsWith<IllegalStateException> { mutationController().launchRelease(authenticated(), missingId) }
        assertFailsWith<IllegalStateException> {
            mutationController().promoteRelease(
                authenticated(), missingId, PromoteReleaseInput("production"),
            )
        }
        assertFailsWith<IllegalStateException> { mutationController().rollbackReleaseArtifacts(authenticated(), missingId) }
        assertFailsWith<IllegalStateException> {
            mutationController().addAffectedProject(authenticated(), missingId, UUID.random())
        }
        assertFailsWith<IllegalStateException> {
            mutationController().removeAffectedProject(authenticated(), missingId, UUID.random())
        }
        assertFailsWith<IllegalStateException> {
            mutationController().moveTaskToProject(
                authenticated(), missingId, MoveTaskInput(UUID.random(), JsonObject(emptyMap())),
            )
        }

        val orphanRelease = sampleRelease(missingId).copy(id = UUID.random())
        coEvery { releaseService.getById(orphanRelease.id) } returns orphanRelease
        assertFailsWith<IllegalStateException> {
            mutationController().updateRelease(
                authenticated(), orphanRelease.id, UpdateReleaseInput("1.4.1"), orphanRelease.version,
            )
        }
        assertFailsWith<IllegalStateException> { mutationController().deleteRelease(authenticated(), orphanRelease.id) }
        assertFailsWith<IllegalStateException> {
            mutationController().bundleVersion(authenticated(), orphanRelease.id, UUID.random(), UUID.random())
        }
        assertFailsWith<IllegalStateException> {
            mutationController().unbundleVersion(authenticated(), orphanRelease.id, UUID.random())
        }
        assertFailsWith<IllegalStateException> {
            mutationController().markReleased(authenticated(), orphanRelease.id, orphanRelease.version)
        }
        assertFailsWith<IllegalStateException> { mutationController().launchRelease(authenticated(), orphanRelease.id) }
        assertFailsWith<IllegalStateException> {
            mutationController().promoteRelease(
                authenticated(), orphanRelease.id, PromoteReleaseInput("production"),
            )
        }
        assertFailsWith<IllegalStateException> {
            mutationController().rollbackReleaseArtifacts(authenticated(), orphanRelease.id)
        }

        val program = sampleProgram()
        val release = sampleRelease(program.id)
        coEvery { releaseService.getById(release.id) } returns release
        coEvery { programService.getById(program.id) } returns program
        coEvery { releasePipelineService.repositoryIdsForRelease(release.id) } returns emptyList()
        coEvery { environmentService.getByProgramAndKey(program.id, "missing") } returns null
        coEvery { environmentService.getByProgramAndName(program.id, "missing") } returns null
        assertFailsWith<IllegalStateException> {
            mutationController().promoteRelease(
                authenticated(), release.id, PromoteReleaseInput("missing"),
            )
        }

        val task = mockk<Task>()
        val taskId = UUID.random()
        val project = sampleProject()
        coEvery { taskService.getById(taskId) } returns task
        coEvery { projectService.getById(project.id) } returns project
        assertFailsWith<IllegalStateException> {
            mutationController().moveTaskToProject(
                AuthenticationContext(null, null), taskId, MoveTaskInput(project.id, JsonObject(emptyMap())),
            )
        }

        val missingProjectId = UUID.random()
        coEvery { projectService.getById(missingProjectId) } returns null
        assertFailsWith<IllegalStateException> {
            mutationController().moveTaskToProject(
                authenticated(), taskId, MoveTaskInput(missingProjectId, JsonObject(emptyMap())),
            )
        }
    }

    @Test
    fun `release type resolves its deployments`() = runTest {
        val release = sampleRelease(UUID.random())
        coEvery { environmentService.deploymentsByRelease(release.id) } returns emptyList()

        assertTrue(ReleaseTypeController(environmentService).environmentDeployments(release).isEmpty())
    }

    private fun sampleProgram(): Program = Program(
        id = UUID.random(),
        portfolioId = UUID.random(),
        key = "PROGRAM",
        name = "Program",
        ownerProfileId = profileId,
    )

    private fun sampleProject(): Project = Project(
        id = UUID.random(),
        programId = UUID.random(),
        key = "PROJECT",
        name = "Project",
        ownerProfileId = profileId,
    )

    private fun sampleRelease(programId: UUID): Release = Release(
        id = UUID.random(),
        programId = programId,
        name = "1.4.0",
        description = "Release",
        releaseDate = OffsetDateTime.now(),
        ownerProfileId = profileId,
        version = 1,
    )
}
