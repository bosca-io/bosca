package bosca.workops.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.design.model.SchemaFormOption
import bosca.workops.ui.graphql.*
import kotlinx.serialization.Serializable

/** Both dependency directions resolve names through authorized project roots. */
@Serializable
class WorkOpsProjectDependenciesModel(
    val projectId: String,
    var offset: Long = 0,
    val consumersOffset: Long = 0,
    val targetId: String = "",
    var dependencies: List<WorkOpsProjectDependencyRecord> = emptyList(),
    var consumers: List<WorkOpsProjectDependencyRecord> = emptyList(),
    var projects: List<WorkOpsPlanningRow> = emptyList(),
    var loaded: Boolean = false,
    var creating: Boolean = false,
    var providerDraft: String = "",
    var typeDraft: String = "BUILD",
    var constraintDraft: String = "*",
    var deleteTarget: WorkOpsProjectDependencyRecord? = null,
    var resultId: String = "",
    val feedback: WorkOpsFeedback = WorkOpsFeedback(),
) {
    val pageRows get() = dependencies.drop(offset.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).take(25)
    val consumerRows get() = consumers.drop(consumersOffset.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).take(25)
    val providerChoices get() = projects.filter { !it.archived && it.id != projectId && dependencies.none { dependency -> dependency.providerId == it.id } }
        .map { SchemaFormOption(it.id, "${it.key} · ${it.name}") }
    val offeredProviders get() = providerChoices + if (providerDraft.isNotBlank() && providerChoices.none { it.value == providerDraft })
        listOf(SchemaFormOption(providerDraft, "Selected project unavailable", disabled = true)) else emptyList()
    val types get() = WorkOpsDependencyType.entries.map { SchemaFormOption(it.name, it.name.lowercase().replaceFirstChar(Char::uppercase)) }
    fun projectLabel(id: String) = projects.firstOrNull { it.id == id }?.let { "${it.key} · ${it.name}" } ?: "Project unavailable"
    private fun href(page: Long = offset, consumersPage: Long = consumersOffset) =
        "/workops/projects/${workOpsEncode(projectId)}?tab=dependencies&offset=$page&consumersOffset=$consumersPage"
    val previousHref get() = if (offset > 0) href((offset - 25).coerceAtLeast(0)) else ""
    val nextHref get() = if (dependencies.size.toLong() > offset + 25) href(offset + 25) else ""
    val previousConsumersHref get() = if (consumersOffset > 0) href(consumersPage = (consumersOffset - 25).coerceAtLeast(0)) else ""
    val nextConsumersHref get() = if (consumers.size.toLong() > consumersOffset + 25) href(consumersPage = consumersOffset + 25) else ""
    val resultHref get() = if (resultId.isBlank()) "" else href(0) + "&item=${workOpsEncode(resultId)}#workops-dependency-${workOpsEncode(resultId)}"

    suspend fun reload(ctx: RenderContext) {
        loaded = false
        val data = ctx.gql.execute(WorkOpsProjectDependencies, WorkOpsProjectDependencies.Variables(projectId)).workOps
        dependencies = data.multiRepo.dependencies.map { it.projectDependency() }
        consumers = data.multiRepo.consumers.map { it.projectDependency() }
        projects = data.projects.all.map { WorkOpsPlanningRow(id = it.id, key = it.key, name = it.name, archived = it.archivedAt != null) }
        if (targetId.isNotBlank()) dependencies.indexOfFirst { it.id == targetId }.takeIf { it >= 0 }?.let { offset = it.toLong() / 25 * 25 }
        loaded = true
    }
    suspend fun retry(ctx: RenderContext) = feedback.perform { reload(ctx) }
    fun open() { creating = true; providerDraft = ""; typeDraft = "BUILD"; constraintDraft = "*"; resultId = ""; feedback.message = ""; feedback.failed = false }
    fun cancelCreate() { creating = false }
    suspend fun reloadChoices(provider: String, type: String, constraint: String, ctx: RenderContext) = feedback.perform {
        providerDraft = provider; typeDraft = type; constraintDraft = constraint
        reload(ctx)
    }
    suspend fun create(provider: String, type: String, constraint: String, ctx: RenderContext) = feedback.perform {
        providerDraft = provider; typeDraft = type; constraintDraft = constraint
        require(creating && loaded) { "Reload the dependency choices first." }
        require(providerChoices.any { it.value == provider }) { "Choose an available provider project." }
        val selected = requireNotNull(WorkOpsDependencyType.entries.firstOrNull { it.name == type }) { "Choose a dependency type." }
        resultId = ctx.gql.execute(WorkOpsDeclareProjectDependency, WorkOpsDeclareProjectDependency.Variables(
            CreateWorkOpsDependencyDeclarationInput(consumerProjectId = projectId, providerProjectId = provider,
                providerVersionConstraint = constraint.trim().ifBlank { "*" }, dependencyType = selected))).workOps.multiRepo.declareDependency.id
        creating = false; reload(ctx); feedback.message = "Dependency declared."
    }
    fun beginDelete(id: String) {
        require(loaded) { "Reload dependencies first." }
        deleteTarget = requireNotNull(dependencies.firstOrNull { it.id == id }) { "Reload this dependency." }
        feedback.message = ""; feedback.failed = false
    }
    fun cancelDelete() { deleteTarget = null }
    suspend fun delete(confirmed: Boolean, ctx: RenderContext) = feedback.perform {
        val target = requireNotNull(deleteTarget) { "Choose a dependency first." }
        require(confirmed) { "Confirm removal." }
        ctx.gql.execute(WorkOpsRemoveProjectDependency, WorkOpsRemoveProjectDependency.Variables(target.id))
        deleteTarget = null
        if (resultId == target.id) resultId = ""
        reload(ctx); feedback.message = "Dependency removed."
    }
}

suspend fun workOpsProjectDependencies(projectId: String): WorkOpsProjectDependenciesModel {
    val ctx = currentRenderContext()
    return WorkOpsProjectDependenciesModel(projectId, ctx.query["offset"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0,
        ctx.query["consumersOffset"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0, ctx.query["item"].orEmpty()).also { it.retry(ctx) }
}
