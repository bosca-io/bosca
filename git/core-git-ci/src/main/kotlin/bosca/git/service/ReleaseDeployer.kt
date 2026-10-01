package bosca.git.service

import bosca.serialization.UUID
import bosca.service.Service
import java.time.Duration

/**
 * Executes the `uses: deploy` action. Deploy mechanics — reading the target
 * repository's `.bosca/deploy.yaml`, selecting the target entry, resolving the artifact selector,
 * checking the initiating principal's environment permission, invoking the DeployTarget adapter,
 * and recording the EnvironmentDeployment — live in another module, so this is an SPI.
 *
 * Fail-closed by contract: implementations throw on every unresolvable input — an undeclared
 * environment, an ambiguous or unknown target, a selector matching no declared artifact (named in
 * the error), a missing workops version, or a denied environment permission — and the call site
 * treats a MISSING implementation the same way. Nothing deploys on faith.
 */
interface ReleaseDeployer : Service {

    /**
     * Allocates the mobile-store build identity before compiling the binary. Implementations must
     * be durable, monotonic per platform/application, and idempotent for the request's source.
     */
    suspend fun allocateBuildNumber(request: ReleaseBuildNumberRequest): ReleaseBuildNumberOutcome

    suspend fun deploy(request: ReleaseDeployRequest): ReleaseDeployOutcome

    /**
     * The `uses: rollback` action — target-honest: helm restores the revision AND reverts the
     * ops-repo values; environment permission evaluated against the initiating principal.
     */
    suspend fun rollback(request: ReleaseRollbackRequest): ReleaseDeployOutcome

    /**
     * The `uses: play-rollout` action — changes the current Google Play staged rollout percentage
     * without re-uploading its bundle. Environment permission is evaluated against the initiating
     * principal exactly like deploy and rollback.
     */
    suspend fun playRollout(request: ReleasePlayRolloutRequest): ReleaseDeployOutcome

    /** Reads the selected App Store build's beta or full-review state. */
    suspend fun appStoreReview(request: ReleaseAppStoreReviewRequest): ReleaseAppStoreReviewOutcome

    /** Performs one release-gate observation of the selected store target's vitals. */
    suspend fun storeHealth(request: ReleaseStoreHealthRequest): ReleaseStoreHealthOutcome

    /**
     * The `uses: verify-deployment` gate's single observation: the current deployment's health for
     * [environmentKey] in the project owning [targetRepositoryId] — probed once through the same
     * path the wait-healthy job uses. Returns the HealthCheckStatus name, or `NONE` when nothing is
     * deployed there. The agent polls this until healthy or its timeout. Environment permission is
     * evaluated against [initiatorPrincipalId], exactly like deploy and rollback.
     */
    suspend fun deploymentHealth(
        targetRepositoryId: UUID,
        environmentKey: String,
        initiatorPrincipalId: UUID,
    ): String

    /**
     * The `uses: mark-released` action: stamps the workops release released — idempotent, so a
     * re-run job over an already-released release is a no-op. Requires program MANAGE, evaluated
     * against the initiating principal.
     */
    suspend fun markReleased(releaseId: UUID, initiatorPrincipalId: UUID)

    /**
     * The `uses: generate-release-notes` action: reads each bundled version's tagged Git range,
     * drafts localized store notes through Kit, and stores editable Version-owned drafts in WorkOps.
     */
    suspend fun generateReleaseNotes(releaseId: UUID, initiatorPrincipalId: UUID)
}

data class ReleaseBuildNumberRequest(
    val repositoryId: UUID,
    val pipelineRunId: UUID,
    val sourceCommitSha: String,
    val sourceVersion: String,
    /** `android` or `ios`; kept as a string so core-git-ci does not depend on WorkOps models. */
    val platform: String,
    val applicationId: String,
    val buildKey: String = "default",
    /** Optional platform-formatted first value, used when adopting an app with existing uploads. */
    val minimum: String? = null,
)

