package bosca.git.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.git.model.BranchProtectionRule
import bosca.serialization.UUID

/**
 * Database access layer for branch protection rules in `git.branch_protection_rules`.
 * Rules are evaluated during push (PreReceiveHook) and PR merge to enforce review
 * requirements, CI checks, and push restrictions.
 */
@Repository
interface BranchProtectionRepository {

    @Query("select * from git.branch_protection_rules where repository_id = :repositoryId order by pattern")
    suspend fun findByRepository(repositoryId: UUID): List<BranchProtectionRule>

    /**
     * Returns only the ordered glob patterns needed to decide whether a branch is protected.
     *
     * Keeping this lookup scalar prevents an unrelated rule's array-valued settings from being
     * materialized while evaluating an unprotected branch.
     */
    @Query("select pattern from git.branch_protection_rules where repository_id = :repositoryId order by pattern")
    suspend fun findPatternsByRepository(repositoryId: UUID): List<String>

    /**
     * Returns the first rule with [pattern] in [repositoryId], or null when it was removed while
     * branch protection was being evaluated.
     */
    @Query("""
        select * from git.branch_protection_rules
        where repository_id = :repositoryId and pattern = :pattern
        order by id
        limit 1
    """)
    suspend fun findByRepositoryAndPattern(repositoryId: UUID, pattern: String): BranchProtectionRule?

    @Query("select * from git.branch_protection_rules where id = :id")
    suspend fun findById(id: UUID): BranchProtectionRule?

    @Query("""
        insert into git.branch_protection_rules
            (repository_id, pattern, require_pull_request, required_approvals, dismiss_stale_reviews,
             require_code_owner_review, require_status_checks, require_linear_history,
             allow_force_push, allow_deletion, restrict_push_access)
        values (:repositoryId, :pattern, :requirePullRequest, :requiredApprovals, :dismissStaleReviews,
                :requireCodeOwnerReview, :requireStatusChecks, :requireLinearHistory,
                :allowForcePush, :allowDeletion, :restrictPushAccess)
        returning *
    """)
    suspend fun create(rule: BranchProtectionRule): BranchProtectionRule

    @Query("""
        update git.branch_protection_rules
        set pattern = :pattern, require_pull_request = :requirePullRequest,
            required_approvals = :requiredApprovals, dismiss_stale_reviews = :dismissStaleReviews,
            require_code_owner_review = :requireCodeOwnerReview, require_status_checks = :requireStatusChecks,
            require_linear_history = :requireLinearHistory, allow_force_push = :allowForcePush,
            allow_deletion = :allowDeletion, restrict_push_access = :restrictPushAccess,
            updated = now()
        where id = :id
        returning *
    """)
    suspend fun update(rule: BranchProtectionRule): BranchProtectionRule?

    @Query("delete from git.branch_protection_rules where id = :id")
    suspend fun delete(id: UUID)
}
