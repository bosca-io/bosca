package bosca.workops.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.design.model.SchemaFormOption
import bosca.workops.ui.graphql.*
import kotlinx.serialization.Serializable

/** Program environments use existing MANAGE checks; the global group catalog has its own boundary. */
@Serializable
class WorkOpsEnvironmentsModel(
    var programId: String = "",
    var offset: Long = 0,
    val targetId: String = "",
    var programs: List<WorkOpsPlanningRow> = emptyList(),
    var rows: List<WorkOpsEnvironmentRecord> = emptyList(),
    var types: List<WorkOpsEnvironmentTypeRecord> = emptyList(),
    var loaded: Boolean = false,
    var editing: WorkOpsEnvironmentRecord? = null,
    var creating: Boolean = false,
    var draft: WorkOpsEnvironmentDraft = WorkOpsEnvironmentDraft(),
    var deleting: WorkOpsEnvironmentRecord? = null,
    var groups: List<SchemaFormOption> = emptyList(),
    var groupsLoaded: Boolean = false,
    var groupDraft: String = "",
    var resultId: String = "",
    val feedback: WorkOpsFeedback = WorkOpsFeedback(),
    val groupFeedback: WorkOpsFeedback = WorkOpsFeedback(),
) {
    val selectedProgram get() = programs.firstOrNull { it.id == programId }
    val available get() = loaded && selectedProgram != null
    val pageRows get() = rows.drop(offset.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).take(25)
    val previousHref get() = if (offset > 0) href((offset - 25).coerceAtLeast(0)) else ""
    val nextHref get() = if (rows.size > offset + 25) href(offset + 25) else ""
    val resultHref get() = if (resultId.isBlank()) "" else href(0) + "&item=${workOpsEncode(resultId)}#workops-environment-${workOpsEncode(resultId)}"
    val typeChoices get() = types.map { SchemaFormOption(it.id, it.name) }.let { choices ->
        if (draft.typeId.isNotBlank() && choices.none { it.value == draft.typeId }) choices + SchemaFormOption(draft.typeId, "Type unavailable", disabled = true) else choices
    }
    val groupChoices get() = groups.let { choices ->
        if (groupDraft.isNotBlank() && choices.none { it.value == groupDraft }) choices + SchemaFormOption(groupDraft, "Group unavailable", disabled = true) else choices
    }
    val visibleGrants get() = if (loaded) rows.firstOrNull { it.id == editing?.id }?.permissions.orEmpty() else emptyList()
    fun typeName(id: String) = types.firstOrNull { it.id == id }?.name ?: "Type unavailable"
    fun groupName(id: String) = groups.firstOrNull { it.value == id }?.label ?: "Group unavailable"
    fun sourceNames(ids: List<String>) = ids.joinToString(", ") { id -> rows.firstOrNull { it.id == id }?.name ?: "Environment unavailable" }.ifBlank { "None" }
    private fun href(page: Long) = "/workops/releases/environments?programId=${workOpsEncode(programId)}&offset=$page"

    suspend fun reload(ctx: RenderContext) {
        loaded = false
        programs = ctx.gql.execute(WorkOpsEnvironmentPrograms, Unit).workOps.programs.all.map {
            WorkOpsPlanningRow(id = it.id, key = it.key, name = it.name)
        }
        if (programId.isBlank()) programId = programs.firstOrNull()?.id.orEmpty()
        if (selectedProgram == null) { rows = emptyList(); types = emptyList(); loaded = true; return }
        val data = ctx.gql.execute(WorkOpsProgramEnvironments, WorkOpsProgramEnvironments.Variables(programId)).workOps.multiRepo
        rows = data.environments.map { it.environmentRecord() }.sortedBy { it.order }
        types = data.environmentTypes.map { it.environmentTypeRecord() }.sortedBy { it.order }
        if (targetId.isNotBlank()) rows.indexOfFirst { it.id == targetId }.takeIf { it >= 0 }?.let { offset = it.toLong() / 25 * 25 }
        loaded = true
    }
    suspend fun retry(ctx: RenderContext) = feedback.perform { reload(ctx) }
    private suspend fun loadGroups(ctx: RenderContext) = groupFeedback.perform {
        groupsLoaded = false; groups = emptyList()
        val choices = mutableListOf<SchemaFormOption>(); var page = 0L
        do {
            val values = ctx.gql.execute(WorkOpsEnvironmentGroups, WorkOpsEnvironmentGroups.Variables(page)).security.groups.all
            choices += values.map { SchemaFormOption(it.id, it.name) }
            page += values.size
        } while (values.size == 100)
        groups = choices; groupsLoaded = true
    }
    suspend fun open(id: String, ctx: RenderContext) = feedback.perform {
        require(available) { "Reload an available program first." }
        editing = if (id.isBlank()) null else requireNotNull(rows.firstOrNull { it.id == id }) { "Reload this environment." }
        creating = id.isBlank()
        draft = editing?.let { WorkOpsEnvironmentDraft(it.name, it.key, it.description, it.typeId, it.autoPromote, true) } ?: WorkOpsEnvironmentDraft()
        groupDraft = ""; resultId = ""; groupFeedback.message = ""; groupFeedback.failed = false
        if (editing != null) loadGroups(ctx)
    }
    fun cancel() { creating = false; editing = null }
    private fun capture(name: String, key: String, description: String, type: String, auto: Boolean, manual: Boolean, group: String) {
        draft = WorkOpsEnvironmentDraft(name, editing?.key ?: key, description, type, auto, editing != null || manual)
        groupDraft = group
    }
    suspend fun reloadDraft(name: String, key: String, description: String, type: String, auto: Boolean, manual: Boolean, group: String, ctx: RenderContext) = feedback.perform {
        capture(name, key, description, type, auto, manual, group); reload(ctx)
        if (editing != null) loadGroups(ctx)
    }
    suspend fun save(name: String, key: String, description: String, type: String, auto: Boolean, manual: Boolean, group: String, ctx: RenderContext) = feedback.perform {
        capture(name, key, description, type, auto, manual, group)
        require(creating || editing != null) { "Open an environment first." }
        require(available) { "Reload an available program before saving." }
        require(name.isNotBlank()) { "Enter a name." }
        require(types.any { it.id == type }) { "Choose an available environment type." }
        val details = description.trim().takeIf(String::isNotBlank)
        val target = editing
        resultId = if (target == null) {
            require(key.isNotBlank()) { "Enter a key." }
            ctx.gql.execute(WorkOpsCreateEnvironment, WorkOpsCreateEnvironment.Variables(CreateWorkOpsEnvironmentInput(
                programId = programId, key = key.trim(), name = name.trim(), description = details,
                displayOrder = rows.size, typeId = type, autoPromote = auto))).workOps.multiRepo.createEnvironment.id
        } else ctx.gql.execute(WorkOpsUpdateEnvironment, WorkOpsUpdateEnvironment.Variables(target.id,
            UpdateWorkOpsEnvironmentInput(name = name.trim(), description = details, typeId = type, autoPromote = auto,
                promotionSourceIds = target.sources, requiresApproval = target.requiresApproval), target.version)).workOps.multiRepo.updateEnvironment.id
        cancel(); reload(ctx); feedback.message = "Environment saved."
    }
    fun beginDelete(id: String) {
        require(available) { "Reload an available program first." }
        deleting = requireNotNull(rows.firstOrNull { it.id == id }) { "Reload this environment." }
        feedback.message = ""; feedback.failed = false
    }
    fun cancelDelete() { deleting = null }
    suspend fun delete(confirmed: Boolean, ctx: RenderContext) = feedback.perform {
        val target = requireNotNull(deleting) { "Choose an environment first." }
        require(confirmed) { "Confirm deletion." }
        ctx.gql.execute(WorkOpsDeleteEnvironment, WorkOpsDeleteEnvironment.Variables(target.id))
        deleting = null; if (resultId == target.id) resultId = ""
        reload(ctx); feedback.message = "Environment deleted."
    }
    suspend fun grant(name: String, key: String, description: String, type: String, auto: Boolean, manual: Boolean, group: String, ctx: RenderContext) = feedback.perform {
        capture(name, key, description, type, auto, manual, group)
        val target = requireNotNull(editing) { "Edit an environment first." }
        require(groupsLoaded && groups.any { it.value == group }) { "Choose an available security group." }
        ctx.gql.execute(WorkOpsGrantEnvironment, WorkOpsGrantEnvironment.Variables(target.id, group))
        groupDraft = ""; reload(ctx); feedback.message = "Environment execute grant added."
    }
    suspend fun revoke(groupId: String, action: String, name: String, key: String, description: String, type: String, auto: Boolean, manual: Boolean, group: String, ctx: RenderContext) = feedback.perform {
        capture(name, key, description, type, auto, manual, group)
        val target = requireNotNull(editing) { "Edit an environment first." }
        require(visibleGrants.any { it.groupId == groupId && it.action == action }) { "Reload this environment grant." }
        val permission = requireNotNull(PermissionAction.entries.firstOrNull { it.name == action }) { "Choose a current permission action." }
        ctx.gql.execute(WorkOpsRevokeEnvironment, WorkOpsRevokeEnvironment.Variables(target.id, groupId, permission))
        reload(ctx); feedback.message = "Explicit environment grant removed."
    }
}

suspend fun workOpsEnvironments(): WorkOpsEnvironmentsModel {
    val ctx = currentRenderContext()
    return WorkOpsEnvironmentsModel(ctx.query["programId"].orEmpty(), ctx.query["offset"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0,
        ctx.query["item"].orEmpty()).also { it.retry(ctx) }
}
