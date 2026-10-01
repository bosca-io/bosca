package bosca.profiles.web

import kotlinx.serialization.Serializable

@Serializable
data class ProfileSearchRow(
    val id: String,
    val name: String,
    val slug: String,
    val avatarUrl: String,
) {
    val initials: String
        get() = name.trim()
            .split(Regex("\\s+"))
            .filter(String::isNotBlank)
            .take(2)
            .mapNotNull { it.firstOrNull()?.uppercase() }
            .joinToString("")
            .ifBlank { "?" }
}
