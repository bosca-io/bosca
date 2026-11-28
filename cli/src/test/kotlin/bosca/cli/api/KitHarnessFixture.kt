package bosca.cli.api

import bosca.graphql.client.GraphQLJson
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlinx.serialization.json.*

/** GraphQL boundary fixture: final rich content precedes the session's processing-status update. */
internal class KitHarnessFixture(
    private val outcome: String = "COMPLETED",
    private val waitForCancellation: Boolean = false,
    private val acceptCancellation: Boolean = true,
    val submissionGate: CountDownLatch = CountDownLatch(0),
) : AutoCloseable {
    val operations = CopyOnWriteArrayList<String>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private var sent = false
    private var polls = 0
    private var cancellationRequested = false
    private var cancellationPolls = 0
    @Volatile var confirmCancellation = true
    val event: JsonObject = GraphQLJson.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/kit/harness-turn.json"))
        .bufferedReader().use { it.readText() }).jsonObject
    val url get() = "http://127.0.0.1:${server.address.port}/graphql"
    fun api() = KitApi(NetworkClient(url), pollIntervalMillis = 1)

    init {
        server.createContext("/graphql") { exchange ->
            val request = GraphQLJson.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
            val operation = request.getValue("operationName").jsonPrimitive.content
            operations += operation
            val result = when (operation) {
                "CreateKitChatSession" -> buildJsonObject { put("create", session(false)) }
                "SendKitChatMessage" -> {
                    submissionGate.await()
                    sent = true; polls = 0
                    buildJsonObject { put("send", true) }
                }
                "CancelKitChatSession" -> {
                    cancellationRequested = acceptCancellation
                    buildJsonObject { put("cancel", acceptCancellation) }
                }
                "GetKitChatSession" -> buildJsonObject { put("session", session(sent)) }
                else -> error("Unexpected operation: $operation")
            }
            val bytes = buildJsonObject { putJsonObject("data") { put("chatSessions", result) } }.toString().encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    private fun session(afterSend: Boolean): JsonObject = buildJsonObject {
        put("id", SESSION_ID); put("agentKey", "kit"); put("title", "Rich results")
        put("processing", afterSend); put("status", if (afterSend) "STREAMING" else "COMPLETED")
        put("created", "2026-10-23T00:00:00Z"); put("modified", "2026-10-23T00:00:00Z")
        putJsonArray("messages") {
            if (afterSend) {
                polls++
                if (cancellationRequested) cancellationPolls++
                val completed = if (waitForCancellation) cancellationRequested && confirmCancellation && cancellationPolls >= 2 else polls > 1
                add(buildJsonObject {
                    put("id", TURN_ID); put("sessionId", SESSION_ID); put("author", "kit"); put("created", "2026-10-23T00:00:00Z")
                    put("event", JsonObject(event + ("status" to JsonPrimitive(if (completed) outcome else "STREAMING"))))
                })
            }
        }
    }

    override fun close() { submissionGate.countDown(); server.stop(0) }

    companion object {
        const val SESSION_ID = "11111111-1111-1111-1111-111111111111"
        const val TURN_ID = "33333333-3333-3333-3333-333333333333"
    }
}
