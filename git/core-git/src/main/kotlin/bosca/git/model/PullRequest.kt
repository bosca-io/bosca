package bosca.git.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * A request to merge changes from a source branch into a target branch within
 * a repository (or across forks). Carries review state, merge metadata, and
 * the auto-incrementing per-repo [number] that serves as the user-facing
 * identifier (e.g. #42).
 */
@Serializable
data class PullRequest(
    @Contextual val id: UUID = UUID.NIL,
    @Contextual @ColumnName("repository_id") val repositoryId: UUID,
    val number: Int = 0,
    val title: String,
    val description: String? = null,
    @Contextual @ColumnName("author_id") val authorId: UUID,
    @ColumnName("source_branch") val sourceBranch: String,
    @ColumnName("target_branch") val targetBranch: String,
    @Contextual @ColumnName("source_repository_id") val sourceRepositoryId: UUID? = null,
    val status: PullRequestStatus = PullRequestStatus.OPEN,
    @ColumnName("merge_strategy") val mergeStrategy: MergeStrategy? = null,
    @Contextual @ColumnName("merged_by") val mergedBy: UUID? = null,
    @Contextual @ColumnName("merged_at") val mergedAt: OffsetDateTime? = null,
    @ColumnName("merge_sha") val mergeSha: String? = null,
    @Contextual val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual val updated: OffsetDateTime = OffsetDateTime.now()
)
