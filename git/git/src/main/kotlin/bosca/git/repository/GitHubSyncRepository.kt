package bosca.git.repository

import bosca.git.model.GitHubPullRequestState

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.git.model.GitHubUser
import bosca.git.model.GitHubDelivery
import bosca.git.model.GitHubRepositoryPair
import bosca.git.model.GitHubRefState
import bosca.serialization.UUID

@Repository
interface GitHubSyncRepository {
    @Query("select * from git.github_pull_request_states where repository_id = :repositoryId and id = :id")
    suspend fun findPullRequestStateById(repositoryId: UUID, id: UUID): GitHubPullRequestState?

    @Query("select * from git.github_pull_request_states where repository_id = :repositoryId and pull_request_id = :pullRequestId")
    suspend fun findPullRequestState(repositoryId: UUID, pullRequestId: UUID): GitHubPullRequestState?

    @Query("select * from git.github_pull_request_states where repository_id = :repositoryId and github_number = :number")
    suspend fun findPullRequestState(repositoryId: UUID, number: Int): GitHubPullRequestState?

    @Query("select * from git.github_pull_request_states where repository_id = :repositoryId order by id limit :limit offset :offset")
    suspend fun findPullRequestStates(repositoryId: UUID, offset: Long, limit: Int): List<GitHubPullRequestState>

    @Query("""
        insert into git.github_pull_request_states(id, repository_id, pull_request_id, github_id, github_number, imported, snapshot, pending, bosca, github, problem)
        values (:id, :repositoryId, :pullRequestId, :githubId, :githubNumber, :imported, :snapshot, :pending, :boscaSnapshot, :githubSnapshot, :problem)
        on conflict(id) do update set pull_request_id = excluded.pull_request_id,
            github_id = excluded.github_id, github_number = excluded.github_number,
            snapshot = excluded.snapshot, pending = excluded.pending,
            bosca = excluded.bosca, github = excluded.github, problem = excluded.problem, modified = now()
        returning *
    """)
    suspend fun savePullRequestState(state: GitHubPullRequestState): GitHubPullRequestState

    @Query("select * from git.github_repository_pairs where repository_id = :repositoryId")
    suspend fun findPair(repositoryId: UUID): GitHubRepositoryPair?

    @Query("select * from git.github_repository_pairs where repository_id = :repositoryId for no key update")
    suspend fun lockPair(repositoryId: UUID): GitHubRepositoryPair?

    @Query("select * from git.github_repository_pairs where enabled order by repository_id")
    suspend fun findEnabledPairs(): List<GitHubRepositoryPair>

    @Query("select * from git.github_ref_states where repository_id = :repositoryId order by ref")
    suspend fun findAllRefStates(repositoryId: UUID): List<GitHubRefState>

    @Query("select * from git.github_ref_states where repository_id = :repositoryId and ref = :ref")
    suspend fun findRefState(repositoryId: UUID, ref: String): GitHubRefState?

    @Query("select * from git.github_ref_states where repository_id = :repositoryId order by ref limit :limit offset :offset")
    suspend fun findRefStates(repositoryId: UUID, offset: Long, limit: Int): List<GitHubRefState>

    @Query("""
        insert into git.github_ref_states(repository_id, ref, sha, synchronized, bosca_sha, github_sha, conflict,
            unattributed_before_sha, unattributed_ref_modified)
        values (:repositoryId, :ref, :sha, :synchronized, :boscaSha, :githubSha, :conflict, :unattributedBeforeSha,
            case when :unattributedBeforeSha is not null then coalesce(:unattributedRefModified,
                (select updated from git.dfs_refs where repository_id = :repositoryId and name = :ref)) end)
        on conflict(repository_id, ref) do update
        set sha = excluded.sha, synchronized = excluded.synchronized,
            bosca_sha = excluded.bosca_sha, github_sha = excluded.github_sha,
            conflict = excluded.conflict, unattributed_before_sha = excluded.unattributed_before_sha,
            unattributed_ref_modified = excluded.unattributed_ref_modified, modified = now()
        returning *
    """)
    suspend fun saveRefState(state: GitHubRefState): GitHubRefState

