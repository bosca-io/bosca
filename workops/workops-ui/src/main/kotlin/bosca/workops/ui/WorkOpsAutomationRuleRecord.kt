package bosca.workops.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** An inspected automation rule doubles as an independent raw editor draft. */
@Serializable
data class WorkOpsAutomationRuleRecord(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val enabled: Boolean = true,
    val scope: String = "GLOBAL",
    val scopeId: String = "",
    val trigger: String = "{\"type\":\"TaskCreated\"}",
    val conditions: String = "[]",
    val actions: String = "[]",
    val failureMode: String = "STOP_ON_ERROR",
    val fires: String = "5",
    val retention: String = "30",
    val runAsId: String = "",
    val version: Long = 0,
) {
    val triggerType get() = ((runCatching { Json.parseToJsonElement(trigger) }.getOrNull() as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull.orEmpty()
}
