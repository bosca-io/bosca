package bosca.git.graphql

import bosca.git.model.BranchProtectionRule
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.UUID

/**
 * Resolves all fields on [GitBranchProtectionRule] representing the set of
 * policies enforced on branches matching the rule's glob [pattern].
 */
@TypeController(type = "GitBranchProtectionRule")
class GitBranchProtectionRuleController : GraphQLController<BranchProtectionRule> {

    @Field
    fun id(source: BranchProtectionRule): UUID = source.id

    @Field
    fun repositoryId(source: BranchProtectionRule): UUID = source.repositoryId

    @Field
    fun pattern(source: BranchProtectionRule): String = source.pattern

    @Field
    fun requirePullRequest(source: BranchProtectionRule): Boolean = source.requirePullRequest

    @Field
    fun requiredApprovals(source: BranchProtectionRule): Int = source.requiredApprovals

    @Field
    fun dismissStaleReviews(source: BranchProtectionRule): Boolean = source.dismissStaleReviews

    @Field
    fun requireCodeOwnerReview(source: BranchProtectionRule): Boolean = source.requireCodeOwnerReview

    @Field
    fun requireStatusChecks(source: BranchProtectionRule): List<String> = source.requireStatusChecks

    @Field
    fun requireLinearHistory(source: BranchProtectionRule): Boolean = source.requireLinearHistory

    @Field
    fun allowForcePush(source: BranchProtectionRule): Boolean = source.allowForcePush

    @Field
    fun allowDeletion(source: BranchProtectionRule): Boolean = source.allowDeletion

    @Field
    fun restrictPushAccess(source: BranchProtectionRule): List<UUID>? = source.restrictPushAccess
}
