package bosca.artifacts.ui

import bosca.artifacts.ui.graphql.*
import bosca.bml.graphql.execute
import bosca.bml.render.currentRenderContext

class ArtifactSettingsPage(
    val admin: Boolean,
    val repository: ArtifactRepositoryView?,
    val sync: ArtifactSyncModel?,
    val publication: ArtifactPublicationModel?,
)

suspend fun artifactSettings(): ArtifactSettingsPage {
    val ctx = currentRenderContext()
    if (!artifactAdministrator(ctx.gql)) return ArtifactSettingsPage(false, null, null, null)
    val repository = ctx.gql.execute(ArtifactsRepository, ArtifactsRepository.Variables(ctx.params["id"].orEmpty(), 0, 0)).artifactsAdmin.repository?.view()
    if (repository == null) { ctx.notFound = true; return ArtifactSettingsPage(true, null, null, null) }
    val sync = if (repository.type == "docker") ArtifactSyncModel(repository.id).also { it.reload(ctx) } else null
    val publication = if (repository.type == "raw") ArtifactPublicationModel(repository).also { it.reload(ctx) } else null
    return ArtifactSettingsPage(true, repository, sync, publication)
}
