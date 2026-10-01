package bosca.ai.chat.model

import bosca.analytics.model.AnalyticsVisualizationType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Sealed hierarchy representing all event types that can be sent from the server
 * to the client during a chat session. Each subclass maps to a `type` discriminator
 * used for routing on the client side.
 *
 * Events are serialized with a `type` discriminator field so the client can dispatch
 * on the event type without deserializing the full payload first.
 */
@Serializable
sealed interface ChatEvent {
    val type: String
}

/** Signals the start of a new assistant message in the stream. */
@Serializable
@SerialName("start")
data class MessageStartEvent(
    override val type: String = "start",
    val messageId: String,
) : ChatEvent

/** Signals the end of the current assistant message. */
@Serializable
@SerialName("finish")
data class MessageFinishEvent(
    override val type: String = "finish",
) : ChatEvent

/** Signals the start of a reasoning/thinking block. */
@Serializable
@SerialName("reasoning-start")
data class ReasoningStartEvent(
    override val type: String = "reasoning-start",
    val id: String,
) : ChatEvent

/** An incremental chunk of reasoning/thinking content. */
@Serializable
@SerialName("reasoning-delta")
data class ReasoningDeltaEvent(
    override val type: String = "reasoning-delta",
    val id: String,
    val delta: String,
) : ChatEvent

/** Signals the end of a reasoning/thinking block. */
@Serializable
@SerialName("reasoning-end")
data class ReasoningEndEvent(
    override val type: String = "reasoning-end",
    val id: String,
) : ChatEvent

/** Signals the start of a text content block. */
@Serializable
@SerialName("text-start")
data class TextStartEvent(
    override val type: String = "text-start",
    val id: String,
) : ChatEvent

/** An incremental chunk of text content. */
@Serializable
@SerialName("text-delta")
data class TextDeltaEvent(
    override val type: String = "text-delta",
    val id: String,
    val delta: String,
) : ChatEvent

/** Signals the end of a text content block. */
@Serializable
@SerialName("text-end")
data class TextEndEvent(
    override val type: String = "text-end",
    val id: String,
) : ChatEvent

/**
 * A confirmation or choice request sent to the client. The user's selection
 * is submitted as their next chat message. The agent does not block — it
 * finishes the current turn and waits for the user's response.
 */
@Serializable
@SerialName("tool-request")
data class ToolRequestEvent(
    override val type: String = "tool-request",
    val requestId: String,
    val title: String,
    val message: String,
    val options: List<ToolRequestOption>,
) : ChatEvent

/** A single selectable option within a [ToolRequestEvent]. */
@Serializable
data class ToolRequestOption(
    /** Unique key returned when this option is selected */
    val key: String,
    /** Display label shown in the UI */
    val label: String,
    /** Optional description providing additional context for this option */
    val description: String? = null,
)

/**
 * A visualization display event sent to the client for chart/table rendering.
 * Uses the existing VisualizationFactory infrastructure on the client side.
 * Non-interactive — no response expected.
 */
@Serializable
@SerialName("tool-display")
data class ToolDisplayEvent(
    override val type: String = "tool-display",
    val requestId: String,
    val title: String,
    val visualizationType: AnalyticsVisualizationType,
    /** Type-specific configuration matching VisualizationFactory format */
    val configuration: JsonElement,
    /** Row data to visualize */
    val data: List<JsonObject>,
    /** The SQL statement that produced [data], when the visualization is sourced from a query — shown to the user as the answer's source. */
    val sourceQuery: String? = null,
    /** Saved analytics artifacts created or updated during this turn. */
    val savedQueryId: String? = null,
    val savedQueryKey: String? = null,
    val visualizationId: String? = null,
    val dashboardId: String? = null,
    /** Ordered, payload-free provenance assembled from recorded tool executions. */
    val investigation: List<AnalyticsInvestigationStep> = emptyList(),
) : ChatEvent
