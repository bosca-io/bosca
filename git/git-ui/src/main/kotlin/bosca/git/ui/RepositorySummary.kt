package bosca.git.ui

import kotlinx.serialization.Serializable

@Serializable
data class RepositorySummary(
    val id: String,
    val name: String,
    val description: String,
    val defaultBranch: String,
    val visibility: String,
    val archived: Boolean,
    val slug: String,
    val contentType: String,
    val size: Long,
    val updated: String,
    val listHref: String,
    val canManage: Boolean,
    val canExecute: Boolean,
) {
    val href: String
        get() = "/repositories/${encode(id)}?return=${encode(listHref)}"

    /** Content type in Studio's title-cased form, such as `Script Project`. */
    val contentTypeLabel: String
        get() =
            contentType
                .split('_')
                .filter { it.isNotBlank() }
                .joinToString(" ") { word -> word.lowercase().replaceFirstChar(Char::uppercase) }
                .ifBlank { "—" }

    /** Badge tone matching Studio's visibility colours. */
    val visibilityTone: String
        get() =
            when (visibility) {
                "PUBLIC" -> "success"
                "INTERNAL" -> "warning"
                else -> "neutral"
            }
}
