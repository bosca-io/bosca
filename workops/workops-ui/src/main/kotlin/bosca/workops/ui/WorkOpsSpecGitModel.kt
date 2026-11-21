package bosca.workops.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.workops.ui.graphql.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Pushes the saved specification document using the caller's explicit human commit details. */
@Serializable
class WorkOpsSpecGitModel(
    val specId: String,
    var data: WorkOpsSpecGitData? = null,
    var repositoryName: String = "",
    var pushing: Boolean = false,
    var authorNameDraft: String = "",
    var authorEmailDraft: String = "",
    var commitSha: String = "",
    val feedback: WorkOpsFeedback = WorkOpsFeedback(),
) {
    val spec get() = data?.workOps?.specs?.spec
    val bound get() = spec?.gitRepositoryId != null && !spec?.gitPath.isNullOrBlank()
    val repositoryHref get() = spec?.gitRepositoryId?.let { "/repositories/${workOpsEncode(it)}" }.orEmpty()
    suspend fun reload(ctx: RenderContext) {
        val firstLoad = data == null
        data = ctx.gql.execute(WorkOpsSpecGit, WorkOpsSpecGit.Variables(specId))
        val spec = requireNotNull(spec) { "The specification is unavailable." }
        if (firstLoad) {
            val profiles = data?.profiles?.current.orEmpty()
            val profile = profiles.firstOrNull { it.isPrimary } ?: profiles.firstOrNull()
            authorNameDraft = profile?.name.orEmpty().trim()
            authorEmailDraft = ((profile?.attributes?.firstOrNull { it.typeId == "bosca.profiles.email" }?.attributes as? JsonObject)
                ?.get("email") as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty().trim()
        }
        repositoryName = if (spec.gitRepositoryId == null) "" else
            ctx.gql.execute(WorkOpsSpecGitRepositories, Unit).git.repositories.firstOrNull { it.id == spec.gitRepositoryId }?.name.orEmpty()
    }
    suspend fun retry(ctx: RenderContext) = feedback.perform { reload(ctx) }
    fun open() { pushing = true; commitSha = "" }
    fun cancel() { pushing = false }
    suspend fun push(name: String, email: String, ctx: RenderContext) = feedback.perform {
        authorNameDraft = name; authorEmailDraft = email
        require(pushing && bound) { "Open a linked specification before pushing." }
        require(name.isNotBlank()) { "Enter the commit author's name." }
        require(email.isNotBlank() && '@' in email && email.none(Char::isWhitespace)) { "Enter the commit author's email." }
        val fresh = requireNotNull(ctx.gql.execute(WorkOpsSpecGit, WorkOpsSpecGit.Variables(specId)).workOps.specs.spec) {
            "The specification is unavailable."
        }
        require(fresh.gitRepositoryId == spec?.gitRepositoryId && fresh.gitPath == spec?.gitPath) {
            "The Git binding changed. Reload it before pushing."
        }
        commitSha = requireNotNull(ctx.gql.execute(WorkOpsPushSavedSpec, WorkOpsPushSavedSpec.Variables(specId, name.trim(), email.trim()))
            .workOps.specs.pushSavedToGit) { "The specification is not linked to a Git file." }
        pushing = false
        feedback.message = "Saved document pushed to Git."
    }
}

suspend fun workOpsSpecGit(specId: String): WorkOpsSpecGitModel {
    val ctx = currentRenderContext()
    return WorkOpsSpecGitModel(specId).also { model -> model.feedback.perform { model.reload(ctx) } }
}
