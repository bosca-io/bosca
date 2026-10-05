package bosca.graphql.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.application.install
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Drives [KtorGraphQLSubscriptionClient] end-to-end against an embedded Ktor CIO websocket server speaking
 * graphql-transport-ws (CIO client <-> CIO server is the canonical, reliable pairing). Every collection is
 * wrapped in [withTimeout] so a protocol bug fails fast rather than hanging the test task.
 */
class KtorGraphQLSubscriptionClientTest {

    /** ACK `connection_init` (capturing its raw text), then reply to `subscribe` with [framesFor] its id. */
    private suspend fun withServer(
        onInit: (String) -> Unit = {},
        framesFor: (id: String) -> List<String>,
        block: suspend (endpoint: String) -> Unit,
    ) {
        val server = embeddedServer(ServerCIO, port = 0) {
            install(ServerWebSockets)
            routing {
                webSocket("/graphql") {
                    for (frame in incoming) {
                        val text = (frame as? Frame.Text)?.readText() ?: continue
                        val obj = Json.parseToJsonElement(text).jsonObject
                        when (obj["type"]?.jsonPrimitive?.content) {
                            "connection_init" -> {
                                onInit(text)
                                send(Frame.Text("""{"type":"connection_ack"}"""))
                            }
                            "subscribe" -> {
                                send(Frame.Binary(true, byteArrayOf(0))) // a non-text frame the client must skip
                                framesFor(obj.getValue("id").jsonPrimitive.content).forEach { send(Frame.Text(it)) }
                            }
                        }
                    }
                }
            }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            block("ws://127.0.0.1:$port/graphql")
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 500)
        }
    }

    private fun client(endpoint: String, payload: suspend () -> JsonObject? = { null }) =
        KtorGraphQLSubscriptionClient(
            endpoint = endpoint,
            httpClient = HttpClient(CIO) { install(WebSockets) },
            connectionPayloadProvider = payload,
        )

    @Test
    fun `streams next frames for the operation id, sends auth payload, ponging and ignoring noise, then completes`() =
        runBlocking {
            var initPayload: String? = null
            withServer(
                onInit = { initPayload = it },
                framesFor = { id ->
                    listOf(
                        """{"type":"pong"}""",                                              // keep-alive pong (ignored)
                        """{"type":"surprise"}""",                                          // unknown type (ignored)
                        """{"type":"error","id":"other","payload":[{"message":"x"}]}""",    // different id -> not thrown
                        """{"type":"complete","id":"other"}""",                            // different id -> not ended
                        """{"type":"next","id":"$id","payload":{"data":{"tick":1}}}""",     // ours -> emitted
                        """{"type":"next","id":"other","payload":{"data":{"tick":9}}}""",   // different id -> dropped
                        """{"type":"ping"}""",                                              // server ping -> client pongs
                        """{"type":"next","id":"$id","payload":{"data":{"tick":2}}}""",     // ours -> emitted
                        """{"type":"complete","id":"$id"}""",                              // ours -> ends the flow
                    )
                },
            ) { endpoint ->
                val ticks = withTimeout(15_000.milliseconds) {
                    client(endpoint, payload = { buildJsonObject { put("Authorization", "Bearer t") } })
                        .subscribe("subscription S { tick }", null, "S")
                        .toList()
                }.map { it.data!!.jsonObject.getValue("tick").jsonPrimitive.content.toInt() }

                assertEquals(listOf(1, 2), ticks)
                assertTrue(initPayload!!.contains("Bearer t"), "connection_init should carry the auth payload")
            }
        }

    @Test
    fun `a subscription error frame throws GraphQLClientException`() = runBlocking {
        withServer(framesFor = { id -> listOf("""{"type":"error","id":"$id","payload":[{"message":"boom"}]}""") }) { endpoint ->
            // Construct without connectionPayloadProvider to exercise its default ({ null } -> payload-less init).
            val client = KtorGraphQLSubscriptionClient(endpoint, HttpClient(CIO) { install(WebSockets) })
            val ex = assertFailsWith<GraphQLClientException> {
                withTimeout(15_000.milliseconds) { client.subscribe("subscription S { tick }", null, "S").toList() }
            }
            assertEquals("boom", ex.errors.single().message)
        }
    }
}
