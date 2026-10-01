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
 * A translated document for a specific language, stored as structured JSONB.
 *
 * Mirrors the workflow, origin, and review tracking used for string translations
 * so document and string translations share the same review surface in the admin UI.
 * The JSONB content follows the same schema the source metadata document uses, which
 * enables round-tripping through external translation tools via
 * [bosca.documents.html.DocumentHtmlConverter].
 */
@Serializable
data class LocalizationDocumentTranslation(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("document_id")
    @Contextual
    val documentId: UUID,
    @ColumnName("language_tag")
    val languageTag: String,
    @Contextual
    val content: JsonElement,
    val state: TranslationState = TranslationState.DRAFT,
    val origin: TranslationOrigin = TranslationOrigin.HUMAN,
    @ColumnName("origin_detail")
    val originDetail: String? = null,
    @ColumnName("reviewed_by")
    @Contextual
    val reviewedBy: UUID? = null,
    @ColumnName("reviewed_at")
    @Contextual
    val reviewedAt: OffsetDateTime? = null,
    @ColumnName("created_by")
    @Contextual
    val createdBy: UUID? = null,
    @Contextual
    val created: OffsetDateTime? = null,
    @Contextual
    val modified: OffsetDateTime? = null
)
