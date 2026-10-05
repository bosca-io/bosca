package bosca.analytics.delivery

import bosca.analytics.api.Events
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import kotlinx.serialization.json.Json

internal class BoscaEventSender(
    private val config: BoscaSinkConfig,
    private val client: HttpClient,
) {
    suspend fun send(events: Events) {
        val response = client.post("${config.url.trimEnd('/')}/events") {
            header(BoscaRequestHeaders.INSTALLATION_ID, events.context.device.installationId)
            header(BoscaRequestHeaders.APP_ID, config.appId)
            header(BoscaRequestHeaders.APP_VERSION, config.appVersion)
            header(BoscaRequestHeaders.SESSION_ID, events.context.sessionId)
            setBody(TextContent(JSON.encodeToString(Events.serializer(), events), ContentType.Application.Json))
        }
        check(response.status.value == 200 || response.status.value == 202) {
            "Analytics delivery failed with HTTP ${response.status.value}: ${response.bodyAsText()}"
        }
    }

    private companion object {
        val JSON = Json { encodeDefaults = true; explicitNulls = true }
    }
}
