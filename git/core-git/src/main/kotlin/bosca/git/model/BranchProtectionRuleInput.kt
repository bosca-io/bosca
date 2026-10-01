package bosca.git.model

import bosca.serialization.UUID
import kotlinx.serialization.Serializable

/**
 * Input fields accepted when creating or updating a branch protection rule.
 *
 * Repository ownership and persistence fields are deliberately absent. The mutation boundary
 * supplies those values after authorizing access to the target repository.
 *
 * @property pattern glob pattern matching branch names
 * @property requirePullRequest whether changes must arrive through pull requests
 * @property requiredApprovals minimum number of approving reviews
 * @property dismissStaleReviews whether new commits invalidate existing approvals
 * @property requireCodeOwnerReview whether a code owner approval is required
 * @property requireStatusChecks named status checks required before merge
 * @property requireLinearHistory whether merge history must remain linear
 * @property allowForcePush whether matching branches accept non-fast-forward updates
 * @property allowDeletion whether matching branches may be deleted
 * @property restrictPushAccess profiles allowed to push, or null when unrestricted
 */
@Serializable
data class BranchProtectionRuleInput(
    val pattern: String,
    val requirePullRequest: Boolean = false,
    val requiredApprovals: Int = 1,
    val dismissStaleReviews: Boolean = false,
    val requireCodeOwnerReview: Boolean = false,
    val requireStatusChecks: List<String> = emptyList(),
    val requireLinearHistory: Boolean = false,
    val allowForcePush: Boolean = false,
    val allowDeletion: Boolean = false,
    val restrictPushAccess: List<UUID>? = null,
)
