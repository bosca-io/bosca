package bosca.git.ui

import kotlinx.serialization.Serializable

@Serializable
data class OwnerOption(val id: String, val name: String, val slug: String) {
    val label: String
        get() = "$name · ${if (slug.isNotBlank()) "@$slug" else id}"
}
