@file:OptIn(ExperimentalUuidApi::class)

package bosca.localization.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi

/**
 * An export format configured for a [LocalizationProject]. A project may target
 * multiple platforms (Android, iOS, web) and therefore declare multiple formats
 * so the UI can offer one-click export for each.
 */
@Serializable
data class LocalizationProjectFormat(
    @ColumnName("project_id")
    @Contextual
    val projectId: UUID,
    val format: String,
    @Contextual
    val created: OffsetDateTime? = null
)
