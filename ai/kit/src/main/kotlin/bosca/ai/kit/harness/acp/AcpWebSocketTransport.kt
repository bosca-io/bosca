package bosca.ai.kit.harness.acp

import com.agentclientprotocol.rpc.ACPJson
import com.agentclientprotocol.rpc.JsonRpcMessage
import com.agentclientprotocol.rpc.decodeJsonRpcMessage
import com.agentclientprotocol.transport.BaseTransport
import com.agentclientprotocol.transport.Transport.State
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Bosca's custom ACP transport: exactly one JSON-RPC message per WebSocket text frame. */
class AcpWebSocketTransport(
    private val scope: CoroutineScope,
    private val socket: WebSocketSession,
) : BaseTransport() {
    private val outgoing = Channel<JsonRpcMessage>(64)
    private var job: Job? = null

    override fun start() {
        check(_state.compareAndSet(State.CREATED, State.STARTING)) { "ACP transport already started" }
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                coroutineScope {
                    _state.value = State.STARTED
                    val writer = launch {
                        for (message in outgoing) {
                            socket.send(Frame.Text(ACPJson.encodeToString(JsonRpcMessage.serializer(), message)))
                        }
                    }
                    try {
                        for (frame in socket.incoming) {
                            check(frame is Frame.Text) { "ACP requires WebSocket text frames" }
                            fireMessage(decodeJsonRpcMessage(frame.readText()))
                        }
                    } finally {
                        writer.cancel()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                fireError(failure)
            } finally {
                _state.value = State.CLOSING
                outgoing.close()
                socket.cancel()
                _state.value = State.CLOSED
                fireClose()
            }
        }
    }

    override fun send(message: JsonRpcMessage) {
        if (state.value != State.STARTING && state.value != State.STARTED) {
            throw CancellationException("ACP transport is closed")
        }
        val sent = outgoing.trySend(message)
        if (sent.isClosed) throw CancellationException("ACP transport is closed", sent.exceptionOrNull())
        if (sent.isFailure) {
            val failure = IllegalStateException("ACP transport output queue is unavailable")
            fireError(failure)
            close()
            throw failure
        }
    }

    override fun close() {
        if (_state.value == State.CLOSED || _state.value == State.CLOSING) return
        _state.value = State.CLOSING
        outgoing.close()
        socket.cancel()
        val activeJob = job
        if (activeJob == null) {
            _state.value = State.CLOSED
            fireClose()
        } else {
            activeJob.cancel()
        }
    }
}
