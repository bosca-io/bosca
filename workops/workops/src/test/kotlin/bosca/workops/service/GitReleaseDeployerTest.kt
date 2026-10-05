@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.workops.service

import bosca.di.provides
import bosca.git.service.EnvironmentActionAuthorizer
import bosca.git.service.ReleaseDeployRequest
import bosca.git.service.RepositoryBrowseService
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.security.service.SecurityService
import bosca.security.service.impersonate
import bosca.serialization.UUID
import bosca.store.pipelines.AppStorePublisher
import bosca.store.pipelines.AppStoreReviewMode
import bosca.store.pipelines.PlayPublisher
import bosca.store.pipelines.PlayVitalsResult
import bosca.workops.deploy.DeployConfigService
import bosca.workops.deploy.DeployOutcome
import bosca.workops.deploy.DeployRequest
import bosca.workops.deploy.DeployTarget
import bosca.workops.deploy.DeployTargetKind
import bosca.workops.deploy.ProjectDeployConfig
import bosca.workops.deploy.RolloutRequest
import bosca.workops.model.artifact.ArtifactPublication
import bosca.workops.model.artifact.ArtifactType
import bosca.workops.model.artifact.AppBuildNumberAllocation
import bosca.workops.model.artifact.AppBuildNumberAllocationResult
import bosca.workops.model.artifact.AppBuildPlatform
import bosca.workops.model.environment.Environment
import bosca.workops.model.environment.EnvironmentDeploymentStatus
import bosca.workops.model.project.Project
import bosca.workops.model.project.ProjectRepository as ProjectRepositoryLink
import bosca.workops.model.release.ReleaseProjectVersion
import bosca.workops.model.version.Version
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Duration
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GitReleaseDeployerTest {

    private val deployConfigService = mockk<DeployConfigService>()
    private val projectRepositories = mockk<ProjectRepositoryService>()
    private val projectService = mockk<ProjectService>()
    private val versionService = mockk<VersionService>()
    private val releaseService = mockk<ReleaseService>()
    private val publications = mockk<ArtifactPublicationService>()
    private val environmentService = mockk<EnvironmentService>()
    private val authorizer = mockk<EnvironmentActionAuthorizer>(relaxed = true)
    private val securityService = mockk<SecurityService>()
    private val appBuildNumbers = mockk<AppBuildNumberService>()
    private val repositoryBrowse = mockk<RepositoryBrowseService>()
    private val secrets = mockk<bosca.pipelines.service.PipelineSecretService>()
    private val playPublisher = mockk<PlayPublisher>()
    private val appStorePublisher = mockk<AppStorePublisher>()
    private val releaseNotesService = mockk<ReleaseNotesService>()
    private val adapter = mockk<DeployTarget>()

    private val programService = mockk<ProgramService>(relaxed = true)
    private val programPermissionEvaluator = mockk<ProgramPermissionEvaluator>(relaxed = true)

    private val deployer = GitReleaseDeployer(
        deployConfigService, projectRepositories, projectService, versionService, releaseService,
        publications, environmentService, authorizer, programService, programPermissionEvaluator,
        securityService, appBuildNumbers, repositoryBrowse, secrets, playPublisher, appStorePublisher,
        releaseNotesService,
    )

    private val repositoryId = UUID.random()
    private val projectId = UUID.random()
    private val programId = UUID.random()
    private val releaseId = UUID.random()
    private val versionId = UUID.random()
    private val environmentId = UUID.random()
    private val initiator = UUID.random()
    private val sourceCommit = "a".repeat(40)

    @kotlin.test.AfterTest
    fun teardown() {
        // mockkStatic is JVM-global: leaving it mocked breaks every later test that calls
        // SecurityService.impersonate in this test JVM.
        io.mockk.unmockkStatic("bosca.security.service.SecurityServiceKt")
        bosca.di.ProviderRegistry.clear()
    }

    @BeforeTest
    fun setup() {
        bosca.di.ProviderRegistry.clear()
        mockkStatic("bosca.security.service.SecurityServiceKt")
        coEvery { securityService.impersonate(any<UUID>()) } returns mockk<ImpersonatedAuthenticationContext>()
        coEvery { projectRepositories.listByRepository(repositoryId) } returns listOf(
            ProjectRepositoryLink(id = UUID.random(), projectId = projectId, repositoryId = repositoryId),
        )
        coEvery { projectService.getById(projectId) } returns
            Project(id = projectId, programId = programId, key = "SRV", name = "Server", ownerProfileId = UUID.random())
        coEvery { environmentService.getByProgramAndKey(programId, "production") } returns
            Environment(id = environmentId, programId = programId, key = "production", name = "Production")
        coEvery { releaseService.listVersions(releaseId) } returns listOf(
            ReleaseProjectVersion(releaseId = releaseId, projectId = projectId, versionId = versionId),
        )
        coEvery { versionService.getById(versionId) } returns
            Version(id = versionId, projectId = projectId, name = "6.2.0", sequenceNumber = 1)
        provides<DeployTarget>(name = "HELM_VALUES", singleton = true) { adapter }
        provides<DeployTarget>(name = "GOOGLE_PLAY", singleton = true) { adapter }
        provides<DeployTarget>(name = "APP_STORE", singleton = true) { adapter }
    }

    private fun request(
        target: String? = null,
        overrides: Map<String, String> = emptyMap(),
        parameters: Map<String, String> = mapOf("release.id" to releaseId.toString(), "release.version" to "6.2.0"),
    ) = ReleaseDeployRequest(
        targetRepositoryId = repositoryId,
        ref = "refs/tags/6.2.0",
        environmentKey = "production",
        target = target,
        overrides = overrides,
        parameters = parameters,
        initiatorPrincipalId = initiator,
    )

    private fun rollbackRequest(
        target: String? = null,
        parameters: Map<String, String> = mapOf("release.version" to "6.2.0"),
    ) = bosca.git.service.ReleaseRollbackRequest(
        targetRepositoryId = repositoryId,
        ref = "refs/tags/6.2.0",
        environmentKey = "production",
        target = target,
        toRevision = 0,
        overrides = emptyMap(),
        parameters = parameters,
        initiatorPrincipalId = initiator,
    )

    private fun wireConfig(config: ProjectDeployConfig) {
        coEvery { deployConfigService.read(repositoryId, any()) } returns config
    }

    @Test
    fun `deployment configuration is pinned to the release tag before the run ref`() = runTest {
        coEvery { deployConfigService.read(repositoryId, "refs/tags/6.2.0") } returns
            ProjectDeployConfig(environments = emptyMap())
        coEvery { deployConfigService.read(repositoryId, "refs/heads/main") } returns
            ProjectDeployConfig(
                environments = mapOf(
                    "production" to bosca.workops.deploy.EnvironmentDeployConfig(target = "helm-values"),
                ),
            )

        val failure = assertFailsWith<IllegalStateException> {
            deployer.deploy(request().copy(ref = "refs/heads/main"))
        }

        assertTrue("declares no environment 'production'" in failure.message.orEmpty())
        coVerify(exactly = 0) { deployConfigService.read(repositoryId, "refs/heads/main") }
    }

    @Test
    fun `allocates an iOS build number using an integer migration floor`() = runTest {
        val captured = slot<bosca.workops.model.artifact.AllocateAppBuildNumberInput>()
        val allocation = AppBuildNumberAllocation(
            id = UUID.random(),
            platform = AppBuildPlatform.IOS,
            applicationId = "com.example.app",
            buildKey = "release",
            repositoryId = repositoryId,
            sourceCommitSha = sourceCommit,
            sourceVersion = "6.2.0",
            pipelineRunId = UUID.random(),
            number = 7,
            value = "7",
        )
        coEvery { appBuildNumbers.allocate(capture(captured)) } returns
            AppBuildNumberAllocationResult(allocation, reused = false)

        val outcome = deployer.allocateBuildNumber(
            bosca.git.service.ReleaseBuildNumberRequest(
                repositoryId = repositoryId,
                pipelineRunId = allocation.pipelineRunId,
                sourceCommitSha = sourceCommit.uppercase(),
                sourceVersion = "6.2.0",
                platform = "ios",
                applicationId = "com.example.app",
                buildKey = "release",
                minimum = "7",
            ),
        )

        assertEquals(7, captured.captured.minimumNumber)
        assertEquals(AppBuildPlatform.IOS, captured.captured.platform)
        assertEquals("7", outcome.value)
        assertEquals(false, outcome.reused)
    }

    @Test
    fun `Android allocation defaults its floor and rejects unknown platforms`() = runTest {
        val captured = slot<bosca.workops.model.artifact.AllocateAppBuildNumberInput>()
        val allocation = AppBuildNumberAllocation(
            platform = AppBuildPlatform.ANDROID,
            applicationId = "io.bosca.app",
            repositoryId = repositoryId,
            sourceCommitSha = sourceCommit,
            sourceVersion = "6.2.0",
            pipelineRunId = UUID.random(),
            number = 42,
            value = "42",
        )
        coEvery { appBuildNumbers.allocate(capture(captured)) } returns
            AppBuildNumberAllocationResult(allocation, reused = true)

        val outcome = deployer.allocateBuildNumber(
            bosca.git.service.ReleaseBuildNumberRequest(
                repositoryId = repositoryId,
                pipelineRunId = allocation.pipelineRunId,
                sourceCommitSha = sourceCommit,
                sourceVersion = "6.2.0",
                platform = " ANDROID ",
                applicationId = "io.bosca.app",
            ),
        )

        assertEquals(1, captured.captured.minimumNumber)
        assertEquals(AppBuildPlatform.ANDROID, captured.captured.platform)
        assertEquals(42, outcome.number)
        assertTrue(outcome.reused)

        val failure = assertFailsWith<IllegalArgumentException> {
            deployer.allocateBuildNumber(
                bosca.git.service.ReleaseBuildNumberRequest(
                    repositoryId, allocation.pipelineRunId, sourceCommit, "6.2.0",
                    "desktop", "io.bosca.app",
                ),
            )
        }
        assertTrue("android" in (failure.message ?: "") && "ios" in (failure.message ?: ""))
    }

    @Test
    fun `App Store deploy resolves the exact unprefixed version tag and durable bundle number`() = runTest {
        wireConfig(
            ProjectDeployConfig(
                environments = mapOf(
                    "production" to bosca.workops.deploy.EnvironmentDeployConfig(
                        target = "app_store",
                        config = JsonObject(
                            mapOf(
                                "bundleId" to JsonPrimitive("com.example.app"),
                                "buildNumberKey" to JsonPrimitive("release"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val allocation = AppBuildNumberAllocation(
            id = UUID.random(),
            platform = AppBuildPlatform.IOS,
            applicationId = "com.example.app",
            buildKey = "release",
            repositoryId = repositoryId,
            sourceCommitSha = sourceCommit,
            sourceVersion = "6.2.0",
            pipelineRunId = UUID.random(),
            number = 1,
            value = "1",
        )
        coEvery { repositoryBrowse.resolveRef(repositoryId, "refs/tags/6.2.0") } returns null
        coEvery { repositoryBrowse.resolveRef(repositoryId, "6.2.0") } returns sourceCommit
        coEvery {
            appBuildNumbers.find(
                repositoryId, sourceCommit, "6.2.0", AppBuildPlatform.IOS, "com.example.app", "release",
            )
        } returns allocation
        val captured = slot<DeployRequest>()
        coEvery { adapter.deploy(capture(captured), any()) } returns
            DeployOutcome(UUID.random(), "com.example.app@1", EnvironmentDeploymentStatus.DEPLOYED)

        val outcome = deployer.deploy(request())

        assertEquals("com.example.app@1", outcome.reference)
        assertEquals(allocation.id, captured.captured.buildNumber?.allocationId)
        assertEquals(1, captured.captured.buildNumber?.number)
        assertEquals("1", captured.captured.buildNumber?.value)
        coVerify(exactly = 0) { repositoryBrowse.resolveRef(repositoryId, "refs/tags/v6.2.0") }
    }

    @Test
    fun `store deploy requires its platform application identifier for invalid or blank config`() = runTest {
        wireConfig(
            ProjectDeployConfig(
                environments = mapOf(
                    "production" to bosca.workops.deploy.EnvironmentDeployConfig(
                        target = "google_play",
                        config = JsonPrimitive("invalid"),
                    ),
                ),
            ),
        )
        val nonObject = assertFailsWith<IllegalStateException> { deployer.deploy(request()) }
        assertTrue("packageName" in (nonObject.message ?: ""), nonObject.message)

        wireConfig(
            ProjectDeployConfig(
                environments = mapOf(
                    "production" to bosca.workops.deploy.EnvironmentDeployConfig(
                        target = "google_play",
                        config = JsonObject(mapOf("packageName" to JsonPrimitive(" "))),
                    ),
                ),
            ),
        )
        val missingId = assertFailsWith<IllegalStateException> { deployer.deploy(request()) }
        assertTrue("packageName" in (missingId.message ?: ""), missingId.message)
    }

    @Test
    fun `store deploy fails when source commit or durable allocation cannot be resolved`() = runTest {
        wireConfig(
            ProjectDeployConfig(
                environments = mapOf(
                    "production" to bosca.workops.deploy.EnvironmentDeployConfig(
                        target = "google_play",
                        config = JsonObject(mapOf("packageName" to JsonPrimitive("io.bosca.app"))),
                    ),
                ),
            ),
        )
        coEvery { repositoryBrowse.resolveRef(repositoryId, any()) } returns null

        val missingCommit = assertFailsWith<IllegalStateException> { deployer.deploy(request()) }
        assertTrue("Cannot resolve source commit" in (missingCommit.message ?: ""))

        coEvery { repositoryBrowse.resolveRef(repositoryId, "refs/tags/6.2.0") } returns sourceCommit
        coEvery {
            appBuildNumbers.find(
                repositoryId, sourceCommit, "6.2.0", AppBuildPlatform.ANDROID,
                "io.bosca.app", "default",
            )
        } returns null

        val missingAllocation = assertFailsWith<IllegalStateException> { deployer.deploy(request()) }
        assertTrue("uses: allocate-build-number" in (missingAllocation.message ?: ""))
    }

    @Test
    fun `deploys through the adapter with the release-bundled version and resolved publication`() = runTest {
        wireConfig(
            ProjectDeployConfig(
                environments = mapOf(
                    "production" to bosca.workops.deploy.EnvironmentDeployConfig(
                        target = "helm-values",
                        config = JsonObject(mapOf("clusterId" to JsonPrimitive("c"))),
                        artifact = bosca.workops.deploy.ArtifactSelector(
                            type = "docker", coordinate = "server:\${{ version }}",
                        ),
                    ),
                ),
            ),
        )
        val publication = ArtifactPublication(
            id = UUID.random(), versionId = versionId, projectId = projectId,
            artifactType = ArtifactType.DOCKER, coordinates = "server:6.2.0",
        )
        coEvery { publications.listByVersion(versionId) } returns listOf(publication)
        val captured = slot<DeployRequest>()
        coEvery { adapter.deploy(capture(captured), any()) } returns
            DeployOutcome(deploymentId = UUID.random(), reference = "bosca@42", status = EnvironmentDeploymentStatus.DEPLOYED)

        val outcome = deployer.deploy(request(overrides = mapOf("resetValues" to "true", "artifact.type" to "docker")))

        assertEquals("bosca@42", outcome.reference)
        assertEquals("DEPLOYED", outcome.status)
        assertEquals(environmentId, captured.captured.environmentId)
        assertEquals(versionId, captured.captured.versionId)
        assertEquals(publication.id, captured.captured.artifactPublicationId)
        assertEquals(publication.id, captured.captured.artifact?.publicationId)
        assertEquals("server:6.2.0", captured.captured.artifact?.coordinate)
        assertEquals(initiator, captured.captured.deployedByPrincipalId)
        val config = captured.captured.config as JsonObject
        // Overrides merged with coercion; artifact.* keys routed to the selector, not the config.
        assertEquals(JsonPrimitive(true), config["resetValues"])
        assertEquals(JsonPrimitive("c"), config["clusterId"])
        assertEquals(null, config["artifact.type"])
    }

    @Test
    fun `a selector matching no declared publication fails naming the selector`() = runTest {
        wireConfig(
            ProjectDeployConfig(
                environments = mapOf(
                    "production" to bosca.workops.deploy.EnvironmentDeployConfig(
                        target = "helm-values",
                        artifact = bosca.workops.deploy.ArtifactSelector(coordinate = "server:\${{ version }}"),
                    ),
                ),
            ),
        )
        coEvery { publications.listByVersion(versionId) } returns listOf(
            ArtifactPublication(
                id = UUID.random(), versionId = versionId, projectId = projectId,
                artifactType = ArtifactType.DOCKER, coordinates = "other:6.2.0",
            ),
        )

        val e = assertFailsWith<IllegalStateException> { deployer.deploy(request()) }
        assertTrue("server:6.2.0" in (e.message ?: ""), e.message)
        assertTrue("other:6.2.0" in (e.message ?: ""), e.message)
    }

    @Test
    fun `artifact matching checks type and namespace independently and rejects blank named versions`() = runTest {
        wireConfig(
            ProjectDeployConfig(
                environments = mapOf(
                    "production" to bosca.workops.deploy.EnvironmentDeployConfig(
                        target = "helm-values",
                        artifact = bosca.workops.deploy.ArtifactSelector(
                            type = "docker",
                            namespace = "production",
                            coordinate = "server:\${{ version }}",
                        ),
                    ),
                ),
            ),
        )

        assertTrue(
            "Deploy needs a workops Version" in assertFailsWith<IllegalStateException> {
                deployer.deploy(request(parameters = mapOf("release.version" to " ")))
            }.message.orEmpty(),
        )

        coEvery { publications.listByVersion(versionId) } returns listOf(
            ArtifactPublication(
                id = UUID.random(), versionId = versionId, projectId = projectId,
                artifactType = ArtifactType.MAVEN, namespace = "production", coordinates = "server:6.2.0",
            ),
            ArtifactPublication(
                id = UUID.random(), versionId = versionId, projectId = projectId,
                artifactType = ArtifactType.DOCKER, namespace = "staging", coordinates = "server:6.2.0",
            ),
        )

        val failure = assertFailsWith<IllegalStateException> { deployer.deploy(request()) }
        assertTrue("matches none" in failure.message.orEmpty())
        assertTrue("type=docker" in failure.message.orEmpty())
        assertTrue("namespace=production" in failure.message.orEmpty())

        wireConfig(
            ProjectDeployConfig(
                environments = mapOf(
                    "production" to bosca.workops.deploy.EnvironmentDeployConfig(target = "helm-values"),
                ),
            ),
        )
        val publication = ArtifactPublication(
            id = UUID.random(), versionId = versionId, projectId = projectId,
            artifactType = ArtifactType.DOCKER, namespace = "production", coordinates = "server:6.2.0",
        )
        coEvery { publications.listByVersion(versionId) } returns listOf(publication)
        coEvery { adapter.deploy(any(), any()) } returns
            DeployOutcome(UUID.random(), "server:6.2.0", EnvironmentDeploymentStatus.DEPLOYED)

        assertEquals(
            "server:6.2.0",
            deployer.deploy(
                request(overrides = mapOf("artifact.coordinate" to "server:\${{ release.version }}")),
            ).reference,
        )
    }

    @Test
    fun `multiple targets require a name and resolve by it`() = runTest {
        wireConfig(
            ProjectDeployConfig(
                environments = mapOf(
                    "production" to bosca.workops.deploy.EnvironmentDeployConfig(
                        targets = listOf(
                            bosca.workops.deploy.DeployTargetEntry(target = "helm-values"),
                            bosca.workops.deploy.DeployTargetEntry(target = "google_play"),
                        ),
                    ),
                ),
            ),
        )

        val e = assertFailsWith<IllegalStateException> { deployer.deploy(request()) }
        assertTrue("helm-values" in (e.message ?: "") && "google_play" in (e.message ?: ""), e.message)

        coEvery { adapter.deploy(any(), any()) } returns
            DeployOutcome(deploymentId = UUID.random(), reference = "bosca@7", status = EnvironmentDeploymentStatus.DEPLOYED)
        val outcome = deployer.deploy(request(target = "helm-values"))
        assertEquals("bosca@7", outcome.reference)
    }

    @Test
    fun `an undeclared environment fails naming the file`() = runTest {
        wireConfig(ProjectDeployConfig(environments = mapOf()))

        val e = assertFailsWith<IllegalStateException> { deployer.deploy(request()) }
        assertTrue("production" in (e.message ?: ""), e.message)
    }

    @Test
    fun `rollback selects the entry and passes toRevision through the adapter`() = runTest {
        wireConfig(
            ProjectDeployConfig(
                environments = mapOf(
                    "production" to bosca.workops.deploy.EnvironmentDeployConfig(target = "helm-values"),
                ),
            ),
        )
        val captured = slot<bosca.workops.deploy.RollbackRequest>()
        coEvery { adapter.rollback(capture(captured), any()) } returns
            DeployOutcome(deploymentId = UUID.random(), reference = "bosca@41", status = EnvironmentDeploymentStatus.ROLLED_BACK)

        val outcome = deployer.rollback(
            bosca.git.service.ReleaseRollbackRequest(
                targetRepositoryId = repositoryId,
                ref = "refs/tags/6.2.0",
                environmentKey = "production",
                target = null,
                toRevision = 0,
                overrides = emptyMap(),
                parameters = mapOf("release.version" to "6.2.0"),
                initiatorPrincipalId = initiator,
            ),
        )

        assertEquals("bosca@41", outcome.reference)
        assertEquals("ROLLED_BACK", outcome.status)
        assertEquals(0, captured.captured.toRevision)
        assertEquals(environmentId, captured.captured.environmentId)
        assertEquals(initiator, captured.captured.deployedByPrincipalId)
    }

    @Test
    fun `rollback resolves an environment by name and fails when it does not exist`() = runTest {
        wireConfig(
            ProjectDeployConfig(
                environments = mapOf(
                    "production" to bosca.workops.deploy.EnvironmentDeployConfig(target = "helm-values"),
                ),
            ),
        )
        coEvery { environmentService.getByProgramAndKey(programId, "production") } returns null
        coEvery { environmentService.getByProgramAndName(programId, "production") } returns
            Environment(id = environmentId, programId = programId, key = "production", name = "Production")
        coEvery { adapter.rollback(any(), any()) } returns
            DeployOutcome(UUID.random(), "bosca@previous", EnvironmentDeploymentStatus.ROLLED_BACK)

        assertEquals("ROLLED_BACK", deployer.rollback(rollbackRequest()).status)

        coEvery { environmentService.getByProgramAndName(programId, "production") } returns null
        val missing = assertFailsWith<IllegalStateException> { deployer.rollback(rollbackRequest()) }
        assertTrue("does not exist" in missing.message.orEmpty(), missing.message)
    }

    @Test
    fun `rollback rejects an unknown target and a missing adapter`() = runTest {
        wireConfig(
            ProjectDeployConfig(
                environments = mapOf(
                    "production" to bosca.workops.deploy.EnvironmentDeployConfig(target = "custom-target"),
                ),
            ),
        )
        val unknown = assertFailsWith<IllegalStateException> { deployer.rollback(rollbackRequest()) }
        assertTrue("unknown target" in unknown.message.orEmpty(), unknown.message)

        wireConfig(
            ProjectDeployConfig(
                environments = mapOf(
                    "production" to bosca.workops.deploy.EnvironmentDeployConfig(target = "helm-values"),
                ),
            ),
        )
        bosca.di.ProviderRegistry.clear()
        val missingAdapter = assertFailsWith<IllegalStateException> { deployer.rollback(rollbackRequest()) }
        assertTrue("deploy adapter" in missingAdapter.message.orEmpty(), missingAdapter.message)
    }

    @Test
    fun `store rollback resolves each platform build identity and optional artifact`() = runTest {
        val version = Version(id = versionId, projectId = projectId, name = "6.2.0", sequenceNumber = 1)
        coEvery { versionService.listByProject(projectId) } returns listOf(version)
        coEvery { repositoryBrowse.resolveRef(repositoryId, "refs/tags/6.2.0") } returns sourceCommit
        val publication = ArtifactPublication(
            id = UUID.random(),
            versionId = versionId,
            projectId = projectId,
            artifactType = ArtifactType.ANDROID_AAR,
            coordinates = "app:6.2.0",
            namespace = "bosca-raw",
        )
        coEvery { publications.listByVersion(versionId) } returns listOf(publication)
        val captured = mutableListOf<bosca.workops.deploy.RollbackRequest>()
        coEvery { adapter.rollback(capture(captured), any()) } returns
            DeployOutcome(UUID.random(), "store rollback", EnvironmentDeploymentStatus.ROLLED_BACK)

        data class StoreCase(
            val target: String,
            val platform: AppBuildPlatform,
            val applicationField: String,
            val applicationId: String,
            val buildKey: String,
            val artifact: bosca.workops.deploy.ArtifactSelector?,
        )

        val cases = listOf(
            StoreCase(
                target = "google_play",
                platform = AppBuildPlatform.ANDROID,
                applicationField = "packageName",
                applicationId = "io.bosca.app",
                buildKey = "default",
                artifact = bosca.workops.deploy.ArtifactSelector(coordinate = "app:\${{ version }}"),
            ),
            StoreCase(
                target = "app_store",
                platform = AppBuildPlatform.IOS,
                applicationField = "bundleId",
                applicationId = "com.example.app",
                buildKey = "release",
                artifact = null,
            ),
        )

        cases.forEachIndexed { index, case ->
            val configuredBuildKey = if (case.buildKey == "default") " " else case.buildKey
            wireConfig(
                ProjectDeployConfig(
                    environments = mapOf(
                        "production" to bosca.workops.deploy.EnvironmentDeployConfig(
                            target = case.target,
                            config = JsonObject(
                                mapOf(
                                    case.applicationField to JsonPrimitive(case.applicationId),
                                    "buildNumberKey" to JsonPrimitive(configuredBuildKey),
                                ),
                            ),
                            artifact = case.artifact,
                        ),
                    ),
                ),
            )
            val allocation = AppBuildNumberAllocation(
                id = UUID.random(),
                platform = case.platform,
                applicationId = case.applicationId,
                buildKey = case.buildKey,
                repositoryId = repositoryId,
                sourceCommitSha = sourceCommit,
                sourceVersion = version.name,
                pipelineRunId = UUID.random(),
                number = index + 42L,
                value = (index + 42L).toString(),
            )
            coEvery {
                appBuildNumbers.find(
                    repositoryId, sourceCommit, version.name, case.platform,
                    case.applicationId, case.buildKey,
                )
            } returns allocation

            val outcome = deployer.rollback(
                bosca.git.service.ReleaseRollbackRequest(
                    targetRepositoryId = repositoryId,
                    ref = "refs/tags/6.2.0",
                    environmentKey = "production",
                    target = null,
                    toRevision = index,
                    overrides = emptyMap(),
                    parameters = mapOf("release.version" to version.name),
                    initiatorPrincipalId = initiator,
                ),
            )

            assertEquals("ROLLED_BACK", outcome.status)
        }

        assertEquals(2, captured.size)
        assertEquals(AppBuildPlatform.ANDROID, cases[0].platform)
        assertEquals(publication.id, captured[0].artifact?.publicationId)
        assertEquals(ArtifactType.ANDROID_AAR.name, captured[0].artifact?.type)
        assertEquals("bosca-raw", captured[0].artifact?.namespace)
        assertEquals("default", cases[0].buildKey)
        assertEquals(42L, captured[0].buildNumber?.number)
        assertEquals(43L, captured[1].buildNumber?.number)
        assertEquals(null, captured[1].artifact)
    }

    @Test
    fun `play rollout selects a Google Play target and passes the store-neutral percentage`() = runTest {
        wireConfig(
            ProjectDeployConfig(
                environments = mapOf(
                    "production" to bosca.workops.deploy.EnvironmentDeployConfig(
                        target = "google_play",
                        config = JsonObject(
                            mapOf(
                                "packageName" to JsonPrimitive("io.bosca.app"),
                                "track" to JsonPrimitive("production"),
                            ),
                        ),
                        artifact = bosca.workops.deploy.ArtifactSelector(
                            type = "android-aar",
                            namespace = "bosca-raw",
                            coordinate = "app:\${{ version }}",
                        ),
                    ),
                ),
            ),
        )
        val publication = ArtifactPublication(
            id = UUID.random(),
            versionId = versionId,
            projectId = projectId,
            artifactType = ArtifactType.ANDROID_AAR,
            coordinates = "app:6.2.0",
            namespace = "bosca-raw",
        )
        coEvery { publications.listByVersion(versionId) } returns listOf(publication)
        coEvery { repositoryBrowse.resolveRef(repositoryId, any()) } returns sourceCommit
        coEvery {
            appBuildNumbers.find(
                repositoryId, sourceCommit, "6.2.0", AppBuildPlatform.ANDROID,
                "io.bosca.app", "default",
            )
        } returns AppBuildNumberAllocation(
            id = UUID.random(),
            platform = AppBuildPlatform.ANDROID,
            applicationId = "io.bosca.app",
            repositoryId = repositoryId,
            sourceCommitSha = sourceCommit,
            sourceVersion = "6.2.0",
            pipelineRunId = UUID.random(),
            number = 42,
            value = "42",
        )
        val captured = slot<RolloutRequest>()
        coEvery { adapter.rollout(capture(captured), any()) } returns
            DeployOutcome(
                deploymentId = UUID.random(),
                reference = "io.bosca.app:production@42 (10%)",
                status = EnvironmentDeploymentStatus.DEPLOYED,
            )

        val rolloutRequest = bosca.git.service.ReleasePlayRolloutRequest(
            targetRepositoryId = repositoryId,
            ref = "refs/tags/6.2.0",
            environmentKey = "production",
            target = null,
            rolloutPercentage = 10.0,
            parameters = mapOf("release.id" to releaseId.toString(), "release.version" to "6.2.0"),
            initiatorPrincipalId = initiator,
        )
        val outcome = deployer.playRollout(rolloutRequest)

        assertEquals("io.bosca.app:production@42 (10%)", outcome.reference)
        assertEquals(10.0, captured.captured.rolloutPercentage)
        assertEquals(environmentId, captured.captured.environmentId)
        assertEquals(projectId, captured.captured.projectId)
        assertEquals(42L, captured.captured.buildNumber?.number)
        assertEquals("42", captured.captured.buildNumber?.value)
        assertEquals(publication.id, captured.captured.artifact?.publicationId)
        assertEquals(initiator, captured.captured.deployedByPrincipalId)

        bosca.di.ProviderRegistry.clear()
        val missingAdapter = assertFailsWith<IllegalStateException> { deployer.playRollout(rolloutRequest) }
        assertTrue("deploy adapter" in missingAdapter.message.orEmpty())
    }

    @Test
    fun `play rollout rejects invalid percentages and non Play targets before adapter invocation`() = runTest {
        val base = bosca.git.service.ReleasePlayRolloutRequest(
            targetRepositoryId = repositoryId,
            ref = "refs/tags/6.2.0",
            environmentKey = "production",
            target = null,
            rolloutPercentage = 10.0,
            parameters = mapOf("release.version" to "6.2.0"),
            initiatorPrincipalId = initiator,
        )

        listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.1, 100.1).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> {
                deployer.playRollout(base.copy(rolloutPercentage = invalid))
            }
        }

        wireConfig(
            ProjectDeployConfig(
                environments = mapOf(
                    "production" to bosca.workops.deploy.EnvironmentDeployConfig(target = "app_store"),
                ),
            ),
        )
        val wrongTarget = assertFailsWith<IllegalArgumentException> { deployer.playRollout(base) }
        assertTrue("google_play" in wrongTarget.message.orEmpty())

        wireConfig(
            ProjectDeployConfig(
                environments = mapOf(
                    "production" to bosca.workops.deploy.EnvironmentDeployConfig(target = "custom-store"),
                ),
            ),
        )
        val unknownTarget = assertFailsWith<IllegalStateException> { deployer.playRollout(base) }
        assertTrue("unknown target" in unknownTarget.message.orEmpty())
        coVerify(exactly = 0) { adapter.rollout(any(), any()) }
    }

    @Test
    fun `play rollout fails when the environment or selected artifact is missing`() = runTest {
        val rollout = bosca.git.service.ReleasePlayRolloutRequest(
            targetRepositoryId = repositoryId,
            ref = "refs/tags/6.2.0",
            environmentKey = "production",
            target = null,
            rolloutPercentage = 10.0,
            parameters = mapOf("release.version" to "6.2.0"),
            initiatorPrincipalId = initiator,
        )
        coEvery { environmentService.getByProgramAndKey(programId, "production") } returns null
        coEvery { environmentService.getByProgramAndName(programId, "production") } returns null
        assertTrue(
            "does not exist" in assertFailsWith<IllegalStateException> {
                deployer.playRollout(rollout)
            }.message.orEmpty(),
        )

        coEvery { environmentService.getByProgramAndName(programId, "production") } returns
            Environment(id = environmentId, programId = programId, key = "production", name = "Production")
        wireConfig(
            ProjectDeployConfig(
                environments = mapOf(
                    "production" to bosca.workops.deploy.EnvironmentDeployConfig(
                        target = "google_play",
                        config = JsonObject(mapOf("packageName" to JsonPrimitive("io.bosca.app"))),
                    ),
                ),
            ),
        )
        assertTrue(
            "artifact selector" in assertFailsWith<IllegalStateException> {
                deployer.playRollout(rollout)
            }.message.orEmpty(),
        )

        wireConfig(
            ProjectDeployConfig(
                environments = mapOf(
                    "production" to bosca.workops.deploy.EnvironmentDeployConfig(
                        target = "google_play",
                        config = JsonPrimitive("invalid"),
                        artifact = bosca.workops.deploy.ArtifactSelector(coordinate = "app:\${{ version }}"),
                    ),
                ),
            ),
        )
        coEvery { versionService.listByProject(projectId) } returns listOf(
            Version(id = versionId, projectId = projectId, name = "6.2.0", sequenceNumber = 1),
        )
        coEvery { publications.listByVersion(versionId) } returns listOf(
            ArtifactPublication(
                id = UUID.random(), versionId = versionId, projectId = projectId,
                artifactType = ArtifactType.ANDROID_AAR, coordinates = "app:6.2.0",
            ),
        )
        val invalidConfig = assertFailsWith<IllegalStateException> { deployer.playRollout(rollout) }
        assertTrue("must be an object" in invalidConfig.message.orEmpty())
    }

    @Test
    fun `App Store review resolves the durable iOS build and maps typed terminal states`() = runTest {
        wireConfig(
            ProjectDeployConfig(
                environments = mapOf(
                    "production" to bosca.workops.deploy.EnvironmentDeployConfig(
                        target = "app_store",
                        config = JsonObject(
                            mapOf(
                                "bundleId" to JsonPrimitive("com.example.app"),
                                "buildNumberKey" to JsonPrimitive("release"),
                                "ascKeySecret" to JsonPrimitive("asc-key"),
                                "testflightGroups" to kotlinx.serialization.json.buildJsonArray {
                                    add(JsonPrimitive("Beta"))
                                },
                            ),
                        ),
                    ),
                ),
            ),
        )
        coEvery { repositoryBrowse.resolveRef(repositoryId, any()) } returns sourceCommit
        coEvery {
            appBuildNumbers.find(
                repositoryId, sourceCommit, "6.2.0", AppBuildPlatform.IOS, "com.example.app", "release",
            )
        } returns AppBuildNumberAllocation(
            id = UUID.random(),
            platform = AppBuildPlatform.IOS,
            applicationId = "com.example.app",
            buildKey = "release",
            repositoryId = repositoryId,
            sourceCommitSha = sourceCommit,
            sourceVersion = "6.2.0",
            pipelineRunId = UUID.random(),
            number = 1,
            value = "1",
        )
        coEvery { secrets.resolve("asc-key") } returns "credential-json"
        coEvery {
            appStorePublisher.reviewState(
                "credential-json", "com.example.app", "6.2.0", "1", AppStoreReviewMode.BETA,
            )
        } returnsMany listOf("IN_REVIEW", "APPROVED", "REJECTED")
        val request = bosca.git.service.ReleaseAppStoreReviewRequest(
            targetRepositoryId = repositoryId,
            ref = "refs/tags/6.2.0",
            environmentKey = "production",
            target = null,
            mode = bosca.git.service.ReleaseAppStoreReviewMode.BETA,
            parameters = mapOf("release.id" to releaseId.toString(), "release.version" to "6.2.0"),
            initiatorPrincipalId = initiator,
        )

        val pending = deployer.appStoreReview(request)
        val approved = deployer.appStoreReview(request)
        val rejected = deployer.appStoreReview(request)

        assertEquals(bosca.git.service.ReleaseAppStoreReviewOutcome("IN_REVIEW", false, false), pending)
        assertEquals(bosca.git.service.ReleaseAppStoreReviewOutcome("APPROVED", true, true), approved)
        assertEquals(bosca.git.service.ReleaseAppStoreReviewOutcome("REJECTED", true, false), rejected)
        coVerify(exactly = 3) {
            appStorePublisher.reviewState(
                "credential-json", "com.example.app", "6.2.0", "1", AppStoreReviewMode.BETA,
            )
        }

        coEvery {
            appStorePublisher.reviewState(
                "credential-json", "com.example.app", "6.2.0", "1", AppStoreReviewMode.APP_STORE,
            )
        } returnsMany listOf("WAITING_FOR_REVIEW", "READY_FOR_DISTRIBUTION", "INVALID_BINARY")
        val appStoreRequest = request.copy(mode = bosca.git.service.ReleaseAppStoreReviewMode.APP_STORE)

        assertEquals(
            bosca.git.service.ReleaseAppStoreReviewOutcome("WAITING_FOR_REVIEW", false, false),
            deployer.appStoreReview(appStoreRequest),
        )
        assertEquals(
            bosca.git.service.ReleaseAppStoreReviewOutcome("READY_FOR_DISTRIBUTION", true, true),
            deployer.appStoreReview(appStoreRequest),
        )
        assertEquals(
            bosca.git.service.ReleaseAppStoreReviewOutcome("INVALID_BINARY", true, false),
            deployer.appStoreReview(appStoreRequest),
        )

        coEvery { secrets.resolve("asc-key") } returns null
        val missingCredential = assertFailsWith<IllegalStateException> {
            deployer.appStoreReview(request)
        }
        assertTrue("asc-key" in missingCredential.message.orEmpty())

        wireConfig(
            ProjectDeployConfig(
                environments = mapOf(
                    "production" to bosca.workops.deploy.EnvironmentDeployConfig(
                        target = "google_play",
                        config = JsonObject(mapOf("packageName" to JsonPrimitive("io.bosca.app"))),
                    ),
                ),
            ),
        )
        assertTrue(
            "app_store target" in assertFailsWith<IllegalArgumentException> {
                deployer.appStoreReview(request)
            }.message.orEmpty(),
        )

        wireConfig(
            ProjectDeployConfig(
                environments = mapOf(
                    "production" to bosca.workops.deploy.EnvironmentDeployConfig(
                        target = "app_store",
                        config = JsonObject(
                            mapOf(
                                "bundleId" to JsonPrimitive("com.example.app"),
                                "buildNumberKey" to JsonPrimitive("release"),
                                "ascKeySecret" to JsonPrimitive("asc-key"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        coEvery {
            appBuildNumbers.find(
                repositoryId, sourceCommit, "6.2.0", AppBuildPlatform.IOS, "com.example.app", "release",
            )
        } returns null
        assertTrue(
            "durable ios build number" in assertFailsWith<IllegalStateException> {
                deployer.appStoreReview(request)
            }.message.orEmpty(),
        )
    }

    @Test
    fun `store health performs one exact-version Play observation and applies the threshold`() = runTest {
        wireConfig(
            ProjectDeployConfig(
                environments = mapOf(
                    "production" to bosca.workops.deploy.EnvironmentDeployConfig(
                        target = "google_play",
                        config = JsonObject(
                            mapOf(
                                "packageName" to JsonPrimitive("io.bosca.app"),
                                "serviceAccountSecret" to JsonPrimitive("play-key"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        coEvery { repositoryBrowse.resolveRef(repositoryId, any()) } returns sourceCommit
        coEvery {
            appBuildNumbers.find(
                repositoryId, sourceCommit, "6.2.0", AppBuildPlatform.ANDROID, "io.bosca.app", "default",
            )
        } returns AppBuildNumberAllocation(
            id = UUID.random(),
            platform = AppBuildPlatform.ANDROID,
            applicationId = "io.bosca.app",
            repositoryId = repositoryId,
            sourceCommitSha = sourceCommit,
            sourceVersion = "6.2.0",
            pipelineRunId = UUID.random(),
            number = 42,
            value = "42",
        )
        coEvery { secrets.resolve("play-key") } returns "credential-json"
        coEvery {
            playPublisher.crashRate("credential-json", "io.bosca.app", 42, Duration.ofHours(24))
        } returnsMany listOf(
            PlayVitalsResult(0.75, Duration.ofHours(24), 42),
            PlayVitalsResult(0.25, Duration.ofHours(24), 42),
        )
        val request = bosca.git.service.ReleaseStoreHealthRequest(
            targetRepositoryId = repositoryId,
            ref = "refs/tags/6.2.0",
            environmentKey = "production",
            target = null,
            maxCrashRate = 0.5,
            window = Duration.ofHours(24),
            parameters = mapOf("release.id" to releaseId.toString(), "release.version" to "6.2.0"),
            initiatorPrincipalId = initiator,
        )

        val outcome = deployer.storeHealth(request)

        assertEquals(0.75, outcome.crashRate)
        assertEquals(0.5, outcome.maxCrashRate)
        assertEquals(86_400, outcome.windowSeconds)
        assertEquals(false, outcome.healthy)
        assertEquals(true, deployer.storeHealth(request).healthy)
        coVerify(exactly = 2) {
            playPublisher.crashRate("credential-json", "io.bosca.app", 42, Duration.ofHours(24))
        }

        coEvery { secrets.resolve("play-key") } returns null
        val missingCredential = assertFailsWith<IllegalStateException> { deployer.storeHealth(request) }
        assertTrue("play-key" in missingCredential.message.orEmpty())

        listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.1, 100.1).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> { deployer.storeHealth(request.copy(maxCrashRate = invalid)) }
        }
        assertFailsWith<IllegalArgumentException> { deployer.storeHealth(request.copy(window = Duration.ZERO)) }

        wireConfig(
            ProjectDeployConfig(
                environments = mapOf(
                    "production" to bosca.workops.deploy.EnvironmentDeployConfig(
                        target = "app_store",
                        config = JsonObject(mapOf("bundleId" to JsonPrimitive("com.example.app"))),
                    ),
                ),
            ),
        )
        assertTrue(
            "google_play target" in assertFailsWith<IllegalArgumentException> {
                deployer.storeHealth(request)
            }.message.orEmpty(),
        )

        wireConfig(
            ProjectDeployConfig(
                environments = mapOf(
                    "production" to bosca.workops.deploy.EnvironmentDeployConfig(
                        target = "google_play",
                        config = JsonObject(
                            mapOf(
                                "packageName" to JsonPrimitive("io.bosca.app"),
                                "serviceAccountSecret" to JsonPrimitive("play-key"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        coEvery {
            appBuildNumbers.find(
                repositoryId, sourceCommit, "6.2.0", AppBuildPlatform.ANDROID, "io.bosca.app", "default",
            )
        } returns null
        assertTrue(
            "durable android build number" in assertFailsWith<IllegalStateException> {
                deployer.storeHealth(request)
            }.message.orEmpty(),
        )
    }

    @Test
    fun `deploymentHealth probes the current deployment and reports NONE when nothing is deployed`() = runTest {
        coEvery { environmentService.currentState(environmentId) } returns emptyList()
        assertEquals("NONE", deployer.deploymentHealth(repositoryId, "production", initiator))

        val deployment = bosca.workops.model.environment.EnvironmentDeployment(
            id = UUID.random(), environmentId = environmentId, projectId = projectId, versionId = versionId,
        )
        coEvery { environmentService.currentState(environmentId) } returns listOf(deployment)
        coEvery { environmentService.probeHealth(deployment.id, any()) } returns
            bosca.workops.model.environment.HealthCheckStatus.HEALTHY
        assertEquals("HEALTHY", deployer.deploymentHealth(repositoryId, "production", initiator))
        coVerify(atLeast = 2) {
            authorizer.verifyAllowed(any(), repositoryId, "production", bosca.security.model.PermissionAction.EXECUTE)
        }
    }

    @Test
    fun `deployment health reports the most severe state across every current target`() = runTest {
        val deployments = listOf(
            bosca.workops.model.environment.HealthCheckStatus.HEALTHY,
            bosca.workops.model.environment.HealthCheckStatus.UNKNOWN,
            bosca.workops.model.environment.HealthCheckStatus.DEGRADED,
            bosca.workops.model.environment.HealthCheckStatus.UNHEALTHY,
        ).zip(DeployTargetKind.entries).map { (status, targetKind) ->
            bosca.workops.model.environment.EnvironmentDeployment(
                id = UUID.random(),
                environmentId = environmentId,
                projectId = projectId,
                targetKind = targetKind,
                versionId = versionId,
            ) to status
        }
        coEvery { environmentService.currentState(environmentId) } returns
            deployments.map { it.first } + bosca.workops.model.environment.EnvironmentDeployment(
                id = UUID.random(),
                environmentId = environmentId,
                projectId = UUID.random(),
                versionId = versionId,
            )
        deployments.forEach { (deployment, status) ->
            coEvery { environmentService.probeHealth(deployment.id, any()) } returns status
        }

        assertEquals("UNHEALTHY", deployer.deploymentHealth(repositoryId, "production", initiator))
    }

    @Test
    fun `deployment health resolves an environment by name and fails when it does not exist`() = runTest {
        coEvery { environmentService.getByProgramAndKey(programId, "production") } returns null
        coEvery { environmentService.getByProgramAndName(programId, "production") } returns
            Environment(id = environmentId, programId = programId, key = "production", name = "Production")
        coEvery { environmentService.currentState(environmentId) } returns emptyList()

        assertEquals("NONE", deployer.deploymentHealth(repositoryId, "production", initiator))

        coEvery { environmentService.getByProgramAndName(programId, "production") } returns null
        val missing = assertFailsWith<IllegalStateException> {
            deployer.deploymentHealth(repositoryId, "production", initiator)
        }
        assertTrue("does not exist" in missing.message.orEmpty(), missing.message)
    }

    @Test
    fun `markReleased is idempotent and requires program MANAGE`() = runTest {
        val release = bosca.workops.model.release.Release(
            id = releaseId, programId = programId, name = "6.2.0", version = 3,
        )
        coEvery { releaseService.getById(releaseId) } returns release
        coEvery { programService.getById(programId) } returns
            bosca.workops.model.project.Program(
                id = programId, portfolioId = UUID.random(), key = "P", name = "P", ownerProfileId = UUID.random(),
            )
        coEvery { releaseService.release(releaseId, 3) } returns release

        deployer.markReleased(releaseId, initiator)
        io.mockk.coVerify(exactly = 1) { releaseService.release(releaseId, 3) }
        io.mockk.coVerify { programPermissionEvaluator.verifyAllowed(any(), any(), bosca.security.model.PermissionAction.MANAGE) }

        // Already released: the stamp is a no-op, not an error.
        coEvery { releaseService.getById(releaseId) } returns
            release.copy(releasedAt = bosca.serialization.OffsetDateTime.now())
        deployer.markReleased(releaseId, initiator)
        io.mockk.coVerify(exactly = 1) { releaseService.release(any(), any()) }
    }

    @Test
    fun `markReleased fails when the release or its program is missing`() = runTest {
        coEvery { releaseService.getById(releaseId) } returns null
        assertFailsWith<NoSuchElementException> { deployer.markReleased(releaseId, initiator) }

        coEvery { releaseService.getById(releaseId) } returns bosca.workops.model.release.Release(
            id = releaseId,
            programId = programId,
            name = "6.2.0",
            version = 3,
        )
        coEvery { programService.getById(programId) } returns null
        assertFailsWith<NoSuchElementException> { deployer.markReleased(releaseId, initiator) }
    }

    @Test
    fun `generate release notes requires program MANAGE and retains the initiating principal`() = runTest {
        val release = bosca.workops.model.release.Release(
            id = releaseId, programId = programId, name = "6.2.0", version = 3,
        )
        val program = bosca.workops.model.project.Program(
            id = programId, portfolioId = UUID.random(), key = "P", name = "P", ownerProfileId = UUID.random(),
        )
        coEvery { releaseService.getById(releaseId) } returns release
        coEvery { programService.getById(programId) } returns program
        coEvery { releaseNotesService.autoGenerateLocalized(releaseId, initiator) } returns emptyList()

        deployer.generateReleaseNotes(releaseId, initiator)

        coVerify(exactly = 1) { securityService.impersonate(initiator) }
        coVerify(exactly = 1) {
            programPermissionEvaluator.verifyAllowed(any(), program, bosca.security.model.PermissionAction.MANAGE)
        }
        coVerify(exactly = 1) { releaseNotesService.autoGenerateLocalized(releaseId, initiator) }
    }

    @Test
    fun `a deploy without a resolvable workops version fails`() = runTest {
        wireConfig(
            ProjectDeployConfig(
                environments = mapOf(
                    "production" to bosca.workops.deploy.EnvironmentDeployConfig(target = "helm-values"),
                ),
            ),
        )
        coEvery { versionService.listByProject(projectId) } returns emptyList()

        coEvery { releaseService.listVersions(releaseId) } returns listOf(
            bosca.workops.model.release.ReleaseProjectVersion(
                releaseId = releaseId,
                projectId = UUID.random(),
                versionId = UUID.random(),
            ),
        )
        val unbundled = assertFailsWith<IllegalStateException> {
            deployer.deploy(request(parameters = mapOf("release.id" to releaseId.toString())))
        }
        assertTrue("Deploy needs a workops Version" in unbundled.message.orEmpty())

        val e = assertFailsWith<IllegalStateException> {
            deployer.deploy(request(parameters = mapOf("release.version" to "9.9.9")))
        }
        assertTrue("9.9.9" in (e.message ?: ""), e.message)
    }

    @Test
    fun `named version deploy accepts normalized target and typed overrides with an introduced artifact selector`() = runTest {
        wireConfig(
            ProjectDeployConfig(
                environments = mapOf(
                    "production" to bosca.workops.deploy.EnvironmentDeployConfig(
                        target = "helm-values",
                        config = JsonPrimitive("adapter defaults"),
                    ),
                ),
            ),
        )
        val version = Version(id = versionId, projectId = projectId, name = "6.2.0", sequenceNumber = 1)
        val publication = ArtifactPublication(
            id = UUID.random(),
            versionId = versionId,
            projectId = projectId,
            artifactType = ArtifactType.DOCKER,
            namespace = "production",
            coordinates = "server:6.2.0",
        )
        coEvery { environmentService.getByProgramAndKey(programId, "production") } returns null
        coEvery { environmentService.getByProgramAndName(programId, "production") } returns
            Environment(id = environmentId, programId = programId, key = "production", name = "Production")
        coEvery { versionService.listByProject(projectId) } returns listOf(version)
        coEvery { publications.listByVersion(versionId) } returns listOf(publication)
        val captured = slot<DeployRequest>()
        coEvery { adapter.deploy(capture(captured), any()) } returns
            DeployOutcome(UUID.random(), "server:6.2.0", EnvironmentDeploymentStatus.DEPLOYED)

        val outcome = deployer.deploy(
            request(
                target = "HELM_VALUES",
                parameters = mapOf("release.version" to "6.2.0"),
                overrides = mapOf(
                    "enabled" to "false",
                    "replicas" to "3",
                    "ratio" to "1.5",
                    "label" to "stable",
                    "artifact.type" to "docker",
                    "artifact.namespace" to "production",
                    "artifact.coordinate" to "server:\${{ release.version }}",
                ),
            ),
        )

        assertEquals("server:6.2.0", outcome.reference)
        assertEquals(publication.id, captured.captured.artifactPublicationId)
        assertEquals("production", captured.captured.artifact?.namespace)
        val config = captured.captured.config as JsonObject
        assertEquals(JsonPrimitive(false), config["enabled"])
        assertEquals(JsonPrimitive(3), config["replicas"])
        assertEquals(JsonPrimitive(1.5), config["ratio"])
        assertEquals(JsonPrimitive("stable"), config["label"])
    }

    @Test
    fun `release bundled deploy works without a version parameter and inherits publication selector values`() = runTest {
        wireConfig(
            ProjectDeployConfig(
                environments = mapOf(
                    "production" to bosca.workops.deploy.EnvironmentDeployConfig(
                        target = "helm-values",
                        artifact = bosca.workops.deploy.ArtifactSelector(coordinate = "server:fixed"),
                    ),
                ),
            ),
        )
        val publication = ArtifactPublication(
            id = UUID.random(),
            versionId = versionId,
            projectId = projectId,
            artifactType = ArtifactType.DOCKER,
            namespace = "release",
            coordinates = "server:fixed",
        )
        coEvery { publications.listByVersion(versionId) } returns listOf(publication)
        val captured = slot<DeployRequest>()
        coEvery { adapter.deploy(capture(captured), any()) } returns
            DeployOutcome(UUID.random(), "server:fixed", EnvironmentDeploymentStatus.DEPLOYED)

        deployer.deploy(request(parameters = mapOf("release.id" to releaseId.toString())))

        assertEquals(ArtifactType.DOCKER.name, captured.captured.artifact?.type)
        assertEquals("release", captured.captured.artifact?.namespace)
        assertEquals("server:fixed", captured.captured.artifact?.coordinate)
    }

    @Test
    fun `deploy failures name unresolved project environment config version target and adapter`() = runTest {
        coEvery { projectRepositories.listByRepository(repositoryId) } returns emptyList()
        assertTrue(
            "not linked" in assertFailsWith<IllegalStateException> { deployer.deploy(request()) }.message.orEmpty(),
        )

        coEvery { projectRepositories.listByRepository(repositoryId) } returns listOf(
            ProjectRepositoryLink(id = UUID.random(), projectId = projectId, repositoryId = repositoryId),
        )
        coEvery { projectService.getById(projectId) } returns null
        assertTrue(
            "not linked" in assertFailsWith<IllegalStateException> { deployer.deploy(request()) }.message.orEmpty(),
        )

        coEvery { projectService.getById(projectId) } returns
            Project(id = projectId, programId = programId, key = "SRV", name = "Server", ownerProfileId = UUID.random())
        coEvery { environmentService.getByProgramAndKey(programId, "production") } returns null
        coEvery { environmentService.getByProgramAndName(programId, "production") } returns null
        assertTrue(
            "does not exist" in assertFailsWith<IllegalStateException> { deployer.deploy(request()) }.message.orEmpty(),
        )

        coEvery { environmentService.getByProgramAndName(programId, "production") } returns
            Environment(id = environmentId, programId = programId, key = "production", name = "Production")
        coEvery { deployConfigService.read(repositoryId, any()) } returns null
        val noConfig = assertFailsWith<IllegalStateException> {
            deployer.deploy(request(parameters = mapOf("release.version" to " ")).copy(ref = "refs/heads/main"))
        }
        assertTrue(DeployConfigService.DEPLOY_CONFIG_PATH in noConfig.message.orEmpty())

        wireConfig(
            ProjectDeployConfig(
                environments = mapOf(
                    "production" to bosca.workops.deploy.EnvironmentDeployConfig(target = "unknown-adapter"),
                ),
            ),
        )
        val unknownKind = assertFailsWith<IllegalStateException> { deployer.deploy(request()) }
        assertTrue("unknown target" in unknownKind.message.orEmpty())

        wireConfig(
            ProjectDeployConfig(
                environments = mapOf(
                    "production" to bosca.workops.deploy.EnvironmentDeployConfig(target = "helm-values"),
                ),
            ),
        )
        val unknownTarget = assertFailsWith<IllegalStateException> { deployer.deploy(request(target = "app-store")) }
        assertTrue("available" in unknownTarget.message.orEmpty())

        coEvery { versionService.getById(versionId) } returns null
        val missingBundled = assertFailsWith<IllegalStateException> { deployer.deploy(request()) }
        assertTrue("bundles a missing version" in missingBundled.message.orEmpty())

        coEvery { versionService.getById(versionId) } returns
            Version(id = versionId, projectId = projectId, name = "6.2.0", sequenceNumber = 1)
        bosca.di.ProviderRegistry.clear()
        val missingAdapter = assertFailsWith<IllegalStateException> { deployer.deploy(request()) }
        assertTrue("deploy adapter" in missingAdapter.message.orEmpty())
    }

    @Test
    fun `generate release notes reports missing release and program`() = runTest {
        coEvery { releaseService.getById(releaseId) } returns null
        assertFailsWith<NoSuchElementException> { deployer.generateReleaseNotes(releaseId, initiator) }

        coEvery { releaseService.getById(releaseId) } returns bosca.workops.model.release.Release(
            id = releaseId,
            programId = programId,
            name = "6.2.0",
            version = 1,
        )
        coEvery { programService.getById(programId) } returns null
        assertFailsWith<NoSuchElementException> { deployer.generateReleaseNotes(releaseId, initiator) }
        coVerify(exactly = 0) { releaseNotesService.autoGenerateLocalized(any(), any()) }
    }
}
