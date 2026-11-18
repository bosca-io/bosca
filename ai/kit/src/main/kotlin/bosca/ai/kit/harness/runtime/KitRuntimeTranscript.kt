package bosca.ai.kit.harness.runtime

import bosca.ai.chat.model.ChatHistoryMessage
import bosca.ai.kit.harness.KitHarnessTurnEvent
import bosca.ai.kit.harness.KitRuntimeSession
import bosca.ai.kit.harness.KitRuntimeTurn
import bosca.ai.kit.harness.acp.CodexAcpToolContent
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallContent
import kotlinx.serialization.json.*

/** One stable display row per turn. Raw tool arguments, hidden reasoning and credentials are not display fields. */
internal class KitRuntimeTranscript(
    private val session: KitRuntimeSession,
    private val turn: KitRuntimeTurn,
    private val json: Json,
) {
    private val text = StringBuilder()
    private val tools = linkedMapOf<String, JsonObject>()
    private val content = mutableListOf<JsonElement>()
    private var plan = JsonArray(emptyList())
    private var revision = 0L
    private var lastFlush = Long.MIN_VALUE
    private var dirty = false

    val responseText: String get() = text.toString()

    fun accept(update: SessionUpdate) {
        when (update) {
            is SessionUpdate.AgentMessageChunk -> when (val block = update.content) {
                is ContentBlock.Text -> text.append(block.text)
                else -> content.add(json.encodeToJsonElement(ContentBlock.serializer(), block))
            }
            is SessionUpdate.ToolCall, is SessionUpdate.ToolCallUpdate -> {
                val value = json.encodeToJsonElement(SessionUpdate.serializer(), update).jsonObject
                val id = value.getValue("toolCallId").jsonPrimitive.content
                val fields = value.filter { (key, value) -> key in setOf("toolCallId", "title", "kind", "status", "content", "locations") && value != JsonNull }.toMutableMap()
                CodexAcpToolContent.extract(value["rawInput"], value["rawOutput"])?.let { blocks ->
                    val native = (fields["content"] as? JsonArray).orEmpty()
                    fields["content"] = JsonArray((native + blocks.map {
                        json.encodeToJsonElement(ToolCallContent.serializer(), it)
                    }).distinct())
                }
                tools[id] = JsonObject(tools[id].orEmpty() + fields)
            }
            is SessionUpdate.PlanUpdate -> plan = json.encodeToJsonElement(SessionUpdate.serializer(), update).jsonObject.getValue("entries").jsonArray
            else -> return
        }
        revision++
        dirty = true
    }

    fun snapshot(status: String = "STREAMING", force: Boolean = false): ChatHistoryMessage? {
        val now = System.nanoTime()
        if (!force && (!dirty || lastFlush != Long.MIN_VALUE && now - lastFlush < 250_000_000)) return null
        lastFlush = now
        dirty = false
        revision++
        return ChatHistoryMessage(id = turn.id, sessionId = session.sessionId, author = "kit",
            event = json.encodeToJsonElement(KitHarnessTurnEvent.serializer(), KitHarnessTurnEvent(
                turnId = turn.id.toString(), generation = session.generation, epoch = session.epoch, revision = revision,
                status = status, text = if (status == "STREAMING") text.toString() else "", plan = plan,
                tools = tools.values.toList(), content = content.toList())))
    }
}
