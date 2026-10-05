package bosca.kubernetes.pipelines

import bosca.artifacts.model.ArtifactType
import bosca.artifacts.service.ArtifactRepositoryService
import bosca.artifacts.service.BlobStorageService
import bosca.kubernetes.service.KubernetesControllerClient
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.workops.deploy.DeployOutcome
import bosca.workops.deploy.DeployRequest
import bosca.workops.deploy.DeployTarget
import bosca.workops.deploy.DeployTargetKind
import bosca.workops.deploy.RollbackRequest
import bosca.workops.model.environment.DeployInput
import bosca.workops.service.ArtifactPublicationService
import bosca.workops.service.EnvironmentService
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The Helm routing a `target: helm-values` environment stanza declares — pure routing, no values
 * plumbing (the values come from the registry artifact, not the config):
 *
 * ```yaml
 * environments:
 *   development:
 *     target: helm-values
 *     config:
 *       clusterId: "…"
 *       releaseName: "bosca"
 *       namespace: "development"
 *       repo: "bosca-helm"
 *       chart: "bosca"
 *       chartVersion: ""        # blank = the release version
 *       paths: ["image.tag"]    # optional — keys set to the deploying version at apply time
 * ```
 */
@Serializable
data class HelmValuesApplyConfig(
    val clusterId: String,
    val releaseName: String,
    val namespace: String,
    val repo: String = "",
    val chart: String = "",
    val chartVersion: String = "",
    val resetValues: Boolean = false,
    /** The values keys set to the deploying version at APPLY time — the file in git (and the registry
     *  artifact) never carries a release version; the version is applied, not stored. */
    val paths: List<String> = listOf("image.tag"),
)

/**
 * the values-artifact [DeployTarget] (`target: helm-values`). CI publishes the
 * environment's values file as a `helm-values` artifact (a RAW registry upload, declared in the build
 * yaml with an `environments` filter) — version-free; deploying APPLIES those values: the adapter picks the deploying
 * version's `HELM_VALUES` publication matching the target environment, fetches its file from the
 * registry, and runs a real `helm upgrade` through the [KubernetesControllerClient]. Nothing is ever
 * committed back to git — the registry artifact is the single source of the applied values.
 *
 * Environment matching: a publication whose `environments` contains the environment's name wins; one
 * with no environment scope serves every environment. No matching publication fails the run — a
 * missing values artifact is a broken release, not something to skip silently.
 *
 * Rollback is a real `helm rollback` to the requested revision, exactly like the `helm` target.
 */
