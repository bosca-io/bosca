package bosca.workops.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.design.model.SchemaFormOption
import bosca.workops.ui.graphql.*
import kotlinx.serialization.Serializable

/** Global reference catalogs share controls while retaining each owning typed API and edit revision. */
@Serializable
class WorkOpsGlobalCatalogModel(
    val kind: String,
    var offset: Long = 0,
    val targetId: String = "",
    var rows: List<WorkOpsGlobalCatalogEntry> = emptyList(),
    var loaded: Boolean = false,
    var editing: Boolean = false,
    var draft: WorkOpsGlobalCatalogEntry = WorkOpsGlobalCatalogEntry(),
    var deleting: WorkOpsGlobalCatalogEntry? = null,
    var resultId: String = "",
    val feedback: WorkOpsFeedback = WorkOpsFeedback(),
) {
    val title get() = when (kind) { "task-types" -> "Task types"; "link-types" -> "Link types"; else -> kind.replaceFirstChar(Char::uppercase) }
    val entityLabel get() = when (kind) { "task-types" -> "task type"; "statuses" -> "status"; "priorities" -> "priority"; "labels" -> "label"; "link-types" -> "link type"; else -> "resolution" }
    val hasColor get() = kind in setOf("task-types", "statuses", "priorities", "labels")
    val hasIcon get() = kind in setOf("task-types", "priorities")
    val hasOrder get() = kind in setOf("priorities", "resolutions")
    val hasDescription get() = kind !in setOf("labels", "link-types")
    val pageRows get() = rows.drop(offset.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).take(25)
    val previousHref get() = if (offset > 0) href((offset - 25).coerceAtLeast(0)) else ""
    val nextHref get() = if (rows.size > offset + 25) href(offset + 25) else ""
    val resultHref get() = resultId.takeIf(String::isNotBlank)?.let { href(0) + "&item=${workOpsEncode(it)}#workops-catalog-${workOpsEncode(it)}" }.orEmpty()
    val presets get() = when (kind) {
        "task-types" -> listOf("#5ec5ff", "#4ade80", "#a78bff", "#ec4899", "#ffb547", "#ff5d6c", "#f97316", "#6c7388")
        "statuses" -> listOf("#6c7388", "#5ec5ff", "#a78bff", "#ffb547", "#4ade80", "#ff5d6c", "#ec4899", "#f97316")
        "priorities" -> listOf("#ff5d6c", "#ff8a4d", "#ffb547", "#5ec5ff", "#6c7388", "#4ade80", "#a78bff", "#ec4899")
        else -> listOf("#a78bff", "#5ec5ff", "#4ade80", "#ffb547", "#ff5d6c", "#ec4899", "#f97316", "#6c7388")
    }
    val categories get() = when (kind) {
        "task-types" -> listOf("INITIATIVE", "EPIC", "STANDARD", "SUBTASK")
        "statuses" -> WorkOpsStatusCategory.entries.map { it.name }
        "link-types" -> listOf("BLOCKS", "RELATES_TO", "DUPLICATES", "CLONES", "CAUSES", "CUSTOM")
        else -> emptyList()
    }.map { SchemaFormOption(it, label(it)) }
    val icons get() = listOf("arrow-up-double" to "Critical (↑↑)", "arrow-up" to "High (↑)", "arrow-right" to "Medium (→)", "arrow-down" to "Low (↓)", "arrow-down-double" to "Lowest (↓↓)")
        .map { SchemaFormOption(it.first, it.second) }.let { values -> if (draft.icon.isNotBlank() && values.none { it.value == draft.icon }) values + SchemaFormOption(draft.icon, draft.icon) else values }
    fun label(value: String) = value.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)
    private fun href(page: Long) = "/workops/settings/$kind?offset=$page"

    suspend fun reload(ctx: RenderContext) {
        loaded = false
        val values = when (kind) {
            "task-types" -> ctx.gql.execute(WorkOpsCatalogTaskTypes, Unit).workOps.tasks.taskTypes.map { WorkOpsGlobalCatalogEntry(it.id, it.name, it.description.orEmpty(), it.colorHex, it.iconKey, it.hierarchyLevel.name, version = it.version) }
            "statuses" -> ctx.gql.execute(WorkOpsCatalogStatuses, Unit).workOps.statuses.all.map { WorkOpsGlobalCatalogEntry(it.id, it.name, it.description.orEmpty(), it.colorHex, category = it.category.name, version = it.version) }
            "priorities" -> ctx.gql.execute(WorkOpsCatalogPriorities, Unit).workOps.tasks.priorities.map { WorkOpsGlobalCatalogEntry(it.id, it.name, it.description.orEmpty(), it.colorHex, it.iconKey, order = it.displayOrder.toString(), version = it.version) }
            "resolutions" -> ctx.gql.execute(WorkOpsCatalogResolutions, Unit).workOps.tasks.resolutions.map { WorkOpsGlobalCatalogEntry(it.id, it.name, it.description.orEmpty(), order = it.displayOrder.toString(), version = it.version) }
            "link-types" -> ctx.gql.execute(WorkOpsCatalogLinkTypes, Unit).workOps.links.linkTypes.map { WorkOpsGlobalCatalogEntry(it.id, it.name, category = it.category.name, inward = it.inwardLabel, outward = it.outwardLabel, version = it.version) }
            "labels" -> ctx.gql.execute(WorkOpsCatalogLabels, Unit).workOps.labels.global.map { WorkOpsGlobalCatalogEntry(it.id, it.name, color = it.colorHex.orEmpty(), category = it.scope.name, version = it.version) }
            else -> error("Choose a WorkOps global catalog.")
        }
        rows = if (hasOrder) values.sortedBy { it.order.toIntOrNull() ?: 0 } else values
        if (targetId.isNotBlank()) rows.indexOfFirst { it.id == targetId }.takeIf { it >= 0 }?.let { offset = it.toLong() / 25 * 25 }
        loaded = true
    }
    suspend fun retry(ctx: RenderContext) = feedback.perform { reload(ctx) }
    fun open(id: String) {
        require(loaded) { "Reload the catalog first." }
        draft = if (id.isNotBlank()) requireNotNull(rows.firstOrNull { it.id == id }) { "Reload this $entityLabel." }
        else WorkOpsGlobalCatalogEntry(color = when (kind) { "statuses" -> "#6c7388"; "labels" -> "#a78bff"; else -> "#5ec5ff" },
            icon = if (kind == "priorities") "arrow-right" else "check-square", category = when (kind) { "task-types" -> "STANDARD"; "statuses" -> "TODO"; else -> "RELATES_TO" }, order = rows.size.coerceAtMost(99).toString())
        editing = true; resultId = ""; feedback.message = ""; feedback.failed = false
    }
    fun cancel() { editing = false }
    private fun capture(name: String, description: String, color: String, icon: String, category: String, order: String, inward: String, outward: String) {
        draft = draft.copy(name = name, description = description, color = color, icon = icon, category = category, order = order, inward = inward, outward = outward)
    }
    suspend fun reloadDraft(name: String, description: String, color: String, icon: String, category: String, order: String, inward: String, outward: String, ctx: RenderContext) = feedback.perform {
        capture(name, description, color, icon, category, order, inward, outward); reload(ctx)
    }
    suspend fun save(name: String, description: String, color: String, icon: String, category: String, order: String, inward: String, outward: String, ctx: RenderContext) = feedback.perform {
        capture(name, description, color, icon, category, order, inward, outward)
        require(editing && loaded) { "Reload the catalog before saving." }
        require(name.isNotBlank()) { "Enter a name." }
        if (hasColor && kind != "labels") require(color.isNotBlank()) { "Enter a color." }
        if (hasColor) require(color.length <= 7) { "Use a color with up to 7 characters." }
        if (hasIcon) require(icon.isNotBlank()) { "Enter an icon key." }
        val position = if (hasOrder) requireNotNull(order.toIntOrNull()?.takeIf { it in 0..99 }) { "Enter a display order from 0 to 99." } else 0
        val details = description.trim().takeIf(String::isNotBlank)
        val id = draft.id; val version = draft.version
        resultId = when (kind) {
            "task-types" -> {
                val level = requireNotNull(WorkOpsTaskHierarchyLevel.entries.firstOrNull { it.name == category }) { "Choose a hierarchy level." }
                if (id.isBlank()) ctx.gql.execute(WorkOpsCatalogCreateTaskType, WorkOpsCatalogCreateTaskType.Variables(CreateWorkOpsTaskTypeInput(name.trim(), details, icon, color, level))).workOps.taskTypes.create.id
                else ctx.gql.execute(WorkOpsCatalogUpdateTaskType, WorkOpsCatalogUpdateTaskType.Variables(id, UpdateWorkOpsTaskTypeInput(name.trim(), details, icon, color, level, version))).workOps.taskTypes.update.id
            }
            "statuses" -> {
                val status = requireNotNull(WorkOpsStatusCategory.entries.firstOrNull { it.name == category }) { "Choose a category." }
                if (id.isBlank()) ctx.gql.execute(WorkOpsCatalogCreateStatus, WorkOpsCatalogCreateStatus.Variables(CreateWorkOpsStatusInput(name.trim(), details, status, color))).workOps.statuses.create.id
                else ctx.gql.execute(WorkOpsCatalogUpdateStatus, WorkOpsCatalogUpdateStatus.Variables(id, UpdateWorkOpsStatusInput(name.trim(), details, status, color, version))).workOps.statuses.update.id
            }
            "priorities" -> if (id.isBlank()) ctx.gql.execute(WorkOpsCatalogCreatePriority, WorkOpsCatalogCreatePriority.Variables(CreateWorkOpsPriorityInput(name.trim(), details, icon, color, position))).workOps.priorities.create.id
                else ctx.gql.execute(WorkOpsCatalogUpdatePriority, WorkOpsCatalogUpdatePriority.Variables(id, UpdateWorkOpsPriorityInput(name.trim(), details, icon, color, position, version))).workOps.priorities.update.id
            "resolutions" -> if (id.isBlank()) ctx.gql.execute(WorkOpsCatalogCreateResolution, WorkOpsCatalogCreateResolution.Variables(CreateWorkOpsResolutionInput(name.trim(), details, position))).workOps.resolutions.create.id
                else ctx.gql.execute(WorkOpsCatalogUpdateResolution, WorkOpsCatalogUpdateResolution.Variables(id, UpdateWorkOpsResolutionInput(name.trim(), details, position, version))).workOps.resolutions.update.id
            "link-types" -> {
                require(inward.isNotBlank() && outward.isNotBlank()) { "Enter both relationship labels." }
                val relation = requireNotNull(WorkOpsLinkCategory.entries.firstOrNull { it.name == category }) { "Choose a category." }
                if (id.isBlank()) ctx.gql.execute(WorkOpsCatalogCreateLinkType, WorkOpsCatalogCreateLinkType.Variables(CreateWorkOpsTaskLinkTypeInput(name.trim(), inward.trim(), outward.trim(), relation))).workOps.linkTypes.create.id
                else ctx.gql.execute(WorkOpsCatalogUpdateLinkType, WorkOpsCatalogUpdateLinkType.Variables(id, UpdateWorkOpsTaskLinkTypeInput(name.trim(), inward.trim(), outward.trim(), relation, version))).workOps.linkTypes.update.id
            }
            "labels" -> if (id.isBlank()) ctx.gql.execute(WorkOpsCatalogCreateLabel, WorkOpsCatalogCreateLabel.Variables(CreateWorkOpsLabelInput(name.trim(), color.takeIf(String::isNotBlank), WorkOpsLabelScope.GLOBAL))).workOps.labels.create.id
                else ctx.gql.execute(WorkOpsCatalogUpdateLabel, WorkOpsCatalogUpdateLabel.Variables(id, name.trim(), color.takeIf(String::isNotBlank), version)).workOps.labels.update.id
            else -> error("Choose a WorkOps global catalog.")
        }
        editing = false; reload(ctx); feedback.message = "${entityLabel.replaceFirstChar(Char::uppercase)} saved."
    }
    fun beginDelete(id: String) {
        require(loaded) { "Reload the catalog first." }
        deleting = requireNotNull(rows.firstOrNull { it.id == id }) { "Reload this $entityLabel." }; feedback.message = ""; feedback.failed = false
    }
    fun cancelDelete() { deleting = null }
    suspend fun delete(confirmed: Boolean, ctx: RenderContext) = feedback.perform {
        val target = requireNotNull(deleting) { "Choose a $entityLabel first." }
        require(confirmed) { "Confirm deletion." }
        val deleted = when (kind) {
            "task-types" -> ctx.gql.execute(WorkOpsCatalogDeleteTaskType, WorkOpsCatalogDeleteTaskType.Variables(target.id)).workOps.taskTypes.delete
            "statuses" -> ctx.gql.execute(WorkOpsCatalogDeleteStatus, WorkOpsCatalogDeleteStatus.Variables(target.id)).workOps.statuses.delete
            "priorities" -> ctx.gql.execute(WorkOpsCatalogDeletePriority, WorkOpsCatalogDeletePriority.Variables(target.id)).workOps.priorities.delete
            "resolutions" -> ctx.gql.execute(WorkOpsCatalogDeleteResolution, WorkOpsCatalogDeleteResolution.Variables(target.id)).workOps.resolutions.delete
            "link-types" -> ctx.gql.execute(WorkOpsCatalogDeleteLinkType, WorkOpsCatalogDeleteLinkType.Variables(target.id)).workOps.linkTypes.delete
            "labels" -> ctx.gql.execute(WorkOpsCatalogDeleteLabel, WorkOpsCatalogDeleteLabel.Variables(target.id)).workOps.labels.delete
            else -> error("Choose a WorkOps global catalog.")
        }
        check(deleted) { "The $entityLabel was not deleted." }
        deleting = null; if (resultId == target.id) resultId = ""
        reload(ctx); feedback.message = "${entityLabel.replaceFirstChar(Char::uppercase)} deleted."
    }
}

suspend fun workOpsGlobalCatalog(kind: String): WorkOpsGlobalCatalogModel {
    val ctx = currentRenderContext()
    return WorkOpsGlobalCatalogModel(kind, ctx.query["offset"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0, ctx.query["item"].orEmpty()).also { it.retry(ctx) }
}
