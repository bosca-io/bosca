@file:OptIn(ExperimentalSerializationApi::class)

package bosca.ai.kit.harness

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Coalesced display state; the final conversational response is persisted separately.
 * [type] is always encoded because clients select harness rows by it and platform Json omits defaults.
 */
@Serializable
data class KitHarnessTurnEvent(
    @EncodeDefault val type: String = "harness-turn",
    val turnId: String,
    val generation: Long,
    val epoch: Long,
    val revision: Long,
    val status: String,
    val text: String,
    val plan: JsonArray = JsonArray(emptyList()),
    val tools: List<JsonObject> = emptyList(),
    val content: List<JsonElement> = emptyList(),
)
