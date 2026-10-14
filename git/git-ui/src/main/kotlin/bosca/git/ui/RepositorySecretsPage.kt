package bosca.git.ui

import bosca.git.ui.graphql.*
import kotlinx.serialization.Serializable

@Serializable
data class RepositorySecretsPage(
    val id: String,
    val name: String,
    val secrets: List<GitUiSecretsData.Git.PipelineSecrets>,
    val allowed: Boolean,
    val baseHref: String = "",
)
