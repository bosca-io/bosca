package bosca.artifacts.ui

import kotlinx.serialization.Serializable

@Serializable
data class ArtifactNamespaceView(
    val id: String,
    val name: String,
    val public: Boolean,
    val created: String,
    val repositoryCount: Long,
    val counts: Map<String, Long> = emptyMap(),
) {
    val href: String get() = "/artifacts/namespaces/${artifactEncode(id)}"
}
