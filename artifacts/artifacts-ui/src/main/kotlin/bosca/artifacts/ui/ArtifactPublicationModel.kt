package bosca.artifacts.ui

import bosca.artifacts.ui.graphql.*
import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import kotlinx.serialization.Serializable

@Serializable
class ArtifactPublicationModel(
    val repository: ArtifactRepositoryView,
    var destinations: List<ArtifactPublicationDestinationView> = emptyList(),
    var secrets: List<String> = emptyList(),
    var versions: List<ArtifactVersionView> = emptyList(),
    var offset: Long = 0,
    var versionCount: Long = 0,
    var selectedVersionId: String = "",
    var reviewedVersion: ArtifactVersionView? = null,
    var history: List<ArtifactPublicationView> = emptyList(),
    var historyOffset: Long = 0,
    var hasNext: Boolean = false,
    var draft: ArtifactPublicationDraft = ArtifactPublicationDraft(),
    var editing: Boolean = false,
    var commitDraft: String = "",
    val feedback: ArtifactFeedback = ArtifactFeedback(),
) {
    val versionsHasNext: Boolean get() = offset + versions.size < versionCount
    suspend fun refresh(ctx: RenderContext) = feedback.perform { reload(ctx) }
    fun destinationLabel(id: String): String = destinations.firstOrNull { it.id == id }?.key ?: "Removed destination"
    suspend fun reload(ctx: RenderContext) {
        destinations = ctx.gql.execute(ArtifactsPublicationDestinations, ArtifactsPublicationDestinations.Variables(repository.id)).artifactsAdmin.publicationDestinations.map { ArtifactPublicationDestinationView(it.id, it.key, it.owner, it.githubRepository, it.githubRepositoryId, it.tagPrefix, it.tokenSecretName, it.enabled, it.version) }
        secrets = ctx.gql.execute(ArtifactsSecrets, Unit).pipelines.secrets.map { it.name }
        val result = ctx.gql.execute(ArtifactsRepository, ArtifactsRepository.Variables(repository.id, offset, 0)).artifactsAdmin.repository
        versions = result?.versions.orEmpty().map { it.view(repository) }
        versionCount = result?.versionCount ?: 0
        if (selectedVersionId.isBlank()) selectedVersionId = versions.firstOrNull()?.id.orEmpty()
        if (selectedVersionId.isNotBlank()) {
            reviewedVersion = ctx.gql.execute(ArtifactsVersion, ArtifactsVersion.Variables(selectedVersionId)).artifactsAdmin.version?.view(repository)
            if (reviewedVersion?.id != selectedVersionId) error("The selected version is unavailable.")
            val rows = ctx.gql.execute(ArtifactsPublications, ArtifactsPublications.Variables(selectedVersionId, historyOffset)).artifactsAdmin.publications
            hasNext = rows.size > 15
            history = rows.take(15).map { ArtifactPublicationView(it.id, it.destinationId, it.tagName, it.commitSha, it.attempts, it.published.orEmpty(), it.verified.orEmpty(), it.verifiedAttempt, it.error.orEmpty(), it.files.map { f -> ArtifactBlobView(f.digest, "file", f.filename, f.mediaType, f.size) }) }
        } else { reviewedVersion = null; history = emptyList(); hasNext = false }
    }
    fun edit(id: String) {
        draft = destinations.firstOrNull { it.id == id }?.let { ArtifactPublicationDraft(it.id, it.version, it.key, it.owner, it.githubRepository, it.githubRepositoryId.toString(), it.tagPrefix, it.tokenSecretName, it.enabled) } ?: ArtifactPublicationDraft()
        editing = true
        feedback.message = ""
    }
    fun cancel() { editing = false; draft = ArtifactPublicationDraft() }
    suspend fun save(key: String, owner: String, githubRepository: String, githubRepositoryId: String, tagPrefix: String, tokenSecretName: String, enabled: Boolean, ctx: RenderContext) {
        draft = draft.copy(key = key, owner = owner, githubRepository = githubRepository, githubRepositoryId = githubRepositoryId, tagPrefix = tagPrefix, tokenSecretName = tokenSecretName, enabled = enabled)
        feedback.perform {
            require(tokenSecretName.trim() in ctx.gql.execute(ArtifactsSecrets, Unit).pipelines.secrets.map { it.name }) { "Add the token secret before saving." }
            if (draft.id.isBlank()) {
                val numericId = githubRepositoryId.toLongOrNull()
                require(numericId != null && numericId > 0 && numericId <= 9_007_199_254_740_991L && listOf(key, owner, githubRepository).none(String::isBlank)) { "Enter a destination name, owner, repository name, and positive numeric repository ID." }
                ctx.gql.execute(ArtifactsCreatePublication, ArtifactsCreatePublication.Variables(ArtifactPublicationDestinationInput(repository.id, key.trim(), numericId, owner.trim(), githubRepository.trim(), tokenSecretName.trim(), tagPrefix.trim(), enabled)))
            } else ctx.gql.execute(ArtifactsUpdatePublication, ArtifactsUpdatePublication.Variables(draft.id, draft.version, enabled, tokenSecretName.trim()))
            editing = false
            draft = ArtifactPublicationDraft()
            reload(ctx)
            feedback.message = "Release destination saved."
        }
    }
    suspend fun saveSecret(name: String, value: String, ctx: RenderContext) = feedback.perform {
        require(name.isNotBlank() && value.isNotBlank()) { "Enter a secret name and token." }
        ctx.gql.execute(ArtifactsSetSecret, ArtifactsSetSecret.Variables(name.trim(), value))
        secrets = ctx.gql.execute(ArtifactsSecrets, Unit).pipelines.secrets.map { it.name }
        draft = draft.copy(tokenSecretName = name.trim())
        feedback.message = "Token secret saved."
    }
    suspend fun selectVersion(id: String, ctx: RenderContext) = feedback.perform {
        selectedVersionId = id
        historyOffset = 0
        reviewedVersion = null
        commitDraft = ""
        reload(ctx)
    }
    suspend fun complete(commit: String, confirmed: Boolean, ctx: RenderContext) {
        commitDraft = commit
        feedback.perform {
            require(confirmed) { "Confirm that every reviewed file has finished uploading and matches the build commit." }
            require(commit.matches(Regex("[0-9a-fA-F]{40}"))) { "Enter the exact 40-character build commit SHA." }
            val reviewed = requireNotNull(reviewedVersion) { "Select a version and review its files." }
            require(reviewed.blobs.isNotEmpty()) { "The version has no stored files." }
            val expected = reviewed.blobs.map { ArtifactVersionBlobInput(reviewed.id, it.digest, it.role, it.filename.takeIf(String::isNotBlank), it.mediaType.takeIf(String::isNotBlank)) }
            ctx.gql.execute(ArtifactsCompleteVersion, ArtifactsCompleteVersion.Variables(reviewed.id, commit, expected))
            reload(ctx)
            feedback.message = "Completed uploads recorded."
        }
    }
    suspend fun publish(destinationId: String, commit: String, ctx: RenderContext) {
        commitDraft = commit
        feedback.perform {
            require(commit.matches(Regex("[0-9a-fA-F]{40}"))) { "Enter the exact 40-character build commit SHA." }
            require(reviewedVersion?.completed == true) { "Confirm completed uploads before publication." }
            val run = ctx.gql.execute(ArtifactsPublishRelease, ArtifactsPublishRelease.Variables(destinationId, selectedVersionId, commit)).artifactsAdmin.publishRelease
            feedback.runHref = "/artifacts/runs/${artifactEncode(run)}"
            feedback.message = "Release publication queued."
        }
    }
    suspend fun versionsPage(delta: Int, ctx: RenderContext) = feedback.perform { offset = (offset + delta * 15).coerceAtLeast(0); selectedVersionId = ""; historyOffset = 0; reload(ctx) }
    suspend fun historyPage(delta: Int, ctx: RenderContext) = feedback.perform { historyOffset = (historyOffset + delta * 15).coerceAtLeast(0); reload(ctx) }
}
