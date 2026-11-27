package bosca.workops.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.workops.ui.graphql.WorkOpsEnvironmentTypes
import bosca.workops.ui.graphql.WorkOpsCreateEnvironmentType
import bosca.workops.ui.graphql.WorkOpsUpdateEnvironmentType
import bosca.workops.ui.graphql.WorkOpsDeleteEnvironmentType
import kotlinx.serialization.Serializable

/** The global type catalog uses its existing administrator boundary and versioned edit API. */
@Serializable
class WorkOpsEnvironmentTypesModel(
    var rows: List<WorkOpsEnvironmentTypeRecord> = emptyList(),
    var loaded: Boolean = false,
    var offset: Long = 0,
    val targetId: String = "",
    var editing: WorkOpsEnvironmentTypeRecord? = null,
    var creating: Boolean = false,
    var draft: WorkOpsEnvironmentTypeDraft = WorkOpsEnvironmentTypeDraft(),
    var deleting: WorkOpsEnvironmentTypeRecord? = null,
    var resultId: String = "",
    val feedback: WorkOpsFeedback = WorkOpsFeedback(),
) {
    val pageRows get() = rows.drop(offset.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).take(25)
    val previousHref get() = if (offset > 0) href((offset - 25).coerceAtLeast(0)) else ""
    val nextHref get() = if (rows.size > offset + 25) href(offset + 25) else ""
    val resultHref get() = if (resultId.isBlank()) "" else href(0) + "&item=${workOpsEncode(resultId)}#workops-environment-type-${workOpsEncode(resultId)}"
    private fun href(page: Long) = "/workops/settings/environment-types?offset=$page"

    suspend fun reload(ctx: RenderContext) {
        loaded = false
        rows = ctx.gql.execute(WorkOpsEnvironmentTypes, Unit).workOps.multiRepo.environmentTypes
            .map { it.environmentTypeRecord() }.sortedBy { it.order }
        if (targetId.isNotBlank()) rows.indexOfFirst { it.id == targetId }.takeIf { it >= 0 }?.let { offset = it.toLong() / 25 * 25 }
        loaded = true
    }
    suspend fun retry(ctx: RenderContext) = feedback.perform { reload(ctx) }
    suspend fun reloadDraft(name: String, description: String, order: String, ctx: RenderContext) = feedback.perform {
        draft = WorkOpsEnvironmentTypeDraft(name, description, order); reload(ctx)
    }
    fun open(id: String) {
        require(loaded) { "Reload environment types first." }
        editing = if (id.isBlank()) null else requireNotNull(rows.firstOrNull { it.id == id }) { "Reload this environment type." }
        creating = id.isBlank()
        draft = editing?.let { WorkOpsEnvironmentTypeDraft(it.name, it.description, it.order.toString()) }
            ?: WorkOpsEnvironmentTypeDraft(order = rows.size.coerceAtMost(99).toString())
        resultId = ""; feedback.message = ""; feedback.failed = false
    }
    fun cancel() { creating = false; editing = null }
    suspend fun save(name: String, description: String, order: String, ctx: RenderContext) = feedback.perform {
        draft = WorkOpsEnvironmentTypeDraft(name, description, order)
        require(creating || editing != null) { "Open an environment type first." }
        require(loaded) { "Reload the catalog before saving." }
        require(name.isNotBlank()) { "Enter a name." }
        val position = requireNotNull(order.toIntOrNull()?.takeIf { it in 0..99 }) { "Enter a display order from 0 to 99." }
        val details = description.trim().takeIf(String::isNotBlank)
        val target = editing
        resultId = if (target == null) ctx.gql.execute(WorkOpsCreateEnvironmentType,
            WorkOpsCreateEnvironmentType.Variables(name.trim(), details, position)).workOps.multiRepo.createEnvironmentType.id
        else ctx.gql.execute(WorkOpsUpdateEnvironmentType,
            WorkOpsUpdateEnvironmentType.Variables(target.id, name.trim(), details, position, target.version)).workOps.multiRepo.updateEnvironmentType.id
        cancel(); reload(ctx); feedback.message = "Environment type saved."
    }
    fun beginDelete(id: String) {
        require(loaded) { "Reload environment types first." }
        deleting = requireNotNull(rows.firstOrNull { it.id == id }) { "Reload this environment type." }
        feedback.message = ""; feedback.failed = false
    }
    fun cancelDelete() { deleting = null }
    suspend fun delete(confirmed: Boolean, ctx: RenderContext) = feedback.perform {
        val target = requireNotNull(deleting) { "Choose an environment type first." }
        require(confirmed) { "Confirm deletion." }
        ctx.gql.execute(WorkOpsDeleteEnvironmentType, WorkOpsDeleteEnvironmentType.Variables(target.id))
        deleting = null; if (resultId == target.id) resultId = ""
        reload(ctx); feedback.message = "Environment type deleted."
    }
}

suspend fun workOpsEnvironmentTypes(): WorkOpsEnvironmentTypesModel {
    val ctx = currentRenderContext()
    return WorkOpsEnvironmentTypesModel(offset = ctx.query["offset"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0,
        targetId = ctx.query["item"].orEmpty()).also { it.retry(ctx) }
}
