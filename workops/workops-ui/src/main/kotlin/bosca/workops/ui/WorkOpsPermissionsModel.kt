package bosca.workops.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.design.model.NavigationItem
import bosca.design.model.SchemaFormOption
import bosca.workops.ui.graphql.*
import kotlinx.serialization.Serializable

/** Direct grants and group discovery retain their separate API permission boundaries. */
@Serializable
class WorkOpsPermissionsModel(
    val kind: String = "portfolio", val entityId: String = "", var offset: Long = 0, val targetId: String = "",
    var entities: List<WorkOpsPlanningRow> = emptyList(), var choicesLoaded: Boolean = false,
    var groups: List<SchemaFormOption> = emptyList(), var groupsLoaded: Boolean = false,
    var rows: List<WorkOpsGrantRecord> = emptyList(), var loaded: Boolean = false,
    var editing: Boolean = false, var groupDraft: String = "", var actionDraft: String = "VIEW",
    var deleting: WorkOpsGrantRecord? = null, var resultId: String = "",
    val feedback: WorkOpsFeedback = WorkOpsFeedback(), val choiceFeedback: WorkOpsFeedback = WorkOpsFeedback(), val groupFeedback: WorkOpsFeedback = WorkOpsFeedback(),
) {
    val selected get() = if (choicesLoaded) entities.firstOrNull { it.id == entityId } else null
    val entityLabel get() = kind.replaceFirstChar(Char::uppercase)
    val tabs get() = listOf("portfolio", "program", "project").map { NavigationItem(it.replaceFirstChar(Char::uppercase), "/workops/settings/permissions?kind=$it", it == kind) }
    val actions get() = listOf("VIEW", "EDIT", "DELETE", "MANAGE", "LIST").map { SchemaFormOption(it, it) }
    val groupChoices get() = groups.let { options -> if (groupDraft.isNotBlank() && options.none { it.value == groupDraft }) options + SchemaFormOption(groupDraft, "Group unavailable") else options }
    val pageRows get() = rows.drop(offset.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).take(25)
    val previousHref get() = if (offset > 0) href((offset - 25).coerceAtLeast(0)) else ""
    val nextHref get() = if (rows.size > offset + 25) href(offset + 25) else ""
    val resultHref get() = if (resultId.isBlank()) "" else href(0) + "&grant=${workOpsEncode(resultId)}#workops-grant-${workOpsEncode(resultId)}"
    private fun href(page: Long) = "/workops/settings/permissions?kind=$kind&entityId=${workOpsEncode(entityId)}&offset=$page"

    /** Refreshes entity choices, group choices and the selected entity's grants in order. */
    suspend fun refresh(ctx: RenderContext) {
        retryChoices(ctx)
        retryGroups(ctx)
        if (entityId.isNotBlank()) retry(ctx)
    }

    suspend fun retryChoices(ctx: RenderContext) = choiceFeedback.perform {
        choicesLoaded = false
        entities = when (kind) {
            "portfolio" -> buildList {
                var page = 0L
                do {
                    val data = ctx.gql.execute(WorkOpsPermissionPortfolioChoices, WorkOpsPermissionPortfolioChoices.Variables(page)).workOps.portfolios.all
                    addAll(data.take(100).map { WorkOpsPlanningRow(it.id, it.key, it.name) })
                    page += 100
                } while (data.size > 100)
            }
            "program" -> ctx.gql.execute(WorkOpsPermissionProgramChoices, Unit).workOps.programs.all.map { WorkOpsPlanningRow(it.id, it.key, it.name) }
            "project" -> ctx.gql.execute(WorkOpsVersionProjects, Unit).workOps.projects.all.map { WorkOpsPlanningRow(it.id, it.key, it.name) }
            else -> error("Choose a portfolio, program or project.")
        }
        choicesLoaded = true
    }
    suspend fun retryGroups(ctx: RenderContext) = groupFeedback.perform {
        groupsLoaded = false
        groups = buildList {
            var page = 0L
            do {
                val data = ctx.gql.execute(WorkOpsPermissionGroups, WorkOpsPermissionGroups.Variables(page)).security.groups.all
                addAll(data.take(100).map { SchemaFormOption(it.id, it.name) })
                page += 100
            } while (data.size > 100)
        }
        groupsLoaded = true
    }
    private suspend fun reload(ctx: RenderContext) {
        loaded = false
        requireNotNull(selected) { "Choose an available $kind." }
        rows = when (kind) {
            "portfolio" -> requireNotNull(ctx.gql.execute(WorkOpsPortfolioGrants, WorkOpsPortfolioGrants.Variables(entityId)).workOps.portfolios.portfolio) { "This portfolio is unavailable." }.permissions.map { WorkOpsGrantRecord(it.groupId, it.group.name, it.action.name) }
            "program" -> requireNotNull(ctx.gql.execute(WorkOpsProgramGrants, WorkOpsProgramGrants.Variables(entityId)).workOps.programs.program) { "This program is unavailable." }.permissions.map { WorkOpsGrantRecord(it.groupId, it.group.name, it.action.name) }
            "project" -> requireNotNull(ctx.gql.execute(WorkOpsProjectGrants, WorkOpsProjectGrants.Variables(entityId)).workOps.projects.project) { "This project is unavailable." }.permissions.map { WorkOpsGrantRecord(it.groupId, it.group.name, it.action.name) }
            else -> error("Choose a portfolio, program or project.")
        }.sortedWith(compareBy<WorkOpsGrantRecord> { it.groupName.lowercase() }.thenBy { it.action }.thenBy { it.groupId })
        if (targetId.isNotBlank()) rows.indexOfFirst { it.key == targetId }.takeIf { it >= 0 }?.let { offset = it.toLong() / 25 * 25 }
        loaded = true
    }
    suspend fun retry(ctx: RenderContext) = feedback.perform { reload(ctx) }
    fun open() {
        require(selected != null && loaded && groupsLoaded) { "Reload entity grants and group choices first." }
        editing = true; groupDraft = ""; actionDraft = "VIEW"; resultId = ""; feedback.message = ""; feedback.failed = false
    }
    fun cancel() { editing = false }
    suspend fun reloadDraft(groupId: String, action: String, ctx: RenderContext) {
        groupDraft = groupId; actionDraft = action; retryGroups(ctx)
    }
    suspend fun save(groupId: String, action: String, ctx: RenderContext) = feedback.perform {
        groupDraft = groupId; actionDraft = action
        require(editing && selected != null) { "Choose an available $kind before adding a grant." }
        require(groupId.isNotBlank()) { "Choose a group." }
        require(actions.any { it.value == action }) { "Choose an action." }
        val parsed = PermissionAction.valueOf(action)
        val saved = when (kind) {
            "portfolio" -> ctx.gql.execute(WorkOpsPortfolioGrant, WorkOpsPortfolioGrant.Variables(entityId, groupId, parsed)).workOps.portfolios.addPermission
            "program" -> ctx.gql.execute(WorkOpsProgramGrant, WorkOpsProgramGrant.Variables(entityId, groupId, parsed)).workOps.programs.addPermission
            "project" -> ctx.gql.execute(WorkOpsProjectGrant, WorkOpsProjectGrant.Variables(entityId, groupId, parsed)).workOps.projects.addPermission
            else -> error("Choose a portfolio, program or project.")
        }
        check(saved) { "The permission was not added." }
        resultId = "$groupId-$action"; editing = false; reload(ctx); feedback.message = "Permission added."
    }
    fun beginDelete(key: String) {
        require(loaded) { "Reload grants first." }
        deleting = requireNotNull(rows.firstOrNull { it.key == key }) { "Reload this permission." }; feedback.message = ""; feedback.failed = false
    }
    fun cancelDelete() { deleting = null }
    suspend fun delete(confirmed: Boolean, ctx: RenderContext) = feedback.perform {
        val target = requireNotNull(deleting) { "Choose a permission first." }
        require(confirmed) { "Confirm removal." }
        val action = PermissionAction.valueOf(target.action)
        val removed = when (kind) {
            "portfolio" -> ctx.gql.execute(WorkOpsPortfolioRevoke, WorkOpsPortfolioRevoke.Variables(entityId, target.groupId, action)).workOps.portfolios.removePermission
            "program" -> ctx.gql.execute(WorkOpsProgramRevoke, WorkOpsProgramRevoke.Variables(entityId, target.groupId, action)).workOps.programs.removePermission
            "project" -> ctx.gql.execute(WorkOpsProjectRevoke, WorkOpsProjectRevoke.Variables(entityId, target.groupId, action)).workOps.projects.removePermission
            else -> error("Choose a portfolio, program or project.")
        }
        check(removed) { "The permission was not removed." }
        deleting = null; if (resultId == target.key) resultId = ""
        reload(ctx); feedback.message = "Permission removed."
    }
}

suspend fun workOpsPermissions(): WorkOpsPermissionsModel {
    val ctx = currentRenderContext()
    return WorkOpsPermissionsModel(ctx.query["kind"].takeIf { it in setOf("portfolio", "program", "project") } ?: "portfolio", ctx.query["entityId"].orEmpty(), ctx.query["offset"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0, ctx.query["grant"].orEmpty()).also {
        it.retryChoices(ctx); it.retryGroups(ctx); if (it.entityId.isNotBlank()) it.retry(ctx)
    }
}
