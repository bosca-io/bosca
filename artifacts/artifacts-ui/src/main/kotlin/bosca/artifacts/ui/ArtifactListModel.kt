package bosca.artifacts.ui

import bosca.artifacts.ui.graphql.*
import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import kotlinx.serialization.Serializable

@Serializable
class ArtifactListModel(
    val kind: String, val namespaceId: String = "", val type: String = "all", var offset: Long = 0,
    val admin: Boolean = false, var namespaces: List<ArtifactNamespaceView> = emptyList(),
    var repositories: List<ArtifactRepositoryView> = emptyList(), var namespace: ArtifactNamespaceView? = null,
    var hasNext: Boolean = false, var draft: ArtifactCreateDraft = ArtifactCreateDraft(), val feedback: ArtifactFeedback = ArtifactFeedback(),
) {
    val title: String get() = if (kind == "namespaces") "Namespaces" else namespace?.name ?: "Repositories"
    val href: String get() = if (kind == "namespaces") "/artifacts/namespaces" else if (namespaceId.isNotBlank()) "/artifacts/namespaces/${artifactEncode(namespaceId)}" else "/artifacts/repositories"
    val previousHref: String get() = "$href?type=$type&offset=${(offset - 15).coerceAtLeast(0)}"
    val nextHref: String get() = "$href?type=$type&offset=${offset + 15}"
    suspend fun reload(ctx: RenderContext) {
        if (!admin) return
        namespaces = ctx.gql.execute(ArtifactsNamespaces, Unit).artifactsAdmin.namespaces.map {
            val counts = listOf("docker" to it.dockerCount, "helm" to it.helmCount, "maven" to it.mavenCount, "npm" to it.npmCount, "raw" to it.rawCount, "ml" to it.mlCount)
            it.view().copy(counts = counts.toMap())
        }
        if (kind == "namespaces") return
        val registryType = type.takeIf { it in artifactTypes }?.let(ArtifactRegistryType::valueOf)
        if (namespaceId.isNotBlank()) {
            val result = ctx.gql.execute(ArtifactsNamespace, ArtifactsNamespace.Variables(namespaceId, registryType, offset)).artifactsAdmin.namespace
            namespace = result?.view()
            repositories = result?.repositories.orEmpty().map { it.view() }
            if (result != null && offset > 0 && offset >= result.filteredRepositoryCount) {
                offset = ((result.filteredRepositoryCount - 1).coerceAtLeast(0) / 15) * 15
                reload(ctx)
                return
            }
            hasNext = offset + repositories.size < (result?.filteredRepositoryCount ?: 0)
            if (result == null) ctx.notFound = true
        } else {
            val rows = ctx.gql.execute(ArtifactsRepositories, ArtifactsRepositories.Variables(registryType, offset)).artifactsAdmin.repositories
            hasNext = rows.size > 15
            repositories = rows.take(15).map { it.view() }
            if (rows.isEmpty() && offset > 0) { offset = 0; reload(ctx) }
        }
    }
    suspend fun create(name: String, selectedNamespace: String, registryType: String, public: Boolean, ctx: RenderContext) {
        draft = ArtifactCreateDraft(name, selectedNamespace, registryType, public)
        feedback.perform {
            require(name.isNotBlank()) { "Enter a name." }
            if (kind == "namespaces") {
                val created = ctx.gql.execute(ArtifactsCreateNamespace, ArtifactsCreateNamespace.Variables(name.trim(), public)).artifactsAdmin.createNamespace
                feedback.nextHref = "/artifacts/namespaces/${artifactEncode(created.id)}"
            } else {
                require(selectedNamespace.isNotBlank()) { "Choose a namespace." }
                require(registryType in artifactTypes) { "Choose a registry type." }
                val created = ctx.gql.execute(ArtifactsCreateRepository, ArtifactsCreateRepository.Variables(selectedNamespace, name.trim(), ArtifactRegistryType.valueOf(registryType))).artifactsAdmin.createRepository
                feedback.nextHref = "/artifacts/repositories/${artifactEncode(created.id)}"
            }
        }
    }
    suspend fun delete(id: String, confirmed: Boolean, ctx: RenderContext) = feedback.perform {
        require(confirmed) { "Confirm deletion." }
        if (kind == "namespaces") ctx.gql.execute(ArtifactsDeleteNamespace, ArtifactsDeleteNamespace.Variables(id))
        else ctx.gql.execute(ArtifactsDeleteRepository, ArtifactsDeleteRepository.Variables(id))
        reload(ctx)
        feedback.message = "${if (kind == "namespaces") "Namespace" else "Repository"} deleted."
    }
    suspend fun visibility(public: Boolean, ctx: RenderContext) = feedback.perform {
        ctx.gql.execute(ArtifactsUpdateNamespace, ArtifactsUpdateNamespace.Variables(namespaceId, public))
        reload(ctx)
        feedback.message = "Namespace visibility saved."
    }
    suspend fun deleteNamespace(confirmed: Boolean, ctx: RenderContext) = feedback.perform {
        require(confirmed) { "Confirm namespace deletion." }
        ctx.gql.execute(ArtifactsDeleteNamespace, ArtifactsDeleteNamespace.Variables(namespaceId))
        feedback.nextHref = "/artifacts/namespaces"
    }
}

suspend fun artifactList(kind: String): ArtifactListModel {
    val ctx = currentRenderContext()
    return ArtifactListModel(kind, ctx.params["id"].orEmpty(), ctx.query["type"].takeIf { it in artifactTypes } ?: "all", artifactOffset(ctx.query["offset"]), artifactAdministrator(ctx.gql)).also { it.reload(ctx) }
}
