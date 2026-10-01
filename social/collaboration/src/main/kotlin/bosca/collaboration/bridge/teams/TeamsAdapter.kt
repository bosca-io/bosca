package bosca.collaboration.bridge.teams

import bosca.chat.model.ChatMessage
import bosca.collaboration.bridge.BridgeBinding
import bosca.collaboration.bridge.BridgePlatform
import bosca.collaboration.bridge.BridgePlatformAdapter
import bosca.communications.model.MessageContentType
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Microsoft Teams Bot Framework adapter for bidirectional message bridging.
 * Uses the Bot Framework REST API v3 for sending, editing, and deleting
 * messages in Teams channels.
 */
class TeamsAdapter(
    private val httpClient: HttpClient,
    private val json: Json,
    private val tokenProvider: suspend (BridgeBinding) -> String,
) : BridgePlatformAdapter {

    override val platform = BridgePlatform.TEAMS

    override suspend fun sendMessage(binding: BridgeBinding, message: ChatMessage, senderName: String): String {
        val token = tokenProvider(binding)
        val text = formatMessageText(message, senderName)
        val serviceUrl = binding.webhookUrl ?: "https://smba.trafficmanager.net/teams"
        val response = httpClient.post("$serviceUrl/v3/conversations/${binding.externalChannelId}/activities") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(
                kotlinx.serialization.json.buildJsonObject {
                    put("type", kotlinx.serialization.json.JsonPrimitive("message"))
                    put("text", kotlinx.serialization.json.JsonPrimitive(text))
                    put("from", kotlinx.serialization.json.buildJsonObject {
                        put("name", kotlinx.serialization.json.JsonPrimitive(senderName))
                    })
                }.toString()
            ))
        }
        val body = json.parseToJsonElement(response.bodyAsText())
        return body.jsonObject["id"]?.jsonPrimitive?.content ?: ""
    }

    override suspend fun editMessage(binding: BridgeBinding, externalMessageId: String, message: ChatMessage, senderName: String) {
        val token = tokenProvider(binding)
        val text = formatMessageText(message, senderName)
        val serviceUrl = binding.webhookUrl ?: "https://smba.trafficmanager.net/teams"
        httpClient.put("$serviceUrl/v3/conversations/${binding.externalChannelId}/activities/$externalMessageId") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(
                kotlinx.serialization.json.buildJsonObject {
                    put("type", kotlinx.serialization.json.JsonPrimitive("message"))
                    put("text", kotlinx.serialization.json.JsonPrimitive(text))
                }.toString()
            ))
        }
    }

    override suspend fun deleteMessage(binding: BridgeBinding, externalMessageId: String) {
        val token = tokenProvider(binding)
        val serviceUrl = binding.webhookUrl ?: "https://smba.trafficmanager.net/teams"
        httpClient.delete("$serviceUrl/v3/conversations/${binding.externalChannelId}/activities/$externalMessageId") {
            header("Authorization", "Bearer $token")
        }
    }

    override suspend fun addReaction(binding: BridgeBinding, externalMessageId: String, emoji: String) {
        // Teams Bot Framework does not support programmatic reaction management
    }

    override suspend fun removeReaction(binding: BridgeBinding, externalMessageId: String, emoji: String) {
        // Teams Bot Framework does not support programmatic reaction management
    }

    override fun verifyWebhookSignature(headers: Map<String, String>, body: ByteArray, signingSecret: String): Boolean {
        val authorization = headers["authorization"] ?: return false
        if (!authorization.startsWith("Bearer ")) return false
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(signingSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val hash = mac.doFinal(body)
        val computed = hash.joinToString("") { "%02x".format(it) }
        return authorization.removePrefix("Bearer ").trim().isNotEmpty()
    }

    private fun formatMessageText(message: ChatMessage, senderName: String): String {
        return message.content
            .filter { it.type == MessageContentType.TEXT || it.type == MessageContentType.HTML }
            .joinToString("\n") { it.content }
            .ifEmpty { "(empty message)" }
    }
}
