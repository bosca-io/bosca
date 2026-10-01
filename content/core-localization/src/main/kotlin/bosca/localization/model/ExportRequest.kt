@file:OptIn(ExperimentalUuidApi::class)

package bosca.localization.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi

/**
 * Parameters for exporting translations of a single language from a project.
 *
 * [statesFilter] controls which translation states are included; when omitted,
 * exporters default to [TranslationState.PUBLISHED] so production builds never
 * ship unreviewed work. Development builds can broaden this to include
 * [TranslationState.APPROVED] or all states.
 */
@Serializable
data class ExportRequest(
    @Contextual
    val projectId: UUID,
    val languageTag: String,
    val format: ExportFormat,
    val statesFilter: List<TranslationState>? = null
)
