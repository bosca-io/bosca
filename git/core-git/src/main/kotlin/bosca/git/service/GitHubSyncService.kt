package bosca.git.service

import bosca.git.model.GitHubUser
import bosca.git.model.GitHubDelivery
import bosca.git.model.GitHubRepositoryPair
import bosca.git.model.GitHubRepositoryPairInput
import bosca.git.model.GitHubRefState
import bosca.git.model.GitHubSyncResult
import bosca.git.model.RefUpdateEvent
import bosca.serialization.UUID
import bosca.service.Service

/** Owns repository pairing, user attribution, verified intake and ref synchronization state. */
interface GitHubSyncService : Service {
    /** The repository's pair, including disabled configuration. */
    suspend fun findPair(repositoryId: UUID): GitHubRepositoryPair?

    /** Creates or optimistically updates a pair; changing its immutable GitHub repository ID is forbidden. */
    suspend fun savePair(input: GitHubRepositoryPairInput): GitHubRepositoryPair

    /** All administrator-verified user mappings, ordered by immutable GitHub user ID. */
    suspend fun findUsers(offset: Long, limit: Int): List<GitHubUser>

    /** Maps a GitHub human user to an existing Bosca principal. Does not grant any permissions. */
    suspend fun mapUser(githubUserId: Long, principalId: UUID): GitHubUser

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
     * Reconciles missed branch/tag changes with the same operations as event-driven nodes.
     * Null selects all enabled pairs. Verified pending pushes are redispatched through the event
     * system and their refs are deferred to the import pipeline, preserving original attribution.
     * Unattributed recovered changes cannot authorize builds. Unresolved initial deletion conflicts
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
