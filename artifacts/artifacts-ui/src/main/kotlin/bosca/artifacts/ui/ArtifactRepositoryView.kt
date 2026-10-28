package bosca.artifacts.ui

import kotlinx.serialization.Serializable

@Serializable
data class ArtifactRepositoryView(
    val id: String,
    val namespaceId: String,
    val namespace: String,
    val name: String,
    val type: String,
    val created: String,
    val modified: String,
    val versionCount: Long = 0,
    val tagCount: Long = 0,
) {
    val path: String get() = "$namespace/$name"
    val href: String get() = "/artifacts/repositories/${artifactEncode(id)}"
    val settingsHref: String get() = "/artifacts/settings/repositories/${artifactEncode(id)}"
    val uploadHref: String get() = "/artifacts/repositories/upload?repositoryId=${artifactEncode(id)}"
}
