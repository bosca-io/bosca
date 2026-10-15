package bosca.git.ui

import bosca.git.ui.components.NavigationItem
import kotlinx.serialization.Serializable

@Serializable
data class RepositoryPage(
    val id: String,
    val name: String,
    val description: String,
    val cloneUrl: String,
    val ref: String,
    val path: String,
    val file: String,
    val branches: List<String>,
    val entries: List<RepositoryEntry>,
    val content: String?,
    val fileMessage: String,
    val tags: List<RefOption>,
    val commits: List<CommitSummary>,
    val tab: String,
    val offset: Long,
    val resolvedSha: String,
    val rawHref: String,
    val markdownHtml: String,
    val readmeHtml: String,
    val isMarkdown: Boolean,
    val isImage: Boolean,
    val archived: Boolean,
    val listHref: String,
    val authorName: String,
    val authorEmail: String,
    val slug: String,
    val visibility: String,
    val contentType: String,
    val canEdit: Boolean,
    val canManage: Boolean,
    val editing: Boolean,
    val creating: Boolean,
    val pulls: PullListPage? = null,
    val pipelines: PipelinePage? = null,
    val statuses: ExplorePage? = null,
    val secrets: RepositorySecretsPage? = null,
    val settings: RepositorySettingsPage? = null,
) {
    val sidebar: RepositorySidebarContext
        get() = RepositorySidebarContext(id, ref, resolvedSha, file, path, listHref)

    val rootHref: String
        get() = href()

    val parentHref: String
        get() = href(path = path.substringBeforeLast('/', ""))

    val directoryHref: String
        get() = href(path = path)

    val breadcrumbs: List<PathLink>
        get() =
            path.split('/').filter(String::isNotBlank).mapIndexed { index, part ->
                PathLink(part, href(path = path.split('/').take(index + 1).joinToString("/")))
            }

    val tabs: List<NavigationItem>
        get() =
            listOf(
                    "Code",
                    "Commits",
                    "Branches",
                    "Tags",
                    "Pull requests",
                    "Pipelines",
                    "Statuses",
                    "Secrets",
                    "Settings",
                )
                .map {
                    val key = if (it == "Pull requests") "pulls" else it.lowercase()
                    NavigationItem(it, href(tab = key, path = path), tab == key)
                }

    val previousHref: String
        get() = href(tab = tab, path = path, offset = (offset - 25).coerceAtLeast(0))

    val nextHref: String
        get() = href(tab = tab, path = path, offset = offset + 25)

    fun href(
        ref: String = this.ref,
        path: String = "",
        file: String = "",
        tab: String = "code",
        offset: Long = 0,
    ): String = repositoryHref(id, ref, path, file, listHref, tab, offset)
}
