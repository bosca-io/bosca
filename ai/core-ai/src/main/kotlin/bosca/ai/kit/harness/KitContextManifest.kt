package bosca.ai.kit.harness

import kotlinx.serialization.Serializable

/** Compact audit of one context handoff. Character budgets count UTF-16 code units, not model tokens. */
@Serializable
data class KitContextManifest(
    val revision: String,
    val instructionRevision: String,
    val agentId: String?,
    val promptId: String?,
    val sources: List<KitContextSource>,
    val integrations: List<String> = listOf("bosca"),
    val omissions: List<String> = emptyList(),
    val budgetCharacters: Int,
    val usedCharacters: Int,
    val historyMessages: Int = 0,
    val format: String = "text",
)