class HelmValuesDeployTarget(
    private val controller: KubernetesControllerClient,
    private val environmentService: EnvironmentService,
    private val publications: ArtifactPublicationService,
    private val artifacts: ArtifactRepositoryService,
    private val blobs: BlobStorageService,
    private val json: Json,
) : DeployTarget {

    override val kind: DeployTargetKind = DeployTargetKind.HELM_VALUES

    override fun validateConfig(config: kotlinx.serialization.json.JsonElement): List<String> {
        val cfg = try {
            json.decodeFromJsonElement(HelmValuesApplyConfig.serializer(), config)
        } catch (e: Exception) {
            return listOf("helm-values config does not decode: ${e.message}")
        }
        return buildList {
            if (cfg.clusterId.isBlank()) add("helm-values config requires clusterId")
            if (cfg.releaseName.isBlank()) add("helm-values config requires releaseName")
            if (cfg.namespace.isBlank()) add("helm-values config requires namespace")
        }
    }

    override suspend fun deploy(request: DeployRequest, authentication: AuthenticationContext): DeployOutcome {
        val cfg = json.decodeFromJsonElement(HelmValuesApplyConfig.serializer(), request.config)
        val environment = environmentService.getById(request.environmentId)
            ?: error("helm-values deploy: environment ${request.environmentId} not found")
        // The version is applied, never stored: the artifact's file is fetched as CI built it and the
        // version keys are set here, at apply time.
        val values = bosca.workops.deploy.ValuesVersionRewriter.rewrite(
            resolveValues(request.versionId, environment.name),
            cfg.paths,
            request.version,
        )

        // Record the deployment as PENDING before touching the cluster, so a failure is still tracked.
        val deployment = environmentService.createDeployment(
            DeployInput(
                environmentId = request.environmentId,
                projectId = request.projectId,
                targetKind = kind,
                versionId = request.versionId,
                releaseId = request.releaseId,
                artifactPublicationId = request.artifactPublicationId,
                healthCheckUrl = request.healthCheckUrl,
            ),
            request.deployedByPrincipalId,
        )
        return try {
            val release = controller.helmUpgrade(
                authentication = authentication,
                clusterId = UUID.parse(cfg.clusterId),
                name = cfg.releaseName,
                namespace = cfg.namespace,
                version = cfg.chartVersion.ifBlank { request.version },
                values = values,
                dryRun = false,
                resetValues = cfg.resetValues,
                repo = cfg.repo,
                chart = cfg.chart,
            )
            val deployed = environmentService.markDeployed(deployment.id, request.deployedByPrincipalId, deployment.version)
            DeployOutcome(deployed.id, "${cfg.releaseName}@${release.revision}", deployed.status)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            environmentService.markFailed(deployment.id, deployment.version)
            throw e
        }
    }

    override suspend fun rollback(request: RollbackRequest, authentication: AuthenticationContext): DeployOutcome {
        val cfg = json.decodeFromJsonElement(HelmValuesApplyConfig.serializer(), request.config)
        val current = environmentService.currentState(request.environmentId)
            .firstOrNull {
                it.projectId == request.projectId &&
                    it.targetKind == kind
            }
            ?: error(
                "helm-values rollback: no current deployment for target '$kind' and project " +
                    "${request.projectId} in environment ${request.environmentId}"
            )
        val release = controller.helmRollback(
            authentication = authentication,
            clusterId = UUID.parse(cfg.clusterId),
            namespace = cfg.namespace,
            name = cfg.releaseName,
            toRevision = request.toRevision,
        )
        val rolledBack = environmentService.markRolledBack(current.id, current.version)
        return DeployOutcome(rolledBack.id, "${cfg.releaseName}@${release.revision}", rolledBack.status)
    }

    /**
     * The values content to apply: the deploying version's `HELM_VALUES` publication matching
     * [environmentName] (its own `environments` scope wins; an unscoped one serves every environment),
     * fetched from the RAW registry repository its coordinate names. Prefers a blob named
     * `values.yaml` when the artifact carries several files.
     */
    private suspend fun resolveValues(versionId: UUID, environmentName: String): String {
        val candidates = publications.listByVersion(versionId)
            .filter { it.artifactType == bosca.workops.model.artifact.ArtifactType.HELM_VALUES }
        val publication = candidates.firstOrNull { pub -> pub.environments.any { it.equals(environmentName, ignoreCase = true) } }
            ?: candidates.firstOrNull { it.environments.isEmpty() }
            ?: error(
                "helm-values deploy: version $versionId has no helm-values publication for environment " +
                    "'$environmentName' — declare one in the build yaml's artifacts (with an 'environments' filter)",
            )
        val namespace = publication.namespace
            ?: error("helm-values deploy: publication '${publication.coordinates}' records no registry namespace")
        val name = publication.coordinates.substringBeforeLast(':')
        val version = publication.coordinates.substringAfterLast(':')
        check(name.isNotBlank() && version.isNotBlank() && name != publication.coordinates) {
            "helm-values deploy: publication coordinate '${publication.coordinates}' is not name:version"
        }

        val repository = artifacts.findRepository(namespace, name, ArtifactType.RAW)
            ?: error("helm-values deploy: no raw registry repository '$namespace/$name' — did the build's registry-upload run?")
        val artifactVersion = artifacts.findVersion(repository.id, version)
            ?: error("helm-values deploy: registry repository '$namespace/$name' has no version '$version'")
        val versionBlobs = artifacts.getVersionBlobs(artifactVersion.id)
        val blob = versionBlobs.singleOrNull()
            ?: versionBlobs.firstOrNull { it.filename.equals(VALUES_FILE, ignoreCase = true) }
            ?: error(
                "helm-values deploy: '$namespace/$name:$version' has ${versionBlobs.size} files and none is " +
                    "'$VALUES_FILE' — can't pick the values file (${versionBlobs.joinToString { it.filename ?: "(unnamed)" }})",
            )
        return blobs.getInputStream(blob.digest).use { it.readBytes().decodeToString() }
    }

    companion object {
        /** The blob applied when the artifact carries several files. */
        private const val VALUES_FILE = "values.yaml"
    }
}
