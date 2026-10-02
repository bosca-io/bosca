package bosca.git.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.git.model.GitHubUser
import bosca.git.model.GitHubDelivery
import bosca.git.model.GitHubRepositoryPair
import bosca.serialization.UUID

@Repository
interface GitHubSyncRepository {
    @Query("select * from git.github_repository_pairs where repository_id = :repositoryId")
    suspend fun findPair(repositoryId: UUID): GitHubRepositoryPair?

    @Query("""
        insert into git.github_repository_pairs
            (repository_id, github_repository_id, owner, name, webhook_secret_name, token_secret_name, enabled)
        values (:repositoryId, :githubRepositoryId, :owner, :name, :webhookSecretName, :tokenSecretName, :enabled)
        returning *
    """)
    suspend fun createPair(pair: GitHubRepositoryPair): GitHubRepositoryPair

    @Query("""
        update git.github_repository_pairs
        set owner = :owner, name = :name, webhook_secret_name = :webhookSecretName,
            token_secret_name = :tokenSecretName, enabled = :enabled, version = version + 1, modified = now()
        where repository_id = :repositoryId and github_repository_id = :githubRepositoryId and version = :version
        returning *
    """)
    suspend fun updatePair(pair: GitHubRepositoryPair): GitHubRepositoryPair?

    @Query("select * from git.github_users order by github_user_id limit :limit offset :offset")
    suspend fun findUsers(offset: Long, limit: Int): List<GitHubUser>

    @Query("select * from git.github_users where github_user_id = :githubUserId")
    suspend fun findUser(githubUserId: Long): GitHubUser?

    @Query("""
        insert into git.github_users(github_user_id, principal_id) values (:githubUserId, :principalId)
        on conflict(github_user_id) do update set principal_id = excluded.principal_id, modified = now()
        returning *
    """)
    suspend fun mapUser(githubUserId: Long, principalId: UUID): GitHubUser

    @Query("delete from git.github_users where github_user_id = :githubUserId")
    suspend fun unmapUser(githubUserId: Long)

    @Query("""
        insert into git.github_deliveries
            (delivery_id, repository_id, event, payload, payload_digest, github_user_id, principal_id, ignored)
        values (:deliveryId, :repositoryId, :event, :payload, :payloadDigest, :githubUserId, :principalId, :ignored)
        on conflict(delivery_id) do nothing returning *
    """)
    suspend fun createDelivery(delivery: GitHubDelivery): GitHubDelivery?

    @Query("select * from git.github_deliveries where delivery_id = :deliveryId")
    suspend fun findDelivery(deliveryId: String): GitHubDelivery?

    @Query("""
        select * from git.github_deliveries where repository_id = :repositoryId
        order by created desc, delivery_id limit :limit offset :offset
    """)
    suspend fun findDeliveries(repositoryId: UUID, offset: Long, limit: Int): List<GitHubDelivery>
}
