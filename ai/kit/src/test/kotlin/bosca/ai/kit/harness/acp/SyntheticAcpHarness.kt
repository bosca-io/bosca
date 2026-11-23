package bosca.ai.kit.harness.acp

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respond
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Wire-level fixture: deliberately independent of the ACP SDK server implementation. */
class SyntheticAcpHarness(
    private val load: Boolean = false,
    private val resume: Boolean = false,
    private val protocolVersion: Int = 1,
    private val failRestore: Boolean = false,
    private val httpMcp: Boolean = false,
    private val authMethods: List<String> = emptyList(),
) : AutoCloseable {
    val requests = CopyOnWriteArrayList<JsonObject>()
    val permissions = CopyOnWriteArrayList<JsonObject>()
    val promptStarted = CompletableDeferred<Unit>()
    val newSessions = AtomicInteger()
    var mode = "complete"
    var hostMethod = "fs/read_text_file"
    private val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
        install(WebSockets)
        routing {
            route("/acp") {
                install(createRouteScopedPlugin("RuntimeAuthentication") {
                    onCall { call ->
                        if (call.request.headers[HttpHeaders.Authorization] != "Bearer test-runtime-token") {
                            call.respond(HttpStatusCode.Unauthorized)
                        }
                    }
                })
                webSocket {
                    var pending: JsonElement? = null
                    var session = "opaque/session:1"
                    suspend fun response(id: JsonElement, body: JsonObject) {
                        send(Frame.Text(buildJsonObject {
                            put("jsonrpc", "2.0"); put("id", id); put("result", body)
                        }.toString()))
                    }
                    suspend fun chunk(text: String) {
                        send(Frame.Text(buildJsonObject {
                            put("jsonrpc", "2.0"); put("method", "session/update")
                            put("params", buildJsonObject {
                                put("sessionId", session)
                                put("update", buildJsonObject {
                                    put("sessionUpdate", "agent_message_chunk")
                                    put("content", buildJsonObject { put("type", "text"); put("text", text) })
                                })
                            })
                        }.toString()))
                    }
                    suspend fun terminal(reason: String) {
                        response(requireNotNull(pending), buildJsonObject { put("stopReason", reason) })
                        pending = null
                    }
                    for (frame in incoming) {
                        val request = Json.parseToJsonElement((frame as Frame.Text).readText()).jsonObject
                        val method = request["method"]?.jsonPrimitive?.content
                        if (method == null) {
                            permissions.add(request)
                            chunk(if (mode == "host") "after callback" else "after approval")
                            terminal("end_turn")
                            continue
                        }
                        requests.add(request)
                        val id = request["id"] ?: JsonNull
                        when (method) {
                            "initialize" -> response(id, buildJsonObject {
                                put("protocolVersion", protocolVersion)
                                put("authMethods", JsonArray(authMethods.map { method ->
                                    buildJsonObject { put("id", method); put("name", method) }
                                }))
                                put("agentCapabilities", buildJsonObject {
                                    put("loadSession", load)
                                    put("mcpCapabilities", buildJsonObject { put("http", httpMcp) })
                                    put("sessionCapabilities", buildJsonObject {
                                        if (resume) put("resume", JsonObject(emptyMap()))
                                    })
                                })
                            })
                            "authenticate" -> response(id, JsonObject(emptyMap()))
                            "session/new", "session/load", "session/resume" -> {
                                if (method != "session/new" && failRestore) {
                                    send(Frame.Text(buildJsonObject {
                                        put("jsonrpc", "2.0"); put("id", id)
                                        put("error", buildJsonObject { put("code", -32603); put("message", "Checkpoint unavailable") })
                                    }.toString()))
                                    continue
                                }
                                session = if (method == "session/new") "opaque/session:${newSessions.incrementAndGet()}"
                                    else request.getValue("params").jsonObject.getValue("sessionId").jsonPrimitive.content
                                response(id, if (method == "session/new") buildJsonObject { put("sessionId", session) }
                                    else JsonObject(emptyMap()))
                            }
                            "session/prompt" -> {
                                pending = id
                                promptStarted.complete(Unit)
                                chunk("before ")
                                when (mode) {
                                    "permission" -> send(Frame.Text("""{"jsonrpc":"2.0","id":"approval-1","method":"session/request_permission","params":{"sessionId":"$session","toolCall":{"toolCallId":"tool-1","title":"Read document","rawInput":{"id":"document-1"}},"options":[{"optionId":"allow","name":"Allow once","kind":"allow_once"},{"optionId":"deny","name":"Deny","kind":"reject_once"}]}}"""))
                                    "host" -> send(Frame.Text("""{"jsonrpc":"2.0","id":"host-1","method":"$hostMethod","params":{"sessionId":"$session"}}"""))
                                    "disconnect" -> { close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "fixture disconnect")); break }
                                    "malformed" -> send(Frame.Text("invalid JSON"))
                                    "binary" -> send(Frame.Binary(true, byteArrayOf(1, 2, 3)))
                                    "wait" -> Unit
                                    else -> terminal("end_turn")
                                }
                            }
                            "session/cancel" -> { chunk("drained"); terminal("cancelled") }
                        }
                    }
                }
            }
        }
    }.start(wait = false)

    suspend fun endpoint(): URI = URI("ws://127.0.0.1:${server.engine.resolvedConnectors().single().port}/acp")

    override fun close() { server.stop(0, 1_000) }
}
