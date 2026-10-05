package bosca.git.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.git.model.PullRequest
import bosca.serialization.UUID

/**
 * Database access for directed pull-request dependencies. A row means that
 * `pull_request_id` cannot be merged until `depends_on_pull_request_id` is merged.
 */
@Repository
interface PullRequestDependencyRepository {

    @Query("""
        select pr.*
        from git.pull_request_dependencies dependency
        join git.pull_requests pr on pr.id = dependency.depends_on_pull_request_id
        where dependency.pull_request_id = :pullRequestId
        order by dependency.created, dependency.depends_on_pull_request_id
    """)
    suspend fun findDependencies(pullRequestId: UUID): List<PullRequest>

    @Query("""
        select pr.*
        from git.pull_request_dependencies dependency
        join git.pull_requests pr on pr.id = dependency.pull_request_id
        where dependency.depends_on_pull_request_id = :pullRequestId
        order by dependency.created, dependency.pull_request_id
    """)
    suspend fun findDependents(pullRequestId: UUID): List<PullRequest>

    @Query("""
        insert into git.pull_request_dependencies (pull_request_id, depends_on_pull_request_id)
        values (:pullRequestId, :dependencyId)
    """)
    suspend fun add(pullRequestId: UUID, dependencyId: UUID)

    @Query("""
        delete from git.pull_request_dependencies
        where pull_request_id = :pullRequestId
          and depends_on_pull_request_id = :dependencyId
    """)
    suspend fun remove(pullRequestId: UUID, dependencyId: UUID)
}
