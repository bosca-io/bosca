package bosca.git.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.git.ui.graphql.*
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable

@Serializable
class GitHubPairModel(
    val page: GitHubPage,
    var owner: String = page.pair?.owner.orEmpty(),
    var name: String = page.pair?.name.orEmpty(),
    var webhook: String = page.pair?.webhookSecretName.orEmpty(),
    var token: String = page.pair?.tokenSecretName.orEmpty(),
    var enabled: Boolean = page.pair?.enabled == true,
    var pushIncludes: String = page.pair?.pushBranchIncludes?.joinToString(", ").orEmpty(),
    var pushExcludes: String = page.pair?.pushBranchExcludes?.joinToString(", ").orEmpty(),
    var pullIncludes: String = page.pair?.pullBranchIncludes?.joinToString(", ").orEmpty(),
    var pullExcludes: String = page.pair?.pullBranchExcludes?.joinToString(", ").orEmpty(),
    var message: String = "",
    var failed: Boolean = false,
    var nextHref: String = "",
) {
    suspend fun save(
        owner: String,
        name: String,
        webhook: String,
        token: String,
        enabled: Boolean,
        pushIncludes: String,
        pushExcludes: String,
        pullIncludes: String,
        pullExcludes: String,
        ctx: RenderContext,
    ) {
        this.owner = owner
        this.name = name
        this.webhook = webhook
        this.token = token
        this.enabled = enabled
        this.pushIncludes = pushIncludes
        this.pushExcludes = pushExcludes
        this.pullIncludes = pullIncludes
        this.pullExcludes = pullExcludes
        message = ""
        failed = false
        try {
            require(
                owner.isNotBlank() &&
                    name.isNotBlank() &&
                    webhook.isNotBlank() &&
                    token.isNotBlank()
            ) {
                "Owner, repository name and both secret names are required."
            }
            val secrets =
                ctx.gql
                    .execute(GitUiGitHubPair, GitUiGitHubPair.Variables(page.id))
                    .pipelines
                    .secrets
                    .map { it.name }
            val lookup =
                page.pair == null ||
                    page.pair.owner != owner.trim() ||
                    page.pair.name != name.trim()
            require(!lookup || token in secrets) {
                "Add the token secret before looking up the repository."
            }
            require(!enabled || (webhook in secrets && token in secrets)) {
                "Add both secrets before enabling synchronization."
            }
            val input =
                GitHubRepositoryPairInput(
                    repositoryId = page.id,
                    owner = owner.trim(),
                    name = name.trim(),
                    webhookSecretName = webhook,
                    tokenSecretName = token,
                    enabled = enabled,
                    version = page.pair?.version ?: 0,
                    pushBranchIncludes = commaValues(pushIncludes),
                    pushBranchExcludes = commaValues(pushExcludes),
                    pullBranchIncludes = commaValues(pullIncludes),
                    pullBranchExcludes = commaValues(pullExcludes),
                )
            ctx.gql.execute(GitUiSavePair, GitUiSavePair.Variables(input))
            nextHref = page.href
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
            message = e.message ?: "Could not save pairing."
        }
    }
}
