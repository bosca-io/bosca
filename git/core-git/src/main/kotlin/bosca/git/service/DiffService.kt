package bosca.git.service

import bosca.git.model.DiffFile
import bosca.git.model.CommitInfo
import bosca.git.model.PullRequest
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Computes structured diffs between two refs in a repository with rename detection.
 * Returns line-level hunk detail suitable for rendering in a code review UI.
 */
interface DiffService : Service {

    /**
     * Computes the diff between [baseRef] and [headRef] (branch names or SHAs)
     * using three-dot merge-base semantics for PR diffs.
     */
    suspend fun computeDiff(repositoryId: UUID, baseRef: String, headRef: String): List<DiffFile>

    /**
     * Lists commits reachable from [headRef] but not [baseRef], in topological newest-first order.
     * [offset] and [limit] page through the entire comparison without truncating its history.
     */
    suspend fun listComparisonCommits(repositoryId: UUID, baseRef: String, headRef: String, offset: Long, limit: Int): List<CommitInfo>

    /**
     * Diffs [commitSha] against its first parent (or the empty tree for a root commit).
     * Requires an exact SHA belonging to the [baseRef]..[headRef] comparison.
     */
    suspend fun computeCommitDiff(repositoryId: UUID, baseRef: String, headRef: String, commitSha: String): List<DiffFile>

    /**
     * Returns the immutable target tip preceding a completed merge. A recorded tip is
     * used for every strategy; merge/squash commit parents can supply it when absent.
     * Returns null when the PR is not merged or its original base cannot be determined.
     */
    suspend fun resolveMergedBase(pullRequest: PullRequest): String?
}
