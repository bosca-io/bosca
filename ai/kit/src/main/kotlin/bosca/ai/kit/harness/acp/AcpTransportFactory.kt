package bosca.ai.kit.harness.acp

import com.agentclientprotocol.transport.Transport
import kotlinx.coroutines.CoroutineScope

/** Opens one authenticated connection to a deployment-resolved runtime endpoint. */
fun interface AcpTransportFactory {
    /** Returns an unstarted transport owned by [scope]; implementations must not retry sent prompts. */
    suspend fun connect(scope: CoroutineScope): Transport
}
