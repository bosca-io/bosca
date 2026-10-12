package bosca.git.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.git.ui.graphql.*
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable

@Serializable
class RepositoryRefModel(
    val page: RepositoryPage,
    val id: String,
    val ref: String,
    val listHref: String,
    var message: String = "",
    var failed: Boolean = false,
    var nextHref: String = "",
    var name: String = "",
    var source: String = ref,
) {
    suspend fun create(name: String, source: String, ctx: RenderContext) {
        this.name = name
        this.source = source
        act {
            require(name.isNotBlank() && source.isNotBlank()) {
                "Enter a branch name and source ref."
            }
            ctx.gql.execute(
                GitUiCreateBranch,
                GitUiCreateBranch.Variables(id, name.trim(), source.trim()),
            )
            nextHref = repositoryHref(id, name.trim(), listHref = listHref, tab = "branches")
        }
    }

    suspend fun remove(name: String, kind: String, confirmed: Boolean, ctx: RenderContext) = act {
        require(confirmed) { "Confirm ref deletion." }
        if (kind == "branch")
            ctx.gql.execute(GitUiDeleteBranch, GitUiDeleteBranch.Variables(id, name))
        else if (kind == "tag") ctx.gql.execute(GitUiDeleteTag, GitUiDeleteTag.Variables(id, name))
        else error("Unknown ref kind")
        nextHref =
            repositoryHref(
                id,
                ref,
                listHref = listHref,
                tab = if (kind == "branch") "branches" else "tags",
            )
    }

    private suspend fun act(block: suspend () -> Unit) {
        message = ""
        failed = false
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message = e.message ?: "Ref action failed."
            failed = true
        }
    }
}
