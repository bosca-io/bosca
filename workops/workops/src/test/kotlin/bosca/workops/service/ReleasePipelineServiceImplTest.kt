package bosca.workops.service

import bosca.git.model.ArtifactDefinition
import bosca.git.model.JobDefinition
import bosca.git.model.EnvironmentDefinition
import bosca.git.model.Pipeline
import bosca.git.model.PipelineDefinition
import bosca.git.model.PipelineExecutionPlan
import bosca.git.model.PipelineRequirement
import bosca.git.model.PipelineRun
import bosca.git.model.PipelineRunStatus
import bosca.git.model.PipelineTrigger
import bosca.git.model.PipelineTriggerType
import bosca.git.model.Repository
import bosca.git.model.StepDefinition
import bosca.git.model.TriggerInput
import bosca.git.service.PipelineRunService
import bosca.git.service.RepositoryBrowseService
import bosca.git.service.RepositoryService
import bosca.git.service.ReleaseDeployer
import bosca.git.service.ReleaseDeployOutcome
import bosca.git.service.ReleaseRollbackRequest
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.workops.model.release.Release
import bosca.workops.model.artifact.ArtifactType
import bosca.workops.deploy.DeployConfigService
import bosca.workops.deploy.DeployTargetKind
import bosca.workops.deploy.DeployTargetEntry
import bosca.workops.deploy.EnvironmentDeployConfig
import bosca.workops.model.environment.Environment
import bosca.workops.model.environment.EnvironmentDeployment
import bosca.workops.model.environment.EnvironmentDeploymentStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.slot
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import java.time.OffsetDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [ReleasePipelineServiceImpl] creates and correlates native git-ci release runs.
 */
class ReleasePipelineServiceImplTest {

    private val runService = mockk<PipelineRunService>()
    private val releaseService = mockk<ReleaseService>()
    private val dependencyService = mockk<DependencyDeclarationService>()
    private val projectService = mockk<ProjectService>()
    private val versionService = mockk<VersionService>()
    private val publicationService = mockk<ArtifactPublicationService>(relaxed = true)
    private val projectRepositories = mockk<ProjectRepositoryService>()
    private val repositoryWrite = mockk<bosca.git.service.RepositoryWriteService>(relaxed = true)
    private val gitPipelines = mockk<bosca.git.service.PipelineService>(relaxed = true)
    private val repositoryService = mockk<RepositoryService>()
    private val repositoryBrowse = mockk<RepositoryBrowseService>()
    private val environmentService = mockk<EnvironmentService>()
    private val deployConfigService = mockk<DeployConfigService>()
    private val releaseDeployer = mockk<ReleaseDeployer>()

    private val service = ReleasePipelineServiceImpl(
        runService = runService,
        releaseService = releaseService,
        dependencyService = dependencyService,
        projectService = projectService,
        versionService = versionService,
        publicationService = publicationService,
        projectRepositories = projectRepositories,
        repositoryWrite = repositoryWrite,
        gitPipelines = gitPipelines,
        repositoryService = repositoryService,
        repositoryBrowse = repositoryBrowse,
        environmentService = environmentService,
        deployConfigService = deployConfigService,
        releaseDeployer = releaseDeployer,
    )

