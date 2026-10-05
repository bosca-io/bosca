package bosca.kubernetes.pipelines

import bosca.git.service.RepositoryWriteService
import bosca.kubernetes.model.K8sHelmRelease
import bosca.kubernetes.service.KubernetesControllerClient
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.workops.deploy.DeployRequest
import bosca.workops.deploy.DeployTargetKind
import bosca.workops.deploy.RollbackRequest
import bosca.workops.model.environment.EnvironmentDeployment
import bosca.workops.model.environment.EnvironmentDeploymentStatus
import bosca.workops.service.EnvironmentService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [HelmDeployTarget]: records PENDING, runs a real `helm upgrade`/`rollback` via
 * the [KubernetesControllerClient], and marks the deployment DEPLOYED / FAILED / ROLLED_BACK.
 */
class HelmDeployTargetTest {

    private val controller = mockk<KubernetesControllerClient>()
    private val environmentService = mockk<EnvironmentService>()
    private val repositoryWrite = mockk<RepositoryWriteService>()
    private val artifactPublications = mockk<bosca.workops.service.ArtifactPublicationService>()
    private val target = HelmDeployTarget(controller, environmentService, repositoryWrite, artifactPublications, Json { ignoreUnknownKeys = true })

    private val auth = AuthenticationContext(null, null)
    private val clusterId = UUID.random()
    private val deploymentId = UUID.random()
    private val principalId = UUID.random()

    private fun helmConfig(
        values: String = "",
        valuesRepositoryId: String = "",
        chartVersion: String = "",
        valuesRef: String = "refs/heads/main",
        versionPaths: List<String> = emptyList(),
    ) = HelmTargetConfig(
        clusterId = clusterId.toString(),
        releaseName = "web",
        namespace = "prod",
        repo = "charts",
        chart = "web",
        chartVersion = chartVersion,
        values = values,
        valuesRepositoryId = valuesRepositoryId,
        valuesRef = valuesRef,
        versionPaths = versionPaths,
    )

    private fun request(
        cfg: HelmTargetConfig,
        version: String = "5.18.0",
        configRepositoryId: UUID? = null,
        artifactPublicationId: UUID? = null,
    ) = DeployRequest(
        environmentId = UUID.random(),
        projectId = UUID.random(),
        versionId = UUID.random(),
        version = version,
        config = Json.encodeToJsonElement(HelmTargetConfig.serializer(), cfg),
        deployedByPrincipalId = principalId,
        configRepositoryId = configRepositoryId,
        artifactPublicationId = artifactPublicationId,
    )

    private fun helmRelease(revision: Int) = mockk<K8sHelmRelease> { every { this@mockk.revision } returns revision }

    private fun wirePendingDeploy() {
        val pending = mockk<EnvironmentDeployment> {
            every { id } returns deploymentId
            every { version } returns 0L
        }
        coEvery { environmentService.createDeployment(any(), principalId) } returns pending
        val deployed = mockk<EnvironmentDeployment> {
            every { id } returns deploymentId
            every { status } returns EnvironmentDeploymentStatus.DEPLOYED
        }
        coEvery { environmentService.markDeployed(deploymentId, principalId, 0L) } returns deployed
    }

    @Test
    fun `deploy config validation requires an explicit chart version`() {
        val blank = Json.encodeToJsonElement(HelmTargetConfig.serializer(), helmConfig())
        val pinned = Json.encodeToJsonElement(
            HelmTargetConfig.serializer(),
            helmConfig(chartVersion = "5.18.9"),
        )

        assertTrue(target.validateConfig(blank).any { "chartVersion" in it })
        assertTrue(target.validateConfig(pinned).isEmpty())
    }

