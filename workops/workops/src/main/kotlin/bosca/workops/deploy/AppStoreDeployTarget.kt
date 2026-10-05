package bosca.workops.deploy

import bosca.pipelines.service.PipelineSecretService
import bosca.security.service.AuthenticationContext
import bosca.store.pipelines.AppStorePublisher
import bosca.workops.model.environment.DeployInput
import bosca.workops.service.EnvironmentService
import bosca.workops.service.ReleaseNotesService
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * WorkOps' App Store deployment lifecycle. All App Store Connect behavior is delegated to the same
 * [AppStorePublisher] used by store-pipeline nodes; this adapter owns only WorkOps config, durable
 * build identity, secret resolution, and EnvironmentDeployment state.
 */
class AppStoreDeployTarget(
    private val environmentService: EnvironmentService,
    private val secrets: PipelineSecretService,
    private val publisher: AppStorePublisher,
    private val releaseNotes: ReleaseNotesService,
) : DeployTarget {

    private val json = Json { ignoreUnknownKeys = true }
    override val kind: DeployTargetKind = DeployTargetKind.APP_STORE

    override fun validateConfig(config: kotlinx.serialization.json.JsonElement): List<String> {
        val cfg = try {
            json.decodeFromJsonElement(AppStoreTargetConfig.serializer(), config)
        } catch (e: Exception) {
            return listOf("app_store config does not decode: ${e.message}")
        }
        return buildList {
            if (cfg.bundleId.isBlank()) add("app_store config requires bundleId")
            if (cfg.buildNumberKey.isBlank()) add("app_store config requires a non-blank buildNumberKey")
            if (cfg.ascKeySecret.isBlank()) add("app_store config requires ascKeySecret")
            if (cfg.betaGroups().isEmpty() && !cfg.phasedRelease) {
                add("app_store config requires testflightGroups or phasedRelease=true")
            }
            if (cfg.testflightGroups.any { it.isBlank() }) {
                add("app_store config testflightGroups cannot contain blank names")
            }
            if (cfg.releaseNotesLocales.isEmpty()) add("app_store config requires releaseNotesLocales")
            if (cfg.releaseNotesLocales.any { it.isBlank() }) add("app_store config releaseNotesLocales cannot contain blanks")
        }
    }

    override suspend fun deploy(request: DeployRequest, authentication: AuthenticationContext): DeployOutcome {
        val cfg = requireConfig(request.config)
        val buildNumber = request.buildNumber ?: error("app_store deploy requires a durable app build number")
        val localizedNotes = releaseNotes.requireLocalized(request.versionId, cfg.releaseNotesLocales)
        val deployment = environmentService.createDeployment(
            DeployInput(
                environmentId = request.environmentId,
                projectId = request.projectId,
                targetKind = kind,
                versionId = request.versionId,
                releaseId = request.releaseId,
                artifactPublicationId = request.artifactPublicationId,
                appBuildNumberAllocationId = buildNumber.allocationId,
                healthCheckUrl = request.healthCheckUrl,
            ),
            request.deployedByPrincipalId,
        )
        try {
            val credential = requireSecret(cfg)
            val results = buildList {
                val groups = cfg.betaGroups()
                if (groups.isNotEmpty()) {
                    add(
                        publisher.assignBetaGroupsWithReleaseNotes(
                            credential, cfg.bundleId, request.version, buildNumber.value, groups,
                            localizedNotes.associate { it.locale to it.testFlightWhatToTest },
                        ),
                    )
                }
                if (cfg.phasedRelease) {
                    add(
                        publisher.submitForReviewWithReleaseNotes(
                            credential, cfg.bundleId, request.version, buildNumber.value, phasedRelease = true,
                            whatsNew = localizedNotes.associate { it.locale to it.appStoreWhatsNew },
                        ),
                    )
                }
            }
            val deployed = environmentService.markDeployed(
                deployment.id, request.deployedByPrincipalId, deployment.version,
            )
            return DeployOutcome(
                deployed.id,
                "${cfg.bundleId}@${request.version} (${buildNumber.value}) → ${results.joinToString(" + ") { it.state }}",
                deployed.status,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            environmentService.markFailed(deployment.id, deployment.version)
            throw e
        }
    }

    override suspend fun rollback(request: RollbackRequest, authentication: AuthenticationContext): DeployOutcome {
        val cfg = requireConfig(request.config)
        val buildNumber = request.buildNumber ?: error("app_store rollback requires a durable app build number")
        val current = environmentService.currentState(request.environmentId).firstOrNull {
            it.projectId == request.projectId && it.targetKind == kind
        } ?: error(
            "App Store rollback: no current '$kind' deployment for project ${request.projectId} " +
                "in environment ${request.environmentId}",
        )
        val credential = requireSecret(cfg)
        val actions = buildList {
            val groups = cfg.betaGroups()
            if (groups.isNotEmpty()) {
                publisher.removeBetaGroups(
                    credential, cfg.bundleId, request.version, buildNumber.value, groups,
                )
                add("TestFlight groups removed")
            }
            if (cfg.phasedRelease) {
                publisher.haltPhasedRelease(credential, cfg.bundleId, request.version)
                add("phased release paused")
            }
        }
        val rolledBack = environmentService.markRolledBack(current.id, current.version)
        return DeployOutcome(rolledBack.id, "${cfg.bundleId} ${actions.joinToString(" + ")}", rolledBack.status)
    }

    private fun requireConfig(element: kotlinx.serialization.json.JsonElement): AppStoreTargetConfig {
        val cfg = json.decodeFromJsonElement(AppStoreTargetConfig.serializer(), element)
        require(cfg.bundleId.isNotBlank()) { "app_store config requires bundleId" }
        require(cfg.ascKeySecret.isNotBlank()) { "app_store config requires ascKeySecret" }
        require(cfg.betaGroups().isNotEmpty() || cfg.phasedRelease) {
            "app_store config requires testflightGroups or phasedRelease=true"
        }
        require(cfg.testflightGroups.none { it.isBlank() }) {
            "app_store config testflightGroups cannot contain blank names"
        }
        require(cfg.releaseNotesLocales.isNotEmpty() && cfg.releaseNotesLocales.none { it.isBlank() }) {
            "app_store config requires non-blank releaseNotesLocales"
        }
        return cfg
    }

    private suspend fun requireSecret(cfg: AppStoreTargetConfig): String = secrets.resolve(cfg.ascKeySecret)
        ?: error("pipeline secret '${cfg.ascKeySecret}' (the App Store Connect API key) is not set")
}

@Serializable
data class AppStoreTargetConfig(
    val bundleId: String,
    val buildNumberKey: String = "default",
    /** Canonical multi-group setting. */
    val testflightGroups: List<String> = emptyList(),
    /** Backward-compatible singular setting; new deploy.yaml files use [testflightGroups]. */
    val testflightGroup: String = "",
    /** Full App Store review with phased automatic release. */
    val phasedRelease: Boolean = false,
    val ascKeySecret: String = "",
    val releaseNotesLocales: List<String> = listOf("en-US"),
) {
    fun betaGroups(): List<String> = (testflightGroups + testflightGroup.takeIf { it.isNotBlank() })
        .filterNotNull().distinct()
}
