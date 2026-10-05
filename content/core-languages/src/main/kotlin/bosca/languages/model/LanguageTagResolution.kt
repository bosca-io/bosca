package bosca.languages.model

import kotlinx.serialization.Serializable

/** The deterministic result of resolving a requested language tag in a context. */
@Serializable
data class LanguageTagResolution(
    val requestedLanguageTag: String?,
    val normalizedLanguageTag: String?,
    val resolvedLanguageTag: String,
    val usedFallback: Boolean,
)
