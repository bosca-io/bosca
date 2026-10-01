@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.workops.service

import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.connection
import bosca.db.migrations.FlywayMigration
import bosca.di.ObjectProvider
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.db.migrations.CoreMigration
import bosca.workops.migration.WorkOpsMigration
import bosca.workops.deploy.DeployTargetKind
import bosca.workops.model.artifact.ArtifactType
import bosca.workops.model.artifact.AllocateAppBuildNumberInput
import bosca.workops.model.artifact.AppBuildPlatform
import bosca.workops.model.artifact.BreakingChangeLevel
import bosca.workops.model.artifact.PublicationStatus
import bosca.workops.model.artifact.RegisterArtifactInput
import bosca.workops.model.artifact.ReleaseDeclaredArtifact
import bosca.workops.model.compatibility.CompatibilityStatus
import bosca.workops.model.compatibility.RecordCompatibilityResultInput
import bosca.workops.model.dependency.BuildBlockerType
import bosca.workops.model.dependency.CreateDependencyDeclarationInput
import bosca.workops.model.dependency.DependencyStatus
import bosca.workops.model.dependency.DependencyType
import bosca.workops.model.environment.CreateEnvironmentInput
import bosca.workops.model.environment.DeployInput
import bosca.workops.model.environment.EnvironmentDeploymentStatus
import bosca.workops.model.environment.EnvironmentTargetType
import bosca.workops.model.environment.HealthCheckStatus
import bosca.workops.model.environment.UpdateEnvironmentInput
import bosca.workops.service.EnvironmentDriftType
import bosca.workops.model.pipeline.CreatePipelineRunInput
import bosca.workops.model.pipeline.PipelineStatus
import bosca.workops.model.pipeline.PipelineTriggerType
import bosca.workops.model.project.PortfolioInput
import bosca.workops.model.project.ProgramInput
import bosca.workops.model.project.ProjectInput
import bosca.workops.model.version.CreateVersionInput
import bosca.workops.repository.*
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.AfterClass
import bosca.test.resources.SharedPostgreSQLContainer
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Phase24IntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("bosca_workops_phase24_test")
            withReuse(true)
            start()
        }
        private val pool = ConnectionPool(
            ConnectionFactoryImpl(
                ConnectionConfig(
                    url = postgres.jdbcUrl,
                    user = postgres.username,
                    password = postgres.password,
                    maxConnections = 5,
                ),
                key = "workops-phase24-test",
            )
        )
        private var schemaInitialized = false

        @AfterClass
        @JvmStatic
        fun shutdown() {
            runBlocking { pool.close() }
            postgres.stop()
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val portfolioRepo = PortfolioRepositoryImpl()
    private val portfolioPermissionRepo = PortfolioPermissionRepositoryImpl()
    private val programRepo = ProgramRepositoryImpl()
    private val programPermissionRepo = ProgramPermissionRepositoryImpl()
    private val projectRepo = ProjectRepositoryImpl()
    private val projectPermissionRepo = ProjectPermissionRepositoryImpl()
    private val keyCounterRepo = ProjectKeyCounterRepositoryImpl()
    private val versionRepo = VersionRepositoryImpl()
    private val releaseRepo = ReleaseRepositoryImpl()

    private val depDeclRepo = DependencyDeclarationRepositoryImpl()
    private val artifactPubRepo = ArtifactPublicationRepositoryImpl()
    private val apiSurfaceRepo = ApiSurfaceReportRepositoryImpl()
    private val pipelineRunRepo = PipelineRunRepositoryImpl()
    private val pipelineStageRepo = PipelineStageRunRepositoryImpl()
    private val envRepo = EnvironmentRepositoryImpl()
    private val envTypeRepo = EnvironmentTypeRepositoryImpl()
    private val envDeployRepo = EnvironmentDeploymentRepositoryImpl()
    private val compatRepo = CompatibilityTestResultRepositoryImpl()
    private val releaseNotesRepo = ReleaseNotesRepositoryImpl()
    private val appBuildNumberRepo = AppBuildNumberRepositoryImpl()

    private val securityService = mockk<SecurityService>(relaxed = true)
    private val dispatcher = mockk<AutomationDispatcher>(relaxed = true)
    private val portfolioService = PortfolioServiceImpl(portfolioRepo, portfolioPermissionRepo, securityService)
    private val groupEvaluator = bosca.security.service.GroupEvaluator(securityService)
    private val portfolioPermissionEvaluator = PortfolioPermissionEvaluator(portfolioService, securityService, groupEvaluator)
    private val programService = ProgramServiceImpl(programRepo, portfolioRepo, programPermissionRepo, portfolioPermissionRepo, portfolioPermissionEvaluator)
    private val programPermissionEvaluator = ProgramPermissionEvaluator(programService, securityService, groupEvaluator)
    private val projectService = ProjectServiceImpl(projectRepo, programRepo, keyCounterRepo, projectPermissionRepo, programPermissionRepo, programPermissionEvaluator)
    private val projectPermissionEvaluator = ProjectPermissionEvaluator(projectService, securityService, groupEvaluator)
    private val versionService = VersionServiceImpl(versionRepo)
    private val releaseService = ReleaseServiceImpl(releaseRepo)

    private val depDeclService = DependencyDeclarationServiceImpl(depDeclRepo, projectRepo, programRepo, dispatcher)
    private val artifactPubService = ArtifactPublicationServiceImpl(artifactPubRepo, projectRepo, programRepo, dispatcher)
    private val apiSurfaceService = ApiSurfaceReportServiceImpl(apiSurfaceRepo, depDeclRepo)
    private val pipelineRunService = PipelineRunServiceImpl(pipelineRunRepo, pipelineStageRepo, projectRepo, programRepo, dispatcher)
    // Channel compatibility reads the release's declared CI artifacts through this seam — tests stub
    // declarations per release; the default (no declarations) keeps every project generic-deployable.
    private val releasePipelines = mockk<ReleasePipelineService> {
        coEvery { releaseDeclaredArtifacts(any()) } returns emptyList()
    }
    private val releasePipelineProvider = mockk<ObjectProvider<ReleasePipelineService>> {
        coEvery { get() } returns releasePipelines
    }
    private val envPermissionRepo = EnvironmentPermissionRepositoryImpl()
    private val envService = EnvironmentServiceImpl(envRepo, envDeployRepo, releaseRepo, projectRepo, programRepo, envPermissionRepo, programPermissionEvaluator, dispatcher, releasePipelineProvider)
    private val compatService = CompatibilityTestResultServiceImpl(compatRepo)
    private val releaseNotesTaskRepo = ReleaseNotesTaskRepositoryImpl()
    private val releaseNotesService = ReleaseNotesServiceImpl(
        releaseNotesRepo,
        releaseRepo,
        releaseNotesTaskRepo,
        projectRepo,
        mockk(),
        versionRepo,
        mockk(),
        mockk(),
        mockk(),
        mockk(),
        mockk(),
        json,
    )
    private val rcvDeployRepo = ReleaseProjectVersionDeploymentRepositoryImpl()
    private val releaseDeploymentService = ReleaseDeploymentServiceImpl(rcvDeployRepo)
    private val graphService = DependencyGraphServiceImpl(depDeclRepo)
    private val buildReadinessService = BuildReadinessServiceImpl(depDeclRepo, compatRepo, artifactPubRepo)
    private val appBuildNumberService = AppBuildNumberServiceImpl(appBuildNumberRepo)

    private var portfolioId: UUID = UUID.NIL
    private var programId: UUID = UUID.NIL
    private var coreProjectId: UUID = UUID.NIL
    private var serverProjectId: UUID = UUID.NIL

    /** The V44-seeded global "development" environment type — the default for test environments. */
    private var devTypeId: UUID = UUID.NIL

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { json }
        provides<bosca.pubsub.PubSubService>(singleton = true) { mockk(relaxed = true) }
        provides<bosca.sharedqueue.jobs.JobQueue>(name = "workops", singleton = true) { mockk(relaxed = true) }
        if (!schemaInitialized) {
            runBlocking { FlywayMigration(pool).migrate(listOf(CoreMigration(), WorkOpsMigration())) }
            schemaInitialized = true
        }
        withDb {
            connection().useStatement("delete from workops.release_notes") { it.execute() }
            connection().useStatement("delete from workops.compatibility_test_result") { it.execute() }
            connection().useStatement("delete from workops.release_gate") { it.execute() }
            connection().useStatement("delete from workops.environment_deployment") { it.execute() }
            connection().useStatement("delete from workops.app_build_number_allocation") { it.execute() }
            connection().useStatement("delete from workops.app_build_number_counter") { it.execute() }
            connection().useStatement("delete from workops.environment") { it.execute() }
            // Keep the V44-seeded catalog; drop only types tests added.
            connection().useStatement("delete from workops.environment_type where name not in ('preview','development','staging','production')") { it.execute() }
            connection().useStatement("delete from workops.pipeline_stage_run") { it.execute() }
            connection().useStatement("delete from workops.pipeline_run") { it.execute() }
            connection().useStatement("delete from workops.api_surface_report") { it.execute() }
            connection().useStatement("delete from workops.artifact_publication") { it.execute() }
            connection().useStatement("delete from workops.dependency_declaration") { it.execute() }
            connection().useStatement("delete from workops.release_component_version") { it.execute() }
            connection().useStatement("delete from workops.release") { it.execute() }
            connection().useStatement("delete from workops.version") { it.execute() }
            connection().useStatement("delete from workops.project_key_counter") { it.execute() }
            connection().useStatement("delete from workops.project") { it.execute() }
            connection().useStatement("delete from workops.program") { it.execute() }
            connection().useStatement("delete from workops.portfolio") { it.execute() }
        }
        withDb { seedProjects() }
    }

    private fun withDb(block: suspend () -> Unit) = runBlocking {
        val mgr = pool.connection()
        try {
            withContext(mgr.asCoroutineContext()) { block() }
        } finally {
            mgr.release()
        }
    }

    private suspend fun <T> withDbResult(block: suspend () -> T): T {
        val mgr = pool.connection()
        return try {
            withContext(mgr.asCoroutineContext()) { block() }
        } finally {
            mgr.release()
        }
    }

    private suspend fun seedProjects() {
        devTypeId = envTypeRepo.getByName("development")?.id
            ?: error("V44 must seed the global environment types")
        val owner = UUID.NIL
        val portfolio = portfolioService.create(PortfolioInput(key = "TST", name = "Test Portfolio", ownerProfileId = owner))
        portfolioId = portfolio.id
        val program = programService.create(ProgramInput(portfolioId = portfolioId, key = "PLAT", name = "Platform Libraries", ownerProfileId = owner))
        programId = program.id
        val core = projectService.create(ProjectInput(programId = programId, key = "CORE", name = "bosca-core", ownerProfileId = owner))
        coreProjectId = core.id
        val server = projectService.create(ProjectInput(programId = programId, key = "SRV", name = "bosca-server", ownerProfileId = owner))
        serverProjectId = server.id
    }

    @Test
    fun `app build numbers serialize concurrent sources and reuse the same source identity`() = runBlocking {
        val repositoryId = UUID.random()
        val inputs = (1..12).map { index ->
            AllocateAppBuildNumberInput(
                platform = AppBuildPlatform.ANDROID,
                applicationId = "io.bosca.concurrent",
                repositoryId = repositoryId,
                sourceCommitSha = index.toString(16).padStart(40, '0'),
                sourceVersion = "6.2.$index",
                pipelineRunId = UUID.random(),
            )
        }

        val allocations = coroutineScope {
            inputs.map { input ->
                async { withDbResult { appBuildNumberService.allocate(input) } }
            }.awaitAll()
        }

        assertEquals((1L..12L).toSet(), allocations.map { it.allocation.number }.toSet())
        assertTrue(allocations.none { it.reused })

        val original = allocations[4].allocation
        val retry = withDbResult {
            appBuildNumberService.allocate(inputs[4].copy(pipelineRunId = UUID.random()))
        }
        assertTrue(retry.reused)
        assertEquals(original.id, retry.allocation.id)
        assertEquals(original.number, retry.allocation.number)
    }

    @Test
    fun `iOS build numbers increment across patches and restart for minor and major releases`() = runBlocking {
        val repositoryId = UUID.random()
        val versions = listOf("6.2.0", "6.2.1", "6.3.0", "7.0.0")
        val allocations = versions.mapIndexed { index, sourceVersion ->
            withDbResult {
                appBuildNumberService.allocate(
                    AllocateAppBuildNumberInput(
                        platform = AppBuildPlatform.IOS,
                        applicationId = "app.bosca.ios-scoped",
                        repositoryId = repositoryId,
                        sourceCommitSha = (index + 1).toString(16).padStart(40, '0'),
                        sourceVersion = sourceVersion,
                        pipelineRunId = UUID.random(),
                    ),
                )
            }
        }

        assertEquals(listOf(1L, 2L, 1L, 1L), allocations.map { it.allocation.number })
        assertEquals(listOf("1", "2", "1", "1"), allocations.map { it.allocation.value })
        assertEquals(listOf("6.2", "6.2", "6.3", "7.0"), allocations.map { it.allocation.versionScope })

        val retry = withDbResult {
            appBuildNumberService.allocate(
                AllocateAppBuildNumberInput(
                    platform = AppBuildPlatform.IOS,
                    applicationId = "app.bosca.ios-scoped",
                    repositoryId = repositoryId,
                    sourceCommitSha = "1".padStart(40, '0'),
                    sourceVersion = "6.2.0",
                    pipelineRunId = UUID.random(),
                ),
            )
        }
        assertTrue(retry.reused)
        assertEquals(allocations.first().allocation.id, retry.allocation.id)
        assertEquals(1L, retry.allocation.number)
    }

    // ── Dependency Declaration ─────────────────────────────────────────

    @Test
    fun `declare dependency between two projects and query both directions`() = withDb {
        val dep = depDeclService.declare(
            CreateDependencyDeclarationInput(
                consumerProjectId = serverProjectId,
                providerProjectId = coreProjectId,
                providerVersionConstraint = ">=4.3.0 <5.0.0",
                dependencyType = DependencyType.BUILD,
                artifactCoordinates = "io.bosca:bosca-bom:4.3.0",
            )
        )
        assertNotNull(dep.id)
        assertEquals(DependencyStatus.CURRENT, dep.status)
        assertEquals(DependencyType.BUILD, dep.dependencyType)
        assertEquals("io.bosca:bosca-bom:4.3.0", dep.artifactCoordinates)

        val byConsumer = depDeclService.listByConsumer(serverProjectId)
        assertEquals(1, byConsumer.size)
        assertEquals(coreProjectId, byConsumer[0].providerProjectId)

        val byProvider = depDeclService.listByProvider(coreProjectId)
        assertEquals(1, byProvider.size)
        assertEquals(serverProjectId, byProvider[0].consumerProjectId)
    }

    @Test
    fun `self-dependency is rejected`() = withDb {
        assertFailsWith<Exception> {
            depDeclService.declare(
                CreateDependencyDeclarationInput(
                    consumerProjectId = coreProjectId,
                    providerProjectId = coreProjectId,
                    providerVersionConstraint = ">=1.0.0",
                    dependencyType = DependencyType.BUILD,
                )
            )
        }
    }

    @Test
    fun `dependency status transitions through full lifecycle`() = withDb {
        val dep = depDeclService.declare(
            CreateDependencyDeclarationInput(
                consumerProjectId = serverProjectId,
                providerProjectId = coreProjectId,
                providerVersionConstraint = ">=4.3.0",
                dependencyType = DependencyType.BUILD,
            )
        )
        assertEquals(DependencyStatus.CURRENT, dep.status)
        assertEquals(0L, dep.version)

        val outdated = depDeclService.updateStatus(dep.id, DependencyStatus.OUTDATED, dep.version)
        assertEquals(DependencyStatus.OUTDATED, outdated.status)
        assertEquals(1L, outdated.version)

        val incompatible = depDeclService.updateStatus(outdated.id, DependencyStatus.INCOMPATIBLE, outdated.version)
        assertEquals(DependencyStatus.INCOMPATIBLE, incompatible.status)
        assertEquals(2L, incompatible.version)

        val resolved = depDeclService.updateResolvedVersion(incompatible.id, null, DependencyStatus.CURRENT, incompatible.version)
        assertEquals(DependencyStatus.CURRENT, resolved.status)
        assertEquals(3L, resolved.version)
    }

    @Test
    fun `delete dependency removes it`() = withDb {
        val dep = depDeclService.declare(
            CreateDependencyDeclarationInput(
                consumerProjectId = serverProjectId,
                providerProjectId = coreProjectId,
                providerVersionConstraint = ">=1.0.0",
                dependencyType = DependencyType.RUNTIME,
            )
        )
        depDeclService.remove(dep.id)
        assertNull(depDeclService.getById(dep.id))
    }

    // ── Artifact Publication ──────────────────────────────────────────

    @Test
    fun `register artifact and transition through publication lifecycle`() = withDb {
        val version = versionService.create(CreateVersionInput(coreProjectId, "4.3.0"))

        val artifact = artifactPubService.register(
            RegisterArtifactInput(
                versionId = version.id,
                projectId = coreProjectId,
                artifactType = ArtifactType.MAVEN,
                coordinates = "io.bosca:core:4.3.0",
                repositoryUrl = "https://maven.example.com",
            )
        )
        assertEquals(PublicationStatus.PENDING, artifact.status)
        assertNull(artifact.publishedAt)

        val published = artifactPubService.markPublished(artifact.id, UUID.NIL, artifact.version)
        assertEquals(PublicationStatus.PUBLISHED, published.status)
        assertNotNull(published.publishedAt)

        val byVersion = artifactPubService.listByVersion(version.id)
        assertEquals(1, byVersion.size)
        assertEquals("io.bosca:core:4.3.0", byVersion[0].coordinates)
    }

    @Test
    fun `mark artifact as failed`() = withDb {
        val version = versionService.create(CreateVersionInput(coreProjectId, "4.3.1-fail"))
        val artifact = artifactPubService.register(
            RegisterArtifactInput(versionId = version.id, projectId = coreProjectId, artifactType = ArtifactType.DOCKER, coordinates = "bosca-core:4.3.1-fail")
        )
        val failed = artifactPubService.markFailed(artifact.id, artifact.version)
        assertEquals(PublicationStatus.FAILED, failed.status)
    }

    @Test
    fun `yank a published artifact`() = withDb {
        val version = versionService.create(CreateVersionInput(coreProjectId, "4.3.1-yank"))
        val artifact = artifactPubService.register(
            RegisterArtifactInput(versionId = version.id, projectId = coreProjectId, artifactType = ArtifactType.MAVEN, coordinates = "io.bosca:core:4.3.1-yank")
        )
        val published = artifactPubService.markPublished(artifact.id, UUID.NIL, artifact.version)
        val yanked = artifactPubService.yank(published.id, published.version)
        assertEquals(PublicationStatus.YANKED, yanked.status)
    }

    // ── Pipeline Run ──────────────────────────────────────────────────

    @Test
    fun `create pipeline run with stages and complete them`() = withDb {
        val run = pipelineRunService.create(
            CreatePipelineRunInput(
                projectId = coreProjectId,
                pipelineId = "build",
                pipelineName = "Build & Test",
                triggerType = PipelineTriggerType.PUSH,
                triggerRef = "refs/heads/main",
            )
        )
        assertEquals(PipelineStatus.PENDING, run.status)
        assertNull(run.completedAt)

        val stage1 = pipelineRunService.addStage(run.id, "compile", null)
        val stage2 = pipelineRunService.addStage(run.id, "test", null)
        assertEquals(PipelineStatus.RUNNING, stage1.status)

        val s1Done = pipelineRunService.completeStage(stage1.id, PipelineStatus.PASSED)
        assertEquals(PipelineStatus.PASSED, s1Done.status)
        assertNotNull(s1Done.completedAt)

        pipelineRunService.completeStage(stage2.id, PipelineStatus.PASSED)

        val completed = pipelineRunService.complete(run.id, PipelineStatus.PASSED, run.version)
        assertEquals(PipelineStatus.PASSED, completed.status)
        assertNotNull(completed.completedAt)

        val stages = pipelineRunService.listStages(run.id)
        assertEquals(2, stages.size)
        assertTrue(stages.all { it.status == PipelineStatus.PASSED })
    }

    @Test
    fun `failed pipeline run records failure status`() = withDb {
        val run = pipelineRunService.create(
            CreatePipelineRunInput(
                projectId = serverProjectId,
                pipelineId = "compile-check",
                pipelineName = "Compile Check",
                triggerType = PipelineTriggerType.WEBHOOK,
            )
        )
        val failed = pipelineRunService.complete(run.id, PipelineStatus.FAILED, run.version)
        assertEquals(PipelineStatus.FAILED, failed.status)

        val runs = pipelineRunService.listByProject(serverProjectId)
        assertEquals(1, runs.size)
        assertEquals(PipelineStatus.FAILED, runs[0].status)
    }

    // ── Environment ───────────────────────────────────────────────────

    @Test
    fun `create environment promotion chain and deploy`() = withDb {
        val dev = envService.create(CreateEnvironmentInput(programId, "dev", "dev", typeId = devTypeId, displayOrder = 1))
        val staging = envService.create(
            CreateEnvironmentInput(programId, "staging", "staging", typeId = devTypeId, displayOrder = 2, promotionSourceIds = listOf(dev.id))
        )
        val prod = envService.create(
            CreateEnvironmentInput(programId, "prod", "prod", typeId = devTypeId, displayOrder = 3, promotionSourceIds = listOf(staging.id), requiresApproval = true)
        )
        assertFalse(dev.requiresApproval)
        assertTrue(prod.requiresApproval)
        assertEquals(listOf(staging.id), envService.promotionSourceIds(prod.id))
        assertEquals(listOf(dev.id), envService.promotionSourceIds(staging.id))

        val envs = envService.listByProgram(programId)
        assertEquals(3, envs.size)
        assertEquals(listOf("dev", "staging", "prod"), envs.map { it.name })
    }

    @Test
    fun `deploy to environment and track health`() = withDb {
        val env = envService.create(CreateEnvironmentInput(programId, "test-env", "test-env", typeId = devTypeId))
        val version = versionService.create(CreateVersionInput(coreProjectId, "4.3.0-deploy"))

        val deployment = envService.createDeployment(
            DeployInput(environmentId = env.id, projectId = coreProjectId, versionId = version.id, healthCheckUrl = "http://localhost:8080/health"),
            createdByPrincipalId = UUID.NIL,
        )
        assertEquals(EnvironmentDeploymentStatus.PENDING, deployment.status)
        assertEquals(HealthCheckStatus.UNKNOWN, deployment.healthCheckStatus)
        assertNull(deployment.previousDeploymentId)

        val deployed = envService.markDeployed(deployment.id, UUID.NIL, deployment.version)
        assertEquals(EnvironmentDeploymentStatus.DEPLOYED, deployed.status)
        assertNotNull(deployed.deployedAt)

        val healthy = envService.updateHealthCheck(deployed.id, HealthCheckStatus.HEALTHY, deployed.version)
        assertEquals(HealthCheckStatus.HEALTHY, healthy.healthCheckStatus)
        assertNotNull(healthy.lastHealthCheckAt)

        val state = envService.currentState(env.id)
        assertEquals(1, state.size)
        assertEquals(coreProjectId, state[0].projectId)
        assertEquals(HealthCheckStatus.HEALTHY, state[0].healthCheckStatus)
    }

    @Test
    fun `redeployment links to previous deployment`() = withDb {
        val env = envService.create(CreateEnvironmentInput(programId, "redeploy-env", "redeploy-env", typeId = devTypeId))
        val v1 = versionService.create(CreateVersionInput(coreProjectId, "4.3.0-redep"))
        val v2 = versionService.create(CreateVersionInput(coreProjectId, "4.3.1-redep"))

        val dep1 = envService.createDeployment(
            DeployInput(environmentId = env.id, projectId = coreProjectId, versionId = v1.id),
            createdByPrincipalId = UUID.NIL,
        )
        envService.markDeployed(dep1.id, UUID.NIL, dep1.version)

        val dep2 = envService.createDeployment(
            DeployInput(environmentId = env.id, projectId = coreProjectId, versionId = v2.id),
            createdByPrincipalId = UUID.NIL,
        )
        assertEquals(dep1.id, dep2.previousDeploymentId)
    }

    @Test
    fun `current deployment and rollback chain are independent per target`() = withDb {
        val env = envService.create(CreateEnvironmentInput(programId, "multi-target", "multi-target", typeId = devTypeId))
        val v1 = versionService.create(CreateVersionInput(coreProjectId, "4.3.0-multi-target"))
        val v2 = versionService.create(CreateVersionInput(coreProjectId, "4.3.1-multi-target"))

        val helm = envService.createDeployment(
            DeployInput(env.id, coreProjectId, targetKind = DeployTargetKind.HELM_VALUES, versionId = v1.id),
            createdByPrincipalId = UUID.NIL,
        )
        envService.markDeployed(helm.id, UUID.NIL, helm.version)
        val play = envService.createDeployment(
            DeployInput(env.id, coreProjectId, targetKind = DeployTargetKind.GOOGLE_PLAY, versionId = v1.id),
            createdByPrincipalId = UUID.NIL,
        )
        envService.markDeployed(play.id, UUID.NIL, play.version)

        val nextPlay = envService.createDeployment(
            DeployInput(env.id, coreProjectId, targetKind = DeployTargetKind.GOOGLE_PLAY, versionId = v2.id),
            createdByPrincipalId = UUID.NIL,
        )
        assertEquals(play.id, nextPlay.previousDeploymentId)
        envService.markDeployed(nextPlay.id, UUID.NIL, nextPlay.version)

        val serverVersion = versionService.create(CreateVersionInput(serverProjectId, "4.3.0-server-multi-target"))
        val firstServerDeployment = envService.createDeployment(
            DeployInput(env.id, serverProjectId, targetKind = DeployTargetKind.GOOGLE_PLAY, versionId = serverVersion.id),
            createdByPrincipalId = UUID.NIL,
        )
        assertNull(firstServerDeployment.previousDeploymentId)

        val current = envService.currentState(env.id).associateBy { it.targetKind }
        assertEquals(setOf(DeployTargetKind.HELM_VALUES, DeployTargetKind.GOOGLE_PLAY), current.keys)
        assertEquals(v1.id, current.getValue(DeployTargetKind.HELM_VALUES).versionId)
        assertEquals(v2.id, current.getValue(DeployTargetKind.GOOGLE_PLAY).versionId)
    }

    @Test
    fun `environment type and target taxonomy round-trip through their columns`() = withDb {
        // The V44-seeded global catalog is what environments reference.
        val productionTypeId = envTypeRepo.getByName("production")?.id ?: error("seeded 'production' type missing")
        val previewTypeId = envTypeRepo.getByName("preview")?.id ?: error("seeded 'preview' type missing")
        val stagingTypeId = envTypeRepo.getByName("staging")?.id ?: error("seeded 'staging' type missing")

        val playProd = envService.create(
            CreateEnvironmentInput(
                programId, "play-prod", "play-prod", displayOrder = 9,
                typeId = productionTypeId,
                targetType = EnvironmentTargetType.PLAY_TRACK,
                targetRef = "production",
            ),
        )
        // Read back independently to prove the type_id FK and pg enum target column persist.
        val reloaded = envService.getById(playProd.id) ?: error("environment missing")
        assertEquals(productionTypeId, reloaded.typeId)
        assertEquals(EnvironmentTargetType.PLAY_TRACK, reloaded.targetType)
        assertEquals("production", reloaded.targetRef)
        assertFalse(reloaded.ephemeral)

        // An ephemeral preview (defaults to GENERIC), then updated in place to a TestFlight staging env.
        val preview = envService.create(CreateEnvironmentInput(programId, "pr-123", "pr-123", typeId = previewTypeId, ephemeral = true))
        assertEquals(previewTypeId, preview.typeId)
        assertEquals(EnvironmentTargetType.GENERIC, preview.targetType)
        assertTrue(preview.ephemeral)

        val updated = envService.update(
            preview.id,
            UpdateEnvironmentInput(name = "pr-123", typeId = stagingTypeId, targetType = EnvironmentTargetType.TESTFLIGHT),
            preview.version,
        )
        assertEquals(stagingTypeId, updated.typeId)
        assertEquals(EnvironmentTargetType.TESTFLIGHT, updated.targetType)
    }

    @Test
    fun `environment types are a global user-managed catalog with an in-use delete guard`() = withDb {
        val typeService = EnvironmentTypeServiceImpl(envTypeRepo)
        // The V44 seed is present, ordered, and case-insensitively resolvable by name.
        val seeded = typeService.list().map { it.name }
        assertEquals(listOf("preview", "development", "staging", "production"), seeded.take(4))
        assertNotNull(typeService.getByName("PRODUCTION"))

        // Users extend the catalog; duplicate names are refused.
        val qa = typeService.create("qa", "Manual verification", 9)
        assertFailsWith<IllegalStateException> { typeService.create("qa", null, 10) }

        // A type in use can't be deleted; retyping the environment frees it.
        val env = envService.create(CreateEnvironmentInput(programId, "qa-env", "qa-env", typeId = qa.id))
        assertFailsWith<IllegalStateException> { typeService.delete(qa.id) }
        envService.update(
            env.id,
            UpdateEnvironmentInput(name = "qa-env", typeId = devTypeId),
            env.version,
        )
        typeService.delete(qa.id)
        assertNull(typeService.getById(qa.id))
    }

    @Test
    fun `promote moves deployed versions into the target and stamps the release`() = withDb {
        val staging = envService.create(CreateEnvironmentInput(programId, "promo-staging", "promo-staging", typeId = devTypeId))
        val prod = envService.create(CreateEnvironmentInput(programId, "promo-prod", "promo-prod", typeId = devTypeId, promotionSourceIds = listOf(staging.id)))
        val version = versionService.create(CreateVersionInput(coreProjectId, "5.0.0-promo"))
        val previousVersion = versionService.create(CreateVersionInput(coreProjectId, "4.9.0-promo"))
        val release = releaseService.create(programId, "2025.11-promo", null, null, null)

        val previousProd = envService.createDeployment(
            DeployInput(environmentId = prod.id, projectId = coreProjectId, versionId = previousVersion.id),
            createdByPrincipalId = UUID.NIL,
        )
        envService.markDeployed(previousProd.id, UUID.NIL, previousProd.version)

        // Deploy into staging, stamped with the release, marked DEPLOYED so it is staging's current state.
        val dep = envService.createDeployment(
            DeployInput(environmentId = staging.id, projectId = coreProjectId, versionId = version.id, releaseId = release.id),
            createdByPrincipalId = UUID.NIL,
        )
        envService.markDeployed(dep.id, UUID.NIL, dep.version)

        val promoted = envService.createPromotionDeployment(staging.id, prod.id, release.id, UUID.NIL)
        assertEquals(1, promoted.size)
        // A promotion records INTENT — a PENDING deployment on the target, not a confirmed one.
        assertEquals(EnvironmentDeploymentStatus.PENDING, promoted[0].status)
        assertEquals(version.id, promoted[0].versionId)
        assertEquals(prod.id, promoted[0].environmentId)
        assertEquals(previousProd.id, promoted[0].previousDeploymentId)

        // The pending intent does not replace prod's last confirmed state.
        assertEquals(previousVersion.id, envService.currentState(prod.id).single().versionId)

        // Confirm the target deployment actually landed (the honest DEPLOYED transition); now prod runs it.
        envService.markDeployed(promoted[0].id, UUID.NIL, promoted[0].version)
        val prodState = envService.currentState(prod.id)
        assertEquals(1, prodState.size)
        assertEquals(version.id, prodState[0].versionId)

        // The release's "what artifact/version is in what environment" view sees both deployments.
        val byRelease = envService.deploymentsByRelease(release.id)
        assertEquals(2, byRelease.size)
        assertTrue(byRelease.all { it.releaseId == release.id })
        assertEquals(setOf(staging.id, prod.id), byRelease.map { it.environmentId }.toSet())
    }

    @Test
    fun `first promotion into a target has no previous deployment`() = withDb {
        val source = envService.create(CreateEnvironmentInput(programId, "first-promo-source", "first-promo-source", typeId = devTypeId))
        val target = envService.create(
            CreateEnvironmentInput(
                programId, "first-promo-target", "first-promo-target",
                typeId = devTypeId,
                promotionSourceIds = listOf(source.id),
            ),
        )
        val version = versionService.create(CreateVersionInput(coreProjectId, "5.0.0-first-promo"))
        val sourceDeployment = envService.createDeployment(
            DeployInput(source.id, coreProjectId, targetKind = DeployTargetKind.HELM_VALUES, versionId = version.id),
            UUID.NIL,
        )
        envService.markDeployed(sourceDeployment.id, UUID.NIL, sourceDeployment.version)

        val promoted = envService.createPromotionDeployment(source.id, target.id, null, UUID.NIL).single()

        assertEquals(DeployTargetKind.HELM_VALUES, promoted.targetKind)
        assertNull(promoted.previousDeploymentId)
    }

    @Test
    fun `promote refuses a move that is not a configured promotion edge`() = withDb {
        val a = envService.create(CreateEnvironmentInput(programId, "edge-a", "edge-a", typeId = devTypeId))
        val b = envService.create(CreateEnvironmentInput(programId, "edge-b", "edge-b", typeId = devTypeId)) // no promotion source configured
        val e = assertFailsWith<IllegalArgumentException> { envService.createPromotionDeployment(a.id, b.id, null, UUID.NIL) }
        assertTrue("promotion edge" in (e.message ?: ""), e.message)
    }

    // ── Compatibility Test Results ────────────────────────────────────

    @Test
    fun `record compatibility test results for version pair`() = withDb {
        val coreVersion = versionService.create(CreateVersionInput(coreProjectId, "4.3.0-compat"))
        val serverVersion = versionService.create(CreateVersionInput(serverProjectId, "1.0.0-compat"))

        val passed = compatService.record(
            RecordCompatibilityResultInput(
                consumerProjectId = serverProjectId,
                consumerVersionId = serverVersion.id,
                providerProjectId = coreProjectId,
                providerVersionId = coreVersion.id,
                testSuite = "compile-check",
                status = CompatibilityStatus.PASSED,
            )
        )
        assertEquals(CompatibilityStatus.PASSED, passed.status)
        assertFalse(passed.breakingChangesDetected)

        val failedResult = compatService.record(
            RecordCompatibilityResultInput(
                consumerProjectId = serverProjectId,
                consumerVersionId = serverVersion.id,
                providerProjectId = coreProjectId,
                providerVersionId = coreVersion.id,
                testSuite = "integration-smoke",
                status = CompatibilityStatus.FAILED,
                breakingChangesDetected = true,
            )
        )
        assertEquals(CompatibilityStatus.FAILED, failedResult.status)
        assertTrue(failedResult.breakingChangesDetected)

        val results = compatService.listByConsumerVersion(serverProjectId, serverVersion.id)
        assertEquals(2, results.size)
    }

    // ── API Surface Report + Dependency Cascade ───────────────────────

    @Test
    fun `breaking API surface report flips consumer dependencies to INCOMPATIBLE`() = withDb {
        val dep = depDeclService.declare(
            CreateDependencyDeclarationInput(
                consumerProjectId = serverProjectId,
                providerProjectId = coreProjectId,
                providerVersionConstraint = ">=4.0.0",
                dependencyType = DependencyType.BUILD,
            )
        )
        assertEquals(DependencyStatus.CURRENT, dep.status)

        val v1 = versionService.create(CreateVersionInput(coreProjectId, "4.3.0-break"))
        val v2 = versionService.create(CreateVersionInput(coreProjectId, "5.0.0-break"))

        val report = bosca.workops.model.artifact.ApiSurfaceReport(
            projectId = coreProjectId,
            versionId = v2.id,
            previousVersionId = v1.id,
            breakingChangeLevel = BreakingChangeLevel.MAJOR_BREAKING,
            changes = JsonArray(emptyList()),
            analyzerTool = "japicmp",
        )
        apiSurfaceService.register(report, json)

        val updated = depDeclService.getById(dep.id)
        assertNotNull(updated)
        assertEquals(DependencyStatus.INCOMPATIBLE, updated.status)
    }

    @Test
    fun `compatible API surface report does not flip dependency status`() = withDb {
        val dep = depDeclService.declare(
            CreateDependencyDeclarationInput(
                consumerProjectId = serverProjectId,
                providerProjectId = coreProjectId,
                providerVersionConstraint = ">=4.0.0",
                dependencyType = DependencyType.BUILD,
            )
        )

        val v1 = versionService.create(CreateVersionInput(coreProjectId, "4.4.0-compat"))
        val v2 = versionService.create(CreateVersionInput(coreProjectId, "4.4.1-compat"))

        val report = bosca.workops.model.artifact.ApiSurfaceReport(
            projectId = coreProjectId,
            versionId = v2.id,
            previousVersionId = v1.id,
            breakingChangeLevel = BreakingChangeLevel.NONE,
            changes = JsonArray(emptyList()),
            analyzerTool = "japicmp",
        )
        apiSurfaceService.register(report, json)

        val unchanged = depDeclService.getById(dep.id)
        assertNotNull(unchanged)
        assertEquals(DependencyStatus.CURRENT, unchanged.status)
    }

    @Test
    fun `deprecation-level report does not flip dependency status`() = withDb {
        val dep = depDeclService.declare(
            CreateDependencyDeclarationInput(
                consumerProjectId = serverProjectId,
                providerProjectId = coreProjectId,
                providerVersionConstraint = ">=4.0.0",
                dependencyType = DependencyType.BUILD,
            )
        )

        val v1 = versionService.create(CreateVersionInput(coreProjectId, "4.5.0-dep"))
        val v2 = versionService.create(CreateVersionInput(coreProjectId, "4.5.1-dep"))

        val report = bosca.workops.model.artifact.ApiSurfaceReport(
            projectId = coreProjectId,
            versionId = v2.id,
            previousVersionId = v1.id,
            breakingChangeLevel = BreakingChangeLevel.DEPRECATION,
            changes = JsonArray(emptyList()),
            analyzerTool = "japicmp",
        )
        apiSurfaceService.register(report, json)

        val unchanged = depDeclService.getById(dep.id)
        assertNotNull(unchanged)
        assertEquals(DependencyStatus.CURRENT, unchanged.status)
    }

    // ── Release Notes ─────────────────────────────────────────────────

    @Test
    fun `generate and manually edit release notes`() = withDb {
        val release = releaseService.create(programId, "2025.08-notes", null, null, null)

        val sections = """[{"category":"NEW_FEATURE","entries":[{"taskId":"${UUID.NIL}","taskKey":"CORE-1","summary":"Add dependency graph","projectName":"bosca-core"}]}]"""
        val generated = releaseNotesService.generate(release.id, sections)
        assertNotNull(generated.id)
        assertFalse(generated.manuallyEdited)

        val edited = releaseNotesService.editManually(release.id, sections, generated.version)
        assertTrue(edited.manuallyEdited)
        assertEquals(generated.version + 1, edited.version)

        val fetched = releaseNotesService.getByRelease(release.id)
        assertNotNull(fetched)
        assertTrue(fetched.manuallyEdited)
    }

    @Test
    fun `auto-generate release notes produces empty sections when no tasks reference bundled versions`() = withDb {
        val release = releaseService.create(programId, "auto-gen-empty", null, null, null)
        val version = versionService.create(CreateVersionInput(coreProjectId, "4.3.0-auto-notes"))
        releaseService.bundle(release.id, coreProjectId, version.id)

        val notes = releaseNotesService.autoGenerate(release.id)
        assertNotNull(notes.id)
        assertFalse(notes.manuallyEdited)
        assertEquals("[]", json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), notes.sections))
    }

    @Test
    fun `regenerating release notes overwrites manual edits`() = withDb {
        val release = releaseService.create(programId, "2025.09-regen", null, null, null)
        val sections1 = """[{"category":"BUG_FIX","entries":[]}]"""
        val sections2 = """[{"category":"NEW_FEATURE","entries":[]}]"""

        releaseNotesService.generate(release.id, sections1)
        val edited = releaseNotesService.getByRelease(release.id)!!
        releaseNotesService.editManually(release.id, sections1, edited.version)

        val regenerated = releaseNotesService.generate(release.id, sections2)
        assertFalse(regenerated.manuallyEdited)
    }

    // ── Release ───────────────────────────────────────────────────────

    @Test
    fun `markReleased sets releasedAt`() = withDb {
        val release = releaseService.create(programId, "2025.10-nogates", null, null, null)
        val released = releaseService.release(release.id, release.version)
        assertNotNull(released.releasedAt)
    }

    // ── Dependency Graph Service ──────────────────────────────────────

    @Test
    fun `transitive consumers walks the dependency graph downstream`() = withDb {
        val thirdProject = projectService.create(
            ProjectInput(programId = programId, key = "WEB", name = "bosca-web", ownerProfileId = UUID.NIL)
        )

        depDeclService.declare(CreateDependencyDeclarationInput(
            consumerProjectId = serverProjectId, providerProjectId = coreProjectId,
            providerVersionConstraint = ">=4.0.0", dependencyType = DependencyType.BUILD,
        ))
        depDeclService.declare(CreateDependencyDeclarationInput(
            consumerProjectId = thirdProject.id, providerProjectId = serverProjectId,
            providerVersionConstraint = ">=1.0.0", dependencyType = DependencyType.BUILD,
        ))

        val consumers = graphService.transitiveConsumerProjectIds(coreProjectId)
        assertTrue(serverProjectId in consumers)
        assertTrue(thirdProject.id in consumers)
        assertEquals(2, consumers.size)
    }

    @Test
    fun `transitive providers walks the dependency graph upstream`() = withDb {
        val thirdProject = projectService.create(
            ProjectInput(programId = programId, key = "DI2", name = "bosca-di", ownerProfileId = UUID.NIL)
        )

        depDeclService.declare(CreateDependencyDeclarationInput(
            consumerProjectId = coreProjectId, providerProjectId = thirdProject.id,
            providerVersionConstraint = ">=1.0.0", dependencyType = DependencyType.BUILD,
        ))
        depDeclService.declare(CreateDependencyDeclarationInput(
            consumerProjectId = serverProjectId, providerProjectId = coreProjectId,
            providerVersionConstraint = ">=4.0.0", dependencyType = DependencyType.BUILD,
        ))

        val providers = graphService.transitiveProviderProjectIds(serverProjectId)
        assertTrue(coreProjectId in providers)
        assertTrue(thirdProject.id in providers)
        assertEquals(2, providers.size)
    }

    @Test
    fun `transitive consumers returns empty for leaf project`() = withDb {
        val consumers = graphService.transitiveConsumerProjectIds(serverProjectId)
        assertTrue(consumers.isEmpty())
    }

    // ── Build Readiness Service ───────────────────────────────────────

    @Test
    fun `build readiness reports READY when all dependencies are CURRENT`() = withDb {
        depDeclService.declare(CreateDependencyDeclarationInput(
            consumerProjectId = serverProjectId, providerProjectId = coreProjectId,
            providerVersionConstraint = ">=4.0.0", dependencyType = DependencyType.BUILD,
        ))

        val readiness = buildReadinessService.check(serverProjectId)
        assertTrue(readiness.ready)
        assertTrue(readiness.blockers.isEmpty())
    }

    @Test
    fun `build readiness reports INCOMPATIBLE blocker`() = withDb {
        val dep = depDeclService.declare(CreateDependencyDeclarationInput(
            consumerProjectId = serverProjectId, providerProjectId = coreProjectId,
            providerVersionConstraint = ">=4.0.0", dependencyType = DependencyType.BUILD,
        ))
        depDeclService.updateStatus(dep.id, DependencyStatus.INCOMPATIBLE, dep.version)

        val readiness = buildReadinessService.check(serverProjectId)
        assertFalse(readiness.ready)
        assertEquals(1, readiness.blockers.size)
        assertEquals(BuildBlockerType.INCOMPATIBLE_DEPENDENCY, readiness.blockers[0].blockerType)
    }

    @Test
    fun `build readiness reports READY when no dependencies exist`() = withDb {
        val readiness = buildReadinessService.check(serverProjectId)
        assertTrue(readiness.ready)
    }

    // ── Environment Drift ─────────────────────────────────────────────

    @Test
    fun `drift detects NOT_DEPLOYED when bundled version has no deployment`() = withDb {
        val env = envService.create(CreateEnvironmentInput(programId, "drift-env", "drift-env", typeId = devTypeId))
        val release = releaseService.create(programId, "drift-release", null, null, null)
        val version = versionService.create(CreateVersionInput(coreProjectId, "4.3.0-drift"))
        releaseService.bundle(release.id, coreProjectId, version.id)

        val drift = envService.environmentDrift(env.id, release.id)
        assertEquals(1, drift.size)
        assertEquals(EnvironmentDriftType.NOT_DEPLOYED, drift[0].driftType)
        assertNull(drift[0].deployedVersionId)
        assertEquals(version.id, drift[0].expectedVersionId)
    }

    @Test
    fun `drift ignores a project the release itself marked deployed even with no environment record`() = withDb {
        val env = envService.create(CreateEnvironmentInput(programId, "release-deployed-env", "release-deployed-env", typeId = devTypeId))
        val release = releaseService.create(programId, "release-deployed", null, null, null)
        val version = versionService.create(CreateVersionInput(coreProjectId, "4.3.0-reldeployed"))
        releaseService.bundle(release.id, coreProjectId, version.id)
        // The relay's Mark Deployed sets the release project version's status; it writes no environment
        // deployment. The difference must still count it as deployed rather than reporting NOT_DEPLOYED.
        releaseDeploymentService.markDeployed(release.id, coreProjectId, version.id, UUID.NIL)

        val drift = envService.environmentDrift(env.id, release.id)
        assertTrue(drift.isEmpty(), "a release-deployed project is not a difference, even with no environment record")
    }

    @Test
    fun `drift detects VERSION_MISMATCH when deployed version differs from bundled`() = withDb {
        val env = envService.create(CreateEnvironmentInput(programId, "mismatch-env", "mismatch-env", typeId = devTypeId))
        val v1 = versionService.create(CreateVersionInput(coreProjectId, "4.2.0-old"))
        val v2 = versionService.create(CreateVersionInput(coreProjectId, "4.3.0-new"))
        val release = releaseService.create(programId, "mismatch-release", null, null, null)
        releaseService.bundle(release.id, coreProjectId, v2.id)

        val dep = envService.createDeployment(
            DeployInput(environmentId = env.id, projectId = coreProjectId, versionId = v1.id),
            createdByPrincipalId = UUID.NIL,
        )
        envService.markDeployed(dep.id, UUID.NIL, dep.version)

        val drift = envService.environmentDrift(env.id, release.id)
        assertEquals(1, drift.size)
        assertEquals(EnvironmentDriftType.VERSION_MISMATCH, drift[0].driftType)
        assertEquals(v1.id, drift[0].deployedVersionId)
        assertEquals(v2.id, drift[0].expectedVersionId)
    }

    // ── Release Component Deployment Tracking ───────────────────────

    @Test
    fun `set deployment order and transition through deployment lifecycle`() = withDb {
        val release = releaseService.create(programId, "deploy-lifecycle", null, null, null)
        val version = versionService.create(CreateVersionInput(coreProjectId, "4.3.0-deploy-track"))
        releaseService.bundle(release.id, coreProjectId, version.id)

        val ordered = releaseDeploymentService.setDeploymentOrder(release.id, coreProjectId, version.id, 1)
        assertEquals(1, ordered.deploymentOrder)
        assertEquals("PENDING", ordered.deploymentStatus)

        val deploying = releaseDeploymentService.markDeploying(release.id, coreProjectId, version.id, UUID.NIL)
        assertEquals("DEPLOYING", deploying.deploymentStatus)
        assertNotNull(deploying.deployedAt)

        val deployed = releaseDeploymentService.markDeployed(release.id, coreProjectId, version.id, UUID.NIL)
        assertEquals("DEPLOYED", deployed.deploymentStatus)
    }

    @Test
    fun `rollback records the rollback version`() = withDb {
        val release = releaseService.create(programId, "rollback-test", null, null, null)
        val v1 = versionService.create(CreateVersionInput(coreProjectId, "4.2.0-rb"))
        val v2 = versionService.create(CreateVersionInput(coreProjectId, "4.3.0-rb"))
        releaseService.bundle(release.id, coreProjectId, v2.id)

        val rolledBack = releaseDeploymentService.markRolledBack(release.id, coreProjectId, v2.id, v1.id)
        assertEquals(v1.id, rolledBack.rollbackVersionId)
    }

    @Test
    fun `no drift when deployed version matches bundled`() = withDb {
        val env = envService.create(CreateEnvironmentInput(programId, "nodrift-env", "nodrift-env", typeId = devTypeId))
        val version = versionService.create(CreateVersionInput(coreProjectId, "4.3.0-match"))
        val release = releaseService.create(programId, "nodrift-release", null, null, null)
        releaseService.bundle(release.id, coreProjectId, version.id)

        val dep = envService.createDeployment(
            DeployInput(environmentId = env.id, projectId = coreProjectId, versionId = version.id),
            createdByPrincipalId = UUID.NIL,
        )
        envService.markDeployed(dep.id, UUID.NIL, dep.version)

        val drift = envService.environmentDrift(env.id, release.id)
        assertTrue(drift.isEmpty())
    }

    // ── Channel compatibility: drift only considers projects the environment can take ──

    @Test
    fun `a store environment shows no drift for a project without its platform artifact`() = withDb {
        val env = envService.create(CreateEnvironmentInput(
            programId, "play-internal", "play-internal", typeId = devTypeId, targetType = EnvironmentTargetType.PLAY_TRACK,
        ))
        val release = releaseService.create(programId, "store-drift", null, null, null)
        val version = versionService.create(CreateVersionInput(coreProjectId, "4.3.0-store"))
        releaseService.bundle(release.id, coreProjectId, version.id)
        coEvery { releasePipelines.releaseDeclaredArtifacts(release.id) } returns listOf(
            ReleaseDeclaredArtifact(coreProjectId, ArtifactType.DOCKER, "docker", "core:4.3.0"),
        )

        assertTrue(envService.environmentDrift(env.id, release.id).isEmpty(), "a docker-only project can never deploy to a Play track")
        assertTrue(envService.channelCompatibleProjects(env.id, release.id).isEmpty())
    }

    @Test
    fun `a store environment takes only the project declaring its platform artifact`() = withDb {
        val env = envService.create(CreateEnvironmentInput(
            programId, "play-production", "play-production", typeId = devTypeId, targetType = EnvironmentTargetType.PLAY_TRACK,
        ))
        val release = releaseService.create(programId, "store-mixed", null, null, null)
        val coreVersion = versionService.create(CreateVersionInput(coreProjectId, "4.3.0-mixed"))
        val appVersion = versionService.create(CreateVersionInput(serverProjectId, "1.0.0-android"))
        releaseService.bundle(release.id, coreProjectId, coreVersion.id)
        releaseService.bundle(release.id, serverProjectId, appVersion.id)
        coEvery { releasePipelines.releaseDeclaredArtifacts(release.id) } returns listOf(
            ReleaseDeclaredArtifact(coreProjectId, ArtifactType.DOCKER, "docker", "core:4.3.0"),
            ReleaseDeclaredArtifact(serverProjectId, ArtifactType.ANDROID_AAR, "store", "app-1.0.0.aab"),
        )

        assertEquals(listOf(serverProjectId), envService.channelCompatibleProjects(env.id, release.id))
        val drift = envService.environmentDrift(env.id, release.id)
        assertEquals(1, drift.size)
        assertEquals(serverProjectId, drift[0].projectId)
    }

    @Test
    fun `a generic environment shows no drift for a store-only project`() = withDb {
        val env = envService.create(CreateEnvironmentInput(programId, "generic-env", "generic-env", typeId = devTypeId))
        val release = releaseService.create(programId, "generic-store-only", null, null, null)
        val version = versionService.create(CreateVersionInput(serverProjectId, "1.0.1-android"))
        releaseService.bundle(release.id, serverProjectId, version.id)
        coEvery { releasePipelines.releaseDeclaredArtifacts(release.id) } returns listOf(
            ReleaseDeclaredArtifact(serverProjectId, ArtifactType.ANDROID_AAR, "store", "app-1.0.1.aab"),
        )

        assertTrue(envService.environmentDrift(env.id, release.id).isEmpty(), "an android app never deploys to an infrastructure environment")
        assertTrue(envService.channelCompatibleProjects(env.id, release.id).isEmpty())
    }

    @Test
    fun `an artifact scoped to specific environments serves only those`() = withDb {
        val dev = envService.create(CreateEnvironmentInput(programId, "scoped-development", "scoped-development", typeId = devTypeId))
        val prod = envService.create(CreateEnvironmentInput(programId, "scoped-production", "scoped-production", typeId = devTypeId))
        val release = releaseService.create(programId, "scoped-values", null, null, null)
        val version = versionService.create(CreateVersionInput(coreProjectId, "4.4.0-scoped"))
        releaseService.bundle(release.id, coreProjectId, version.id)
        coEvery { releasePipelines.releaseDeclaredArtifacts(release.id) } returns listOf(
            ReleaseDeclaredArtifact(
                coreProjectId, ArtifactType.HELM_VALUES, "raw", "core-values-4.4.0",
                environments = listOf("scoped-development"),
            ),
        )

        assertEquals(listOf(coreProjectId), envService.channelCompatibleProjects(dev.id, release.id))
        assertTrue(envService.channelCompatibleProjects(prod.id, release.id).isEmpty())
    }

    @Test
    fun `a project with no declared artifacts stays visible in generic environments`() = withDb {
        val env = envService.create(CreateEnvironmentInput(programId, "undeclared-env", "undeclared-env", typeId = devTypeId))
        val release = releaseService.create(programId, "undeclared", null, null, null)
        val version = versionService.create(CreateVersionInput(coreProjectId, "4.5.0-undeclared"))
        releaseService.bundle(release.id, coreProjectId, version.id)

        assertEquals(listOf(coreProjectId), envService.channelCompatibleProjects(env.id, release.id))
        assertEquals(1, envService.environmentDrift(env.id, release.id).size)
    }
}
