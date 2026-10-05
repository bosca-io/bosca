package bosca.kubernetes.pipelines

import bosca.artifacts.service.ArtifactRepositoryService
import bosca.artifacts.service.BlobStorageService
import bosca.kubernetes.model.K8sHelmRelease
import bosca.kubernetes.service.KubernetesControllerClient
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.workops.deploy.DeployRequest
import bosca.workops.deploy.DeployTargetKind
import bosca.workops.deploy.RollbackRequest
import bosca.workops.model.artifact.ArtifactPublication
import bosca.workops.model.artifact.ArtifactType
import bosca.workops.model.environment.Environment
import bosca.workops.model.environment.EnvironmentDeployment
import bosca.workops.model.environment.EnvironmentDeploymentStatus
import bosca.workops.service.ArtifactPublicationService
import bosca.workops.service.EnvironmentService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [HelmValuesDeployTarget] (`target: helm-values`): resolves the deploying
 * version's HELM_VALUES publication for the target environment, fetches the values file from the RAW
 * registry, and APPLIES it via a real `helm upgrade` — never writing to git.
 */
class HelmValuesDeployTargetTest {

    private val controller = mockk<KubernetesControllerClient>()
    private val environmentService = mockk<EnvironmentService>()
    private val publications = mockk<ArtifactPublicationService>()
    private val artifacts = mockk<ArtifactRepositoryService>()
    private val blobs = mockk<BlobStorageService>()
    private val target = HelmValuesDeployTarget(controller, environmentService, publications, artifacts, blobs, Json { ignoreUnknownKeys = true })

    private val auth = AuthenticationContext(null, null)
    private val clusterId = UUID.random()
    private val environmentId = UUID.random()
    private val projectId = UUID.random()
    private val versionId = UUID.random()
    private val deploymentId = UUID.random()
    private val principalId = UUID.random()
    private val registryRepoId = UUID.random()
    private val registryVersionId = UUID.random()

    /** As CI built it — version-free/stale; the target sets image.tag to the deploying version. */
    private val valuesYaml = "replicas: 2\nimage:\n  tag: \"0.0.0-dev\"\n"
    private val appliedYaml = "replicas: 2\nimage:\n  tag: \"6.0.5\"\n"

    private fun config() = Json.encodeToJsonElement(
        HelmValuesApplyConfig.serializer(),
        HelmValuesApplyConfig(
            clusterId = clusterId.toString(),
            releaseName = "bosca",
            namespace = "development",
            repo = "bosca-helm",
            chart = "bosca",
        ),
    )

    private fun request() = DeployRequest(
        environmentId = environmentId,
        projectId = projectId,
        versionId = versionId,
        version = "6.0.5",
        config = config(),
        deployedByPrincipalId = principalId,
    )

    private fun publication(coordinates: String, environments: List<String>, namespace: String? = "bosca-helm") =
        ArtifactPublication(
            id = UUID.random(), versionId = versionId, projectId = projectId,
            artifactType = ArtifactType.HELM_VALUES, coordinates = coordinates,
            namespace = namespace, environments = environments,
        )

    private fun helmRelease(revision: Int) = mockk<K8sHelmRelease> { every { this@mockk.revision } returns revision }

    private fun wireEnvironment(name: String = "development") {
        coEvery { environmentService.getById(environmentId) } returns
            Environment(id = environmentId, programId = UUID.random(), key = name.lowercase(), name = name)
    }

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

    private fun wireRegistry(filename: String = "values.yaml", blobCount: Int = 1) {
        coEvery { artifacts.findRepository("bosca-helm", "bosca-values", bosca.artifacts.model.ArtifactType.RAW) } returns
            mockk { every { id } returns registryRepoId }
        coEvery { artifacts.findVersion(registryRepoId, "6.0.5") } returns
            mockk { every { id } returns registryVersionId }
        coEvery { artifacts.getVersionBlobs(registryVersionId) } returns (1..blobCount).map { n ->
            mockk {
                every { digest } returns "sha256:d$n"
                every { this@mockk.filename } returns (if (n == 1) filename else "extra-$n.yaml")
            }
        }
        coEvery { blobs.getInputStream("sha256:d1") } returns ByteArrayInputStream(valuesYaml.toByteArray())
    }

    @Test
    fun `applies the environment's values artifact via helm upgrade and marks DEPLOYED`() = runTest {
        wireEnvironment()
        wirePendingDeploy()
        wireRegistry()
        coEvery { publications.listByVersion(versionId) } returns listOf(
            publication("bosca-values-staging:6.0.5", listOf("staging")),
            publication("bosca-values:6.0.5", listOf("development")),
        )
        coEvery {
            controller.helmUpgrade(auth, clusterId, "bosca", "development", "6.0.5", appliedYaml, false, false, "bosca-helm", "bosca")
        } returns helmRelease(7)

        val outcome = target.deploy(request(), auth)

        assertEquals(deploymentId, outcome.deploymentId)
        assertEquals(EnvironmentDeploymentStatus.DEPLOYED, outcome.status)
        assertEquals("bosca@7", outcome.reference)
        coVerify(exactly = 1) {
            controller.helmUpgrade(auth, clusterId, "bosca", "development", "6.0.5", appliedYaml, false, false, "bosca-helm", "bosca")
        }
    }

