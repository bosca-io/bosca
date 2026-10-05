@file:OptIn(ExperimentalUuidApi::class)

package bosca.localization.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.uuid.ExperimentalUuidApi

/**
 * Request payload for linking an existing metadata item to a [LocalizationProject]
 * as a translatable document.
 */
@Serializable
data class LocalizationProjectDocumentInput(
    @Contextual
    val projectId: UUID,
    @Contextual
    val metadataId: UUID,
    @Contextual
    val attributes: JsonElement? = null
)
