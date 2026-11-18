package bosca.artifacts.ui

import bosca.artifacts.ui.graphql.*
import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import kotlinx.serialization.Serializable

@Serializable
class ArtifactSyncModel(
    val repositoryId: String,
    var destinations: List<ArtifactSyncDestinationView> = emptyList(),
    var secrets: List<String> = emptyList(),
    var history: List<ArtifactSyncView> = emptyList(),
    var offset: Long = 0,
    var hasNext: Boolean = false,
    var draft: ArtifactSyncDraft = ArtifactSyncDraft(),
    var editing: Boolean = false,
    var tags: List<ArtifactTagView> = emptyList(),
    var tagOffset: Long = 0,
    var tagCount: Long = 0,
    val feedback: ArtifactFeedback = ArtifactFeedback(),
) {
    val tagsHasNext: Boolean get() = tagOffset + tags.size < tagCount
    suspend fun refresh(ctx: RenderContext) = feedback.perform { reload(ctx) }
    fun destinationLabel(id: String): String = destinations.firstOrNull { it.id == id }?.key ?: "Removed destination"
    suspend fun reload(ctx: RenderContext) {
        destinations = ctx.gql.execute(ArtifactsSyncDestinations, ArtifactsSyncDestinations.Variables(repositoryId)).artifactsAdmin.syncDestinations.map { ArtifactSyncDestinationView(it.id, it.key, it.remoteRepository, it.username, it.tokenSecretName, it.enabled, it.version) }
        secrets = ctx.gql.execute(ArtifactsSecrets, Unit).pipelines.secrets.map { it.name }
        val rows = ctx.gql.execute(ArtifactsSyncs, ArtifactsSyncs.Variables(repositoryId, offset)).artifactsAdmin.syncs
        hasNext = rows.size > 15
        history = rows.take(15).map { ArtifactSyncView(it.id, it.destinationId, it.tagName, it.manifestDigest, it.attempts, it.synced.orEmpty(), it.error.orEmpty(), it.modified.orEmpty()) }
        val repo = ctx.gql.execute(ArtifactsRepository, ArtifactsRepository.Variables(repositoryId, 0, tagOffset)).artifactsAdmin.repository
        tags = repo?.tags.orEmpty().map { ArtifactTagView(it.name, it.manifestDigest, it.modified.orEmpty()) }
        tagCount = repo?.tagCount ?: 0
    }
    fun edit(id: String) {
        val target = destinations.firstOrNull { it.id == id }
        draft = target?.let { ArtifactSyncDraft(it.id, it.version, it.key, it.remoteRepository, it.username, it.tokenSecretName, it.enabled) } ?: ArtifactSyncDraft()
        editing = true
        feedback.message = ""
    }
    fun cancel() { editing = false; draft = ArtifactSyncDraft() }
    suspend fun save(key: String, remoteRepository: String, username: String, tokenSecretName: String, enabled: Boolean, ctx: RenderContext) {
        draft = draft.copy(key = key, remoteRepository = remoteRepository, username = username, tokenSecretName = tokenSecretName, enabled = enabled)
        feedback.perform {
            require(listOf(key, remoteRepository, username, tokenSecretName).none(String::isBlank)) { "Destination name, image path, username, and token secret are required." }
            require(tokenSecretName.trim() in ctx.gql.execute(ArtifactsSecrets, Unit).pipelines.secrets.map { it.name }) { "Add the token secret before saving." }
            if (draft.id.isBlank()) ctx.gql.execute(ArtifactsCreateSync, ArtifactsCreateSync.Variables(ArtifactSyncDestinationInput(repositoryId, key.trim(), remoteRepository.trim(), username.trim(), tokenSecretName.trim(), enabled)))
            else ctx.gql.execute(ArtifactsUpdateSync, ArtifactsUpdateSync.Variables(draft.id, draft.version, enabled, username.trim(), tokenSecretName.trim(), key.trim(), remoteRepository.trim()))
            editing = false
            draft = ArtifactSyncDraft()
            reload(ctx)
            feedback.message = "Sync destination saved."
        }
    }
    suspend fun saveSecret(name: String, value: String, ctx: RenderContext) = feedback.perform {
        require(name.isNotBlank() && value.isNotBlank()) { "Enter a secret name and token." }
        ctx.gql.execute(ArtifactsSetSecret, ArtifactsSetSecret.Variables(name.trim(), value))
        secrets = ctx.gql.execute(ArtifactsSecrets, Unit).pipelines.secrets.map { it.name }
        draft = draft.copy(tokenSecretName = name.trim())
        feedback.message = "Token secret saved."
    }
    suspend fun delete(id: String, version: Long, confirmed: Boolean, ctx: RenderContext) = feedback.perform {
        require(confirmed) { "Confirm destination deletion." }
        ctx.gql.execute(ArtifactsDeleteSync, ArtifactsDeleteSync.Variables(id, version))
        reload(ctx)
        feedback.message = "Destination deleted. Source and remote images are kept."
    }
    suspend fun retry(id: String, ctx: RenderContext) = feedback.perform {
        ctx.gql.execute(ArtifactsRetrySync, ArtifactsRetrySync.Variables(id))
        reload(ctx)
        feedback.message = "Sync queued."
    }
    suspend fun push(destinationId: String, tag: String, ctx: RenderContext) = feedback.perform {
        require(tag.isNotBlank()) { "Choose a tag." }
        val run = ctx.gql.execute(ArtifactsPushImage, ArtifactsPushImage.Variables(destinationId, tag)).artifactsAdmin.pushImage
        feedback.runHref = "/artifacts/runs/${artifactEncode(run)}"
        feedback.message = "Image push queued."
    }
    suspend fun historyPage(delta: Int, ctx: RenderContext) = feedback.perform { offset = (offset + delta * 15).coerceAtLeast(0); reload(ctx) }
    suspend fun tagsPage(delta: Int, ctx: RenderContext) = feedback.perform { tagOffset = (tagOffset + delta * 15).coerceAtLeast(0); reload(ctx) }
}
