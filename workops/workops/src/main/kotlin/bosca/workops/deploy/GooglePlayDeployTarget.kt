package bosca.workops.deploy

import bosca.artifacts.model.ArtifactType as RegistryArtifactType
import bosca.artifacts.service.ArtifactRepositoryService
import bosca.artifacts.service.BlobStorageService
import bosca.pipelines.service.PipelineSecretService
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.store.pipelines.PlayPublisher
import bosca.store.pipelines.playUserFraction
import bosca.workops.model.environment.DeployInput
import bosca.workops.service.EnvironmentService
import bosca.workops.service.ReleaseNotesService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * WorkOps' Google Play deployment lifecycle. Vendor calls are deliberately delegated to the same
 * [PlayPublisher] used by store-pipeline nodes; this class only resolves WorkOps artifacts, secrets,
 * durable build identity, and EnvironmentDeployment state.
 */
class GooglePlayDeployTarget(
    private val environmentService: EnvironmentService,
    private val secrets: PipelineSecretService,
    private val artifacts: ArtifactRepositoryService,
    private val blobs: BlobStorageService,
    private val publisher: PlayPublisher,
    private val releaseNotes: ReleaseNotesService,
) : DeployTarget {

    private val json = Json { ignoreUnknownKeys = true }
    override val kind: DeployTargetKind = DeployTargetKind.GOOGLE_PLAY

    override fun validateConfig(config: kotlinx.serialization.json.JsonElement): List<String> {
        val cfg = try {
            json.decodeFromJsonElement(GooglePlayTargetConfig.serializer(), config)
        } catch (e: Exception) {
            return listOf("google_play config does not decode: ${e.message}")
        }
        return buildList {
            if (cfg.packageName.isBlank()) add("google_play config requires packageName")
            if (cfg.buildNumberKey.isBlank()) add("google_play config requires a non-blank buildNumberKey")
            if (cfg.serviceAccountSecret.isBlank()) add("google_play config requires serviceAccountSecret")
            if (!cfg.rolloutPercentage.isFinite() || cfg.rolloutPercentage !in 0.0..100.0) {
                add("google_play config rolloutPercentage must be between 0 and 100")
            }
            if (cfg.releaseNotesLocales.isEmpty()) add("google_play config requires releaseNotesLocales")
            if (cfg.releaseNotesLocales.any { it.isBlank() }) add("google_play config releaseNotesLocales cannot contain blanks")
        }
    }

    override suspend fun deploy(request: DeployRequest, authentication: AuthenticationContext): DeployOutcome {
        val cfg = requireConfig(request.config)
        val artifact = requireArtifact(request.artifact, "deploy")
        val buildNumber = requireBuildNumber(request.buildNumber, "deploy")
        val localizedNotes = releaseNotes.requireLocalized(request.versionId, cfg.releaseNotesLocales)
        check(buildNumber.value == buildNumber.number.toString()) {
            "Android build-number allocation ${buildNumber.allocationId} has invalid value '${buildNumber.value}'"
        }
        val deployment = environmentService.createDeployment(
            DeployInput(
                environmentId = request.environmentId,
                projectId = request.projectId,
                targetKind = kind,
                versionId = request.versionId,
                releaseId = request.releaseId,
                artifactPublicationId = artifact.publicationId,
                appBuildNumberAllocationId = buildNumber.allocationId,
                healthCheckUrl = request.healthCheckUrl,
            ),
            request.deployedByPrincipalId,
        )
        var bundle: File? = null
        try {
            val credential = requireSecret(cfg.serviceAccountSecret, "Play service account")
            bundle = fetchBundle(artifact)
            val result = publisher.deployBundleWithReleaseNotes(
                credential,
                cfg.packageName,
                bundle.absolutePath,
                cfg.track,
                playUserFraction(cfg.rolloutPercentage),
                buildNumber.number,
                localizedNotes.associate { it.locale to it.playReleaseNotes },
            )
            val deployed = environmentService.markDeployed(
                deployment.id, request.deployedByPrincipalId, deployment.version,
            )
            return DeployOutcome(
                deployed.id,
                "${cfg.packageName}@${result.versionCode} → ${result.track} (${displayPercentage(cfg.rolloutPercentage)}%)",
                deployed.status,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            environmentService.markFailed(deployment.id, deployment.version)
            throw e
        } finally {
            bundle?.delete()
        }
    }

    override suspend fun rollback(request: RollbackRequest, authentication: AuthenticationContext): DeployOutcome {
        val cfg = requireConfig(request.config)
        requireArtifact(request.artifact, "rollback")
        val buildNumber = requireBuildNumber(request.buildNumber, "rollback")
        val current = currentDeployment(request.environmentId, request.projectId, "rollback")
        val credential = requireSecret(cfg.serviceAccountSecret, "Play service account")
        publisher.halt(credential, cfg.packageName, cfg.track, buildNumber.number)
        val rolledBack = environmentService.markRolledBack(current.id, current.version)
        return DeployOutcome(rolledBack.id, "${cfg.packageName}:${cfg.track} halted", rolledBack.status)
    }

    override suspend fun rollout(request: RolloutRequest, authentication: AuthenticationContext): DeployOutcome {
        val fraction = playUserFraction(request.rolloutPercentage)
        val cfg = requireConfig(request.config)
        requireArtifact(request.artifact, "rollout")
        val buildNumber = requireBuildNumber(request.buildNumber, "rollout")
        val current = currentDeployment(request.environmentId, request.projectId, "rollout")
        val credential = requireSecret(cfg.serviceAccountSecret, "Play service account")
        val result = publisher.setRollout(
            credential, cfg.packageName, cfg.track, fraction, buildNumber.number,
        )
        return DeployOutcome(
            current.id,
            "${cfg.packageName}:${cfg.track}@${result.versionCode} (${displayPercentage(request.rolloutPercentage)}%)",
            current.status,
        )
    }

    private fun requireConfig(element: kotlinx.serialization.json.JsonElement): GooglePlayTargetConfig {
        val cfg = json.decodeFromJsonElement(GooglePlayTargetConfig.serializer(), element)
        require(cfg.packageName.isNotBlank()) { "google_play config requires packageName" }
        require(cfg.serviceAccountSecret.isNotBlank()) { "google_play config requires serviceAccountSecret" }
        require(cfg.releaseNotesLocales.isNotEmpty() && cfg.releaseNotesLocales.none { it.isBlank() }) {
            "google_play config requires non-blank releaseNotesLocales"
        }
        playUserFraction(cfg.rolloutPercentage)
        return cfg
    }

    private suspend fun requireSecret(name: String, description: String): String = secrets.resolve(name)
        ?: error("pipeline secret '$name' (the $description) is not set")

    private suspend fun currentDeployment(environmentId: UUID, projectId: UUID, operation: String) =
        environmentService.currentState(environmentId).firstOrNull {
            it.projectId == projectId && it.targetKind == kind
        } ?: error("Google Play $operation: no current '$kind' deployment for project $projectId in environment $environmentId")

    private suspend fun fetchBundle(artifact: DeployArtifact): File {
        val namespace = artifact.namespace ?: error("Google Play artifact requires namespace")
        val name = artifact.coordinate.substringBeforeLast(':')
        val version = artifact.coordinate.substringAfterLast(':')
        require(name != artifact.coordinate && name.isNotBlank() && version.isNotBlank()) {
            "Google Play artifact coordinate '${artifact.coordinate}' must be name:version"
        }
        val repository = artifacts.findRepository(namespace, name, RegistryArtifactType.RAW)
            ?: error("artifact repository '$namespace/$name' not found in the registry")
        val artifactVersion = artifacts.findVersion(repository.id, version)
            ?: error("artifact version '$version' not published to '$namespace/$name'")
        val blob = artifacts.getVersionBlobs(artifactVersion.id)
            .firstOrNull { it.filename?.endsWith(".aab") == true }
            ?: error("artifact '$name@$version' has no .aab blob to upload")
        return withContext(Dispatchers.IO) {
            File.createTempFile("bosca-play-", ".aab").also { output ->
                blobs.getInputStream(blob.digest).use { input -> output.outputStream().use(input::copyTo) }
            }
        }
    }

    private fun requireArtifact(artifact: DeployArtifact?, operation: String): DeployArtifact {
        val selected = artifact ?: error("Google Play $operation requires a selected artifact")
        require(!selected.namespace.isNullOrBlank()) { "Google Play $operation artifact requires namespace" }
        return selected
    }

    private fun requireBuildNumber(buildNumber: DeployBuildNumber?, operation: String): DeployBuildNumber {
        val selected = buildNumber ?: error("Google Play $operation requires a durable app build number")
        require(selected.number in 1..2_100_000_000L) {
            "Google Play $operation versionCode must be between 1 and 2100000000"
        }
        return selected
    }

    private fun displayPercentage(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
}

@Serializable
data class GooglePlayTargetConfig(
    val packageName: String,
    val buildNumberKey: String = "default",
    val track: String = "internal",
    val serviceAccountSecret: String = "",
    val rolloutPercentage: Double = 100.0,
    val releaseNotesLocales: List<String> = listOf("en-US"),
)
