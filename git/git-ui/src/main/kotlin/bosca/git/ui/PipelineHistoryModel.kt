package bosca.git.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.git.ui.graphql.*
import kotlinx.serialization.Serializable
import kotlin.coroutines.cancellation.CancellationException

@Serializable
class PipelineHistoryModel(
    val repository: String,
    var offset: Long = 0,
    var runs: List<GitUiPipelineRunsData.Git.PipelineRuns> = emptyList(),
    var loaded: Boolean = false,
    var message: String = "",
) {
    suspend fun load(offset: Long, ctx: RenderContext) {
        message = ""
        try {
            val next = offset.coerceAtLeast(0)
            val result = ctx.gql.execute(GitUiPipelineRuns, GitUiPipelineRuns.Variables(repository, next))
            runs = result.git.pipelineRuns
            this.offset = next
            loaded = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message = e.message ?: "Could not load run history."
        }
    }
}
