package bosca.git.ui

import kotlinx.serialization.Serializable

@Serializable
data class RepositoryDraft(
    val name: String = "",
    val slug: String = "",
    val description: String = "",
    val ownerId: String = "",
    val visibility: String = "PRIVATE",
    val contentType: String = "GENERAL",
    val branch: String = "main",
    val readme: Boolean = true,
    val gitignore: String = "",
    val license: String = "",
    val cloneUrl: String = "",
)
