package bosca.git.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.git.ui.graphql.*
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable

@Serializable
class RepositoryProtectionModel(
    val page: RepositorySettingsPage,
    var id: String = "",
    var pattern: String = "",
    var pullRequest: Boolean = true,
    var approvals: Int = 1,
    var stale: Boolean = false,
    var codeOwner: Boolean = false,
    var linear: Boolean = false,
    var force: Boolean = false,
    var deletion: Boolean = false,
    var checks: String = "",
    var editing: Boolean = false,
    var message: String = "",
    var failed: Boolean = false,
    var nextHref: String = "",
) {
    suspend fun edit(id: String) {
        editing = true
        failed = false
        message = ""
        val rule = page.rules.first { it.id == id }
        this.id = id
        pattern = rule.pattern
        pullRequest = rule.requirePullRequest
        approvals = rule.requiredApprovals
        stale = rule.dismissStaleReviews
        codeOwner = rule.requireCodeOwnerReview
        linear = rule.requireLinearHistory
        force = rule.allowForcePush
        deletion = rule.allowDeletion
        checks = rule.requireStatusChecks.joinToString(", ")
    }

    suspend fun reset() {
        editing = true
        failed = false
        message = ""
        id = ""
        pattern = ""
        pullRequest = true
        approvals = 1
        stale = false
        codeOwner = false
        linear = false
        force = false
        deletion = false
        checks = ""
    }

    suspend fun save(
        pattern: String,
        pullRequest: Boolean,
        approvals: Int,
        stale: Boolean,
        codeOwner: Boolean,
        linear: Boolean,
        force: Boolean,
        deletion: Boolean,
        checks: String,
        ctx: RenderContext,
    ) {
        this.pattern = pattern
        this.pullRequest = pullRequest
        this.approvals = approvals
        this.stale = stale
        this.codeOwner = codeOwner
        this.linear = linear
        this.force = force
        this.deletion = deletion
        this.checks = checks
        perform {
            require(pattern.isNotBlank() && approvals >= 0) {
                "Enter a branch pattern and a nonnegative approval count."
            }
            val input =
                GitBranchProtectionRuleInput(
                    pattern.trim(),
                    pullRequest,
                    approvals,
                    stale,
                    codeOwner,
                    commaValues(checks),
                    linear,
                    force,
                    deletion,
                    page.rules.firstOrNull { it.id == id }?.restrictPushAccess,
                )
            if (id.isBlank())
                ctx.gql.execute(
                    GitUiCreateProtection,
                    GitUiCreateProtection.Variables(page.repository.id, input),
                )
            else ctx.gql.execute(GitUiUpdateProtection, GitUiUpdateProtection.Variables(id, input))
            nextHref = page.href
        }
    }

    suspend fun delete(id: String, confirmed: Boolean, ctx: RenderContext) = perform {
        require(confirmed && page.rules.any { it.id == id }) {
            "Choose a branch rule and confirm deletion."
        }
        require(
            ctx.gql
                .execute(GitUiDeleteProtection, GitUiDeleteProtection.Variables(id))
                .git
                .deleteBranchProtectionRule
        ) {
            "Rule was not deleted."
        }
        nextHref = page.href
    }

    private suspend fun perform(block: suspend () -> Unit) {
        failed = false
        message = ""
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
            message = e.message ?: "Branch protection action failed."
        }
    }
}
