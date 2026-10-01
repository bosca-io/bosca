package bosca.slug.model

import bosca.db.annotation.ColumnName
import bosca.graphql.annotations.BatchKey
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.text.Normalizer
import java.util.Locale

@BatchKey("slug")
@Serializable
data class Slug(
    val slug: String,
    @Contextual
    @ColumnName("metadata_id")
    val metadataId: UUID? = null,
    @Contextual
    @ColumnName("collection_id")
    val collectionId: UUID? = null,
    @ColumnName("language_tag")
    val languageTag: String? = null,
    @Contextual
    @ColumnName("profile_id")
    val profileId: UUID? = null
)

private val transliterationMap = mapOf(
    'ß' to "ss",
    'Æ' to "ae", 'æ' to "ae",
    'Œ' to "oe", 'œ' to "oe",
    'Ø' to "o",  'ø' to "o",
    'Đ' to "d",  'đ' to "d",
    'Ł' to "l",  'ł' to "l",
    'Þ' to "th", 'þ' to "th"
)

fun String.slugify(): String {
    if (isBlank()) return "n-a"
    val preprocessed = buildString {
        for (ch in this@slugify.trim()) {
            append(transliterationMap[ch] ?: ch)
        }
    }
    val normalized = Normalizer.normalize(preprocessed, Normalizer.Form.NFD)
    val withoutDiacritics = normalized.replace(Regex("\\p{M}+"), "")
    return withoutDiacritics
        .lowercase(Locale.ROOT)
        .replace("&", " and ")
        .replace(':', '-')
        .replace(Regex("[^\\p{L}\\p{N}\\s-]"), "")
        .replace(Regex("[\\s_-]+"), "-")
        .replace(Regex("^-+|-+$"), "")
        .ifEmpty { "n-a" }
}