package bosca.ai.kit.harness.acp

import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.ContentBlock
import kotlinx.serialization.json.JsonObject

/** A claimed turn; the durable owner supplies correlation and persists events before returning. */
data class KitAcpTurn(
    val connection: KitAcpConnection,
    val session: ClientSession,
    val content: List<ContentBlock>,
    val correlation: JsonObject,
    val onEvent: suspend (Event) -> Unit,
)
