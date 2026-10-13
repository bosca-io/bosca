package bosca.git.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.git.ui.graphql.*
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable

@Serializable
class RepositoryUtilitiesModel(
    val page: RepositorySettingsPage,
    var message: String = "",
    var failed: Boolean = false,
    var size: Long = page.repository.diskSizeBytes,
) {
    suspend fun run(action: String, confirmed: Boolean, ctx: RenderContext) {
        failed = false
        message = ""
        try {
            require(confirmed) { "Confirm the repository maintenance action." }
            size =
                when (action) {
                    "gc" ->
                        ctx.gql
                            .execute(GitUiGc, GitUiGc.Variables(page.repository.id))
                            .git
                            .runRepositoryGc
                            .diskSizeBytes
                    "repair" ->
                        ctx.gql
                            .execute(GitUiRepair, GitUiRepair.Variables(page.repository.id))
                            .git
                            .runRepositoryRepair
                            .diskSizeBytes
                    else -> error("Unknown maintenance action")
                }
            message = if (action == "gc") "Garbage collection complete." else "Repair complete."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
            message = e.message ?: "Maintenance failed."
        }
    }
}
