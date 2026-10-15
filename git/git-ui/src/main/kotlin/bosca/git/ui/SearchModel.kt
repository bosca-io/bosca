package bosca.git.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.git.ui.graphql.*
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable

/** Owns search criteria and read-only results; every action uses the caller's authorized API. */
@Serializable
class SearchModel(
    var repositories: List<RepositorySummary> = emptyList(),
    var id: String = "",
    var query: String = "",
    var mode: String = "content",
    var ref: String = "",
    var refs: List<RefOption> = emptyList(),
    var hits: List<SearchHit> = emptyList(),
    var message: String = "",
    var notice: String = "",
    var failed: Boolean = false,
) {
    constructor(
        page: SearchModel
    ) : this(
        page.repositories,
        page.id,
        page.query,
        page.mode,
        page.ref,
        page.refs,
        page.hits,
        page.message,
        page.notice,
        page.failed,
    )

    val repositoryName: String
        get() = repositories.firstOrNull { it.id == id }?.name.orEmpty()

    val href: String
        get() =
            "/search?repo=${encode(id)}&q=${encode(query)}&mode=${encode(mode)}&ref=${encode(ref)}"

    val refChoices: List<RefOption>
        get() = refs.filter { mode == "paths" || it.kind == "branch" }

    val placeholder: String
        get() = if (mode == "paths") "Search file paths…" else "Search file content…"

    fun repositoryLabel(repository: RepositorySummary): String {
        val label =
            repository.name +
                " · " +
                repository.slug +
                if (repository.archived) " (archived)" else ""
        return if (
            repositories.count { it.name == repository.name && it.slug == repository.slug } > 1
        )
            "$label · ${repository.id}"
        else label
    }

    fun refValue(option: RefOption): String =
        if (option.kind == "tag") "refs/tags/${option.name}" else option.name

    suspend fun load(
        repository: String,
        text: String,
        selectedMode: String,
        selectedRef: String,
        ctx: RenderContext,
    ) {
        id = repository
        query = text
        mode = if (selectedMode == "paths") "paths" else "content"
        ref = selectedRef
        perform(ctx, initial = true)
    }

    suspend fun search(
        repository: String,
        text: String,
        selectedMode: String,
        selectedRef: String,
        ctx: RenderContext,
    ) {
        val changedRepository = repository != id
        val changedMode = selectedMode != mode
        val refreshChoices = changedRepository || failed || repositories.isEmpty() || refs.isEmpty()
        id = repository
        query = text
        mode = if (selectedMode == "paths") "paths" else "content"
        ref =
            if (
                changedRepository ||
                    (changedMode &&
                        mode == "content" &&
                        refs.none { it.kind == "branch" && it.name == selectedRef })
            )
                ""
            else selectedRef
        perform(ctx, initial = false, refreshChoices = refreshChoices)
    }

    private suspend fun perform(ctx: RenderContext, initial: Boolean, refreshChoices: Boolean = true) {
        failed = false
        message = ""
        notice = ""
        hits = emptyList()
        if (refreshChoices) refs = emptyList()
        try {
            if (refreshChoices) {
                val list = RepositoryListModel(
                    RepositoryState.ACTIVE,
                    OwnerOption("all", "All accessible repositories", ""),
                )
                list.reload(ctx)
                repositories = list.repositories
            }
            if (initial && id.isBlank()) id = repositories.firstOrNull()?.id.orEmpty()
            if (id.isBlank()) return
            if (repositories.none { it.id == id }) {
                val selected =
                    ctx.gql
                        .execute(GitUiRepository, GitUiRepository.Variables(id))
                        .git
                        .repositoryById
                require(selected != null && !selected.deleted) { "Repository unavailable." }
                repositories = repositories + repositorySummary(selected)
            }
            val repository = repositories.first { it.id == id }
            if (ref.isBlank()) ref = repository.defaultBranch
            ref = ref.removePrefix("refs/heads/")
            if (refreshChoices) {
                val data = ctx.gql.execute(GitUiRefs, GitUiRefs.Variables(id)).git
                refs = data.branches.map { RefOption(it.name, "branch", it.sha) } +
                    data.tags.map { RefOption(it.name, "tag", it.targetSha ?: it.sha) }
            }
            if (query.isBlank()) return
            if (refs.isEmpty()) {
                notice = "This repository has no commits yet."
                return
            }
            if (mode == "content") {
                if (refs.none { it.kind == "branch" && it.name == ref }) {
                    notice =
                        "Content search is available on branches. Choose a branch, or use Paths to search a tag or commit."
                    return
                }
                hits =
                    ctx.gql
                        .execute(
                            GitUiSearchContent,
                            GitUiSearchContent.Variables(id, query.trim(), ref),
                        )
                        .git
                        .searchContent
                        .map {
                            SearchHit(
                                it.filePath,
                                it.lineNumber,
                                it.snippet,
                                repositoryHref(
                                    id,
                                    ref,
                                    path = it.filePath.substringBeforeLast('/', ""),
                                    file = it.filePath,
                                ) + "&line=${it.lineNumber}",
                            )
                        }
            } else {
                val known = refs.filter { it.kind == "tag" }.associate { it.name to it.sha } +
                    refs.filter { it.kind == "tag" }.associate { "refs/tags/${it.name}" to it.sha } +
                    refs.filter { it.kind == "branch" }.associate { it.name to it.sha }
                val sha = ctx.gql.commitShaForRef(id, ref, known)
                require(!sha.isNullOrBlank()) { "The selected ref does not exist." }
                hits =
                    ctx.gql
                        .execute(
                            GitUiSearchPaths,
                            GitUiSearchPaths.Variables(id, query.trim(), sha),
                        )
                        .git
                        .searchPaths
                        .map {
                            SearchHit(
                                it.path,
                                0,
                                "",
                                if (it.type == GitTreeEntryType.TREE)
                                    repositoryHref(id, sha, path = it.path)
                                else
                                    repositoryHref(
                                        id,
                                        sha,
                                        path = it.path.substringBeforeLast('/', ""),
                                        file = it.path,
                                    ),
                                it.type.name,
                                it.size,
                            )
                        }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
            message = e.message ?: "Could not search this repository."
        }
    }
}
