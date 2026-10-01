package bosca.localization.model

import kotlinx.serialization.Serializable

/**
 * A point-in-time snapshot of translation coverage for one language within a project.
 *
 * The `aiGeneratedStrings` and `humanTranslatedStrings` counts are disjoint within the
 * same project/language pair and together describe how much of the progress is AI-sourced
 * vs human-sourced, which informs which rows still need human review before publishing.
 */
@Serializable
data class TranslationProgress(
    val totalStrings: Int,
    val translatedStrings: Int,
    val approvedStrings: Int,
    val publishedStrings: Int,
    val aiGeneratedStrings: Int,
    val humanTranslatedStrings: Int,
    val percentage: Double
)
