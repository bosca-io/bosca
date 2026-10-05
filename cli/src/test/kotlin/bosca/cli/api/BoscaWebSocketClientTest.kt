package bosca.cli.api

import bosca.graphql.client.GraphQLClientException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Drives [BoscaWebSocketClient] against a MockWebServer WebSocket speaking graphql-transport-ws. */
class BoscaWebSocketClientTest {

    private val http = OkHttpClient()

    /** A server that ACKs connection_init, then on subscribe replies with the given server frames (raw JSON). */
    private fun server(framesForSubscribe: (id: String) -> List<String>): MockWebServer {
        val server = MockWebServer()
        server.enqueue(
            MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val obj = Json.parseToJsonElement(text).jsonObject
                    when (obj["type"]?.jsonPrimitive?.content) {
                        "connection_init" -> webSocket.send("""{"type":"connection_ack"}""")
                        "subscribe" -> {
                            framesForSubscribe(obj.getValue("id").jsonPrimitive.content).forEach { webSocket.send(it) }
                            webSocket.close(1000, null) // let MockWebServer shut down cleanly
                        }
                    }
                }
            }).build(),
        )
        server.start()
        return server
    }

    @Test
    fun `streams next frames then completes`() = runBlocking {
        val server = server { id ->
            listOf(
                """{"type":"next","id":"$id","payload":{"data":{"tick":1}}}""",
                """{"type":"next","id":"$id","payload":{"data":{"tick":2}}}""",
                """{"type":"complete","id":"$id"}""",
            )
        }
        try {
            val client = BoscaWebSocketClient(server.url("/graphql").toString(), http, token = { null })
            val frames = client.subscribe("subscription S { tick }", null, "S").toList()
            assertEquals(listOf(1, 2), frames.map { it.data!!.jsonObject.getValue("tick").jsonPrimitive.content.toInt() })
        } finally {
            server.close()
        }
    }

    @Test
    fun `an error frame fails the flow`() = runBlocking {
        val server = server { id -> listOf("""{"type":"error","id":"$id","payload":[{"message":"boom"}]}""") }
        try {
            val client = BoscaWebSocketClient(server.url("/graphql").toString(), http, token = { "tok" })
            val ex = assertFailsWith<GraphQLClientException> {
                client.subscribe("subscription S { tick }", null, "S").toList()
            }
            assertTrue(ex.message!!.contains("boom"))
        } finally {
            server.close()
        }
    }
}
