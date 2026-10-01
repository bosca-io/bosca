package bosca.kubernetes.pipelines

import bosca.git.service.CommitFileInput
import bosca.git.service.RepositoryWriteService
import bosca.kubernetes.service.KubernetesControllerClient
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.workops.deploy.DeployOutcome
import bosca.workops.deploy.DeployRequest
import bosca.workops.deploy.DeployTarget
import bosca.workops.deploy.DeployTargetKind
import bosca.workops.deploy.ValuesVersionRewriter
import bosca.workops.deploy.RollbackRequest
import bosca.workops.model.environment.DeployInput
import bosca.workops.service.ArtifactPublicationService
import bosca.workops.service.EnvironmentService
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/**
 * the Helm/K8s [DeployTarget] adapter. Reads the environment's `values.yaml`
 * (inline or from git, the file edited by hand today), runs a real `helm upgrade`/`helm rollback`
 * through the [KubernetesControllerClient] (which speaks HTTP to the standalone `kubernetes-controller`
 * that owns the cluster and shells out to `helm`), and records the `EnvironmentDeployment` lifecycle
 * (PENDING → DEPLOYED / FAILED / ROLLED_BACK) via [EnvironmentService]. Registered under
 * `DeployTargetKind.HELM.name` so the deploy nodes dispatch to it by kind.
 */
