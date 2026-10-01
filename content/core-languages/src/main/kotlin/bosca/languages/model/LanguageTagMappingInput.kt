package bosca.languages.model

import kotlinx.serialization.Serializable

/** Input used to create or replace a language-tag mapping within a resolution context. */
@Serializable
data class LanguageTagMappingInput(
    val sourceLanguageTag: String,
    val resolvedLanguageTag: String,
)
