package bosca.git.service

import bosca.git.model.GitHubUser
import bosca.git.model.GitHubDelivery
import bosca.git.model.GitHubRepositoryPair
import bosca.git.model.GitHubRepositoryPairInput
import bosca.serialization.UUID
import bosca.service.Service

/** Owns repository pairing, user attribution, and verified delivery intake, not workflow execution. */
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
}