data class ReleaseBuildNumberOutcome(
    /** Durable sequence number. Android embeds this value directly. */
    val number: Long,
    /** Platform-formatted value to embed in the app binary. */
    val value: String,
    /** True when this source identity already owned the returned allocation. */
    val reused: Boolean,
)

data class ReleaseRollbackRequest(
    /** The repository whose `.bosca/deploy.yaml` governs this rollback. */
    val targetRepositoryId: UUID,
    /** The run's ref — deploy.yaml is read here first, then the version tags, then the default branch. */
    val ref: String,
    /** The environment KEY being rolled back. */
    val environmentKey: String,
    /** The named target within the environment's `targets:` list; null when the entry has one. */
    val target: String?,
    /** The target-native revision to restore; 0 means "the previous revision" (helm's own convention). */
    val toRevision: Int,
    /** Step-level `with:` config-key overrides. */
    val overrides: Map<String, String>,
    /** The run's effective trigger parameters. */
    val parameters: Map<String, String>,
    /** The run's initiating principal — the subject of every permission evaluation. */
    val initiatorPrincipalId: UUID,
)

data class ReleasePlayRolloutRequest(
    /** The repository whose `.bosca/deploy.yaml` declares the Google Play target. */
    val targetRepositoryId: UUID,
    /** The run ref used to resolve the versioned deploy config. */
    val ref: String,
    /** The environment KEY containing the Google Play target. */
    val environmentKey: String,
    /** The named target within a multi-target environment; null when unambiguous. */
    val target: String?,
    /** Store-neutral percentage from 0 through 100. */
    val rolloutPercentage: Double,
    /** The run's effective trigger parameters. */
    val parameters: Map<String, String>,
    /** The run's initiating principal — the subject of every permission evaluation. */
    val initiatorPrincipalId: UUID,
)

@kotlinx.serialization.Serializable
enum class ReleaseAppStoreReviewMode { BETA, APP_STORE }

data class ReleaseAppStoreReviewRequest(
    val targetRepositoryId: UUID,
    val ref: String,
    val environmentKey: String,
    val target: String?,
    val mode: ReleaseAppStoreReviewMode,
    val parameters: Map<String, String>,
    val initiatorPrincipalId: UUID,
)

data class ReleaseAppStoreReviewOutcome(
    val state: String,
    val complete: Boolean,
    val approved: Boolean,
)

data class ReleaseStoreHealthRequest(
    val targetRepositoryId: UUID,
    val ref: String,
    val environmentKey: String,
    val target: String?,
    /** Maximum acceptable crash rate, in the same percentage unit returned by Play. */
    val maxCrashRate: Double,
    val window: Duration,
    val parameters: Map<String, String>,
    val initiatorPrincipalId: UUID,
)

data class ReleaseStoreHealthOutcome(
    val crashRate: Double,
    val maxCrashRate: Double,
    val windowSeconds: Long,
    val healthy: Boolean,
)

data class ReleaseDeployRequest(
    /** The repository whose `.bosca/deploy.yaml` governs this deploy (the deployed project's repo). */
    val targetRepositoryId: UUID,
    /** The run's ref — deploy.yaml is read here first (config versions with the release), then at
     *  the version tag and the default branch. */
    val ref: String,
    /** The environment KEY being deployed to. */
    val environmentKey: String,
    /** The named target within the environment's `targets:` list; null when the entry has one. */
    val target: String?,
    /** Step-level `with:` config-key overrides — highest precedence over the deploy.yaml entry. */
    val overrides: Map<String, String>,
    /** The run's effective trigger parameters (`release.id`, `release.version`, `inputs.*`). */
    val parameters: Map<String, String>,
    /** The run's initiating principal — the subject of every permission evaluation. */
    val initiatorPrincipalId: UUID,
)

data class ReleaseDeployOutcome(
    /** Target-native reference for humans (e.g. `release@revision`, a Play track). */
    val reference: String,
    /** The recorded deployment status (EnvironmentDeploymentStatus name). */
    val status: String,
)
