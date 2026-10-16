package bosca.git.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.git.ui.graphql.*
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable

@Serializable
class PipelineLogsModel(
    val step: String,
    val repository: String,
    var lines: List<GitUiLogsData.Git.PipelineLogs> = emptyList(),
    var hasEarlier: Boolean = true,
    var message: String = "",
) {
    suspend fun load(ctx: RenderContext) = fetch(ctx, tail = true)

    suspend fun earlier(ctx: RenderContext) = fetch(ctx, before = lines.firstOrNull()?.lineNumber)

    suspend fun refresh(ctx: RenderContext) = fetch(ctx, tail = true)

    private suspend fun fetch(
        ctx: RenderContext,
        offset: Int? = null,
        before: Int? = null,
        tail: Boolean = false,
    ) {
        message = ""
        try {
            var incoming =
                ctx.gql
                    .execute(GitUiLogs, GitUiLogs.Variables(step, offset, before, tail))
                    .git
                    .pipelineLogs
            val last = lines.lastOrNull()?.lineNumber
            while (
                tail &&
                    last != null &&
                    incoming.size < 20000 &&
                    incoming.isNotEmpty() &&
                    incoming.first().lineNumber > last
            ) {
                val older =
                    ctx.gql
                        .execute(
                            GitUiLogs,
                            GitUiLogs.Variables(step, null, incoming.first().lineNumber, false),
                        )
                        .git
                        .pipelineLogs
                if (older.isEmpty()) break
                incoming = older + incoming
            }
            if (before != null && incoming.isEmpty()) hasEarlier = false
            val merged = (lines + incoming).distinctBy { it.lineNumber }.sortedBy { it.lineNumber }
            lines = if (before != null) merged.take(20000) else merged.takeLast(20000)
            if (merged.size > 20000)
                message =
                    "Showing a window of 20,000 lines. Load earlier or refresh the tail for other output."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message = e.message ?: "Could not load logs."
        }
    }
}
