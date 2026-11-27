package bosca.workops.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.design.model.NavigationItem
import bosca.workops.ui.graphql.*
import kotlinx.serialization.Serializable

/** WorkOps hierarchy lists share their editing interaction while each write uses its owning API. */
@Serializable
class WorkOpsPlanningModel(
    val kind: String,
    val tab: String = "active",
    val offset: Long = 0,
    var rows: List<WorkOpsPlanningRow> = emptyList(),
    var hasNext: Boolean = false,
    var parents: List<WorkOpsPlanningRow> = emptyList(),
    var editing: Boolean = false,
    var draft: WorkOpsPlanningRow = WorkOpsPlanningRow(),
    var archiveTarget: WorkOpsPlanningRow? = null,
    var moveTarget: WorkOpsPlanningRow? = null,
    var moveProgramId: String = "",
    val initialParentId: String = "",
    val feedback: WorkOpsFeedback = WorkOpsFeedback(),
) {
    val title get() = when (kind) { "portfolio" -> "Portfolios"; "program" -> "Programs"; else -> "Projects" }
    val subtitle get() = when (kind) { "portfolio" -> "Organize related programs"; "program" -> "Plan releases across projects"; else -> "Manage projects and their owners" }
    val entityLabel get() = when (kind) { "portfolio" -> "portfolio"; "program" -> "program"; else -> "project" }
    val parentLabel get() = if (kind == "program") "Portfolio" else "Program"
    val href get() = "/workops/${title.lowercase()}"
    val tabs get() = listOf("active" to "Active", "all" to "All", "archived" to "Archived")
        .map { (value, label) -> NavigationItem(label, "$href?tab=$value", value == tab) }
    val previousHref get() = if (offset > 0) "$href?tab=$tab&offset=${(offset - 25).coerceAtLeast(0)}" else ""
    val nextHref get() = if (hasNext) "$href?tab=$tab&offset=${offset + 25}" else ""
    val parentChoices get() = parents.filterNot { it.archived }
    val moveChoices get() = parentChoices.filterNot { it.id == moveTarget?.parentId }
    val editReturn get() = if (draft.id.isBlank()) "#workops-planning-new" else "#workops-planning-edit-${draft.id}"
    val archiveTitle get() = if (archiveTarget?.archived == true) "Unarchive $entityLabel" else "Archive $entityLabel"

    private fun visible(row: WorkOpsPlanningRow) = when (tab) { "active" -> !row.archived; "archived" -> row.archived; else -> true }

    suspend fun reloadParents(ctx: RenderContext) {
        parents = when (kind) {
            "program" -> ctx.gql.execute(WorkOpsPlanningPortfolioChoices, Unit).workOps.portfolios.all.map {
                WorkOpsPlanningRow(id = it.id, key = it.key, name = it.name, archived = it.archivedAt != null)
            }
            "project" -> ctx.gql.execute(WorkOpsPlanningProgramChoices, Unit).workOps.programs.all.map {
                WorkOpsPlanningRow(id = it.id, key = it.key, name = it.name, archived = it.archivedAt != null)
            }
            else -> emptyList()
        }
    }

    suspend fun reload(ctx: RenderContext) {
        require(kind in listOf("portfolio", "program", "project")) { "Unknown planning list." }
        reloadParents(ctx)
        val values = when (kind) {
            "portfolio" -> {
                val data = ctx.gql.execute(WorkOpsPlanningPortfolios, WorkOpsPlanningPortfolios.Variables(offset)).workOps
                hasNext = data.portfolios.all.size > 25
                data.portfolios.all.take(25).map {
                    WorkOpsPlanningRow(it.id, it.key, it.name, it.description.orEmpty(), it.ownerProfileId, it.owner?.name.orEmpty(),
                        archived = it.archivedAt != null, modified = it.modifiedAt, version = it.version,
                        programCount = data.programs.all.count { program -> program.portfolio.id == it.id })
                }.filter(::visible)
            }
            "program" -> {
                val data = ctx.gql.execute(WorkOpsPlanningPrograms, Unit).workOps.programs.all
                val names = parents.associate { it.id to it.name }.toMutableMap()
                for (parent in data.map { it.portfolio.id }.distinct().filterNot(names::containsKey)) {
                    ctx.gql.execute(WorkOpsPlanningPortfolioName, WorkOpsPlanningPortfolioName.Variables(parent)).workOps.portfolios.portfolio?.let { names[it.id] = it.name }
                }
                data.map {
                    WorkOpsPlanningRow(it.id, it.key, it.name, it.description.orEmpty(), it.ownerProfileId, it.owner?.name.orEmpty(),
                        it.portfolio.id, names[it.portfolio.id] ?: "Portfolio unavailable", it.archivedAt != null, it.modifiedAt, it.version,
                        startDate = it.startDate, targetDate = it.targetDate)
                }.filter(::visible)
            }
            else -> ctx.gql.execute(WorkOpsPlanningProjects, Unit).workOps.projects.all.map {
                WorkOpsPlanningRow(it.id, it.key, it.name, it.description.orEmpty(), it.ownerProfileId, it.owner?.name.orEmpty(),
                    it.program.id, parents.firstOrNull { parent -> parent.id == it.program.id }?.name ?: "Program unavailable",
                    it.archivedAt != null, it.modifiedAt, it.version, defaultTaskTypeSchemeId = it.defaultTaskTypeSchemeId,
                    taskCreationFormSchemaKey = it.taskCreationFormSchemaKey)
            }.filter(::visible)
        }
        if (kind == "portfolio") rows = values
        else {
            val page = values.drop(offset.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).take(26)
            rows = page.take(25); hasNext = page.size > 25
        }
    }
    suspend fun retry(ctx: RenderContext) = feedback.perform { reload(ctx) }
    suspend fun open(ctx: RenderContext) = feedback.perform {
        editing = true
        draft = WorkOpsPlanningRow(parentId = initialParentId)
        reloadParents(ctx)
        if (draft.parentId.isBlank()) draft = draft.copy(parentId = parentChoices.singleOrNull()?.id.orEmpty())
    }
    fun edit(id: String) {
        rows.firstOrNull { it.id == id }?.let { draft = it; editing = true; feedback.message = ""; feedback.failed = false }
    }
    fun cancelEdit() { editing = false }
    private fun capture(key: String, name: String, description: String, owner: String, parent: String) {
        draft = draft.copy(key = if (draft.id.isBlank()) key.uppercase() else draft.key, name = name, description = description,
            ownerId = owner, ownerName = if (owner == draft.ownerId) draft.ownerName else "",
            parentId = if (draft.id.isBlank()) parent else draft.parentId)
    }
    suspend fun refreshChoices(key: String, name: String, description: String, owner: String, parent: String, ctx: RenderContext) = feedback.perform {
        capture(key, name, description, owner, parent)
        reloadParents(ctx)
        resolveChangedOwner(ctx)
    }
    private suspend fun resolveChangedOwner(ctx: RenderContext) {
        if (draft.ownerId.isNotBlank() && (draft.id.isBlank() || draft.ownerId != draft.originalOwnerId)) {
            val profile = requireNotNull(ctx.gql.execute(WorkOpsTaskProfile, WorkOpsTaskProfile.Variables(draft.ownerId)).profiles.profile) { "The selected owner is unavailable." }
            draft = draft.copy(ownerName = profile.name)
        }
    }
    suspend fun save(key: String, name: String, description: String, owner: String, parent: String, ctx: RenderContext) = feedback.perform {
        capture(key, name, description, owner, parent)
        require(editing) { "Open the $entityLabel editor first." }
        require(Regex("^[A-Z][A-Z0-9_]{1,9}$").matches(draft.key)) { "Enter a key of 2–10 uppercase letters, digits or underscores, starting with a letter." }
        require(name.isNotBlank() && owner.isNotBlank()) { "Enter a name and choose an owner." }
        resolveChangedOwner(ctx)
        if (kind != "portfolio" && draft.id.isBlank()) require(parentChoices.any { it.id == draft.parentId }) { "Choose an available active ${parentLabel.lowercase()}." }
        when (kind) {
            "portfolio" -> {
                val input = WorkOpsPortfolioInput(draft.key, name.trim(), description.takeIf(String::isNotBlank), owner)
                if (draft.id.isBlank()) ctx.gql.execute(WorkOpsCreatePortfolio, WorkOpsCreatePortfolio.Variables(input))
                else ctx.gql.execute(WorkOpsUpdatePortfolio, WorkOpsUpdatePortfolio.Variables(draft.id, input, draft.version))
            }
            "program" -> {
                val input = WorkOpsProgramInput(draft.parentId, draft.key, name.trim(), description.takeIf(String::isNotBlank), owner, draft.startDate, draft.targetDate)
                if (draft.id.isBlank()) ctx.gql.execute(WorkOpsCreateProgram, WorkOpsCreateProgram.Variables(input))
                else ctx.gql.execute(WorkOpsUpdateProgram, WorkOpsUpdateProgram.Variables(draft.id, input, draft.version))
            }
            else -> {
                val input = WorkOpsProjectInput(draft.parentId, draft.key, name.trim(), description.takeIf(String::isNotBlank), owner,
                    draft.defaultTaskTypeSchemeId, draft.taskCreationFormSchemaKey)
                if (draft.id.isBlank()) ctx.gql.execute(WorkOpsCreateProject, WorkOpsCreateProject.Variables(input))
                else ctx.gql.execute(WorkOpsUpdateProject, WorkOpsUpdateProject.Variables(draft.id, input, draft.version))
            }
        }
        editing = false
        reload(ctx)
        feedback.message = "${entityLabel.replaceFirstChar(Char::uppercase)} saved."
    }
    fun beginArchive(id: String) {
        archiveTarget = rows.firstOrNull { it.id == id }; feedback.message = ""; feedback.failed = false
    }
    fun cancelArchive() { archiveTarget = null }
    suspend fun archive(confirmed: Boolean, ctx: RenderContext) = feedback.perform {
        val target = requireNotNull(archiveTarget) { "Choose a $entityLabel first." }
        require(confirmed) { "Confirm this change." }
        when (kind) {
            "portfolio" -> if (target.archived) ctx.gql.execute(WorkOpsUnarchivePortfolio, WorkOpsUnarchivePortfolio.Variables(target.id, target.version)) else ctx.gql.execute(WorkOpsArchivePortfolio, WorkOpsArchivePortfolio.Variables(target.id, target.version))
            "program" -> if (target.archived) ctx.gql.execute(WorkOpsUnarchiveProgram, WorkOpsUnarchiveProgram.Variables(target.id, target.version)) else ctx.gql.execute(WorkOpsArchiveProgram, WorkOpsArchiveProgram.Variables(target.id, target.version))
            else -> if (target.archived) ctx.gql.execute(WorkOpsUnarchiveProject, WorkOpsUnarchiveProject.Variables(target.id, target.version)) else ctx.gql.execute(WorkOpsArchiveProject, WorkOpsArchiveProject.Variables(target.id, target.version))
        }
        archiveTarget = null
        reload(ctx)
        feedback.message = "${entityLabel.replaceFirstChar(Char::uppercase)} ${if (target.archived) "unarchived" else "archived"}."
    }
    suspend fun beginMove(id: String, ctx: RenderContext) = feedback.perform {
        require(kind == "project") { "Only projects can be moved here." }
        moveTarget = requireNotNull(rows.firstOrNull { it.id == id }) { "Reload the projects first." }
        moveProgramId = ""
        reloadParents(ctx)
    }
    fun cancelMove() { moveTarget = null }
    suspend fun move(program: String, ctx: RenderContext) = feedback.perform {
        moveProgramId = program
        val target = requireNotNull(moveTarget) { "Choose a project first." }
        require(kind == "project" && moveChoices.any { it.id == program }) { "Choose another available active program." }
        ctx.gql.execute(WorkOpsMoveProject, WorkOpsMoveProject.Variables(target.id, program, target.version))
        moveTarget = null
        reload(ctx)
        feedback.message = "Project moved."
    }
}

suspend fun workOpsPlanning(kind: String): WorkOpsPlanningModel {
    val ctx = currentRenderContext()
    return WorkOpsPlanningModel(kind, ctx.query["tab"].takeIf { it in setOf("active", "all", "archived") } ?: "active",
        ctx.query["offset"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0,
        initialParentId = ctx.query[if (kind == "program") "portfolioId" else "programId"].orEmpty()).also { model ->
        model.retry(ctx)
        if (ctx.query["new"] == "1" && !model.feedback.failed) model.open(ctx)
    }
}
