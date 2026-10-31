package bosca.artifacts.ui

import bosca.artifacts.ui.graphql.*
import bosca.bml.graphql.execute
import bosca.bml.render.currentRenderContext

class ArtifactUploadPage(
    val admin: Boolean,
    val namespaces: List<ArtifactNamespaceView>,
    val repositories: List<ArtifactRepositoryView>,
    val selectedRepositoryId: String,
)

suspend fun artifactUpload(): ArtifactUploadPage {
    val ctx = currentRenderContext()
    if (!artifactAdministrator(ctx.gql)) return ArtifactUploadPage(false, emptyList(), emptyList(), "")
    val namespaces = ctx.gql.execute(ArtifactsNamespaces, Unit).artifactsAdmin.namespaces.map { it.view() }
    val repositories = mutableListOf<ArtifactRepositoryView>()
    var offset = 0L
    do {
        val batch = ctx.gql.execute(ArtifactsRepositories, ArtifactsRepositories.Variables(ArtifactRegistryType.raw, offset)).artifactsAdmin.repositories
        repositories += batch.take(15).map { it.view() }
        offset += 15
    } while (batch.size > 15)
    return ArtifactUploadPage(true, namespaces, repositories, ctx.query["repositoryId"].orEmpty())
}
