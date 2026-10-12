package bosca.git.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.git.ui.graphql.*
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable

@Serializable
class RepositoryWebhookModel(
    val page: RepositorySettingsPage,
    var url: String = "",
    var events: List<String> = listOf("PUSH"),
    var creating: Boolean = false,
    var active: Boolean = true,
    var message: String = "",
    var failed: Boolean = false,
    var nextHref: String = "",
) {
    suspend fun create(
        url: String,
        secret: String,
        events: String,
        active: Boolean,
        ctx: RenderContext,
    ) {
        creating = true
        this.url = url
        this.active = active
        perform {
            this.events = kotlinx.serialization.json.Json.decodeFromString(events)
            require(url.isNotBlank() && secret.isNotBlank() && this.events.isNotEmpty()) {
                "URL, secret and at least one event are required."
            }
            ctx.gql.execute(
                GitUiCreateWebhook,
                GitUiCreateWebhook.Variables(
                    page.repository.id,
                    GitWebhookInput(
                        url.trim(),
                        secret,
                        this.events.map(GitWebhookEvent::valueOf),
                        active,
                    ),
                ),
            )
            nextHref = page.href
        }
    }

    suspend fun delete(id: String, confirmed: Boolean, ctx: RenderContext) = perform {
        creating = false
        require(confirmed && page.webhooks.any { it.id == id }) {
            "Choose a webhook and confirm deletion."
        }
        require(
            ctx.gql.execute(GitUiDeleteWebhook, GitUiDeleteWebhook.Variables(id)).git.deleteWebhook
        ) {
            "Webhook was not deleted."
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
            message = e.message ?: "Webhook action failed."
        }
    }
}
