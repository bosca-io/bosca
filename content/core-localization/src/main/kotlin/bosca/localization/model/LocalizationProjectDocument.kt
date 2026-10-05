@file:OptIn(ExperimentalUuidApi::class)

package bosca.localization.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.uuid.ExperimentalUuidApi

/**
 * Links an existing `metadata` item to a [LocalizationProject] as a source document
 * to be translated. The source document's authoritative content lives in the content
 * system; translations produced for this project are stored in
 * `localization.document_translations` with their own state and review lifecycle.
 */
@Serializable
data class LocalizationProjectDocument(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("project_id")
    @Contextual
    val projectId: UUID,
    @ColumnName("metadata_id")
    @Contextual
    val metadataId: UUID,
    @Contextual
    val attributes: JsonElement? = null,
    @Contextual
    val created: OffsetDateTime? = null,
    @Contextual
    val modified: OffsetDateTime? = null
)
