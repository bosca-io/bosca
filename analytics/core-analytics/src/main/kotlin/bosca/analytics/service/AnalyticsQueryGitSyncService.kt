package bosca.analytics.service

import bosca.serialization.UUID
import bosca.service.Service

/**
 * Synchronizes analytics query SQL from a Bosca-hosted git repository. When a
 * push lands on a branch that an [bosca.analytics.model.AnalyticsQuery] is
 * linked to (via [bosca.git.model.QuerySourceRef]), the new SQL text in the
 * pushed file is written into the query's `query` field. Git is the source of
 * truth; the database column is a deployed cache.
 */
interface AnalyticsQueryGitSyncService : Service {

    /**
     * Applies a push event to any analytics queries linked to changed files
     * in the repository. The git diff between [beforeSha] and [afterSha] is
     * computed downstream so that only queries whose source file actually
     * changed are touched.
     */
    suspend fun onPushEvent(
        repositoryId: UUID,
        ref: String,
        beforeSha: String,
        afterSha: String
    )

    /**
     * Commits the current SQL of an analytics query back to its linked git
     * file. Called after the query is edited in Studio so git remains the
     * source of truth. Returns the new commit SHA, or null when the query
     * has no source ref.
     *
     * Commit attribution is taken entirely from [authorName] / [authorEmail];
     * the caller is responsible for authorization (e.g. via
     * `verifyCanManage` in the GraphQL controller).
     */
    suspend fun pushToGit(
        queryId: UUID,
        authorName: String,
        authorEmail: String
    ): String?

    /**
     * One-shot backfill: commits every git-backed query in [repositoryId] to
     * its linked file with a regenerated `@bosca-query` metadata block.
     * Intended to be run once after the metadata-block feature is deployed so
     * existing repos pick up parameter declarations without requiring a
     * manual edit of each query.
     *
     * Returns the number of queries that produced a commit. Queries whose
     * source ref has been removed are skipped. Per-query failures are logged
     * and isolated, so a single bad commit does not abandon the rest of the
     * repository.
     */
    suspend fun pushAllToGit(
        repositoryId: UUID,
        authorName: String,
        authorEmail: String
    ): Int
}
