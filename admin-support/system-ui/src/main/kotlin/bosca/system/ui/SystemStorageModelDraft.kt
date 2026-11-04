package bosca.system.ui

import kotlinx.serialization.Serializable

@Serializable
data class SystemStorageModelDraft(
    val key: String = java.util.UUID.randomUUID().toString(), val modelId: String = "", val configuration: String = "{}",
)
