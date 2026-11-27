package bosca.workops.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.workops.ui.graphql.WorkOpsSpecSearch
import bosca.workops.ui.graphql.WorkOpsTaskFilters
import bosca.workops.ui.graphql.WorkOpsValidateSpecBql
import kotlinx.serialization.Serializable
import kotlin.coroutines.cancellation.CancellationException

/** Specification search validates its own catalog and retains transport failures separately from empty results. */
@Serializable
class WorkOpsSpecSearchModel(
    val source: String = "ORDER BY modified DESC",
    val offset: Long = 0,
    var rows: List<WorkOpsSpecRow> = emptyList(),
    var filters: List<WorkOpsQueryFilter> = emptyList(),
    var errors: List<String> = emptyList(),
    var diagnostics: List<WorkOpsBqlDiagnostic> = emptyList(),
    var freeTextTerms: List<String> = emptyList(),
    var hasNext: Boolean = false,
    var failed: Boolean = false,
) {
    val page get() = offset / 25 + 1
    val valid get() = errors.isEmpty() && diagnostics.isEmpty()
    val validationMessage get() = diagnostics.joinToString("\n") { it.description }
    val previousHref get() = if (offset > 0) href((offset - 25).coerceAtLeast(0)) else ""
    val nextHref get() = if (hasNext) href(offset + 25) else ""
    val retryHref get() = href(offset)
    private fun href(offset: Long) = "/workops/specs?bql=${workOpsEncode(source)}&offset=$offset"

    suspend fun load(ctx: RenderContext) {
        try {
            diagnostics = ctx.gql.execute(WorkOpsValidateSpecBql, WorkOpsValidateSpecBql.Variables(source))
                .workOps.savedFilters.validateSpecBql.errors.map { WorkOpsBqlDiagnostic(it.message, it.start, it.end, it.hint.orEmpty()) }
            filters = ctx.gql.execute(WorkOpsTaskFilters, Unit).workOps.savedFilters.mine
                .map { WorkOpsQueryFilter(it.id, it.name, it.bqlSource, "/workops/specs") }
            if (diagnostics.isNotEmpty()) return
            val result = ctx.gql.execute(WorkOpsSpecSearch, WorkOpsSpecSearch.Variables(source, offset)).workOps.savedFilters.searchSpecs
            hasNext = result.rows.size > 25
            rows = result.rows.take(25).map { row -> WorkOpsSpecRow(
                row.id, workOpsContentLabel(row.metadata?.name.orEmpty(), row.metadataId, "Specification unavailable"),
                row.status.name, row.status.category.name, workOpsAssigneeLabel(row.ownerProfileId, row.owner?.name),
                row.project?.name.orEmpty(), row.childCount, row.childDoneCount, row.requirementCount,
                row.gitRepositoryId != null, row.modifiedAt,
            ) }
            freeTextTerms = result.freeTextTerms
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) { failed = true; errors = listOf(failure.message ?: "Could not load specifications.") }
    }
}

suspend fun workOpsSpecSearch(): WorkOpsSpecSearchModel {
    val ctx = currentRenderContext()
    return WorkOpsSpecSearchModel(ctx.query["bql"] ?: "ORDER BY modified DESC",
        ctx.query["offset"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0).also { it.load(ctx) }
}
