package bosca.git.service

import bosca.git.model.PullRequestEvent
import bosca.git.model.GitHubPullRequestState

import bosca.git.model.GitHubUser
import bosca.git.model.GitHubDelivery
import bosca.git.model.GitHubRepositoryPair
import bosca.git.model.GitHubRepositoryPairInput
import bosca.git.model.GitHubRefState
import bosca.git.model.GitHubRefResolutionInput
import bosca.git.model.GitHubSyncResult
import bosca.git.model.RefUpdateEvent
import bosca.serialization.UUID
import bosca.service.Service

/** Owns repository pairing, user attribution, verified intake and ref synchronization state. */
interface GitHubSyncService : Service {
    /** Imports only signed current PR state whose originating principal retains Bosca write permission and merge protections. */
    suspend fun synchronizePullRequest(delivery: GitHubDelivery): GitHubSyncResult

    /** Exports the current native pull request, independently of the event's older snapshot. */
    suspend fun synchronizePullRequest(event: PullRequestEvent): GitHubSyncResult

    /** Counterpart mappings and unresolved problems, using the usual offset pagination. */
    suspend fun findPullRequestStates(repositoryId: UUID, offset: Long, limit: Int): List<GitHubPullRequestState>

    /** Recovers PR lifecycle changes using verified originating attribution; null selects all enabled pairs. Each PR owns its transaction. */
    suspend fun reconcilePullRequests(repositoryId: UUID? = null): List<GitHubPullRequestState>

    /** The repository's pair, including disabled configuration. */
    suspend fun findPair(repositoryId: UUID): GitHubRepositoryPair?

    /**
     * Creates or optimistically updates a pair; changing its immutable GitHub repository ID is forbidden.
     * An omitted ID is resolved with the referenced token on creation or when owner/name changes.
     * Unchanged existing targets retain their ID so synchronization can be disabled without credentials.
     */
    suspend fun savePair(input: GitHubRepositoryPairInput): GitHubRepositoryPair

    /** All administrator-verified user mappings, ordered by immutable GitHub user ID. */
    suspend fun findUsers(offset: Long, limit: Int): List<GitHubUser>

    /** Maps a GitHub human user to an existing Bosca principal. Does not grant any permissions. */
    suspend fun mapUser(githubUserId: Long, principalId: UUID): GitHubUser

    /** Resolves a GitHub human username and saves its immutable ID without granting permissions. */
    suspend fun mapUserByUsername(username: String, principalId: UUID): GitHubUser

    /** Current inbound-access problem for a saved delivery; null does not claim synchronization completed. */
    suspend fun deliveryImportProblem(delivery: GitHubDelivery): String?

    /** Removes a mapping without rewriting the originating attribution of accepted deliveries. */
    suspend fun unmapUser(githubUserId: Long)

    /**
     * Verifies HMAC-SHA256 over [body] before decoding and persists one occurrence per delivery ID.
     * Redelivery returns that occurrence; a different payload/event/repository under the same ID fails.
     * Unknown users remain unattributed and fork-origin pull request deliveries are marked ignored.
     * Eligible deliveries dispatch their typed event; redelivery retains the original ID and attribution.
     */
    suspend fun onDelivery(
        repositoryId: UUID,
        deliveryId: String,
        event: String,
        signature: String?,
        body: ByteArray,
    ): GitHubDelivery

    /** Verified intake history for an administrator; bodies may contain private repository content. */
    suspend fun findDeliveries(repositoryId: UUID, offset: Long, limit: Int): List<GitHubDelivery>

    /**
     * Applies a verified push once, retaining its original principal and surfacing concurrent edits.
     * The principal must retain current repository EDIT permission; destination branch protections apply.
     * Owns its transaction: ref changes, state and notification registration commit together,
     * independently of any caller transaction. A rolled-back import can retry its notifications.
     */
    suspend fun synchronizePush(delivery: GitHubDelivery): GitHubSyncResult

    /**
     * Mirrors a native branch/tag occurrence without overwriting independently edited GitHub refs.
     * Owns its synchronization transaction independently of any caller transaction.
     */
    suspend fun synchronizeRef(event: RefUpdateEvent): GitHubSyncResult

    /** Current common refs and unresolved conflicts, ordered by ref name. */
    suspend fun findRefStates(repositoryId: UUID, offset: Long, limit: Int): List<GitHubRefState>

    /**
     * Pulls current GitHub branches and tags into an enabled pair under [principalId]'s current
     * repository EDIT permission and destination branch protections, without requiring a webhook.
     * Applied writes notify the ordinary ref/CI path as this principal. Independent destination
     * edits remain conflicts; refs absent from the source are deleted only with a common baseline.
     * Each ref commits independently, so earlier writes survive a later failure or cancellation.
     */
    suspend fun pullRefs(repositoryId: UUID, principalId: UUID): List<GitHubRefState>

    /**
     * Pushes current Bosca branches and tags to an enabled pair under [principalId]'s current
     * repository EDIT permission, using the configured token and GitHub's destination protections.
     * Independent destination edits remain conflicts; untracked destination-only refs are preserved.
     * Each ref commits independently, so earlier writes survive a later failure or cancellation.
     */
    suspend fun pushRefs(repositoryId: UUID, principalId: UUID): List<GitHubRefState>

    /**
     * Resolves one recorded branch/tag conflict by retaining the selected host's reviewed value,
     * including deletion. Requires an active [principalId] with repository EDIT permission and an
     * enabled pair. Both observed refs must match [input] when fetched, and the destination write
     * is conditional on its reviewed value. Destination protections still apply. Inbound changes
     * notify the ordinary ref/CI path as the caller. Changed observations remain conflicts and
     * commit before reporting a stale-resolution failure; cancellation rolls back a local write.
     */
    suspend fun resolveRef(input: GitHubRefResolutionInput, principalId: UUID): GitHubRefState

    /**
     * Reconciles current branches/tags in both directions under [principalId]'s active repository
     * EDIT permission, without requiring a webhook. Imports notify the ordinary ref/CI path as
     * this caller; protections and conflicts remain enforced. Requires an enabled, available pair.
     * Each ref commits independently, rechecking the pairing and permission between commits.
     */
    suspend fun reconcileRefs(repositoryId: UUID, principalId: UUID): List<GitHubRefState>

    /**
     * Reconciles missed branch/tag changes with the same operations as event-driven nodes.
     * Null selects all enabled pairs. Verified pending pushes are redispatched through the event
     * system and their refs are deferred to the import pipeline, preserving original attribution.
     * Inbound changes without verified user authority are observed but never imported. Unresolved initial deletion conflicts
     * cannot recreate a ref; agreement or a safe fast-forward can still resolve them.
     * Each ref owns its transaction and commits before releasing its write lock; completed refs
     * survive a failure on a later ref. Cancellation stops reconciliation immediately.
     * Refs whose Bosca and GitHub values both still equal the recorded common value are returned
     * without transferring them again. With a null [repositoryId], a failing pair is logged and the remaining pairs still run,
     * after which an [IllegalStateException] naming every failed repository is thrown, with the
     * first failure as its cause and the rest suppressed.
     */
    suspend fun reconcileRefs(repositoryId: UUID? = null): List<GitHubRefState>
}