    @Test
    fun `an unscoped values publication serves any environment`() = runTest {
        wireEnvironment(name = "production")
        wirePendingDeploy()
        wireRegistry()
        coEvery { publications.listByVersion(versionId) } returns listOf(publication("bosca-values:6.0.5", emptyList()))
        coEvery {
            controller.helmUpgrade(auth, any(), any(), any(), any(), appliedYaml, any(), any(), any(), any())
        } returns helmRelease(3)

        assertEquals(EnvironmentDeploymentStatus.DEPLOYED, target.deploy(request(), auth).status)
    }

    @Test
    fun `no matching values publication fails before recording a deployment`() = runTest {
        wireEnvironment()
        coEvery { publications.listByVersion(versionId) } returns listOf(
            publication("bosca-values-staging:6.0.5", listOf("staging")),
        )

        val e = assertFailsWith<IllegalStateException> { target.deploy(request(), auth) }

        assertTrue("no helm-values publication" in (e.message ?: ""), e.message)
        coVerify(exactly = 0) { environmentService.createDeployment(any(), any()) }
    }

    @Test
    fun `a failed helm upgrade marks the deployment FAILED and rethrows`() = runTest {
        wireEnvironment()
        wireRegistry()
        val pending = mockk<EnvironmentDeployment> {
            every { id } returns deploymentId
            every { version } returns 0L
        }
        coEvery { environmentService.createDeployment(any(), principalId) } returns pending
        coEvery { environmentService.markFailed(deploymentId, 0L) } returns mockk()
        coEvery { publications.listByVersion(versionId) } returns listOf(publication("bosca-values:6.0.5", listOf("development")))
        coEvery {
            controller.helmUpgrade(auth, any(), any(), any(), any(), any(), any(), any(), any(), any())
        } throws IllegalStateException("helm upgrade failed")

        assertFailsWith<IllegalStateException> { target.deploy(request(), auth) }

        coVerify(exactly = 1) { environmentService.markFailed(deploymentId, 0L) }
        coVerify(exactly = 0) { environmentService.markDeployed(any(), any(), any()) }
    }

    @Test
    fun `several blobs prefer the values-yaml file`() = runTest {
        wireEnvironment()
        wirePendingDeploy()
        wireRegistry(blobCount = 3)
        coEvery { publications.listByVersion(versionId) } returns listOf(publication("bosca-values:6.0.5", listOf("development")))
        coEvery {
            controller.helmUpgrade(auth, any(), any(), any(), any(), appliedYaml, any(), any(), any(), any())
        } returns helmRelease(2)

        assertEquals(EnvironmentDeploymentStatus.DEPLOYED, target.deploy(request(), auth).status)
    }

    @Test
    fun `a publication without a recorded namespace fails naming the coordinate`() = runTest {
        wireEnvironment()
        coEvery { publications.listByVersion(versionId) } returns listOf(
            publication("bosca-values:6.0.5", listOf("development"), namespace = null),
        )

        val e = assertFailsWith<IllegalStateException> { target.deploy(request(), auth) }
        assertTrue("records no registry namespace" in (e.message ?: ""), e.message)
    }

    @Test
    fun `rollback runs a real helm rollback and marks the current deployment ROLLED_BACK`() = runTest {
        val current = mockk<EnvironmentDeployment> {
            every { id } returns deploymentId
            every { this@mockk.projectId } returns this@HelmValuesDeployTargetTest.projectId
            every { targetKind } returns DeployTargetKind.HELM_VALUES
            every { version } returns 3L
        }
        coEvery { environmentService.currentState(environmentId) } returns listOf(current)
        coEvery { controller.helmRollback(auth, clusterId, "development", "bosca", 6) } returns helmRelease(8)
        val rolledBack = mockk<EnvironmentDeployment> {
            every { id } returns deploymentId
            every { status } returns EnvironmentDeploymentStatus.ROLLED_BACK
        }
        coEvery { environmentService.markRolledBack(deploymentId, 3L) } returns rolledBack

        val outcome = target.rollback(
            RollbackRequest(
                environmentId = environmentId, projectId = projectId, toRevision = 6,
                config = config(), deployedByPrincipalId = principalId,
            ),
            auth,
        )

        assertEquals(EnvironmentDeploymentStatus.ROLLED_BACK, outcome.status)
        assertEquals("bosca@8", outcome.reference)
    }
}
