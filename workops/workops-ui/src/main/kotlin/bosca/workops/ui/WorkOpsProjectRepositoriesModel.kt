package bosca.workops.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.design.model.SchemaFormOption
import bosca.workops.ui.graphql.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** WorkOps owns project associations and setup; Git owns repository reads and the resulting file. */
@Serializable
class WorkOpsProjectRepositoriesModel(
    val projectId: String,
    var offset: Long = 0,
    val targetId: String = "",
    var rows: List<WorkOpsProjectRepositoryRecord> = emptyList(),
    var choices: List<SchemaFormOption> = emptyList(),
    var loaded: Boolean = false,
    var repositoryDraft: String = "",
    var authorNameDraft: String = "",
    var authorEmailDraft: String = "",
    var authorInitialized: Boolean = false,
    var deleteTarget: WorkOpsProjectRepositoryRecord? = null,
    var setupTarget: WorkOpsProjectRepositoryRecord? = null,
    var generated: WorkOpsProjectDeployConfig? = null,
    var resultId: String = "",
    val feedback: WorkOpsFeedback = WorkOpsFeedback(),
) {
    val pageRows get() = rows.drop(offset.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).take(25)
    val offeredChoices get() = choices + if (repositoryDraft.isNotBlank() && choices.none { it.value == repositoryDraft })
        listOf(SchemaFormOption(repositoryDraft, "Selected repository unavailable", disabled = true)) else emptyList()
    private fun href(page: Long) = "/workops/projects/${workOpsEncode(projectId)}?tab=repositories&offset=$page"
    val previousHref get() = if (offset > 0) href((offset - 25).coerceAtLeast(0)) else ""
    val nextHref get() = if (rows.size.toLong() > offset + 25) href(offset + 25) else ""
    val resultHref get() = if (resultId.isBlank()) "" else href(0) + "&item=${workOpsEncode(resultId)}#workops-project-repository-${workOpsEncode(resultId)}"

    suspend fun reload(ctx: RenderContext) {
        loaded = false
        val data = ctx.gql.execute(WorkOpsProjectRepositories, WorkOpsProjectRepositories.Variables(projectId))
        val project = data.workOps.projects.project
        if (project == null) { rows = emptyList(); choices = emptyList(); error("This project is unavailable.") }
        val repositories = data.git.repositories.associateBy { it.id }
        rows = project.repositories.map { association ->
            val repository = repositories[association.repositoryId]
            WorkOpsProjectRepositoryRecord(association.id, association.repositoryId, repository?.name.orEmpty(),
                repository?.slug.orEmpty(), repository?.archived ?: false, repository != null)
        }
        choices = data.git.repositories.filter { !it.archived && rows.none { row -> row.repositoryId == it.id } }
            .map { SchemaFormOption(it.id, it.name) }
        if (!authorInitialized) {
            val profiles = data.profiles.current.orEmpty()
            val profile = profiles.firstOrNull { it.isPrimary } ?: profiles.firstOrNull()
            authorNameDraft = profile?.name.orEmpty().trim()
            authorEmailDraft = ((profile?.attributes?.firstOrNull { it.typeId == "bosca.profiles.email" }?.attributes as? JsonObject)
                ?.get("email") as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty().trim()
            authorInitialized = true
        }
        if (targetId.isNotBlank()) rows.indexOfFirst { it.id == targetId }.takeIf { it >= 0 }?.let { offset = it.toLong() / 25 * 25 }
        loaded = true
    }
    suspend fun retry(repository: String, ctx: RenderContext) = feedback.perform { repositoryDraft = repository; reload(ctx) }
    suspend fun attach(repository: String, ctx: RenderContext) = feedback.perform {
        repositoryDraft = repository
        require(loaded && choices.any { it.value == repository }) { "Choose an available repository." }
        resultId = ctx.gql.execute(WorkOpsAttachProjectRepository, WorkOpsAttachProjectRepository.Variables(projectId, repository))
            .workOps.projects.addProjectRepository.id
        repositoryDraft = ""; reload(ctx); feedback.message = "Repository attached."
    }
    fun beginDelete(id: String, repository: String) {
        repositoryDraft = repository
        require(loaded) { "Reload the project repositories first." }
        deleteTarget = requireNotNull(rows.firstOrNull { it.id == id }) { "Reload this repository association." }
        feedback.message = ""; feedback.failed = false
    }
    fun cancelDelete() { deleteTarget = null }
    suspend fun delete(confirmed: Boolean, ctx: RenderContext) = feedback.perform {
        val target = requireNotNull(deleteTarget) { "Choose a repository association first." }
        require(confirmed) { "Confirm removal." }
        ctx.gql.execute(WorkOpsRemoveProjectRepository, WorkOpsRemoveProjectRepository.Variables(projectId, target.id))
        deleteTarget = null
        if (resultId == target.id) resultId = ""
        reload(ctx); feedback.message = "Repository association removed."
    }
    fun beginSetup(id: String, repository: String) {
        repositoryDraft = repository
        require(loaded) { "Reload the project repositories first." }
        setupTarget = requireNotNull(rows.firstOrNull { it.id == id }) { "Reload this repository association." }
        generated = null; feedback.message = ""; feedback.failed = false
    }
    fun cancelSetup() { setupTarget = null }
    fun dismissGenerated() { generated = null }
    suspend fun generate(name: String, email: String, ctx: RenderContext) = feedback.perform {
        authorNameDraft = name; authorEmailDraft = email
        val target = requireNotNull(setupTarget) { "Open deploy-config setup first." }
        require(name.isNotBlank()) { "Enter the commit author's name." }
        require(email.isNotBlank() && '@' in email && email.none(Char::isWhitespace)) { "Enter the commit author's email." }
        val fresh = requireNotNull(ctx.gql.execute(WorkOpsProjectRepositories, WorkOpsProjectRepositories.Variables(projectId)).workOps.projects.project) {
            "This project is unavailable."
        }
        require(fresh.repositories.any { it.id == target.id && it.repositoryId == target.repositoryId }) {
            "The repository association changed. Reload it before creating a deploy config."
        }
        val file = ctx.gql.execute(WorkOpsCreateProjectDeployConfig, WorkOpsCreateProjectDeployConfig.Variables(
            projectId, target.repositoryId, name.trim(), email.trim())).workOps.multiRepo.createProjectDeployConfig
        generated = WorkOpsProjectDeployConfig(file.repositoryId, target.label, file.path, file.branch, file.commitSha, file.content)
        setupTarget = null; feedback.message = "Deploy config committed."
    }
}

suspend fun workOpsProjectRepositories(projectId: String): WorkOpsProjectRepositoriesModel {
    val ctx = currentRenderContext()
    return WorkOpsProjectRepositoriesModel(projectId, ctx.query["offset"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0,
        ctx.query["item"].orEmpty()).also { it.feedback.perform { it.reload(ctx) } }
}
