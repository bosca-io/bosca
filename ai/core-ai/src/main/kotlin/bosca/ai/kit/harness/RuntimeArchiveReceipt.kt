package bosca.ai.kit.harness

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class RuntimeArchiveReceipt(
    val sha256: String,
    val size: Long,
    val formatVersion: Int,
    val metadata: JsonObject,
    val policy: String,
)
