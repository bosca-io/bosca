package bosca.git.ui

import kotlinx.serialization.Serializable

@Serializable
data class RepositoryEntry(
    val name: String,
    val directory: Boolean,
    val href: String,
    val kind: String,
)