class HelmDeployTarget(
    private val controller: KubernetesControllerClient,
    private val environmentService: EnvironmentService,
    private val repositoryWrite: RepositoryWriteService,
    private val artifactPublications: ArtifactPublicationService,
    private val json: Json,
) : DeployTarget {

    override val kind: DeployTargetKind = DeployTargetKind.HELM

    override fun validateConfig(config: kotlinx.serialization.json.JsonElement): List<String> {
        val cfg = try {
            json.decodeFromJsonElement(HelmTargetConfig.serializer(), config)
        } catch (e: Exception) {
            return listOf("helm config does not decode: ${e.message}")
        }
        return buildList {
            if (cfg.clusterId.isBlank()) add("helm config requires clusterId")
            if (cfg.releaseName.isBlank()) add("helm config requires releaseName")
            if (cfg.namespace.isBlank()) add("helm config requires namespace")
            if (cfg.repo.isBlank()) add("helm config requires repo")
            if (cfg.chart.isBlank()) add("helm config requires chart")
            if (cfg.chartVersion.isBlank()) add("helm config requires chartVersion")
        }
    }

    override suspend fun deploy(request: DeployRequest, authentication: AuthenticationContext): DeployOutcome {
        val cfg = json.decodeFromJsonElement(HelmTargetConfig.serializer(), request.config)
        // What versionPaths pin is THE ARTIFACT, by reference: the deployed publication's coordinate
        // tag (docker `name:tag`) — the image the build declared is exactly what the cluster pulls.
        // Only without a publication does the version name stand in.
        val deployTag = request.artifactPublicationId
            ?.let { artifactPublications.getById(it) }
            ?.coordinates?.substringAfterLast(':')?.takeIf { it.isNotBlank() }
            ?: request.version
        val values = resolveValues(cfg, deployTag, request.configRepositoryId)

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
            // The upgrade failed — record FAILED (best-effort) and surface the original error.
            environmentService.markFailed(deployment.id, deployment.version)
            throw e
        }
    }

    override suspend fun rollback(request: RollbackRequest, authentication: AuthenticationContext): DeployOutcome {
        val cfg = json.decodeFromJsonElement(HelmTargetConfig.serializer(), request.config)
        // The deployment to mark ROLLED_BACK is the environment's current one for this project.
        val current = environmentService.currentState(request.environmentId)
            .firstOrNull {
                it.projectId == request.projectId &&
                    it.targetKind == kind
            }
            ?: error(
                "Helm rollback: no current deployment for target '$kind' and project " +
                    "${request.projectId} in environment ${request.environmentId}"
            )
        // A git-pinned values rollback can only map Helm's "previous revision" convention to the
        // previous durable WorkOps deployment. An arbitrary native revision has no recorded
        // artifact-to-revision mapping, so guessing the previous tag would make Git lie.
        if (cfg.versionPaths.isNotEmpty() && cfg.values.isBlank()) {
            require(request.toRevision == 0) {
                "Helm rollback: explicit revision ${request.toRevision} cannot be reconciled with " +
                    "versionPaths; use toRevision 0 (previous)"
            }
            val previousDeploymentId = current.previousDeploymentId
                ?: error("Helm rollback: current deployment has no previous deployment to restore")
            val previous = environmentService.getDeployment(previousDeploymentId)
                ?: error("Helm rollback: previous deployment $previousDeploymentId no longer exists")
            val previousPublicationId = previous.artifactPublicationId
                ?: error("Helm rollback: previous deployment $previousDeploymentId has no artifact publication")
            val previousPublication = artifactPublications.getById(previousPublicationId)
                ?: error("Helm rollback: previous artifact publication $previousPublicationId has no usable tag")
            val revertTag = previousPublication.coordinates.substringAfterLast(':')
            if (revertTag.isBlank()) {
                error("Helm rollback: previous artifact publication $previousPublicationId has no usable tag")
            }
            commitVersionPaths(
                cfg, request.configRepositoryId, revertTag,
                "Revert ${cfg.versionPaths.joinToString(", ")} to $revertTag for " +
                    "${cfg.releaseName} (${cfg.namespace})",
            )
        }

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
     * The values to feed `helm upgrade`: inline [HelmTargetConfig.values] if set, else the values file
     * read from git — [HelmTargetConfig.valuesRepositoryId] when declared, defaulting to
     * [configRepositoryId], the repository the deploy.yaml itself came from (so the file needs no
     * repository UUIDs; a values file beside the config just works). Null when no source at all. When
     * [HelmTargetConfig.versionPaths] are declared, the configured keys (e.g. `image.tag`) are set to
     * the deploying [version] first — and for git-sourced values the bump is COMMITTED back to the
     * branch before the upgrade, so the file in git always reflects what's deployed (Flux-style).
     */
    private suspend fun resolveValues(cfg: HelmTargetConfig, version: String, configRepositoryId: UUID?): String? {
        if (cfg.values.isNotBlank()) {
            return if (cfg.versionPaths.isEmpty()) cfg.values
            else ValuesVersionRewriter.rewrite(cfg.values, cfg.versionPaths, version)
        }
        val explicit = cfg.valuesRepositoryId.isNotBlank()
        val repositoryId = if (explicit) UUID.parse(cfg.valuesRepositoryId) else configRepositoryId ?: return null
        val original = repositoryWrite.readFile(repositoryId, cfg.valuesRef, cfg.valuesPath)
        if (original == null) {
            // An EXPLICIT declaration (a named repo, or versionPaths promising a pin) that can't be
            // read must fail; a values.yaml merely absent from the config's own repo means no values.
            if (explicit || cfg.versionPaths.isNotEmpty()) {
                error("Helm deploy: could not read values '${cfg.valuesPath}' on '${cfg.valuesRef}' in $repositoryId")
            }
            return null
        }
        if (cfg.versionPaths.isEmpty()) return original
        val rewritten = ValuesVersionRewriter.rewrite(original, cfg.versionPaths, version)
        if (rewritten != original) {
            commitValues(
                cfg, repositoryId, rewritten,
                "Set ${cfg.versionPaths.joinToString(", ")} to $version for ${cfg.releaseName} (${cfg.namespace})",
            )
        }
        return rewritten
    }

    /**
     * Rewrites the git-resident values file's versionPaths to [version] and commits it — the shared
     * path behind the deploy-time bump and the rollback-time revert, so both move the file the same
     * way. A no-op when the file already carries [version].
     */
    private suspend fun commitVersionPaths(
        cfg: HelmTargetConfig,
        configRepositoryId: UUID?,
        version: String,
        message: String,
    ) {
        val explicit = cfg.valuesRepositoryId.isNotBlank()
        val repositoryId = if (explicit) UUID.parse(cfg.valuesRepositoryId) else configRepositoryId
            ?: error("Helm rollback: versionPaths require a values repository")
        val original = repositoryWrite.readFile(repositoryId, cfg.valuesRef, cfg.valuesPath)
            ?: error("Helm rollback: could not read values '${cfg.valuesPath}' on '${cfg.valuesRef}' in $repositoryId")
        val rewritten = ValuesVersionRewriter.rewrite(original, cfg.versionPaths, version)
        if (rewritten != original) {
            commitValues(cfg, repositoryId, rewritten, message)
        }
    }

    private suspend fun commitValues(cfg: HelmTargetConfig, repositoryId: UUID, content: String, message: String) {
        val branch = branchOf(cfg.valuesRef)
            ?: error(
                "Helm deploy: versionPaths write the version bump back to git, which needs a branch " +
                    "valuesRef — '${cfg.valuesRef}' is not one",
            )
        repositoryWrite.commitFile(
            CommitFileInput(
                repositoryId = repositoryId,
                branch = branch,
                path = cfg.valuesPath,
                content = content,
                message = message,
                authorName = AUTHOR_NAME,
                authorEmail = AUTHOR_EMAIL,
            ),
        )
    }

    /** The branch name of a heads ref (or bare branch name), or null for tags/SHAs. */
    private fun branchOf(ref: String): String? = when {
        ref.startsWith("refs/heads/") -> ref.removePrefix("refs/heads/")
        ref.startsWith("refs/") -> null
        ref.matches(Regex("[0-9a-f]{40}")) -> null
        else -> ref
    }

    companion object {
        /** The bot identity version-bump commits are attributed to (the deploy, not a person, moved the tag). */
        private const val AUTHOR_NAME = "Bosca Release"
        private const val AUTHOR_EMAIL = "releases@bosca.io"

        private val log = org.slf4j.LoggerFactory.getLogger(HelmDeployTarget::class.java)
    }
}
