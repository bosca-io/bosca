package bosca.workops.service

import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.service.Service
import bosca.workops.model.release.Release
import bosca.workops.model.release.ReleasePipelinePlan
import bosca.workops.model.release.ReleaseRunView

/**
 * Starts and promotes releases through repository-owned git-ci pipeline YAML. WorkOps remains the
 * release record; git-ci owns execution, durability, approvals, retries, rollback, and run status.
 */
interface ReleasePipelineService : Service {

    /**
     * Creates a RELEASE run for every release-enabled pipeline attached to a bundled project. Each
     * run receives `release.id`, `release.version`, and the comma-separated `release.projects` scope.
     */
    suspend fun launch(releaseId: UUID, authentication: AuthenticationContext): Boolean =
        launch(releaseId, emptyMap(), authentication)

    /**
     * Starts the release with values for the RELEASE triggers' declared [inputs]. Keys omit the
     * `inputs.` prefix; each pipeline receives only the values it declares.
     */
    suspend fun launch(
        releaseId: UUID,
        inputs: Map<String, String>,
        authentication: AuthenticationContext,
    ): Boolean

    /**
     * Creates PROMOTION runs for [environment]. Git-ci validates the declared promotion chain and
     * downgrade guard before creating any run. [inputs] are trigger inputs without the `inputs.` prefix.
     */
    suspend fun promote(
        releaseId: UUID,
        environment: String,
        allowDowngrade: Boolean,
        inputs: Map<String, String>,
        authentication: AuthenticationContext,
    ): Boolean

    /**
     * Creates a roll-forward patch release containing only [projectIds]. Every selected source version
     * is bumped by one patch component and bundled into the new release.
     */
    suspend fun startPatchRelease(
        releaseId: UUID,
        projectIds: List<UUID>,
        authentication: AuthenticationContext,
    ): Release

    /**
     * Rolls a release ATTEMPT back so the same versions can be released again: deletes the git tags
     * the relay created, deletes the versions' artifact publications, and resets the bundle's
     * deployment statuses. Refused while a git-ci release run is live or after the release was marked released.
     * Store uploads (Google Play / App Store) are NOT withdrawn — Play bundles are immutable once
     * uploaded, and TestFlight expiry is not attempted here.
     */
    suspend fun rollbackArtifacts(releaseId: UUID, authentication: AuthenticationContext): Boolean

    /**
     * Rolls back every current deployment in [environment] that belongs to [releaseId], through the
     * same target adapters and environment authorization used by `uses: rollback`. [toRevision]
     * is target-native; zero means the previous revision where the adapter supports it.
     */
    suspend fun rollbackEnvironment(
        releaseId: UUID,
        environment: String,
        toRevision: Int,
        authentication: AuthenticationContext,
    ): List<String>

    /**
     * The launch-blocking dependency violations for the release RIGHT NOW (empty = ready): every
     * bundled project's BUILD dependencies must be satisfied by a provider in the release or an
     * already-released version matching the constraint. The same list [launch] refuses on — exposed
     * separately so the UI can show the problem before Start is pressed.
     */
    suspend fun dependencyViolations(releaseId: UUID): List<String>

    /**
     * The artifact types the release's projects DECLARE in their CI pipelines (union, as workops
     * [bosca.workops.model.artifact.ArtifactType] names). Relay channels select publications by type;
     * a channel whose type no project declares will never fire — UIs use this to hide those channels'
     * plan steps instead of showing gates that can never arrive.
     */
    suspend fun releaseChannelArtifactTypes(releaseId: UUID): List<String>

    /**
     * Every artifact the release's projects DECLARE in CI, with coordinates resolved against each
     * project's release version — the "what this release will publish" view, available before any
     * build runs. Registered publications supersede these rows as the relay progresses.
     */
    suspend fun releaseDeclaredArtifacts(releaseId: UUID): List<bosca.workops.model.artifact.ReleaseDeclaredArtifact>

    /**
     * The git-ci release and promotion runs for THIS release specifically (newest first, up to [limit]),
     * correlated by their persisted `release.id` trigger parameter.
     */
    suspend fun runsForRelease(releaseId: UUID, limit: Int): List<ReleaseRunView>

    /**
     * Every Git repository attached to a project version bundled in [releaseId], independent of
     * whether its current pipeline YAML exposes a release trigger. Mutation authorization uses
     * this complete set before reading or executing repository-owned pipeline definitions.
     */
    suspend fun repositoryIdsForRelease(releaseId: UUID): List<UUID>

    /**
     * The exact dependency-ordered RELEASE and PROMOTION job plans derived from every attached
     * repository's YAML through git-ci's own selection logic. No run rows are created.
     */
    suspend fun plansForRelease(releaseId: UUID): List<ReleasePipelinePlan>
}