    @Test
    fun `applies inline values, upgrades, and marks the deployment deployed`() = runTest {
        wirePendingDeploy()
        coEvery {
            controller.helmUpgrade(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns helmRelease(3)

        val outcome = target.deploy(request(helmConfig(values = "replicas: 3")), auth)

        assertEquals(deploymentId, outcome.deploymentId)
        assertEquals("web@3", outcome.reference)
        assertEquals(EnvironmentDeploymentStatus.DEPLOYED, outcome.status)
        coVerify(exactly = 1) {
            controller.helmUpgrade(
                authentication = auth, clusterId = clusterId, name = "web", namespace = "prod",
                version = "5.18.0", values = "replicas: 3", dryRun = false, resetValues = false,
                repo = "charts", chart = "web",
            )
        }
        coVerify(exactly = 0) { repositoryWrite.readFile(any(), any(), any()) }
    }

    @Test
    fun `reads values from the git values file when no inline values are set`() = runTest {
        wirePendingDeploy()
        val repoId = UUID.random()
        coEvery { repositoryWrite.readFile(repoId, "refs/heads/main", "values.yaml") } returns "from: git"
        coEvery { controller.helmUpgrade(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns helmRelease(1)

        target.deploy(request(helmConfig(valuesRepositoryId = repoId.toString())), auth)

        coVerify(exactly = 1) { controller.helmUpgrade(any(), any(), any(), any(), any(), values = "from: git", any(), any(), any(), any()) }
    }

    @Test
    fun `passes null values when neither inline nor a git source is configured`() = runTest {
        wirePendingDeploy()
        coEvery { controller.helmUpgrade(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns helmRelease(1)

        target.deploy(request(helmConfig()), auth)

        coVerify(exactly = 1) { controller.helmUpgrade(any(), any(), any(), any(), any(), values = null, any(), any(), any(), any()) }
        coVerify(exactly = 0) { repositoryWrite.readFile(any(), any(), any()) }
    }

    @Test
    fun `chart version falls back to the request version when blank`() = runTest {
        wirePendingDeploy()
        coEvery { controller.helmUpgrade(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns helmRelease(1)

        target.deploy(request(helmConfig(), version = "7.7.7"), auth)

        coVerify(exactly = 1) { controller.helmUpgrade(any(), any(), any(), any(), version = "7.7.7", any(), any(), any(), any(), any()) }
    }

    @Test
    fun `uses an explicit chart version over the request version`() = runTest {
        wirePendingDeploy()
        coEvery { controller.helmUpgrade(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns helmRelease(1)

        target.deploy(request(helmConfig(chartVersion = "1.2.3"), version = "9.9.9"), auth)

        coVerify(exactly = 1) { controller.helmUpgrade(any(), any(), any(), any(), version = "1.2.3", any(), any(), any(), any(), any()) }
    }

    @Test
    fun `versionPaths pin the release version in git values and commit the bump back before the upgrade`() = runTest {
        wirePendingDeploy()
        val repoId = UUID.random()
        coEvery { repositoryWrite.readFile(repoId, "refs/heads/main", "values.yaml") } returns
            "image:\n  tag: \"5.17.0\"\nreplicas: 2"
        val committed = io.mockk.slot<bosca.git.service.CommitFileInput>()
        coEvery { repositoryWrite.commitFile(capture(committed)) } returns
            bosca.git.service.CommitFileResult(commitSha = "sha1", branch = "main", path = "values.yaml")
        coEvery { controller.helmUpgrade(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns helmRelease(4)

        target.deploy(
            request(helmConfig(valuesRepositoryId = repoId.toString(), versionPaths = listOf("image.tag"))),
            auth,
        )

        val expected = "image:\n  tag: \"5.18.0\"\nreplicas: 2"
        // The bump lands in git (on the branch, with the exact rewritten content)…
        assertEquals("main", committed.captured.branch)
        assertEquals(expected, committed.captured.content)
        assertTrue("5.18.0" in committed.captured.message, committed.captured.message)
        // …and the SAME content is what helm deploys.
        coVerify(exactly = 1) { controller.helmUpgrade(any(), any(), any(), any(), any(), values = expected, any(), any(), any(), any()) }
    }

    @Test
    fun `an already-pinned values file deploys without a redundant commit`() = runTest {
        wirePendingDeploy()
        val repoId = UUID.random()
        coEvery { repositoryWrite.readFile(repoId, "refs/heads/main", "values.yaml") } returns "image:\n  tag: \"5.18.0\""
        coEvery { controller.helmUpgrade(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns helmRelease(4)

        target.deploy(request(helmConfig(valuesRepositoryId = repoId.toString(), versionPaths = listOf("image.tag"))), auth)

        coVerify(exactly = 0) { repositoryWrite.commitFile(any()) }
    }

    @Test
    fun `versionPaths with a non-branch valuesRef fail — the bump has nowhere to land`() = runTest {
        wirePendingDeploy()
        coEvery { environmentService.markFailed(deploymentId, 0L) } returns mockk()
        val repoId = UUID.random()
        coEvery { repositoryWrite.readFile(repoId, "refs/tags/v5.17.0", "values.yaml") } returns "image:\n  tag: \"5.17.0\""

        val e = assertFailsWith<IllegalStateException> {
            target.deploy(
                request(helmConfig(valuesRepositoryId = repoId.toString(), valuesRef = "refs/tags/v5.17.0", versionPaths = listOf("image.tag"))),
                auth,
            )
        }
        assertTrue("branch" in (e.message ?: ""), e.message)
        coVerify(exactly = 0) { controller.helmUpgrade(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `versionPaths rewrite inline values too, with nothing to commit`() = runTest {
        wirePendingDeploy()
        coEvery { controller.helmUpgrade(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns helmRelease(2)

        target.deploy(request(helmConfig(values = "image:\n  tag: old", versionPaths = listOf("image.tag"))), auth)

        coVerify(exactly = 1) { controller.helmUpgrade(any(), any(), any(), any(), any(), values = "image:\n  tag: \"5.18.0\"", any(), any(), any(), any()) }
        coVerify(exactly = 0) { repositoryWrite.commitFile(any()) }
    }

    @Test
    fun `versionPaths pin the ARTIFACT's coordinate tag, not the version name — the artifact is what deploys`() = runTest {
        wirePendingDeploy()
        val repoId = UUID.random()
        val publicationId = UUID.random()
        // The build declared the image as v-prefixed; the workops version name has no prefix. The
        // artifact's tag must win — that's the image that actually exists in the registry.
        coEvery { artifactPublications.getById(publicationId) } returns
            bosca.workops.model.artifact.ArtifactPublication(
                id = publicationId, versionId = UUID.random(), projectId = UUID.random(),
                artifactType = bosca.workops.model.artifact.ArtifactType.DOCKER,
                coordinates = "web:v5.18.0",
            )
        coEvery { repositoryWrite.readFile(repoId, "refs/heads/main", "values.yaml") } returns "image:\n  tag: old"
        coEvery { repositoryWrite.commitFile(any()) } returns
            bosca.git.service.CommitFileResult(commitSha = "sha", branch = "main", path = "values.yaml")
        coEvery { controller.helmUpgrade(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns helmRelease(2)

        target.deploy(
            request(
                helmConfig(valuesRepositoryId = repoId.toString(), versionPaths = listOf("image.tag")),
                artifactPublicationId = publicationId,
            ),
            auth,
        )

        coVerify(exactly = 1) {
            controller.helmUpgrade(any(), any(), any(), any(), any(), values = "image:\n  tag: \"v5.18.0\"", any(), any(), any(), any())
        }
    }

    @Test
    fun `the values file resolves from the deploy config's own repository when none is declared`() = runTest {
        wirePendingDeploy()
        val configRepo = UUID.random()
        coEvery { repositoryWrite.readFile(configRepo, "refs/heads/main", "values-prod.yaml") } returns
            "image:\n  tag: \"5.17.0\""
        coEvery { repositoryWrite.commitFile(any()) } returns
            bosca.git.service.CommitFileResult(commitSha = "sha", branch = "main", path = "values-prod.yaml")
        coEvery { controller.helmUpgrade(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns helmRelease(2)

        target.deploy(
            request(
                helmConfig(versionPaths = listOf("image.tag")).copy(valuesPath = "values-prod.yaml"),
                configRepositoryId = configRepo,
            ),
            auth,
        )

        coVerify(exactly = 1) {
            controller.helmUpgrade(any(), any(), any(), any(), any(), values = "image:\n  tag: \"5.18.0\"", any(), any(), any(), any())
        }
    }

    @Test
    fun `a values file merely absent from the config's own repo means no values, not a failure`() = runTest {
        wirePendingDeploy()
        val configRepo = UUID.random()
        coEvery { repositoryWrite.readFile(configRepo, "refs/heads/main", "values.yaml") } returns null
        coEvery { controller.helmUpgrade(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns helmRelease(2)

        target.deploy(request(helmConfig(), configRepositoryId = configRepo), auth)

        coVerify(exactly = 1) { controller.helmUpgrade(any(), any(), any(), any(), any(), values = null, any(), any(), any(), any()) }
    }

    @Test
    fun `versionPaths with an unreadable values file fail even via the fallback repo — the pin was promised`() = runTest {
        wirePendingDeploy()
        val configRepo = UUID.random()
        coEvery { repositoryWrite.readFile(configRepo, "refs/heads/main", "values.yaml") } returns null

        val e = assertFailsWith<IllegalStateException> {
            target.deploy(request(helmConfig(versionPaths = listOf("image.tag")), configRepositoryId = configRepo), auth)
        }
        assertTrue("could not read values" in (e.message ?: ""), e.message)
    }

    @Test
    fun `a helm failure marks the deployment failed and rethrows`() = runTest {
        val pending = mockk<EnvironmentDeployment> {
            every { id } returns deploymentId
            every { version } returns 0L
        }
        coEvery { environmentService.createDeployment(any(), principalId) } returns pending
        coEvery { controller.helmUpgrade(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws RuntimeException("boom")
        coEvery { environmentService.markFailed(deploymentId, 0L) } returns mockk()

        val e = assertFailsWith<RuntimeException> { target.deploy(request(helmConfig(values = "x")), auth) }

        assertEquals("boom", e.message)
        coVerify(exactly = 1) { environmentService.markFailed(deploymentId, 0L) }
        coVerify(exactly = 0) { environmentService.markDeployed(any(), any(), any()) }
    }

    @Test
    fun `fails before recording when the git values file cannot be read`() = runTest {
        val repoId = UUID.random()
        coEvery { repositoryWrite.readFile(repoId, "refs/heads/main", "values.yaml") } returns null

        val e = assertFailsWith<IllegalStateException> {
            target.deploy(request(helmConfig(valuesRepositoryId = repoId.toString())), auth)
        }

        assertTrue("could not read values" in (e.message ?: ""))
        coVerify(exactly = 0) { environmentService.createDeployment(any(), any()) }
        coVerify(exactly = 0) { controller.helmUpgrade(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `the adapter reports its kind`() {
        assertEquals(bosca.workops.deploy.DeployTargetKind.HELM, target.kind)
    }

    // ---- rollback ----

    private fun rollbackRequest(
        environmentId: UUID,
        projectId: UUID,
        toRevision: Int = 0,
        cfg: HelmTargetConfig = helmConfig(),
        configRepositoryId: UUID? = null,
    ) = RollbackRequest(
        environmentId = environmentId,
        projectId = projectId,
        toRevision = toRevision,
        config = Json.encodeToJsonElement(HelmTargetConfig.serializer(), cfg),
        deployedByPrincipalId = principalId,
        configRepositoryId = configRepositoryId,
    )

    @Test
    fun `rollback reverts the git values to the previous deployment's tag and commits it`() = runTest {
        val envId = UUID.random()
        val projId = UUID.random()
        val repoId = UUID.random()
        val prevDeploymentId = UUID.random()
        val prevPublicationId = UUID.random()
        val current = mockk<EnvironmentDeployment> {
            every { id } returns deploymentId
            every { projectId } returns projId
            every { targetKind } returns DeployTargetKind.HELM
            every { version } returns 2L
            every { previousDeploymentId } returns prevDeploymentId
        }
        coEvery { environmentService.currentState(envId) } returns listOf(current)
        coEvery { controller.helmRollback(any(), any(), any(), any(), any()) } returns helmRelease(2)
        coEvery { environmentService.markRolledBack(deploymentId, 2L) } returns mockk {
            every { id } returns deploymentId
            every { status } returns EnvironmentDeploymentStatus.ROLLED_BACK
        }
        coEvery { environmentService.getDeployment(prevDeploymentId) } returns mockk {
            every { artifactPublicationId } returns prevPublicationId
        }
        coEvery { artifactPublications.getById(prevPublicationId) } returns mockk {
            every { coordinates } returns "web:5.17.0"
        }
        coEvery { repositoryWrite.readFile(repoId, "refs/heads/main", "values.yaml") } returns
            "image:\n  tag: \"5.18.0\"\nreplicas: 2"
        val committed = io.mockk.slot<bosca.git.service.CommitFileInput>()
        coEvery { repositoryWrite.commitFile(capture(committed)) } returns
            bosca.git.service.CommitFileResult(commitSha = "sha2", branch = "main", path = "values.yaml")

        target.rollback(
            rollbackRequest(envId, projId, cfg = helmConfig(versionPaths = listOf("image.tag")), configRepositoryId = repoId),
            auth,
        )

        // The cluster moved back, so the file moves back too — to the PREVIOUS publication's tag.
        assertEquals("image:\n  tag: \"5.17.0\"\nreplicas: 2", committed.captured.content)
        assertTrue("Revert" in committed.captured.message, committed.captured.message)
        assertTrue("5.17.0" in committed.captured.message, committed.captured.message)
    }

    @Test
    fun `rollback with version paths fails closed without a previous publication`() = runTest {
        val envId = UUID.random()
        val projId = UUID.random()
        val current = mockk<EnvironmentDeployment> {
            every { id } returns deploymentId
            every { projectId } returns projId
            every { targetKind } returns DeployTargetKind.HELM
            every { version } returns 2L
            every { previousDeploymentId } returns null
        }
        coEvery { environmentService.currentState(envId) } returns listOf(current)
        val failure = assertFailsWith<IllegalStateException> {
            target.rollback(
                rollbackRequest(
                    envId,
                    projId,
                    cfg = helmConfig(versionPaths = listOf("image.tag")),
                    configRepositoryId = UUID.random(),
                ),
                auth,
            )
        }

        assertTrue("no previous deployment" in failure.message.orEmpty(), failure.message)
        coVerify(exactly = 0) { controller.helmRollback(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { environmentService.markRolledBack(any(), any()) }
    }

    @Test
    fun `rollback with version paths rejects an explicit native revision rather than guessing a git tag`() = runTest {
        val envId = UUID.random()
        val projId = UUID.random()
        val current = mockk<EnvironmentDeployment> {
            every { id } returns deploymentId
            every { projectId } returns projId
            every { targetKind } returns DeployTargetKind.HELM
        }
        coEvery { environmentService.currentState(envId) } returns listOf(current)

        val failure = assertFailsWith<IllegalArgumentException> {
            target.rollback(
                rollbackRequest(
                    envId,
                    projId,
                    toRevision = 5,
                    cfg = helmConfig(versionPaths = listOf("image.tag")),
                    configRepositoryId = UUID.random(),
                ),
                auth,
            )
        }

        assertTrue("cannot be reconciled" in failure.message.orEmpty(), failure.message)
        coVerify(exactly = 0) { controller.helmRollback(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `git pinned rollback fails closed for every incomplete previous deployment identity`() = runTest {
        val envId = UUID.random()
        val projId = UUID.random()
        val repoId = UUID.random()
        val previousDeploymentId = UUID.random()
        val previousPublicationId = UUID.random()
        val current = mockk<EnvironmentDeployment> {
            every { id } returns deploymentId
            every { projectId } returns projId
            every { targetKind } returns DeployTargetKind.HELM
            every { version } returns 2L
            every { this@mockk.previousDeploymentId } returns previousDeploymentId
        }
        coEvery { environmentService.currentState(envId) } returns listOf(current)
        val request = rollbackRequest(
            envId,
            projId,
            cfg = helmConfig(versionPaths = listOf("image.tag")),
            configRepositoryId = repoId,
        )

        coEvery { environmentService.getDeployment(previousDeploymentId) } returns null
        assertTrue(
            "no longer exists" in assertFailsWith<IllegalStateException> {
                target.rollback(request, auth)
            }.message.orEmpty(),
        )

        coEvery { environmentService.getDeployment(previousDeploymentId) } returns mockk {
            every { artifactPublicationId } returns null
        }
        assertTrue(
            "no artifact publication" in assertFailsWith<IllegalStateException> {
                target.rollback(request, auth)
            }.message.orEmpty(),
        )

        coEvery { environmentService.getDeployment(previousDeploymentId) } returns mockk {
            every { artifactPublicationId } returns previousPublicationId
        }
        coEvery { artifactPublications.getById(previousPublicationId) } returns null
        assertTrue(
            "no usable tag" in assertFailsWith<IllegalStateException> {
                target.rollback(request, auth)
            }.message.orEmpty(),
        )

        coEvery { artifactPublications.getById(previousPublicationId) } returns mockk {
            every { coordinates } returns " "
        }
        assertTrue(
            "no usable tag" in assertFailsWith<IllegalStateException> {
                target.rollback(request, auth)
            }.message.orEmpty(),
        )

        coVerify(exactly = 0) { controller.helmRollback(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { environmentService.markRolledBack(any(), any()) }
    }

    @Test
    fun `git pinned rollback requires readable values and skips an unchanged revert commit`() = runTest {
        val envId = UUID.random()
        val projId = UUID.random()
        val repoId = UUID.random()
        val previousDeploymentId = UUID.random()
        val previousPublicationId = UUID.random()
        val current = mockk<EnvironmentDeployment> {
            every { id } returns deploymentId
            every { projectId } returns projId
            every { targetKind } returns DeployTargetKind.HELM
            every { version } returns 2L
            every { this@mockk.previousDeploymentId } returns previousDeploymentId
        }
        coEvery { environmentService.currentState(envId) } returns listOf(current)
        coEvery { environmentService.getDeployment(previousDeploymentId) } returns mockk {
            every { artifactPublicationId } returns previousPublicationId
        }
        coEvery { artifactPublications.getById(previousPublicationId) } returns mockk {
            every { coordinates } returns "web:5.17.0"
        }

        val fallbackRepository = rollbackRequest(
            envId,
            projId,
            cfg = helmConfig(versionPaths = listOf("image.tag")),
        )
        assertTrue(
            "require a values repository" in assertFailsWith<IllegalStateException> {
                target.rollback(fallbackRepository, auth)
            }.message.orEmpty(),
        )

        val explicitRepository = rollbackRequest(
            envId,
            projId,
            cfg = helmConfig(valuesRepositoryId = repoId.toString(), versionPaths = listOf("image.tag")),
        )
        coEvery { repositoryWrite.readFile(repoId, "refs/heads/main", "values.yaml") } returns null
        assertTrue(
            "could not read values" in assertFailsWith<IllegalStateException> {
                target.rollback(explicitRepository, auth)
            }.message.orEmpty(),
        )

        coEvery { repositoryWrite.readFile(repoId, "refs/heads/main", "values.yaml") } returns
            "image:\n  tag: \"5.17.0\""
        coEvery { controller.helmRollback(any(), any(), any(), any(), any()) } returns helmRelease(2)
        coEvery { environmentService.markRolledBack(deploymentId, 2L) } returns mockk {
            every { id } returns deploymentId
            every { status } returns EnvironmentDeploymentStatus.ROLLED_BACK
        }

        target.rollback(explicitRepository, auth)

        coVerify(exactly = 0) { repositoryWrite.commitFile(any()) }
        coVerify(exactly = 1) { controller.helmRollback(auth, clusterId, "prod", "web", 0) }
    }

    @Test
    fun `rollback with inline version paths stays a native helm rollback`() = runTest {
        val envId = UUID.random()
        val projId = UUID.random()
        val current = mockk<EnvironmentDeployment> {
            every { id } returns deploymentId
            every { projectId } returns projId
            every { targetKind } returns DeployTargetKind.HELM
            every { version } returns 2L
        }
        coEvery { environmentService.currentState(envId) } returns listOf(current)
        coEvery { controller.helmRollback(any(), any(), any(), any(), any()) } returns helmRelease(3)
        coEvery { environmentService.markRolledBack(deploymentId, 2L) } returns mockk {
            every { id } returns deploymentId
            every { status } returns EnvironmentDeploymentStatus.ROLLED_BACK
        }

        target.rollback(
            rollbackRequest(
                envId,
                projId,
                toRevision = 3,
                cfg = helmConfig(values = "image:\n  tag: current", versionPaths = listOf("image.tag")),
            ),
            auth,
        )

        coVerify(exactly = 1) { controller.helmRollback(auth, clusterId, "prod", "web", 3) }
        coVerify(exactly = 0) { repositoryWrite.readFile(any(), any(), any()) }
    }

    @Test
    fun `rollback rolls the release back and marks the current deployment rolled back`() = runTest {
        val envId = UUID.random()
        val projId = UUID.random()
        val current = mockk<EnvironmentDeployment> {
            every { id } returns deploymentId
            every { projectId } returns projId
            every { targetKind } returns DeployTargetKind.HELM
            every { version } returns 2L
        }
        coEvery { environmentService.currentState(envId) } returns listOf(current)
        coEvery { controller.helmRollback(any(), any(), any(), any(), any()) } returns helmRelease(2)
        val rolledBack = mockk<EnvironmentDeployment> {
            every { id } returns deploymentId
            every { status } returns EnvironmentDeploymentStatus.ROLLED_BACK
        }
        coEvery { environmentService.markRolledBack(deploymentId, 2L) } returns rolledBack

        val outcome = target.rollback(rollbackRequest(envId, projId, toRevision = 5), auth)

        assertEquals(deploymentId, outcome.deploymentId)
        assertEquals("web@2", outcome.reference)
        assertEquals(EnvironmentDeploymentStatus.ROLLED_BACK, outcome.status)
        coVerify(exactly = 1) { controller.helmRollback(auth, clusterId, "prod", "web", 5) }
        coVerify(exactly = 1) { environmentService.markRolledBack(deploymentId, 2L) }
    }

    @Test
    fun `rollback fails when there is no current deployment for the project`() = runTest {
        val envId = UUID.random()
        val requestedProject = UUID.random()
        coEvery { environmentService.currentState(envId) } returns listOf(
            mockk {
                every { projectId } returns UUID.random()
                every { targetKind } returns DeployTargetKind.HELM
            },
            mockk {
                every { projectId } returns requestedProject
                every { targetKind } returns DeployTargetKind.HELM_VALUES
            },
        )

        val e = assertFailsWith<IllegalStateException> { target.rollback(rollbackRequest(envId, requestedProject), auth) }

        assertTrue("no current deployment" in (e.message ?: ""))
        coVerify(exactly = 0) { controller.helmRollback(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { environmentService.markRolledBack(any(), any()) }
    }
}
