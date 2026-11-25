package bosca.ai.kit.harness.acp

import com.agentclientprotocol.client.ClientSession

data class AcpSessionSelection(
    val session: ClientSession,
    val continuity: AcpSessionContinuity,
)
