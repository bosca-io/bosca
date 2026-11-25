package bosca.ai.kit.harness.acp

import com.agentclientprotocol.rpc.decodeJsonRpcMessage
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class AcpWebSocketTransportTest {
    @Test
    fun `closed transport cancels late callbacks but a full live queue remains a loud failure`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val socket = mockk<WebSocketSession> {
            every { incoming } returns Channel<Frame>()
            every { coroutineContext } returns scope.coroutineContext
        }
        val transport = AcpWebSocketTransport(scope, socket)
        val message = decodeJsonRpcMessage("""{"jsonrpc":"2.0","method":"session/cancel","params":{"sessionId":"test"}}""")
        try {
            transport.start()
            repeat(64) { transport.send(message) }
            assertFailsWith<IllegalStateException> { transport.send(message) }
            assertFailsWith<CancellationException> { transport.send(message) }
        } finally {
            transport.close()
            scope.cancel()
        }
    }
}
