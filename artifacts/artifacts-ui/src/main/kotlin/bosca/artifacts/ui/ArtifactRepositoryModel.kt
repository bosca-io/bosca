package bosca.artifacts.ui

import bosca.artifacts.ui.graphql.*
import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import kotlinx.serialization.Serializable

@Serializable
class ArtifactRepositoryModel(val id: String, val admin: Boolean, var offset: Long = 0, var tagOffset: Long = 0, var repository: ArtifactRepositoryView? = null,
    var versions: List<ArtifactVersionView> = emptyList(), var tags: List<ArtifactTagView> = emptyList(), val feedback: ArtifactFeedback = ArtifactFeedback()) {
    val href: String get() = "/artifacts/repositories/${artifactEncode(id)}"
    val title: String get() = repository?.path ?: if (admin) "Repository not found" else "Artifact repository"
    fun pageHref(versionsOffset: Long, tagsOffset: Long): String = "$href?offset=${versionsOffset.coerceAtLeast(0)}&tagOffset=${tagsOffset.coerceAtLeast(0)}"
    suspend fun reload(ctx: RenderContext) {
        if (!admin) return
        val result = ctx.gql.execute(ArtifactsRepository, ArtifactsRepository.Variables(id, offset, tagOffset)).artifactsAdmin.repository
        if (result == null) { ctx.notFound = true; return }
        val info = result.view()
        repository = info
        val clamped = if (offset >= info.versionCount) ((info.versionCount - 1).coerceAtLeast(0) / 15) * 15 else offset
        val tagsClamped = if (tagOffset >= info.tagCount) ((info.tagCount - 1).coerceAtLeast(0) / 15) * 15 else tagOffset
        if (clamped != offset || tagsClamped != tagOffset) { offset = clamped; tagOffset = tagsClamped; reload(ctx); return }
        versions = result.versions.map { it.view(info) }
        tags = result.tags.map { ArtifactTagView(it.name, it.manifestDigest, it.modified.orEmpty()) }
    }
    suspend fun deleteVersion(versionId: String, confirmed: Boolean, ctx: RenderContext) = feedback.perform {
        require(confirmed) { "Confirm version deletion." }
        ctx.gql.execute(ArtifactsDeleteVersion, ArtifactsDeleteVersion.Variables(versionId))
        reload(ctx)
        feedback.message = "Version deleted."
    }
    suspend fun deleteTag(name: String, confirmed: Boolean, ctx: RenderContext) = feedback.perform {
        require(confirmed) { "Confirm tag deletion." }
        ctx.gql.execute(ArtifactsDeleteTag, ArtifactsDeleteTag.Variables(id, name))
        reload(ctx)
        feedback.message = "Tag deleted."
    }
    suspend fun deleteRepository(confirmed: Boolean, ctx: RenderContext) = feedback.perform {
        require(confirmed) { "Confirm repository deletion." }
        ctx.gql.execute(ArtifactsDeleteRepository, ArtifactsDeleteRepository.Variables(id))
        feedback.nextHref = "/artifacts/repositories"
    }
}

suspend fun artifactRepository(): ArtifactRepositoryModel {
    val ctx = currentRenderContext()
    return ArtifactRepositoryModel(ctx.params["id"].orEmpty(), artifactAdministrator(ctx.gql), artifactOffset(ctx.query["offset"]), artifactOffset(ctx.query["tagOffset"])).also { it.reload(ctx) }
}
