package bosca.git.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.git.ui.graphql.*
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable

@Serializable
class RepositoryPermissionsModel(
    val page: RepositorySettingsPage,
    var group: String = "",
    var action: String = "VIEW",
    var removing: Boolean = false,
    var message: String = "",
    var failed: Boolean = false,
    var nextHref: String = "",
) {
    suspend fun change(
        group: String,
        action: String,
        remove: Boolean,
        confirmed: Boolean,
        ctx: RenderContext,
    ) {
        removing = remove
        this.group = group
        this.action = action
        failed = false
        message = ""
        try {
            require(confirmed && group.isNotBlank()) {
                "Choose a group and confirm the permission change."
            }
            val input = PermissionInput(PermissionAction.valueOf(action), page.repository.id, group)
            val changed =
                if (remove)
                    ctx.gql.execute(GitUiRevoke, GitUiRevoke.Variables(input)).git.removePermission
                else ctx.gql.execute(GitUiGrant, GitUiGrant.Variables(input)).git.addPermission
            require(changed) { "Permission was not changed." }
            nextHref = page.href
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
            message = e.message ?: "Permission change failed."
        }
    }
}
