package bosca.git.ui

import bosca.bml.render.RenderContext
import kotlinx.serialization.Serializable

@Serializable
class RepositoryRowModel(
    val repository: RepositorySummary,
    val state: RepositoryState,
    val owner: OwnerOption,
    var pendingAction: String = "",
    var message: String = "",
    var failed: Boolean = false,
    var nextHref: String = "",
) {
    suspend fun prepare(action: String) {
        require(action in setOf("archive", "restore", "delete")) { "Unknown repository action." }
        pendingAction = action
        message = ""
        failed = false
    }

    suspend fun lifecycle(action: String, confirmed: Boolean, ctx: RenderContext) {
        val list = RepositoryListModel(state, owner)
        list.lifecycle(repository.id, action, confirmed, ctx)
        message = list.message
        failed = list.failed
        nextHref = list.nextHref
    }
}
