package bosca.workops.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.workops.ui.graphql.*
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlinx.serialization.Serializable

/** Both milestone entry points share the program-owned lifecycle and its existing MANAGE checks. */
@Serializable
class WorkOpsMilestonesModel(
    val baseHref: String = "/workops/milestones",
    var programId: String = "",
    var offset: Long = 0,
    var programs: WorkOpsMilestoneProgramsData? = null,
    var data: WorkOpsMilestonesData? = null,
    var creating: Boolean = false,
    var nameDraft: String = "",
    var descriptionDraft: String = "",
    var dateDraft: String = "",
    var createProgramId: String = "",
    var closingId: String = "",
    var closingName: String = "",
    var closeVersion: Long = 0,
    val feedback: WorkOpsFeedback = WorkOpsFeedback(),
    var createdId: String = "",
) {
    val choices get() = programs?.workOps?.programs?.all.orEmpty()
    val selectedName get() = choices.firstOrNull { it.id == programId }?.name ?: "Program unavailable"
    val programHref get() = "$baseHref?programId=${workOpsEncode(programId)}"
    val createdHref get() = data?.workOps?.milestones?.byProgram.orEmpty().indexOfFirst { it.id == createdId }
        .takeIf { it >= 0 }?.let { "$programHref&offset=${it / 25 * 25}#workops-milestone-${workOpsEncode(createdId)}" }.orEmpty()
    val rows get() = data?.workOps?.milestones?.byProgram.orEmpty().drop(offset.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).take(25)
    val hasNext get() = data?.workOps?.milestones?.byProgram.orEmpty().size.toLong() > offset + 25
    val previousHref get() = if (offset > 0) "$baseHref?programId=${workOpsEncode(programId)}&offset=${(offset - 25).coerceAtLeast(0)}" else ""
    val nextHref get() = if (hasNext) "$baseHref?programId=${workOpsEncode(programId)}&offset=${offset + 25}" else ""

    fun dueLabel(targetDate: String?, closed: Boolean, today: LocalDate = LocalDate.now(ZoneOffset.UTC)): String {
        if (closed || targetDate == null) return ""
        val remaining = ChronoUnit.DAYS.between(today, OffsetDateTime.parse(targetDate).toLocalDate())
        return when {
            remaining < 0 -> "${-remaining} ${if (remaining == -1L) "day" else "days"} overdue"
            remaining == 0L -> "Due today"
            else -> "$remaining ${if (remaining == 1L) "day" else "days"} left"
        }
    }

    suspend fun reload(ctx: RenderContext) {
        programs = ctx.gql.execute(WorkOpsMilestonePrograms, Unit)
        if (programId.isBlank()) programId = choices.firstOrNull()?.id.orEmpty()
        data = null
        if (programId.isNotBlank()) {
            require(choices.any { it.id == programId }) { "The selected program is unavailable. Choose another program." }
            data = ctx.gql.execute(WorkOpsMilestones, WorkOpsMilestones.Variables(programId))
        }
    }
    suspend fun retry(ctx: RenderContext) = feedback.perform { reload(ctx) }
    fun open() {
        creating = true; createProgramId = programId; nameDraft = ""; descriptionDraft = ""; dateDraft = ""; createdId = ""
        feedback.message = ""; feedback.failed = false
    }
    fun cancelCreate() { creating = false }
    suspend fun create(name: String, description: String, date: String, program: String, ctx: RenderContext) = feedback.perform {
        nameDraft = name; descriptionDraft = description; dateDraft = date; createProgramId = program
        require(creating) { "Open the milestone form first." }
        require(name.isNotBlank()) { "Enter a milestone name." }
        require(choices.any { it.id == program }) { "Choose an available program." }
        val target = date.takeIf(String::isNotBlank)?.let { LocalDate.parse(it).atStartOfDay().atOffset(ZoneOffset.UTC).toString() }
        createdId = ctx.gql.execute(WorkOpsCreateMilestone, WorkOpsCreateMilestone.Variables(CreateWorkOpsMilestoneInput(
            programId = program, name = name.trim(), description = description.takeIf(String::isNotBlank), targetDate = target,
        ))).workOps.milestones.create.id
        creating = false; programId = program; offset = 0
        reload(ctx)
        feedback.message = "Milestone created."
    }
    fun beginClose(id: String) {
        val row = rows.firstOrNull { it.id == id && it.state == WorkOpsMilestoneState.OPEN } ?: return
        closingId = id; closingName = row.name; closeVersion = row.version
        feedback.message = ""; feedback.failed = false
    }
    fun cancelClose() { closingId = "" }
    suspend fun close(confirmed: Boolean, ctx: RenderContext) = feedback.perform {
        require(closingId.isNotBlank() && confirmed) { "Confirm closing this milestone." }
        ctx.gql.execute(WorkOpsCloseMilestone, WorkOpsCloseMilestone.Variables(closingId, closeVersion))
        closingId = ""
        reload(ctx)
        feedback.message = "Milestone closed."
    }
}

suspend fun workOpsMilestones(): WorkOpsMilestonesModel {
    val ctx = currentRenderContext()
    return WorkOpsMilestonesModel(if (ctx.requestPath.startsWith("/workops/settings/")) "/workops/settings/milestones" else "/workops/milestones",
        ctx.query["programId"].orEmpty(), ctx.query["offset"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0).also { model ->
        model.retry(ctx)
        if (ctx.query["new"] == "1" && !model.feedback.failed) model.open()
    }
}
