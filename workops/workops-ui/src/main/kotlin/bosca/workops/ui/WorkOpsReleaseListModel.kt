package bosca.workops.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.workops.ui.graphql.WorkOpsReleaseList
import bosca.workops.ui.graphql.WorkOpsReleasePrograms
import bosca.workops.ui.graphql.WorkOpsReleaseRunStatuses
import kotlinx.serialization.Serializable

/** Directory refreshes do not own the separate release builder's draft or component choices. */
@Serializable
class WorkOpsReleaseListModel(
    var programId: String = "",
    var offset: Long = 0,
    var programs: List<WorkOpsPlanningRow> = emptyList(),
    var rows: List<WorkOpsReleaseRecord> = emptyList(),
    var loaded: Boolean = false,
    val feedback: WorkOpsFeedback = WorkOpsFeedback(),
) {
    val selectedProgram get() = programs.firstOrNull { it.id == programId }
    val pageRows get() = rows.drop(offset.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).take(25)
    val previousHref get() = if (offset > 0) href((offset - 25).coerceAtLeast(0)) else ""
    val nextHref get() = if (rows.size > offset + 25) href(offset + 25) else ""
    private fun href(page: Long) = "/workops/releases?programId=${workOpsEncode(programId)}&offset=$page"

    suspend fun reload(ctx: RenderContext) {
        loaded = false
        programs = ctx.gql.execute(WorkOpsReleasePrograms, Unit).workOps.programs.all.map {
            WorkOpsPlanningRow(id = it.id, key = it.key, name = it.name)
        }
        if (programId.isBlank()) programId = programs.firstOrNull()?.id.orEmpty()
        if (selectedProgram == null) { rows = emptyList(); loaded = true; return }
        rows = ctx.gql.execute(WorkOpsReleaseList, WorkOpsReleaseList.Variables(programId)).workOps.crossProject.releases.map { it.releaseRecord() }
        for (row in pageRows) {
            if (row.released.isBlank()) {
                val result = loadStatus(row, ctx)
                rows = rows.map { if (it.id == row.id) result else it }
            }
        }
        loaded = true
    }
    suspend fun retry(ctx: RenderContext) = feedback.perform { reload(ctx) }
    suspend fun retryStatus(id: String, ctx: RenderContext) = feedback.perform {
        require(loaded) { "Reload releases first." }
        val row = requireNotNull(pageRows.firstOrNull { it.id == id }) { "Reload this release page." }
        val result = if (row.released.isBlank()) loadStatus(row, ctx) else row
        rows = rows.map { if (it.id == id) result else it }
    }
    private suspend fun loadStatus(row: WorkOpsReleaseRecord, ctx: RenderContext): WorkOpsReleaseRecord {
        val outcome = WorkOpsFeedback()
        var result = row
        outcome.perform {
            val latest = ctx.gql.execute(WorkOpsReleaseRunStatuses, WorkOpsReleaseRunStatuses.Variables(row.id)).workOps.crossProject.releaseRuns.firstOrNull()
            result = row.copy(latestRunId = latest?.runId.orEmpty(), latestStatus = latest?.status.orEmpty(), statusLoaded = true, statusError = "")
        }
        return if (outcome.failed) row.copy(latestRunId = "", latestStatus = "", statusLoaded = false, statusError = outcome.message) else result
    }
}

suspend fun workOpsReleaseList(): WorkOpsReleaseListModel {
    val ctx = currentRenderContext()
    return WorkOpsReleaseListModel(ctx.query["programId"].orEmpty(), ctx.query["offset"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0).also { it.retry(ctx) }
}
