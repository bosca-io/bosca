package bosca.languages.model

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * A named use case for translating Bosca language tags into the language-tag vocabulary used by a content domain.
 *
 * A context owns many [LanguageTagMapping] entries and one domain-specific fallback used when an incoming tag is blank or
 * has no explicit mapping. Contexts deliberately have no version: mapping edits take effect immediately.
 */
@Serializable
data class LanguageResolutionContext(
    @Contextual
    val id: UUID = UUID.NIL,
    val key: String,
    val name: String,
    val description: String = "",
    @ColumnName("fallback_language_tag")
    val fallbackLanguageTag: String,
    @ColumnName("protected")
    val isProtected: Boolean = false,
)
