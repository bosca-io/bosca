package bosca.workops.deploy

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.service.Service
import bosca.workops.model.environment.EnvironmentDeploymentStatus
import kotlinx.serialization.json.JsonElement

/**
 * Deploy target kinds the release engine dispatches to. Each kind is served by a
 * [DeployTarget] adapter registered under this name, so a new target (Google Play, App Store) slots in
 * as another adapter without touching the deploy node/executor.
 */
@DbMapper(DeployTargetKindMapper::class)
@kotlinx.serialization.Serializable
enum class DeployTargetKind { HELM, HELM_VALUES, GOOGLE_PLAY, APP_STORE }

object DeployTargetKindMapper : EnumMapper<DeployTargetKind>({ DeployTargetKind.valueOf(it.uppercase()) })

/**
 * The exact published artifact selected for a deploy. Store-native build identifiers belong here,
 * beside the registry coordinates they identify, rather than in target connection/configuration.
 */
data class DeployArtifact(
    val publicationId: UUID,
    val type: String?,
    val namespace: String?,
    val coordinate: String,
)

/** The durable mobile build identity selected for a deployment. */
data class DeployBuildNumber(
    val allocationId: UUID,
    val number: Long,
    val value: String,
)

/**
 * A request handed to a [DeployTarget] adapter. The ids place the deploy on an [environment][environmentId]
 * for a project version; [version] is the release/chart version to roll out; [config] is the
 * target-specific settings (e.g. the Helm cluster/chart/values source) the adapter decodes itself.
 */
data class DeployRequest(
    val environmentId: UUID,
    val projectId: UUID,
    val versionId: UUID,
    val version: String,
    val config: JsonElement,
    val deployedByPrincipalId: UUID,
    val releaseId: UUID? = null,
    val artifactPublicationId: UUID? = null,
    val artifact: DeployArtifact? = null,
    val buildNumber: DeployBuildNumber? = null,
    val healthCheckUrl: String? = null,
    /** The git repository the deploy config was read from — repo-relative references (e.g. the Helm
     *  values file) default to it, so the config file never needs to carry repository UUIDs. */
    val configRepositoryId: UUID? = null,
)

/**
 * A request to roll a target back to an earlier revision. [toRevision] is the
 * target-native revision to restore (e.g. a Helm release revision); [config] carries the same
 * target-specific settings as a deploy.
 */
data class RollbackRequest(
    val environmentId: UUID,
    val projectId: UUID,
    val toRevision: Int,
    val config: JsonElement,
    val deployedByPrincipalId: UUID,
    /** Store marketing version selected by the release; blank for targets that do not need it. */
    val version: String = "",
    val artifact: DeployArtifact? = null,
    val buildNumber: DeployBuildNumber? = null,
    /** See [DeployRequest.configRepositoryId]. */
    val configRepositoryId: UUID? = null,
)

/**
 * A staged-rollout adjustment against an already-deployed target. Unlike [DeployRequest], this
 * moves no artifact bytes and creates no new deployment: it changes the delivery state of the
 * current deployment (for example, a Google Play rollout from 10% to 50%).
 */
data class RolloutRequest(
    val environmentId: UUID,
    val projectId: UUID,
    val rolloutPercentage: Double,
    val config: JsonElement,
    val deployedByPrincipalId: UUID,
    val artifact: DeployArtifact? = null,
    val buildNumber: DeployBuildNumber? = null,
    /** See [DeployRequest.configRepositoryId]. */
    val configRepositoryId: UUID? = null,
)

/**
 * The outcome of a deploy or rollback: the recorded `EnvironmentDeployment` [deploymentId], a
 * target-specific [reference] (e.g. `release@revision`) for humans, and the resulting [status].
 */
data class DeployOutcome(
    val deploymentId: UUID,
    val reference: String,
    val status: EnvironmentDeploymentStatus,
)

/**
 * SPI for a deploy target. An adapter owns *how* a version reaches a running
 * environment for its [kind] (Helm upgrade, Play track, App Store submission) and records the
 * resulting `EnvironmentDeployment`. Adapters register as named providers keyed by `kind.name`; the
 * deploy node resolves `provide<DeployTarget>(name = kind.name)`, so adding a target requires no
 * node/executor change.
 */
interface DeployTarget : Service {
    val kind: DeployTargetKind
    suspend fun deploy(request: DeployRequest, authentication: AuthenticationContext): DeployOutcome

    /** Rolls the target back to [RollbackRequest.toRevision] and records the deployment as ROLLED_BACK. */
    suspend fun rollback(request: RollbackRequest, authentication: AuthenticationContext): DeployOutcome

    /**
     * Advances or halts the current target-native staged rollout without moving artifact bytes.
     * Targets that do not have staged delivery semantics fail closed.
     */
    suspend fun rollout(request: RolloutRequest, authentication: AuthenticationContext): DeployOutcome {
        error("Deploy target '$kind' does not support staged rollout")
    }

    /**
     * Whether [config] decodes as this target's settings — push-time deploy.yaml validation:
     * a config the adapter cannot decode surfaces at push, not three steps into a
     * release. Returns human-readable problems; empty means valid. The default accepts anything,
     * for adapters without a strict schema.
     */
    fun validateConfig(config: JsonElement): List<String> = emptyList()
}