    private val programId = UUID.random()
    private val releaseId = UUID.random()
    private val repositoryId = UUID.random()
    private val pipelineId = UUID.random()
    private val actorId = UUID.random()

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction(any<suspend () -> Any?>()) } coAnswers {
            firstArg<suspend () -> Any?>().invoke()
        }
        // Bundled projects have a buildable repo unless a test says otherwise.
        coEvery { projectRepositories.list(any()) } returns listOf(mockk { every { repositoryId } returns this@ReleasePipelineServiceImplTest.repositoryId })
        coEvery { repositoryService.findById(repositoryId) } returns Repository(
            id = repositoryId,
            slug = "workspace",
            name = "Workspace",
            ownerId = UUID.random(),
        )
        coEvery { repositoryBrowse.resolveRef(repositoryId, "refs/heads/main") } returns "abc123"
        coEvery { gitPipelines.findByRepository(repositoryId) } returns listOf(
            Pipeline(repositoryId = repositoryId, filePath = ".bosca/pipelines/release.yaml", name = "Release", configHash = "hash", id = pipelineId)
        )
        coEvery { gitPipelines.parseDefinition(repositoryId, "abc123", ".bosca/pipelines/release.yaml") } returns releaseDefinition()
        every { runService.plan(any(), any(), any(), any()) } returns
            PipelineExecutionPlan(emptyMap(), mapOf("release" to JobDefinition()))
        coEvery { runService.createRun(any(), any(), any(), any(), any(), any(), any(), any()) } returns gitRun()
    }

    @AfterTest
    fun teardown() = unmockkStatic("bosca.db.ConnectionManagerKt")

    private fun release(): Release {
        val pid = programId
        val rid = releaseId
        return mockk {
            every { id } returns rid
            every { programId } returns pid
            every { name } returns "6.0.0"
            every { ownerProfileId } returns null
        }
    }

    private fun authenticated(): AuthenticationContext = mockk {
        every { principal() } returns mockk { every { id } returns actorId }
    }

    private fun releaseDefinition() = PipelineDefinition(
        name = "Release",
        triggers = listOf(
            PipelineTrigger(type = PipelineTriggerType.RELEASE),
            PipelineTrigger(
                type = PipelineTriggerType.PROMOTION,
                environments = listOf("production"),
                inputs = mapOf("phasedRelease" to TriggerInput(type = "boolean", default = "false")),
            ),
        ),
        jobs = mapOf("release" to JobDefinition()),
    )

    private fun gitRun(
        status: PipelineRunStatus = PipelineRunStatus.QUEUED,
        created: OffsetDateTime = OffsetDateTime.now(),
        started: OffsetDateTime? = null,
        finished: OffsetDateTime? = null,
        id: UUID = UUID.random(),
    ) = PipelineRun(
        id = id,
        pipelineId = pipelineId,
        repositoryId = repositoryId,
        commitSha = "abc123",
        ref = "refs/heads/main",
        triggerType = PipelineTriggerType.RELEASE,
        triggeredBy = actorId,
        status = status,
        created = created,
        started = started,
        finished = finished,
    )

    @Test
    fun `launch creates a native release run with explicit release parameters`() = runTest {
        val projectId = UUID.random()
        val versionId = UUID.random()
        coEvery { releaseService.getById(releaseId) } returns release()
        coEvery { releaseService.listVersions(releaseId) } returns listOf(releaseVersion(projectId, versionId))
        coEvery { versionService.getById(versionId) } returns version("6.0.0")
        coEvery { dependencyService.listByConsumer(projectId) } returns emptyList()

        assertTrue(
            service.launch(
                releaseId,
                mapOf("inputs.undeclared" to "prefixed", "undeclared" to "normalized"),
                authenticated(),
            ),
        )
        coVerify(exactly = 1) {
            runService.createRun(
                pipelineId, repositoryId, any(), "abc123", "refs/heads/main",
                PipelineTriggerType.RELEASE, actorId,
                match {
                    it["release.id"] == releaseId.toString() &&
                        it["release.version"] == "6.0.0" &&
                        it["release.projects"] == "workspace"
                },
            )
        }
    }

    @Test
    fun `promote creates a promotion run with environment downgrade and declared input parameters`() = runTest {
        val projectId = UUID.random()
        val versionId = UUID.random()
        coEvery { releaseService.getById(releaseId) } returns release()
        coEvery { releaseService.listVersions(releaseId) } returns listOf(releaseVersion(projectId, versionId))
        coEvery { versionService.getById(versionId) } returns version("6.0.0")
        coEvery { repositoryBrowse.resolveRef(repositoryId, "refs/tags/6.0.0") } returns "abc123"

        assertTrue(service.promote(releaseId, "production", false, mapOf("phasedRelease" to "true"), authenticated()))

        coVerify(exactly = 1) {
            runService.createRun(
                pipelineId, repositoryId, any(), "abc123", "refs/tags/6.0.0",
                PipelineTriggerType.PROMOTION, actorId,
                match {
                    it["release.id"] == releaseId.toString() &&
                        it["release.version"] == "6.0.0" &&
                        it["promotion.environment"] == "production" &&
                        it["promotion.allowDowngrade"] == "false" &&
                        it["inputs.phasedRelease"] == "true"
                },
            )
        }
    }

    @Test
    fun `launch preflights every repository before creating any run`() = runTest {
        val projectId = UUID.random()
        val versionId = UUID.random()
        val secondRepositoryId = UUID.random()
        val secondPipelineId = UUID.random()
        coEvery { releaseService.getById(releaseId) } returns release()
        coEvery { releaseService.listVersions(releaseId) } returns listOf(releaseVersion(projectId, versionId))
        coEvery { versionService.getById(versionId) } returns version("6.0.0")
        coEvery { dependencyService.listByConsumer(projectId) } returns emptyList()
        coEvery { projectRepositories.list(projectId) } returns listOf(
            mockk { every { repositoryId } returns this@ReleasePipelineServiceImplTest.repositoryId },
            mockk { every { repositoryId } returns secondRepositoryId },
        )
        coEvery { repositoryService.findById(secondRepositoryId) } returns Repository(
            id = secondRepositoryId,
            slug = "web",
            name = "Web",
            ownerId = UUID.random(),
        )
        coEvery { repositoryBrowse.resolveRef(secondRepositoryId, "refs/heads/main") } returns "def456"
        coEvery { gitPipelines.findByRepository(secondRepositoryId) } returns listOf(
            Pipeline(
                id = secondPipelineId,
                repositoryId = secondRepositoryId,
                filePath = ".bosca/pipelines/release.yaml",
                name = "Release",
                configHash = "hash-2",
            ),
        )
        coEvery {
            gitPipelines.parseDefinition(secondRepositoryId, "def456", ".bosca/pipelines/release.yaml")
        } returns null

        assertFailsWith<IllegalStateException> { service.launch(releaseId, authenticated()) }

        coVerify(exactly = 0) {
            runService.createRun(any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `plansForRelease projects exact git-ci jobs in dependency order for release and promotion`() = runTest {
        val projectId = UUID.random()
        val versionId = UUID.random()
        val definition = PipelineDefinition(
            name = "Platform release",
            triggers = listOf(
                PipelineTrigger(
                    type = PipelineTriggerType.RELEASE,
                    inputs = mapOf("notes" to TriggerInput(description = "Release note", default = "standard")),
                ),
                PipelineTrigger(
                    type = PipelineTriggerType.PROMOTION,
                    environments = listOf("production"),
                ),
            ),
            environments = mapOf(
                "production" to EnvironmentDefinition(promotesFrom = "staging", approval = true),
            ),
            jobs = emptyMap(),
        )
        val build = JobDefinition(steps = listOf(StepDefinition("Build", uses = "tag")))
        val deploy = JobDefinition(
            needs = listOf("build"),
            environment = "production",
            approval = true,
            pipelineRequires = listOf(PipelineRequirement("Upstream", "server")),
            steps = listOf(StepDefinition("Deploy", uses = "deploy")),
        )
        coEvery { releaseService.getById(releaseId) } returns release()
        coEvery { releaseService.listVersions(releaseId) } returns listOf(releaseVersion(projectId, versionId))
        coEvery { versionService.getById(versionId) } returns version("6.0.0")
        coEvery { gitPipelines.parseDefinition(repositoryId, "abc123", any()) } returns definition
        every {
            runService.plan(definition, "refs/heads/main", PipelineTriggerType.RELEASE, any())
        } returns PipelineExecutionPlan(emptyMap(), linkedMapOf("build" to build))
        every {
            runService.plan(definition, "refs/heads/main", PipelineTriggerType.PROMOTION, any())
        } returns PipelineExecutionPlan(emptyMap(), linkedMapOf("deploy" to deploy, "build" to build))

        val plans = service.plansForRelease(releaseId)

        assertEquals(listOf("RELEASE", "PROMOTION"), plans.map { it.triggerType })
        assertEquals("notes", plans.first().inputs.single().name)
        assertEquals("production", plans.last().environment)
        assertEquals("staging", plans.last().promotesFrom)
        assertEquals(listOf("build", "deploy"), plans.last().jobs.map { it.key })
        assertEquals(listOf(0, 1), plans.last().jobs.map { it.depth })
        assertTrue(plans.last().jobs.last().approvalRequired)
        assertEquals("pipeline server/Upstream@this ref", plans.last().jobs.last().requirements.single())
        verify {
            runService.plan(
                definition,
                "refs/heads/main",
                PipelineTriggerType.PROMOTION,
                match { it["promotion.environment"] == "production" && it["release.version"] == "6.0.0" },
            )
        }
    }

    @Test
    fun `startPatchRelease bumps and bundles only selected project versions`() = runTest {
        val selectedProject = UUID.random()
        val sourceVersionId = UUID.random()
        val patchVersionId = UUID.random()
        val source = Release(
            id = releaseId,
            programId = programId,
            name = "6.0.0",
            ownerProfileId = UUID.random(),
        )
        val sourceVersion = bosca.workops.model.version.Version(
            id = sourceVersionId,
            projectId = selectedProject,
            name = "6.0.0",
            sequenceNumber = 1,
        )
        val patchVersion = sourceVersion.copy(id = patchVersionId, name = "6.0.1", sequenceNumber = 2)
        val patchRelease = source.copy(id = UUID.random(), name = "6.0.1")
        coEvery { releaseService.getById(releaseId) } returns source
        coEvery { releaseService.listVersions(releaseId) } returns listOf(releaseVersion(selectedProject, sourceVersionId))
        coEvery { versionService.getById(sourceVersionId) } returns sourceVersion
        coEvery { versionService.listByProject(selectedProject) } returns listOf(sourceVersion)
        coEvery { versionService.create(match { it.projectId == selectedProject && it.name == "6.0.1" }) } returns patchVersion
        coEvery {
            releaseService.create(programId, "6.0.1", any(), null, source.ownerProfileId)
        } returns patchRelease
        coEvery { releaseService.bundle(patchRelease.id, selectedProject, patchVersionId) } returns
            releaseVersion(selectedProject, patchVersionId)

        val result = service.startPatchRelease(releaseId, listOf(selectedProject), authenticated())

        assertEquals(patchRelease, result)
        coVerify(exactly = 1) { releaseService.bundle(patchRelease.id, selectedProject, patchVersionId) }
    }

    @Test
    fun `launch fails when a BUILD dependency has no provider in the release and no satisfying released version`() = runTest {
        val apiProject = UUID.random()
        val sharedProject = UUID.random()
        val apiVersion = UUID.random()
        coEvery { releaseService.getById(releaseId) } returns release()
        coEvery { releaseService.listVersions(releaseId) } returns listOf(releaseVersion(apiProject, apiVersion))
        coEvery { versionService.getById(apiVersion) } returns version("10.0.0")
        coEvery { dependencyService.listByConsumer(apiProject) } returns listOf(
            declaration(apiProject, sharedProject, constraint = "*"),
        )
        coEvery { versionService.listByProject(sharedProject) } returns emptyList()
        coEvery { projectService.getById(apiProject) } returns projectRef("API", "Api Service")
        coEvery { projectService.getById(sharedProject) } returns projectRef("SHARED", "Shared Library")

        val e = try {
            service.launch(releaseId, authenticated())
            error("launch should have failed")
        } catch (expected: IllegalStateException) {
            expected
        }
        assertTrue("API (Api Service) depends on SHARED (Shared Library)" in (e.message ?: ""), e.message)
        coVerify(exactly = 0) { runService.createRun(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a BUILD dependency already satisfied by a released provider version does not block`() = runTest {
        val apiProject = UUID.random()
        val sharedProject = UUID.random()
        val apiVersion = UUID.random()
        coEvery { releaseService.getById(releaseId) } returns release()
        coEvery { releaseService.listVersions(releaseId) } returns listOf(releaseVersion(apiProject, apiVersion))
        coEvery { versionService.getById(apiVersion) } returns version("10.0.0")
        coEvery { dependencyService.listByConsumer(apiProject) } returns listOf(
            declaration(apiProject, sharedProject, constraint = "6.0.*"),
        )
        coEvery { versionService.listByProject(sharedProject) } returns listOf(
            version("6.0.5", released = true),
        )

        assertTrue(service.launch(releaseId, authenticated()))
    }

    @Test
    fun `a provider in the release satisfies a plain BUILD dependency`() = runTest {
        val apiProject = UUID.random()
        val sharedProject = UUID.random()
        val apiVersion = UUID.random()
        val sharedVersion = UUID.random()
        coEvery { releaseService.getById(releaseId) } returns release()
        coEvery { releaseService.listVersions(releaseId) } returns listOf(
            releaseVersion(apiProject, apiVersion), releaseVersion(sharedProject, sharedVersion),
        )
        coEvery { versionService.getById(apiVersion) } returns version("10.0.0")
        coEvery { versionService.getById(sharedVersion) } returns version("10.0.0")
        coEvery { dependencyService.listByConsumer(apiProject) } returns listOf(
            declaration(apiProject, sharedProject, constraint = "*"),
        )
        coEvery { dependencyService.listByConsumer(sharedProject) } returns emptyList()

        assertTrue(service.launch(releaseId, authenticated()))
    }

    @Test
    fun `the match constraint requires the provider at the consumer's own version`() = runTest {
        val serverProject = UUID.random()
        val workspaceProject = UUID.random()
        val serverVersion = UUID.random()
        val workspaceVersion = UUID.random()
        coEvery { releaseService.getById(releaseId) } returns release()
        coEvery { releaseService.listVersions(releaseId) } returns listOf(
            releaseVersion(serverProject, serverVersion), releaseVersion(workspaceProject, workspaceVersion),
        )
        coEvery { versionService.getById(serverVersion) } returns version("10.0.0")
        // Workspace is in the release, but at the WRONG version for `match`.
        coEvery { versionService.getById(workspaceVersion) } returns version("9.9.0")
        coEvery { dependencyService.listByConsumer(serverProject) } returns listOf(
            declaration(serverProject, workspaceProject, constraint = "match"),
        )
        coEvery { dependencyService.listByConsumer(workspaceProject) } returns emptyList()
        coEvery { versionService.listByProject(workspaceProject) } returns emptyList()
        coEvery { projectService.getById(serverProject) } returns projectRef("SRV", "Server")
        coEvery { projectService.getById(workspaceProject) } returns projectRef("WS", "Workspace")

        val e = try {
            service.launch(releaseId, authenticated())
            error("launch should have failed")
        } catch (expected: IllegalStateException) {
            expected
        }
        assertTrue("version 10.0.0" in (e.message ?: ""), e.message)
    }

    @Test
    fun `the match constraint passes when a released provider version equals the consumer's version`() = runTest {
        val serverProject = UUID.random()
        val workspaceProject = UUID.random()
        val serverVersion = UUID.random()
        coEvery { releaseService.getById(releaseId) } returns release()
        coEvery { releaseService.listVersions(releaseId) } returns listOf(releaseVersion(serverProject, serverVersion))
        coEvery { versionService.getById(serverVersion) } returns version("10.0.0")
        coEvery { dependencyService.listByConsumer(serverProject) } returns listOf(
            declaration(serverProject, workspaceProject, constraint = "match"),
        )
        coEvery { versionService.listByProject(workspaceProject) } returns listOf(
            version("10.0.0", released = true), version("9.0.0", released = true),
        )

        assertTrue(service.launch(releaseId, authenticated()))
    }

    @Test
    fun `a bundled project with no repositories blocks the launch`() = runTest {
        val projectId = UUID.random()
        val vId = UUID.random()
        coEvery { releaseService.getById(releaseId) } returns release()
        coEvery { releaseService.listVersions(releaseId) } returns listOf(releaseVersion(projectId, vId))
        coEvery { versionService.getById(vId) } returns version("6.0.0")
        coEvery { projectRepositories.list(projectId) } returns emptyList()
        coEvery { dependencyService.listByConsumer(projectId) } returns emptyList()
        coEvery { projectService.getById(projectId) } returns projectRef("SRV", "Server")

        val e = try {
            service.launch(releaseId, authenticated())
            error("launch should have failed")
        } catch (expected: IllegalStateException) {
            expected
        }
        assertTrue("no git repositories attached" in (e.message ?: ""), e.message)
        coVerify(exactly = 0) { runService.createRun(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `rollback deletes tags and publications and resets deployments`() = runTest {
        val projectId = UUID.random()
        val vId = UUID.random()
        val repoId = UUID.random()
        val publicationId = UUID.random()
        val rel = release()
        every { rel.releasedAt } returns null
        coEvery { releaseService.getById(releaseId) } returns rel
        coEvery { runService.findByReleaseId(releaseId, 0, 20) } returns emptyList()
        coEvery { releaseService.listVersions(releaseId) } returns listOf(releaseVersion(projectId, vId))
        coEvery { versionService.getById(vId) } returns version("6.0.5")
        coEvery { projectRepositories.list(projectId) } returns listOf(
            mockk { every { repositoryId } returns repoId },
        )
        coEvery { publicationService.listByVersion(vId) } returns listOf(
            mockk(relaxed = true) { every { id } returns publicationId },
        )
        coEvery { releaseService.resetDeployments(releaseId) } returns 1

        assertTrue(service.rollbackArtifacts(releaseId, authenticated()))

        coVerify(exactly = 1) { repositoryWrite.deleteTag(repoId, "6.0.5") }
        // The v-prefixed form is also swept, for attempts tagged before the prefix was dropped.
        coVerify(exactly = 1) { repositoryWrite.deleteTag(repoId, "v6.0.5") }
        coVerify(exactly = 1) { publicationService.remove(publicationId) }
        coVerify(exactly = 1) { releaseService.resetDeployments(releaseId) }
    }

    @Test
    fun `rollback is refused while a git-ci run is live`() = runTest {
        val rel = release()
        every { rel.releasedAt } returns null
        coEvery { releaseService.getById(releaseId) } returns rel
        coEvery { runService.findByReleaseId(releaseId, 0, 20) } returns listOf(gitRun(status = PipelineRunStatus.RUNNING))

        val e = try {
            service.rollbackArtifacts(releaseId, authenticated())
            error("rollback should have been refused")
        } catch (expected: IllegalStateException) {
            expected
        }
        assertTrue("still live" in (e.message ?: ""), e.message)
        coVerify(exactly = 0) { repositoryWrite.deleteTag(any(), any()) }

        coEvery { runService.findByReleaseId(releaseId, 0, 20) } returns
            listOf(gitRun(status = PipelineRunStatus.QUEUED))
        assertFailsWith<IllegalStateException> { service.rollbackArtifacts(releaseId, authenticated()) }
    }

    @Test
    fun `rollback is refused after the release is marked released`() = runTest {
        val rel = release()
        every { rel.releasedAt } returns mockk()
        coEvery { releaseService.getById(releaseId) } returns rel

        val e = try {
            service.rollbackArtifacts(releaseId, authenticated())
            error("rollback should have been refused")
        } catch (expected: IllegalStateException) {
            expected
        }
        assertTrue("already marked released" in (e.message ?: ""), e.message)
    }

    @Test
    fun `rollbackEnvironment uses the deployed release target and initiating principal`() = runTest {
        val projectId = UUID.random()
        val versionId = UUID.random()
        val environmentId = UUID.random()
        val rel = release()
        val request = slot<ReleaseRollbackRequest>()
        coEvery { releaseService.getById(releaseId) } returns rel
        coEvery { environmentService.getByProgramAndKey(programId, "production") } returns Environment(
            id = environmentId,
            programId = programId,
            key = "production",
            name = "Production",
            typeId = UUID.random(),
        )
        coEvery { environmentService.currentState(environmentId) } returns listOf(
            EnvironmentDeployment(
                environmentId = environmentId,
                projectId = projectId,
                targetKind = DeployTargetKind.HELM,
                versionId = versionId,
                releaseId = releaseId,
                status = EnvironmentDeploymentStatus.DEPLOYED,
            ),
        )
        coEvery { versionService.getById(versionId) } returns version("6.0.0")
        coEvery { deployConfigService.forEnvironment(projectId, "production", any()) } returns
            EnvironmentDeployConfig(target = "helm", sourceRepositoryId = repositoryId.toString())
        coEvery { releaseDeployer.rollback(capture(request)) } returns
            ReleaseDeployOutcome("bosca@41", "ROLLED_BACK")

        assertEquals(listOf("bosca@41"), service.rollbackEnvironment(releaseId, "production", 0, authenticated()))
        assertEquals(repositoryId, request.captured.targetRepositoryId)
        assertEquals("refs/tags/6.0.0", request.captured.ref)
        assertEquals(actorId, request.captured.initiatorPrincipalId)
        assertEquals(releaseId.toString(), request.captured.parameters["release.id"])
    }

    @Test
    fun `rollbackEnvironment validates request release environment authentication and deployed state`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            service.rollbackEnvironment(releaseId, "production", -1, authenticated())
        }

        coEvery { releaseService.getById(releaseId) } returns null
        assertFailsWith<NoSuchElementException> {
            service.rollbackEnvironment(releaseId, "production", 0, authenticated())
        }

        val rel = release()
        coEvery { releaseService.getById(releaseId) } returns rel
        assertFailsWith<IllegalArgumentException> {
            service.rollbackEnvironment(releaseId, "   ", 0, authenticated())
        }

        coEvery { environmentService.getByProgramAndKey(programId, "production") } returns null
        assertFailsWith<NoSuchElementException> {
            service.rollbackEnvironment(releaseId, "production", 0, authenticated())
        }

        val environment = Environment(
            id = UUID.random(),
            programId = programId,
            key = "production",
            name = "Production",
        )
        coEvery { environmentService.getByProgramAndKey(programId, "production") } returns environment
        val unauthenticated = mockk<AuthenticationContext> { every { principal() } returns null }
        assertFailsWith<SecurityException> {
            service.rollbackEnvironment(releaseId, "production", 0, unauthenticated)
        }

        coEvery { environmentService.currentState(environment.id) } returns listOf(
            EnvironmentDeployment(
                environmentId = environment.id,
                projectId = UUID.random(),
                targetKind = DeployTargetKind.HELM,
                versionId = UUID.random(),
                releaseId = UUID.random(),
                status = EnvironmentDeploymentStatus.DEPLOYED,
            ),
            EnvironmentDeployment(
                environmentId = environment.id,
                projectId = UUID.random(),
                targetKind = DeployTargetKind.HELM,
                versionId = UUID.random(),
                releaseId = releaseId,
                status = EnvironmentDeploymentStatus.FAILED,
            ),
        )
        assertFailsWith<IllegalStateException> {
            service.rollbackEnvironment(releaseId, " production ", 0, authenticated())
        }
    }

    @Test
    fun `rollbackEnvironment reports stale declarations and rolls back every matching target`() = runTest {
        val rel = release()
        val environment = Environment(
            id = UUID.random(),
            programId = programId,
            key = "production",
            name = "Production",
        )
        val deployment = EnvironmentDeployment(
            environmentId = environment.id,
            projectId = UUID.random(),
            targetKind = DeployTargetKind.HELM,
            versionId = UUID.random(),
            releaseId = releaseId,
            status = EnvironmentDeploymentStatus.DEPLOYED,
        )
        coEvery { releaseService.getById(releaseId) } returns rel
        coEvery { environmentService.getByProgramAndKey(programId, "production") } returns environment
        coEvery { environmentService.currentState(environment.id) } returns listOf(deployment)

        coEvery { versionService.getById(deployment.versionId) } returns null
        assertFailsWith<IllegalStateException> {
            service.rollbackEnvironment(releaseId, "production", 0, authenticated())
        }

        coEvery { versionService.getById(deployment.versionId) } returns version("6.0.0")
        coEvery { deployConfigService.forEnvironment(deployment.projectId, "production", any()) } returns null
        assertFailsWith<IllegalStateException> {
            service.rollbackEnvironment(releaseId, "production", 0, authenticated())
        }

        coEvery { deployConfigService.forEnvironment(deployment.projectId, "production", any()) } returns
            EnvironmentDeployConfig(target = "helm")
        assertFailsWith<IllegalStateException> {
            service.rollbackEnvironment(releaseId, "production", 0, authenticated())
        }

        coEvery { deployConfigService.forEnvironment(deployment.projectId, "production", any()) } returns
            EnvironmentDeployConfig(target = "google_play", sourceRepositoryId = repositoryId.toString())
        assertFailsWith<IllegalStateException> {
            service.rollbackEnvironment(releaseId, "production", 0, authenticated())
        }

        val config = EnvironmentDeployConfig(
            targets = listOf(
                DeployTargetEntry(target = "helm"),
                DeployTargetEntry(target = "HELM"),
            ),
            sourceRepositoryId = repositoryId.toString(),
        )
        coEvery { deployConfigService.forEnvironment(deployment.projectId, "production", any()) } returns config
        coEvery { releaseDeployer.rollback(any()) } returnsMany listOf(
            ReleaseDeployOutcome("first", "ROLLED_BACK"),
            ReleaseDeployOutcome("second", "ROLLED_BACK"),
        )

        assertEquals(
            listOf("first", "second"),
            service.rollbackEnvironment(releaseId, "production", 2, authenticated()),
        )
        coVerify(exactly = 2) {
            releaseDeployer.rollback(match { it.target != null && it.toRevision == 2 })
        }
    }

    @Test
    fun `artifact rollback tolerates missing versions and tag deletion failures but preserves cancellation`() = runTest {
        val missingRelease = UUID.random()
        coEvery { releaseService.getById(missingRelease) } returns null
        assertFalse(service.rollbackArtifacts(missingRelease, authenticated()))

        val projectId = UUID.random()
        val missingVersionId = UUID.random()
        val versionId = UUID.random()
        val rel = release()
        every { rel.releasedAt } returns null
        coEvery { releaseService.getById(releaseId) } returns rel
        coEvery { runService.findByReleaseId(releaseId, 0, 20) } returns emptyList()
        coEvery { releaseService.listVersions(releaseId) } returns listOf(
            releaseVersion(projectId, missingVersionId),
            releaseVersion(projectId, versionId),
        )
        coEvery { versionService.getById(missingVersionId) } returns null
        coEvery { versionService.getById(versionId) } returns version("6.1.0")
        coEvery { projectRepositories.list(projectId) } returns listOf(
            mockk { every { repositoryId } returns this@ReleasePipelineServiceImplTest.repositoryId },
        )
        coEvery { repositoryWrite.deleteTag(repositoryId, "6.1.0") } throws IllegalStateException("missing")
        coEvery { repositoryWrite.deleteTag(repositoryId, "v6.1.0") } returns Unit
        coEvery { publicationService.listByVersion(versionId) } returns emptyList()
        coEvery { releaseService.resetDeployments(releaseId) } returns 0
        assertTrue(service.rollbackArtifacts(releaseId, authenticated()))

        coEvery { repositoryWrite.deleteTag(repositoryId, "6.1.0") } throws kotlinx.coroutines.CancellationException("stop")
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            service.rollbackArtifacts(releaseId, authenticated())
        }
    }

    private fun releaseVersion(projectId: UUID, versionId: UUID = UUID.random()): bosca.workops.model.release.ReleaseProjectVersion =
        mockk {
            every { this@mockk.projectId } returns projectId
            every { this@mockk.versionId } returns versionId
        }

    private fun version(name: String, released: Boolean = false): bosca.workops.model.version.Version =
        mockk {
            every { this@mockk.name } returns name
            every { this@mockk.released } returns released
        }

    private fun declaration(consumer: UUID, provider: UUID, constraint: String) =
        bosca.workops.model.dependency.DependencyDeclaration(
            consumerProjectId = consumer, providerProjectId = provider,
            providerVersionConstraint = constraint,
            dependencyType = bosca.workops.model.dependency.DependencyType.BUILD,
        )

    private fun projectRef(key: String, name: String): bosca.workops.model.project.Project =
        mockk { every { this@mockk.key } returns key; every { this@mockk.name } returns name }

    @Test
    fun `launch returns false when the release does not exist`() = runTest {
        coEvery { releaseService.getById(releaseId) } returns null
        assertFalse(service.launch(releaseId, authenticated()))
        coVerify(exactly = 0) { runService.createRun(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `runsForRelease maps correlated git-ci runs`() = runTest {
        coEvery { releaseService.getById(releaseId) } returns release()

        val now = OffsetDateTime.now()
        val activeMine = gitRun(status = PipelineRunStatus.RUNNING, created = now, started = now)
        val finishedMine = gitRun(
            status = PipelineRunStatus.SUCCESS,
            created = now.minusHours(1),
            started = now.minusHours(1),
            finished = now.minusMinutes(50),
        )
        coEvery { runService.findByReleaseId(releaseId, 0, 20) } returns listOf(activeMine, finishedMine)

        val result = service.runsForRelease(releaseId, 20)

        assertEquals(
            listOf(activeMine.id, finishedMine.id),
            result.map { it.runId },
        )
        assertEquals("RUNNING", result[0].status)
        assertEquals(null, result[0].durationMs, "an in-flight run has no duration")
        assertEquals("SUCCESS", result[1].status)
        assertEquals(finishedMine.finished, result[1].finishedAt)
        assertEquals(600_000, result[1].durationMs, "10 minutes in ms")
    }

    @Test
    fun `runsForRelease returns empty when the release does not exist`() = runTest {
        coEvery { releaseService.getById(releaseId) } returns null
        assertEquals(emptyList(), service.runsForRelease(releaseId, 20))
        coVerify(exactly = 0) { runService.findByReleaseId(any(), any(), any()) }
    }

    @Test
    fun `launch and promotion reject unauthenticated or unusable requests before dispatch`() = runTest {
        val projectId = UUID.random()
        val versionId = UUID.random()
        val unauthenticated = mockk<AuthenticationContext> { every { principal() } returns null }
        coEvery { releaseService.getById(releaseId) } returns release()
        coEvery { releaseService.listVersions(releaseId) } returns listOf(releaseVersion(projectId, versionId))
        coEvery { versionService.getById(versionId) } returns version("6.0.0")
        coEvery { dependencyService.listByConsumer(projectId) } returns emptyList()

        assertFailsWith<SecurityException> { service.launch(releaseId, emptyMap(), unauthenticated) }
        assertFailsWith<IllegalArgumentException> { service.promote(releaseId, "   ", false, emptyMap(), authenticated()) }
        assertFailsWith<SecurityException> { service.promote(releaseId, "production", false, emptyMap(), unauthenticated) }

        val missing = UUID.random()
        coEvery { releaseService.getById(missing) } returns null
        assertFalse(service.promote(missing, "production", false, emptyMap(), authenticated()))
        coVerify(exactly = 0) { runService.createRun(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `patch release validates selection source versions and semantic patch names`() = runTest {
        val projectId = UUID.random()
        val versionId = UUID.random()
        val unauthenticated = mockk<AuthenticationContext> { every { principal() } returns null }
        assertFailsWith<SecurityException> { service.startPatchRelease(releaseId, listOf(projectId), unauthenticated) }

        coEvery { releaseService.getById(releaseId) } returns null
        assertFailsWith<NoSuchElementException> { service.startPatchRelease(releaseId, listOf(projectId), authenticated()) }

        coEvery { releaseService.getById(releaseId) } returns release()
        assertFailsWith<IllegalArgumentException> { service.startPatchRelease(releaseId, emptyList(), authenticated()) }

        coEvery { releaseService.listVersions(releaseId) } returns emptyList()
        assertFailsWith<IllegalArgumentException> { service.startPatchRelease(releaseId, listOf(projectId), authenticated()) }

        coEvery { releaseService.listVersions(releaseId) } returns listOf(releaseVersion(projectId, versionId))
        coEvery { versionService.getById(versionId) } returns null
        assertFailsWith<IllegalStateException> { service.startPatchRelease(releaseId, listOf(projectId), authenticated()) }

        coEvery { versionService.getById(versionId) } returns version("release-six")
        assertFailsWith<IllegalArgumentException> { service.startPatchRelease(releaseId, listOf(projectId), authenticated()) }

        coEvery { versionService.getById(versionId) } returns version("v6.0.9")
        coEvery { versionService.listByProject(projectId) } returns listOf(version("v6.0.10"))
        assertFailsWith<IllegalStateException> { service.startPatchRelease(releaseId, listOf(projectId), authenticated()) }

        coEvery { versionService.getById(versionId) } returns version("6.0.${Long.MAX_VALUE}")
        coEvery { versionService.listByProject(projectId) } returns emptyList()
        assertFailsWith<IllegalStateException> { service.startPatchRelease(releaseId, listOf(projectId), authenticated()) }
    }

    @Test
    fun `patch release deduplicates projects and uses a descriptive name for mixed patch versions`() = runTest {
        val firstProject = UUID.random()
        val secondProject = UUID.random()
        val firstVersion = UUID.random()
        val secondVersion = UUID.random()
        val firstPatch = mockk<bosca.workops.model.version.Version> {
            every { id } returns UUID.random()
            every { name } returns "6.0.1"
        }
        val secondPatch = mockk<bosca.workops.model.version.Version> {
            every { id } returns UUID.random()
            every { name } returns "7.0.1"
        }
        val patchReleaseId = UUID.random()
        val patchRelease = mockk<Release> { every { id } returns patchReleaseId }
        coEvery { releaseService.getById(releaseId) } returns release()
        coEvery { releaseService.listVersions(releaseId) } returns listOf(
            releaseVersion(firstProject, firstVersion),
            releaseVersion(secondProject, secondVersion),
        )
        coEvery { versionService.getById(firstVersion) } returns version("6.0.0")
        coEvery { versionService.getById(secondVersion) } returns version("7.0.0")
        coEvery { versionService.listByProject(any()) } returns emptyList()
        coEvery { versionService.create(match { it.projectId == firstProject }) } returns firstPatch
        coEvery { versionService.create(match { it.projectId == secondProject }) } returns secondPatch
        coEvery { releaseService.create(programId, "6.0.0 patch", any(), null, null) } returns patchRelease
        coEvery { releaseService.bundle(any(), any(), any()) } returns mockk()

        assertEquals(
            patchRelease,
            service.startPatchRelease(
                releaseId,
                listOf(firstProject, firstProject, secondProject),
                authenticated(),
            ),
        )
        coVerify(exactly = 2) { releaseService.bundle(patchReleaseId, any(), any()) }
    }

    @Test
    fun `release declarations resolve version tokens and preserve unknown version coordinates`() = runTest {
        val firstProject = UUID.random()
        val secondProject = UUID.random()
        val firstVersion = UUID.random()
        val missingVersion = UUID.random()
        val secondRepository = UUID.random()
        val secondPipeline = UUID.random()
        coEvery { releaseService.listVersions(releaseId) } returns listOf(
            releaseVersion(firstProject, firstVersion),
            releaseVersion(secondProject, missingVersion),
        )
        coEvery { versionService.getById(firstVersion) } returns version("6.2.3")
        coEvery { versionService.getById(missingVersion) } returns null
        coEvery { projectRepositories.list(firstProject) } returns listOf(
            mockk { every { repositoryId } returns this@ReleasePipelineServiceImplTest.repositoryId },
        )
        coEvery { projectRepositories.list(secondProject) } returns listOf(mockk { every { repositoryId } returns secondRepository })
        coEvery { gitPipelines.findByRepository(repositoryId) } returns listOf(
            Pipeline(repositoryId = repositoryId, filePath = "release.yaml", name = "Release", configHash = "one", id = pipelineId),
        )
        coEvery { gitPipelines.findByRepository(secondRepository) } returns listOf(
            Pipeline(repositoryId = secondRepository, filePath = "release.yaml", name = "Release", configHash = "two", id = secondPipeline),
        )
        coEvery { gitPipelines.declaredArtifacts(pipelineId) } returns listOf(
            ArtifactDefinition("helm", "charts", "bosca:${'$'}{{ version }}-${'$'}{{ tag }}", listOf("production")),
        )
        coEvery { gitPipelines.declaredArtifacts(secondPipeline) } returns listOf(
            ArtifactDefinition("custom", "raw", "unchanged:${'$'}{{ version }}"),
        )

        val artifacts = service.releaseDeclaredArtifacts(releaseId)
        assertEquals(listOf(ArtifactType.HELM, ArtifactType.OTHER), artifacts.map { it.type })
        assertEquals("bosca:6.2.3-6.2.3", artifacts.first().coordinate)
        assertEquals(listOf("production"), artifacts.first().environments)
        assertEquals("unchanged:${'$'}{{ version }}", artifacts.last().coordinate)
        assertEquals(listOf("HELM", "OTHER"), service.releaseChannelArtifactTypes(releaseId))
    }

    @Test
    fun `release planning supplies typed defaults and reports invalid choice and dependency graphs`() = runTest {
        val projectId = UUID.random()
        val versionId = UUID.random()
        val definition = PipelineDefinition(
            name = "Defaults",
            triggers = listOf(
                PipelineTrigger(
                    type = PipelineTriggerType.RELEASE,
                    inputs = linkedMapOf(
                        "authored" to TriggerInput(default = "yes"),
                        "flag" to TriggerInput(type = "boolean"),
                        "count" to TriggerInput(type = "number"),
                        "track" to TriggerInput(type = "choice", options = listOf("stable", "beta")),
                        "note" to TriggerInput(type = "string"),
                    ),
                ),
            ),
        )
        val jobs = linkedMapOf(
            "build" to JobDefinition(needs = listOf("external"), steps = listOf(StepDefinition("Script"))),
            "reuse" to JobDefinition(needs = listOf("build")),
        )
        coEvery { releaseService.getById(releaseId) } returns release()
        coEvery { releaseService.listVersions(releaseId) } returns listOf(releaseVersion(projectId, versionId))
        coEvery { versionService.getById(versionId) } returns version("6.0.0")
        coEvery { gitPipelines.parseDefinition(repositoryId, "abc123", any()) } returns definition
        every { runService.plan(definition, any(), PipelineTriggerType.RELEASE, any()) } returns
            PipelineExecutionPlan(emptyMap(), jobs)

        val plan = service.plansForRelease(releaseId).single()
        assertEquals(listOf("build", "reuse"), plan.jobs.map { it.key })
        assertEquals(listOf(0, 1), plan.jobs.map { it.depth })
        assertEquals("run", plan.jobs.first().steps.single().action)
        verify {
            runService.plan(
                definition,
                any(),
                PipelineTriggerType.RELEASE,
                match {
                    it["inputs.authored"] == "yes" &&
                        it["inputs.flag"] == "false" &&
                        it["inputs.count"] == "0" &&
                        it["inputs.track"] == "stable" &&
                        it["inputs.note"] == ""
                },
            )
        }

        val invalidChoice = definition.copy(
            triggers = listOf(
                PipelineTrigger(
                    PipelineTriggerType.RELEASE,
                    inputs = mapOf("track" to TriggerInput(type = "choice")),
                ),
            ),
        )
        coEvery { gitPipelines.parseDefinition(repositoryId, "abc123", any()) } returns invalidChoice
        assertFailsWith<IllegalArgumentException> { service.plansForRelease(releaseId) }

        val cyclic = definition.copy(triggers = listOf(PipelineTrigger(PipelineTriggerType.RELEASE)))
        coEvery { gitPipelines.parseDefinition(repositoryId, "abc123", any()) } returns cyclic
        every { runService.plan(cyclic, any(), PipelineTriggerType.RELEASE, any()) } returns PipelineExecutionPlan(
            emptyMap(),
            mapOf("a" to JobDefinition(needs = listOf("b")), "b" to JobDefinition(needs = listOf("a"))),
        )
        assertFailsWith<IllegalStateException> { service.plansForRelease(releaseId) }
    }

    @Test
    fun `release repository resolution validates empty stale and conflicting bundles while deduplicating shared repositories`() = runTest {
        val firstProject = UUID.random()
        val secondProject = UUID.random()
        val firstVersionId = UUID.random()
        val secondVersionId = UUID.random()
        val missingReleaseId = UUID.random()
        coEvery { releaseService.getById(missingReleaseId) } returns null
        assertTrue(service.repositoryIdsForRelease(missingReleaseId).isEmpty())

        coEvery { releaseService.getById(releaseId) } returns release()
        coEvery { releaseService.listVersions(releaseId) } returns emptyList()
        assertFailsWith<IllegalStateException> { service.repositoryIdsForRelease(releaseId) }

        coEvery { releaseService.listVersions(releaseId) } returns listOf(releaseVersion(firstProject, firstVersionId))
        coEvery { versionService.getById(firstVersionId) } returns null
        assertFailsWith<IllegalStateException> { service.repositoryIdsForRelease(releaseId) }

        coEvery { versionService.getById(firstVersionId) } returns version("6.0.0")
        coEvery { repositoryService.findById(repositoryId) } returns null
        assertFailsWith<IllegalStateException> { service.repositoryIdsForRelease(releaseId) }

        coEvery { repositoryService.findById(repositoryId) } returns Repository(
            id = repositoryId,
            slug = "workspace",
            name = "Workspace",
            ownerId = UUID.random(),
        )
        coEvery { releaseService.listVersions(releaseId) } returns listOf(
            releaseVersion(firstProject, firstVersionId),
            releaseVersion(secondProject, secondVersionId),
        )
        coEvery { versionService.getById(secondVersionId) } returns version("7.0.0")
        val conflict = assertFailsWith<IllegalStateException> { service.repositoryIdsForRelease(releaseId) }
        assertTrue("6.0.0" in conflict.message.orEmpty() && "7.0.0" in conflict.message.orEmpty())

        coEvery { versionService.getById(secondVersionId) } returns version("6.0.0")
        assertEquals(listOf(repositoryId), service.repositoryIdsForRelease(releaseId))
    }

    @Test
    fun `release launch filters undeclared inputs and reports absent triggers refs and disappearing repositories`() = runTest {
        val projectId = UUID.random()
        val versionId = UUID.random()
        val repository = Repository(
            id = repositoryId,
            slug = "workspace",
            name = "Workspace",
            ownerId = UUID.random(),
        )
        coEvery { releaseService.getById(releaseId) } returns release()
        coEvery { releaseService.listVersions(releaseId) } returns listOf(releaseVersion(projectId, versionId))
        coEvery { versionService.getById(versionId) } returns version("6.0.0")
        coEvery { dependencyService.listByConsumer(projectId) } returns emptyList()

        val noReleaseTrigger = PipelineDefinition(
            name = "Manual only",
            triggers = listOf(PipelineTrigger(PipelineTriggerType.MANUAL)),
        )
        coEvery { gitPipelines.parseDefinition(repositoryId, "abc123", any()) } returns noReleaseTrigger
        val absentTrigger = assertFailsWith<IllegalStateException> {
            service.launch(releaseId, mapOf("known" to "yes"), authenticated())
        }
        assertTrue("No RELEASE-triggered pipeline" in absentTrigger.message.orEmpty())

        coEvery { repositoryBrowse.resolveRef(repositoryId, "refs/heads/main") } returns null
        val missingRef = assertFailsWith<IllegalStateException> { service.launch(releaseId, authenticated()) }
        assertTrue("cannot be resolved" in missingRef.message.orEmpty())

        coEvery { repositoryBrowse.resolveRef(repositoryId, "refs/heads/main") } returns "abc123"
        coEvery { repositoryService.findById(repositoryId) } returnsMany listOf(repository, null)
        assertFailsWith<IllegalStateException> { service.launch(releaseId, authenticated()) }

        coEvery { repositoryService.findById(repositoryId) } returns repository
        val promotion = PipelineDefinition(
            name = "Promotion",
            triggers = listOf(
                PipelineTrigger(
                    type = PipelineTriggerType.PROMOTION,
                    inputs = mapOf(
                        "known" to TriggerInput(),
                        "already" to TriggerInput(),
                    ),
                ),
            ),
        )
        coEvery { repositoryBrowse.resolveRef(repositoryId, "refs/tags/6.0.0") } returns "tagged"
        coEvery { gitPipelines.parseDefinition(repositoryId, "tagged", any()) } returns promotion
        every { runService.plan(promotion, any(), PipelineTriggerType.PROMOTION, any()) } returns
            PipelineExecutionPlan(emptyMap(), mapOf("promote" to JobDefinition()))
        coEvery {
            runService.createRun(any(), any(), promotion, any(), any(), PipelineTriggerType.PROMOTION, any(), any())
        } returns gitRun()

        assertTrue(
            service.promote(
                releaseId,
                " production ",
                false,
                mapOf("known" to "yes", "inputs.already" to "ready", "unknown" to "discard"),
                authenticated(),
            ),
        )
        coVerify(exactly = 1) {
            runService.createRun(
                any(), any(), promotion, any(), any(), PipelineTriggerType.PROMOTION, actorId,
                match {
                    it["inputs.known"] == "yes" &&
                        it["inputs.already"] == "ready" &&
                        "inputs.unknown" !in it &&
                        it["promotion.environment"] == "production"
                },
            )
        }

        val stagingOnly = promotion.copy(
            triggers = listOf(PipelineTrigger(PipelineTriggerType.PROMOTION, environments = listOf("staging"))),
        )
        coEvery { gitPipelines.parseDefinition(repositoryId, "tagged", any()) } returns stagingOnly
        val wrongEnvironment = assertFailsWith<IllegalStateException> {
            service.promote(releaseId, "production", false, emptyMap(), authenticated())
        }
        assertTrue("No PROMOTION-triggered pipeline" in wrongEnvironment.message.orEmpty())
    }

    @Test
    fun `promotion planning expands declared environments and renders diamond dependencies and requirements`() = runTest {
        val projectId = UUID.random()
        val versionId = UUID.random()
        val definition = PipelineDefinition(
            name = "Environment promotion",
            triggers = listOf(PipelineTrigger(PipelineTriggerType.PROMOTION)),
            environments = linkedMapOf(
                "staging" to EnvironmentDefinition(),
                "production" to EnvironmentDefinition(promotesFrom = "staging"),
            ),
        )
        val jobs = linkedMapOf(
            "build" to JobDefinition(),
            "test" to JobDefinition(needs = listOf("build")),
            "scan" to JobDefinition(needs = listOf("build")),
            "deploy" to JobDefinition(
                needs = listOf("test", "scan", "external"),
                requires = listOf(bosca.git.model.ArtifactRequirement("docker", "registry", "server:6.0.0")),
                pipelineRequires = listOf(PipelineRequirement("Release", "upstream", ref = "refs/tags/6.0.0")),
                steps = listOf(StepDefinition("Deploy")),
            ),
        )
        coEvery { releaseService.getById(releaseId) } returns release()
        coEvery { releaseService.listVersions(releaseId) } returns listOf(releaseVersion(projectId, versionId))
        coEvery { versionService.getById(versionId) } returns version("6.0.0")
        coEvery { gitPipelines.parseDefinition(repositoryId, "abc123", any()) } returns definition
        every { runService.plan(definition, any(), PipelineTriggerType.PROMOTION, any()) } returns
            PipelineExecutionPlan(emptyMap(), jobs)

        val plans = service.plansForRelease(releaseId)

        assertEquals(listOf("production", "staging"), plans.map { it.environment })
        assertEquals("staging", plans.first().promotesFrom)
        assertEquals(null, plans.last().promotesFrom)
        assertEquals(listOf(0, 1, 1, 2), plans.first().jobs.map { it.depth })
        assertEquals(
            listOf(
                "artifact docker:registry:server:6.0.0",
                "pipeline upstream/Release@refs/tags/6.0.0",
            ),
            plans.first().jobs.last().requirements,
        )
        assertEquals("run", plans.first().jobs.last().steps.single().action)
    }

    @Test
    fun `dependency validation handles empty missing nonbuild and every released constraint form`() = runTest {
        coEvery { releaseService.listVersions(releaseId) } returns emptyList()
        assertTrue(service.dependencyViolations(releaseId).isEmpty())

        val consumer = UUID.random()
        val missingVersionId = UUID.random()
        val matchProvider = UUID.random()
        coEvery { releaseService.listVersions(releaseId) } returns listOf(releaseVersion(consumer, missingVersionId))
        coEvery { versionService.getById(missingVersionId) } returns null
        coEvery { dependencyService.listByConsumer(consumer) } returns listOf(declaration(consumer, matchProvider, "match"))
        assertTrue(service.dependencyViolations(releaseId).isEmpty())

        val consumerVersionId = UUID.random()
        val blankProvider = UUID.random()
        val starProvider = UUID.random()
        val prefixProvider = UUID.random()
        val exactProvider = UUID.random()
        val unreleasedProvider = UUID.random()
        val runtimeProvider = UUID.random()
        coEvery { releaseService.listVersions(releaseId) } returns listOf(releaseVersion(consumer, consumerVersionId))
        coEvery { versionService.getById(consumerVersionId) } returns version("10.0.0")
        coEvery { dependencyService.listByConsumer(consumer) } returns listOf(
            declaration(consumer, runtimeProvider, "*").copy(dependencyType = bosca.workops.model.dependency.DependencyType.RUNTIME),
            declaration(consumer, blankProvider, "   "),
            declaration(consumer, starProvider, "*"),
            declaration(consumer, prefixProvider, "6.0.*"),
            declaration(consumer, exactProvider, "7.2.1"),
            declaration(consumer, unreleasedProvider, "8.0.0"),
        )
        coEvery { versionService.listByProject(blankProvider) } returns listOf(version("anything", released = true))
        coEvery { versionService.listByProject(starProvider) } returns listOf(version("2.0.0", released = true))
        coEvery { versionService.listByProject(prefixProvider) } returns listOf(
            version("5.9.9", released = false),
            version("6.0.12", released = true),
        )
        coEvery { versionService.listByProject(exactProvider) } returns listOf(version("7.2.1", released = true))
        coEvery { versionService.listByProject(unreleasedProvider) } returns listOf(version("8.0.0", released = false))
        coEvery { projectService.getById(consumer) } returns null
        coEvery { projectService.getById(unreleasedProvider) } returns null

        val violations = service.dependencyViolations(releaseId)

        assertEquals(1, violations.size)
        assertTrue(consumer.toString() in violations.single())
        assertTrue(unreleasedProvider.toString() in violations.single())
        coVerify(exactly = 0) { versionService.listByProject(runtimeProvider) }
    }

    @Test
    fun `planning and promotion report absent releases disappearing repositories refs definitions and triggers`() = runTest {
        val absentId = UUID.random()
        coEvery { releaseService.getById(absentId) } returns null
        assertTrue(service.plansForRelease(absentId).isEmpty())
        assertFalse(service.promote(absentId, "production", false, emptyMap(), authenticated()))

        val projectId = UUID.random()
        val versionId = UUID.random()
        val repository = Repository(
            id = repositoryId,
            slug = "workspace",
            name = "Workspace",
            ownerId = UUID.random(),
        )
        coEvery { releaseService.getById(releaseId) } returns release()
        coEvery { releaseService.listVersions(releaseId) } returns listOf(releaseVersion(projectId, versionId))
        coEvery { versionService.getById(versionId) } returns version("6.0.0")
        coEvery { repositoryService.findById(repositoryId) } returnsMany listOf(repository, null)
        assertTrue(service.plansForRelease(releaseId).isEmpty())

        coEvery { repositoryService.findById(repositoryId) } returns repository
        coEvery { repositoryBrowse.resolveRef(repositoryId, "refs/heads/main") } returns null
        assertFailsWith<IllegalStateException> { service.plansForRelease(releaseId) }

        coEvery { repositoryBrowse.resolveRef(repositoryId, "refs/heads/main") } returns "abc123"
        coEvery { gitPipelines.parseDefinition(repositoryId, "abc123", any()) } returns null
        assertFailsWith<IllegalStateException> { service.plansForRelease(releaseId) }

        coEvery { gitPipelines.parseDefinition(repositoryId, "abc123", any()) } returns PipelineDefinition(
            name = "Manual",
            triggers = listOf(PipelineTrigger(PipelineTriggerType.MANUAL)),
        )
        assertTrue(service.plansForRelease(releaseId).isEmpty())
    }
}
