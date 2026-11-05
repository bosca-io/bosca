package bosca.ai.kit.harness

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class RuntimeTurnReceipt(val complete: Boolean, val messages: List<JsonObject>)
