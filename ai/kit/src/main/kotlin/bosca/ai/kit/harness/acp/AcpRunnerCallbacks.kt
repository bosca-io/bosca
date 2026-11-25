package bosca.ai.kit.harness.acp

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.jsonRpcMethodNotFound

/** Runner handles durable permissions and notifications; host execution stays inside the runtime. */
@OptIn(UnstableApi::class)
internal fun Protocol.rejectHostCallbacks() {
    for (method in listOf(
        AcpMethod.ClientMethods.FsReadTextFile,
        AcpMethod.ClientMethods.FsWriteTextFile,
        AcpMethod.ClientMethods.TerminalCreate,
        AcpMethod.ClientMethods.TerminalOutput,
        AcpMethod.ClientMethods.TerminalRelease,
        AcpMethod.ClientMethods.TerminalWaitForExit,
        AcpMethod.ClientMethods.TerminalKill,
        AcpMethod.ClientMethods.ElicitationCreate,
    )) {
        setRequestHandlerRaw(method) {
            jsonRpcMethodNotFound("Callback is not supported by Bosca Runner")
        }
    }
}
