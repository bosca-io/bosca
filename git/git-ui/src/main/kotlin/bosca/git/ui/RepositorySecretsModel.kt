package bosca.git.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.git.ui.graphql.*
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable

@Serializable
class RepositorySecretsModel(
    val page: RepositorySecretsPage,
    var name: String = "",
    var environment: String = "",
    var saving: Boolean = false,
    var message: String = "",
    var failed: Boolean = false,
    var nextHref: String = "",
) {
    suspend fun save(
        name: String,
        value: String,
        environment: String,
        confirmed: Boolean,
        ctx: RenderContext,
    ) {
        this.name = name
        this.environment = environment
        saving = true
        perform {
            require(name.isNotBlank() && value.isNotBlank() && confirmed) {
                "Enter the name and value and confirm adding or replacing this secret."
            }
            ctx.gql.execute(
                GitUiSetSecret,
                GitUiSetSecret.Variables(
                    page.id,
                    name.trim(),
                    value,
                    environment.trim().ifBlank { null },
                ),
            )
            nextHref = page.baseHref.ifBlank { "/repositories/${encode(page.id)}/secrets" }
        }
    }

    suspend fun delete(name: String, confirmed: Boolean, ctx: RenderContext) = perform {
        saving = false
        require(confirmed) { "Confirm secret deletion." }
        require(
            ctx.gql
                .execute(GitUiDeleteSecret, GitUiDeleteSecret.Variables(page.id, name))
                .git
                .deletePipelineSecret
        ) {
            "Secret was not deleted."
        }
        nextHref = page.baseHref.ifBlank { "/repositories/${encode(page.id)}/secrets" }
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
            message = e.message ?: "Secret action failed. Re-enter the value to try again."
        }
    }
}
