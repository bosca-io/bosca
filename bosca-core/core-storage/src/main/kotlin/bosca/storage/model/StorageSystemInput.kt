package bosca.storage.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class StorageSystemInput(
    val name: String,
    val description: String,
    val type: StorageSystemType,
    @Contextual
    val configuration: JsonElement,
    val models: List<StorageSystemModelInput>
)

@Serializable
data class StorageSystemModelInput(
    @Contextual
    val modelId: UUID,
    @Contextual
    val configuration: JsonElement
)
