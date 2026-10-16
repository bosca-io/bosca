package bosca.git.ui

import bosca.bml.render.RenderContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable

/** Compared files whose hidden unchanged lines can be revealed from [ref]. */
@Serializable
class CompareDiffModel(
    val repositoryId: String,
    val ref: String,
    var diff: List<DiffView>,
    var contextFile: String = "",
    var contextMessage: String = "",
) {
    val files: List<DiffTreeEntry>
        get() = changedFileTree(diff)

    fun fileHref(path: String): String = repositoryHref(repositoryId, ref, file = path)

    /** Reveals unchanged lines of [file] above hunk [index] (or after the last hunk) in [direction]. */
    suspend fun expandContext(file: String, index: Int, direction: String, ctx: RenderContext) =
        updateContext(file, ctx) { view, source -> view.expandGap(index, direction, source) }

    /** Reveals every unchanged line of [file]. */
    suspend fun expandFile(file: String, ctx: RenderContext) =
        updateContext(file, ctx) { view, source -> view.expandAll(source) }

    /** Hides the unchanged lines revealed in [file]. */
    fun collapseContext(file: String) {
        contextFile = ""
        contextMessage = ""
        diff = diff.map { if (it.path == file) it.collapseRevealed() else it }
    }

    private suspend fun updateContext(
        file: String,
        ctx: RenderContext,
        change: (DiffView, List<String>) -> DiffView,
    ) {
        contextFile = file
        contextMessage = ""
        try {
            diff = diff.withRevealedLines(file, { ctx.gql.diffSourceLines(repositoryId, ref, it) }, change)
            contextFile = ""
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            contextMessage = e.message ?: "Unchanged lines could not be shown."
        }
    }
}
