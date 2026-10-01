package bosca.languages.model

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** An explicit mapping from a Bosca language tag to a content domain's normalized language tag. */
@Serializable
data class LanguageTagMapping(
    @Contextual
    @ColumnName("context_id")
    val contextId: UUID,
    @ColumnName("source_language_tag")
    val sourceLanguageTag: String,
    @ColumnName("resolved_language_tag")
    val resolvedLanguageTag: String,
)
