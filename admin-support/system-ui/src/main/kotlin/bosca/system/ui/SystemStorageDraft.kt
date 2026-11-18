package bosca.system.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class SystemStorageDraft(
    val name: String = "", val description: String = "", val type: String = "SEARCH",
    val configuration: String = "{}", val models: List<SystemStorageModelDraft> = emptyList(),
) {
    val modelsJson: String get() = Json.encodeToString(ListSerializer(SystemStorageModelDraft.serializer()), models)
}
