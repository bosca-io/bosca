@file:OptIn(ExperimentalUuidApi::class)

package bosca.localization.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi

/**
 * Request payload for creating or editing a [LocalizationString].
 *
 * [metadataId] optionally links the string to a metadata item so its supplementary
 * items (screenshots, mockups) are surfaced as context to translators in the admin UI.
 */
@Serializable
data class LocalizationStringInput(
    @Contextual
    val projectId: UUID,
    val key: String,
    val context: String? = null,
    @Contextual
    val metadataId: UUID? = null,
    val placeholders: List<LocalizationPlaceholder>? = null,
    val maxLength: Int? = null,
    val tags: List<String> = emptyList(),
    val plural: Boolean = false
)
