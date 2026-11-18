package bosca.atlas.server

import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import kotlin.test.assertEquals

/** Connects through the production UI relay using the browser's connection-init authentication. */
internal class AtlasLogSubscription(origin: String, token: String, query: String) : AutoCloseable {
    private val messages = LinkedBlockingQueue<JsonObject>()
    private val socket = HttpClient.newHttpClient().newWebSocketBuilder().subprotocols("graphql-transport-ws")
        .buildAsync(URI.create(origin.replaceFirst("http", "ws") + "/_git/subscriptions"), object : WebSocket.Listener {
            private val text = StringBuilder()
            override fun onOpen(webSocket: WebSocket) { webSocket.request(1) }
            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                text.append(data)
                if (last) {
                    messages.add(Json.parseToJsonElement(text.toString()).jsonObject)
                    text.setLength(0)
                }
                webSocket.request(1)
                return null
            }
        }).get(10, TimeUnit.SECONDS)

    init {
        send(buildJsonObject { put("type", "connection_init"); put("payload", buildJsonObject { put("authToken", token) }) })
        assertEquals("connection_ack", messages.poll(10, TimeUnit.SECONDS)?.get("type")?.jsonPrimitive?.content)
        send(buildJsonObject { put("id", "logs"); put("type", "subscribe"); put("payload", buildJsonObject { put("query", query) }) })
    }

    fun poll(): JsonObject? = messages.poll(100, TimeUnit.MILLISECONDS)
    private fun send(message: JsonObject) { socket.sendText(message.toString(), true).join() }
    override fun close() { socket.abort() }
}
