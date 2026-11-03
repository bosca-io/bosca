package bosca.storage.model

import bosca.serialization.UUID
import bosca.db.annotation.ColumnName
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class StorageSystem(
    @Contextual
    val id: UUID = UUID.NIL,
    val name: String,
    val description: String,
    val type: StorageSystemType,
    @Contextual
    val configuration: JsonElement,
)

@Serializable
data class StorageSystemModel(
    @Contextual
    @ColumnName("system_id")
    val systemId: UUID,
    @Contextual
    @ColumnName("model_id")
    val modelId: UUID,
    @Contextual
    val configuration: JsonElement
)
