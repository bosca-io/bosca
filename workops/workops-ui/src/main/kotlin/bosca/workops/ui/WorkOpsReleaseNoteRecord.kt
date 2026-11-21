package bosca.workops.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Raw variants remain editable when a stored value cannot be rendered as localized notes. */
@Serializable
data class WorkOpsReleaseNoteRecord(
    val id: String,
    val versionId: String,
    val sourceLocale: String,
    val generated: String,
    val manuallyEdited: Boolean,
    val variants: JsonElement,
    val localized: List<WorkOpsReleaseNoteVariant> = emptyList(),
    val variantError: String = "",
) {
    fun parsed(): WorkOpsReleaseNoteRecord = try {
        copy(localized = Json { ignoreUnknownKeys = true }.decodeFromJsonElement(ListSerializer(WorkOpsReleaseNoteVariant.serializer()), variants), variantError = "")
    } catch (e: SerializationException) {
        copy(localized = emptyList(), variantError = e.message ?: "Stored note variants could not be read.")
    }
}
