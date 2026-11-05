package bosca.cli.mcp

import bosca.cli.api.KitTurnControl
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.*
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

/** Keeps tool calls off the reader; Kit asks retain their durable cancellation handling. */
internal class KitMcpTransport(private val delegate: Transport, private val scope: CoroutineScope) : Transport by delegate {
    private val turns = ConcurrentHashMap<RequestId, KitTurnControl>()
    private val operations = ConcurrentHashMap<RequestId, Job>()

    override fun onMessage(block: suspend (JSONRPCMessage) -> Unit) {
        delegate.onMessage { message ->
            when {
                message is JSONRPCNotification && message.method == "notifications/cancelled" -> {
                    val params = McpJson.decodeFromJsonElement(CancelledNotificationParams.serializer(), requireNotNull(message.params))
                    turns[params.requestId]?.requestCancellation()
                    operations[params.requestId]?.cancel()
                }
                message is JSONRPCRequest && message.isKitAsk() -> {
                    val control = KitTurnControl()
                    if (operations.containsKey(message.id) || turns.putIfAbsent(message.id, control) != null) {
                        delegate.send(JSONRPCError(message.id, RPCError(RPCError.ErrorCode.INVALID_REQUEST, "Request ID is already active")))
                    } else {
                        scope.launch(control) {
                            try { block(message) } finally {
                                turns.remove(message.id, control)
                                control.finish()
                            }
                        }
                    }
                }
                message is JSONRPCRequest && message.method == "tools/call" -> {
                    val request = scope.launch(start = CoroutineStart.LAZY) {
                        try { block(message) } finally { operations.remove(message.id) }
                    }
                    if (turns.containsKey(message.id) || operations.putIfAbsent(message.id, request) != null) {
                        request.cancel()
                        delegate.send(JSONRPCError(message.id, RPCError(RPCError.ErrorCode.INVALID_REQUEST, "Request ID is already active")))
                    } else request.start()
                }
                else -> block(message)
            }
        }
    }

    override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) {
        val id = when (message) {
            is JSONRPCResponse -> message.id
            is JSONRPCError -> message.id
            else -> null
        }
        // Suppress cancelled calls; cancellation does not establish the remote operation's outcome.
        if (id != null && (turns[id]?.cancellationRequested == true || operations[id]?.isCancelled == true)) return
        delegate.send(message, options)
    }

    private fun JSONRPCRequest.isKitAsk(): Boolean {
        if (method != "tools/call") return false
        val call = params as? JsonObject ?: return false
        return (call["name"] as? JsonPrimitive)?.contentOrNull == "kit_chat" &&
            (((call["arguments"] as? JsonObject)?.get("action")) as? JsonPrimitive)?.contentOrNull == "ask"
    }
}
