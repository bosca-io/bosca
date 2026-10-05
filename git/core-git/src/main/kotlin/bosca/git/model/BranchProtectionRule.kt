package bosca.git.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Defines access restrictions and quality gates for branches matching a glob [pattern]
 * within a repository. Rules are evaluated in the PreReceiveHook during push operations
 * and during pull request merge to enforce review requirements and CI checks.
 *
 * @property pattern glob pattern matching branch names, supports wildcards
 * @property requirePullRequest when true, direct pushes are rejected; changes must come through a merged PR
 * @property requiredApprovals minimum approving reviews before a PR targeting this branch can merge
 * @property dismissStaleReviews when true, existing approvals are dismissed on new commits
 * @property requireCodeOwnerReview when true, at least one approval must come from a code owner
 * @property requireStatusChecks named CI status checks that must pass before merge
 * @property requireLinearHistory when true, only fast-forward or squash merges are allowed
 * @property allowForcePush whether non-fast-forward pushes are permitted
 * @property allowDeletion whether matching branches can be deleted
 * @property restrictPushAccess when set, only these profiles/teams can push even with WRITE permission
 */
@Serializable
data class BranchProtectionRule(
    @Contextual val id: UUID = UUID.NIL,
    @Contextual @ColumnName("repository_id") val repositoryId: UUID,
    val pattern: String,
    @ColumnName("require_pull_request") val requirePullRequest: Boolean = false,
    @ColumnName("required_approvals") val requiredApprovals: Int = 1,
    @ColumnName("dismiss_stale_reviews") val dismissStaleReviews: Boolean = false,
    @ColumnName("require_code_owner_review") val requireCodeOwnerReview: Boolean = false,
    @ColumnName("require_status_checks") val requireStatusChecks: List<String> = emptyList(),
    @ColumnName("require_linear_history") val requireLinearHistory: Boolean = false,
    @ColumnName("allow_force_push") val allowForcePush: Boolean = false,
    @ColumnName("allow_deletion") val allowDeletion: Boolean = false,
    @ColumnName("restrict_push_access") val restrictPushAccess: List<UUID> = emptyList(),
    @Contextual val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual val updated: OffsetDateTime = OffsetDateTime.now()
)
