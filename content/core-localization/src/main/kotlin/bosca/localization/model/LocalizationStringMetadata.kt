@file:OptIn(ExperimentalUuidApi::class)

package bosca.localization.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi

/**
 * Links a [LocalizationString] to a metadata item for translator context
 * (screenshots, mockups, reference images). A string can have multiple
 * metadata items attached.
 */
@Serializable
data class LocalizationStringMetadata(
    @ColumnName("string_id")
    @Contextual
    val stringId: UUID,
    @ColumnName("metadata_id")
    @Contextual
    val metadataId: UUID,
    @Contextual
    val created: OffsetDateTime? = null
)
