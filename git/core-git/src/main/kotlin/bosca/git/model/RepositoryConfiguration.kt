package bosca.git.model

import kotlinx.serialization.Serializable

/**
 * Configurable policies governing how a repository handles pull request merges
 * and commit requirements. Stored as JSONB in the `configuration` column of
 * the `git.repositories` table.
 */
@Serializable
data class RepositoryConfiguration(
    /** The merge strategies permitted when completing pull requests. */
    val mergeStrategies: List<MergeStrategy> = listOf(MergeStrategy.MERGE_COMMIT, MergeStrategy.SQUASH, MergeStrategy.REBASE),
    /** Whether the squash strategy is pre-selected in the merge dialog. */
    val squashByDefault: Boolean = false,
    /** Default source-branch cleanup policy for newly created PRs. Each PR can override it. */
    val deleteBranchOnMerge: Boolean = true,
    /** Whether all pushed commits must carry a valid GPG signature. */
    val requireSignedCommits: Boolean = false
)
