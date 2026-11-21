package bosca.workops.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.workops.ui.graphql.WorkOpsTaskFilters
import bosca.workops.ui.graphql.WorkOpsTaskSearch
import bosca.workops.ui.graphql.WorkOpsValidateBql
import kotlinx.serialization.Serializable
import kotlin.coroutines.cancellation.CancellationException

/** The caller's task search, retaining invalid BQL and distinguishing failed requests from empty data. */
@Serializable
class WorkOpsTaskSearchModel(
    val source: String = "ORDER BY modified DESC",
    val offset: Long = 0,
    var rows: List<WorkOpsTaskRow> = emptyList(),
    var filters: List<WorkOpsQueryFilter> = emptyList(),
    var errors: List<String> = emptyList(),
    var diagnostics: List<WorkOpsBqlDiagnostic> = emptyList(),
    var freeTextTerms: List<String> = emptyList(),
    var hasNext: Boolean = false,
    var failed: Boolean = false,
) {
    val page: Long get() = offset / 25 + 1
    val valid: Boolean get() = errors.isEmpty() && diagnostics.isEmpty()
    val validationMessage: String get() = diagnostics.joinToString("\n") { it.description }
    val previousHref: String get() = if (offset > 0) href((offset - 25).coerceAtLeast(0)) else ""
    val nextHref: String get() = if (hasNext) href(offset + 25) else ""
    val retryHref: String get() = href(offset)

    private fun href(offset: Long): String = "/workops/tasks?bql=${workOpsEncode(source)}&offset=$offset"

    suspend fun load(ctx: RenderContext) {
        try {
            val validation = ctx.gql.execute(WorkOpsValidateBql, WorkOpsValidateBql.Variables(source))
                .workOps.savedFilters.validateBql
            diagnostics = validation.errors.map { error -> WorkOpsBqlDiagnostic(error.message, error.start, error.end, error.hint.orEmpty()) }
            filters = ctx.gql.execute(WorkOpsTaskFilters, Unit).workOps.savedFilters.mine.map {
                WorkOpsQueryFilter(it.id, it.name, it.bqlSource)
            }
            if (diagnostics.isNotEmpty()) return
            val result = ctx.gql.execute(WorkOpsTaskSearch, WorkOpsTaskSearch.Variables(source, offset))
                .workOps.savedFilters.searchTasks
            hasNext = result.rows.size > 25
            rows = result.rows.take(25).map {
                WorkOpsTaskRow(it.id, it.key, workOpsContentLabel(it.summary, it.id, "Task"), it.status.name, it.status.category.name,
                    it.priority.name, it.priority.colorHex, it.taskType.name, it.assignee?.name.orEmpty(), it.dueDate.orEmpty(), it.assigneeProfileId)
            }
            freeTextTerms = result.freeTextTerms
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
            errors = listOf(e.message ?: "Could not load tasks.")
        }
    }
}

suspend fun workOpsTaskSearch(): WorkOpsTaskSearchModel {
    val ctx = currentRenderContext()
    val offset = ctx.query["offset"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0
    return WorkOpsTaskSearchModel(ctx.query["bql"] ?: "ORDER BY modified DESC", offset).also { it.load(ctx) }
}
