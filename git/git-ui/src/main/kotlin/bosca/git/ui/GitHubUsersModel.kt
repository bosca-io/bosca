package bosca.git.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.git.ui.graphql.*
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable

@Serializable
class GitHubUsersModel(
    val allowed: Boolean,
    var offset: Long = 0,
    var principalOffset: Long = 0,
    var mappings: List<GitUiGitHubUsersData.Github.Users> = emptyList(),
    var principals: List<GitUiPrincipalsData.Security.Principals.All> = emptyList(),
    var username: String = "",
    var principal: String = "",
    var message: String = "",
    var failed: Boolean = false,
) {
    fun label(user: GitUiPrincipalsData.Security.Principals.All): String {
        val profile =
            user.profiles.firstOrNull { it.id == user.primaryProfileId }
                ?: user.profiles.firstOrNull()
        val identifier = user.credentials.firstOrNull()?.identifier?.takeIf(String::isNotBlank)
        return listOfNotNull(
                profile?.name?.takeIf(String::isNotBlank),
                identifier ?: user.id.take(8),
            )
            .distinct()
            .joinToString(" · ")
    }

    suspend fun reload(ctx: RenderContext) {
        mappings =
            ctx.gql.execute(GitUiGitHubUsers, GitUiGitHubUsers.Variables(offset)).github.users
        principals =
            ctx.gql
                .execute(GitUiPrincipals, GitUiPrincipals.Variables(principalOffset))
                .security
                .principals
                .all
    }

    suspend fun move(
        offset: Long,
        principalOffset: Long,
        username: String,
        principal: String,
        ctx: RenderContext,
    ) = perform {
        this.username = username
        this.principal = principal
        this.offset = offset.coerceAtLeast(0)
        this.principalOffset = principalOffset.coerceAtLeast(0)
        reload(ctx)
    }

    suspend fun save(username: String, principal: String, ctx: RenderContext) {
        this.username = username
        this.principal = principal
        perform {
            require(username.isNotBlank() && principal.isNotBlank()) {
                "Enter a GitHub username and choose the same person's Bosca principal."
            }
            ctx.gql.execute(
                GitUiMapUser,
                GitUiMapUser.Variables(username.trim().removePrefix("@"), principal),
            )
            this.username = ""
            this.principal = ""
            offset = 0
            reload(ctx)
            message = "User mapping saved."
        }
    }

    suspend fun remove(id: Long, confirmed: Boolean, ctx: RenderContext) = perform {
        require(confirmed) { "Confirm removal of this mapping." }
        require(ctx.gql.execute(GitUiUnmapUser, GitUiUnmapUser.Variables(id)).github.unmapUser) {
            "Mapping was not removed."
        }
        if (mappings.size == 1 && offset >= 25) offset -= 25
        reload(ctx)
        message = "Mapping removed. Previous deliveries retain their attribution."
    }

    private suspend fun perform(block: suspend () -> Unit) {
        message = ""
        failed = false
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
            message = e.message ?: "User mapping action failed."
        }
    }
}