    /** Returns an anonymous import's old value only while its exact DFS ref write remains current. */
    @Query("""
        select s.unattributed_before_sha from git.github_ref_states s
        join git.dfs_refs r on r.repository_id = s.repository_id and r.name = s.ref
        where s.repository_id = :repositoryId and s.ref = :ref and s.sha = :sha
            and r.object_id = s.sha and r.updated = s.unattributed_ref_modified
    """)
    suspend fun findUnattributedBeforeSha(repositoryId: UUID, ref: String, sha: String): String?

    @Query("select result from git.github_push_results where delivery_id = :deliveryId")
    suspend fun findPushResult(deliveryId: String): String?

    @Query("insert into git.github_push_results(delivery_id, result) values (:deliveryId, :result)")
    suspend fun savePushResult(deliveryId: String, result: String)

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

    /** Serializes verified intake with synchronization's pair lock until the delivery commits. */
    @Query("""
        with pair as materialized (
            select repository_id from git.github_repository_pairs
            where repository_id = :repositoryId for no key update
        )
        insert into git.github_deliveries
            (delivery_id, repository_id, event, payload, payload_digest, github_user_id, principal_id, ignored)
        select :deliveryId, repository_id, :event, :payload, :payloadDigest, :githubUserId, :principalId, :ignored from pair
        on conflict(delivery_id) do nothing returning *
    """)
    suspend fun createDelivery(delivery: GitHubDelivery): GitHubDelivery?

    @Query("select * from git.github_deliveries where delivery_id = :deliveryId")
    suspend fun findDelivery(deliveryId: String): GitHubDelivery?

    /** The latest signed PR observation supplies attribution for reconciliation, never the integration identity. */
    @Query("""
        select * from git.github_deliveries where repository_id = :repositoryId and event = 'pull_request'
            and not ignored and payload->>'number' = cast(:number as text)
        order by created desc, delivery_id desc limit 1
    """)
    suspend fun findPullRequestDelivery(repositoryId: UUID, number: Int): GitHubDelivery?

    /** Recovers ref authorization only from a verified push of the exact current source value. */
    @Query("""
        select * from git.github_deliveries where repository_id = :repositoryId and event = 'push'
            and not ignored and payload->>'ref' = :ref and payload->>'after' = :sha
        order by created desc, delivery_id desc limit 1
    """)
    suspend fun findPushDelivery(repositoryId: UUID, ref: String, sha: String): GitHubDelivery?

    /** Refs with verified push deliveries that have not completed synchronization. */
    @Query("""
        select distinct d.payload->>'ref' as ref from git.github_deliveries d
        where d.repository_id = :repositoryId and d.event = 'push' and not d.ignored
            and d.payload->>'ref' is not null
            and not exists (select 1 from git.github_push_results r where r.delivery_id = d.delivery_id)
        order by ref
    """)
    suspend fun findPendingPushRefs(repositoryId: UUID): List<String>

    /** Original verified occurrences for a ref, excluding ignored or already processed deliveries. */
    @Query("""
        select d.* from git.github_deliveries d
        where d.repository_id = :repositoryId and d.event = 'push' and not d.ignored
            and d.payload->>'ref' = :ref
            and not exists (select 1 from git.github_push_results r where r.delivery_id = d.delivery_id)
        order by d.created, d.delivery_id
    """)
    suspend fun findPendingPushDeliveries(repositoryId: UUID, ref: String): List<GitHubDelivery>

    @Query("""
        select * from git.github_deliveries where repository_id = :repositoryId
        order by created desc, delivery_id limit :limit offset :offset
    """)
    suspend fun findDeliveries(repositoryId: UUID, offset: Long, limit: Int): List<GitHubDelivery>
}
