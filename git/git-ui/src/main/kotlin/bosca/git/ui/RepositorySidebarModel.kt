package bosca.git.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.git.ui.graphql.GitTreeEntryType
import bosca.git.ui.graphql.GitUiTree
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable

/** Loads folders on demand at the same commit as the visible file. */
@Serializable
class RepositorySidebarModel(
    val page: RepositorySidebarContext,
    var entries: List<SidebarEntry> = emptyList(),
    var message: String = "",
) {
    val storageKey: String
        get() = "git-tree:${page.id}:${page.resolvedSha}"

    suspend fun load(ctx: RenderContext) {
        entries = directory("", 0, ctx)
        val selectedDirectory = page.file.substringBeforeLast('/', "").ifBlank { page.path }
        var ancestor = ""
        for (segment in selectedDirectory.split('/').filter(String::isNotBlank)) {
            ancestor = if (ancestor.isBlank()) segment else "$ancestor/$segment"
            if (entries.any { it.path == ancestor && it.directory }) expand(ancestor, ctx)
        }
    }

    suspend fun expand(path: String, ctx: RenderContext) {
        message = ""
        try {
            val index = entries.indexOfFirst { it.path == path && it.directory }
            require(index >= 0) { "Folder is no longer available." }
            val current = entries[index]
            if (current.expanded) {
                entries =
                    entries
                        .map { if (it.path == path) it.copy(expanded = false) else it }
                        .filterNot { it.path.startsWith("$path/") }
            } else {
                val children = directory(path, current.depth + 1, ctx)
                entries =
                    entries.take(index) +
                        current.copy(expanded = true) +
                        children +
                        entries.drop(index + 1)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message = e.message ?: "Could not load folder."
        }
    }

    private suspend fun directory(
        path: String,
        depth: Int,
        ctx: RenderContext,
    ): List<SidebarEntry> =
        ctx.gql
            .execute(
                GitUiTree,
                GitUiTree.Variables(page.id, page.resolvedSha.ifBlank { page.ref }, path),
            )
            .git
            .tree
            .sortedWith(compareBy({ it.type != GitTreeEntryType.TREE }, { it.name.lowercase() }))
            .map { entry ->
                SidebarEntry(
                    entry.path,
                    entry.name,
                    entry.type == GitTreeEntryType.TREE,
                    depth,
                    page.href(
                        path =
                            if (entry.type == GitTreeEntryType.TREE) entry.path
                            else entry.path.substringBeforeLast('/', ""),
                        file = if (entry.type == GitTreeEntryType.TREE) "" else entry.path,
                    ),
                )
            }
}
