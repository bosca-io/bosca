package bosca.git.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.git.ui.graphql.*
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable

@Serializable
class GitHubSecretModel(
    val id: String,
    val names: List<String>,
    var name: String = "",
    var message: String = "",
    var failed: Boolean = false,
) {
    suspend fun save(name: String, value: String, confirmed: Boolean, ctx: RenderContext) {
        this.name = name
        message = ""
        failed = false
        try {
            require(name.isNotBlank() && value.isNotBlank() && confirmed) {
                "Enter the name and value and confirm adding or replacing the shared secret."
            }
            ctx.gql.execute(GitUiSharedSecret, GitUiSharedSecret.Variables(name.trim(), value))
            message = "Secret saved. Enter this name in the pairing and save it."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
            message = e.message ?: "Secret save failed. Re-enter the value to try again."
        }
    }
}
