package bosca.cli.api

import bosca.ai.kit.harness.KitHarnessTurnEvent
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.Role
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallContent
import kotlinx.serialization.json.*

private val harnessJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

/** Reconstructs display updates, with tool IDs scoped to the durable Bosca turn. Never forwards opaque tool traffic. */
internal fun KitHarnessTurnEvent.acpUpdates(): List<SessionUpdate> = buildList {
    if (plan.isNotEmpty()) add(harnessJson.decodeFromJsonElement(SessionUpdate.serializer(), buildJsonObject {
        put("sessionUpdate", "plan"); put("entries", withoutHarnessMetadata(plan))
    }))
    tools.forEach { tool ->
        val fields = tool.filterKeys { it in setOf("toolCallId", "title", "kind", "status", "content", "locations") }
        val update = harnessJson.decodeFromJsonElement(SessionUpdate.serializer(), withoutHarnessMetadata(buildJsonObject {
            fields.forEach(::put)
            put("sessionUpdate", "tool_call")
            put("title", (tool["title"] as? JsonPrimitive)?.contentOrNull ?: "Tool operation")
            put("toolCallId", "$turnId/${tool.getValue("toolCallId").jsonPrimitive.content}")
        })) as SessionUpdate.ToolCall
        add(update.copy(content = update.content.filter { it !is ToolCallContent.Content || it.content.visibleToUser() }))
    }
    content.forEach { value ->
        val block = harnessJson.decodeFromJsonElement(ContentBlock.serializer(), withoutHarnessMetadata(value))
        if (block.visibleToUser()) add(SessionUpdate.AgentMessageChunk(block))
    }
}

/** Content plus its optional tool-call correlation, suitable for non-ACP clients. */
internal fun KitHarnessTurnEvent.resultContent(): List<Pair<String?, ToolCallContent>> = acpUpdates().flatMap { update ->
    when (update) {
        is SessionUpdate.ToolCall -> update.content.map { update.toolCallId.value to it }
        is SessionUpdate.AgentMessageChunk -> listOf(null to ToolCallContent.Content(update.content))
        else -> emptyList()
    }
}

/** Payload-free turn and tool provenance shared by inbound protocol adapters. */
internal fun KitHarnessTurnEvent.summaryJson(): JsonObject = buildJsonObject {
    put("turnId", turnId); put("generation", generation); put("epoch", epoch); put("revision", revision); put("status", status)
    put("plan", withoutHarnessMetadata(plan))
    put("tools", JsonArray(acpUpdates().filterIsInstance<SessionUpdate.ToolCall>().map { tool ->
        buildJsonObject {
            put("toolCallId", tool.toolCallId.value); put("title", tool.title)
            tool.kind?.let { put("kind", it.name.lowercase()) }
            tool.status?.let { put("status", it.name.lowercase()) }
        }
    }))
}

/** Full display event for explicit history reads, retaining only the same typed fields forwarded to ACP. */
internal fun KitHarnessTurnEvent.displayJson(): JsonObject {
    val updates = acpUpdates()
    return harnessJson.encodeToJsonElement(KitHarnessTurnEvent.serializer(), copy(
        plan = withoutHarnessMetadata(plan) as JsonArray,
        tools = updates.filterIsInstance<SessionUpdate.ToolCall>().map {
            JsonObject(harnessJson.encodeToJsonElement(SessionUpdate.serializer(), it).jsonObject.filterKeys {
                key -> key in setOf("toolCallId", "title", "kind", "status", "content", "locations")
            })
        },
        content = updates.filterIsInstance<SessionUpdate.AgentMessageChunk>().map {
            harnessJson.encodeToJsonElement(ContentBlock.serializer(), it.content)
        },
    )).jsonObject
}

private fun ContentBlock.visibleToUser() = annotations?.audience?.let { Role.USER in it } != false

private fun withoutHarnessMetadata(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.filterKeys { it != "_meta" }.mapValues { withoutHarnessMetadata(it.value) })
    is JsonArray -> JsonArray(value.map(::withoutHarnessMetadata))
    else -> value
}
