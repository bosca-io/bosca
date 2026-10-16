package bosca.git.ui

import bosca.bml.graphql.execute
import bosca.bml.render.currentRenderContext
import bosca.git.ui.graphql.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

suspend fun repositoryList(loadRepositories: Boolean = true): RepositoryListModel = coroutineScope {
    val ctx = currentRenderContext()
    val state = RepositoryState.fromQuery(ctx.query["state"])
    val explicit = ctx.query["owner"] ?: ctx.cookies["git_owner"]
    if (explicit != null) {
        val listRequest = async {
            RepositoryListModel(state, OwnerOption(explicit, "", "")).also { if (loadRepositories) it.reload(ctx) }
        }
        val owner = if (explicit == "all") OwnerOption("all", "All accessible repositories", "")
        else {
            val profile = ctx.gql.execute(GitUiProfile, GitUiProfile.Variables(explicit)).profiles.profile
                ?: error("The selected repository owner is unavailable.")
            OwnerOption(profile.id, profile.name, profile.slug.orEmpty())
        }
        return@coroutineScope RepositoryListModel(state, owner, repositories = listRequest.await().repositories)
    }
    val profiles = ctx.gql.execute(GitUiOwnerProfiles, Unit).profiles.current.orEmpty()
    val selected = profiles.firstOrNull { it.isPrimary } ?: profiles.firstOrNull()
    val owner = selected?.let { OwnerOption(it.id, it.name, it.slug.orEmpty()) }
        ?: OwnerOption("all", "All accessible repositories", "")
    RepositoryListModel(state, owner).also { if (loadRepositories) it.reload(ctx) }
}

suspend fun repositoryPage(): RepositoryPage? = supervisorScope {
    val ctx = currentRenderContext()
    val id = ctx.params["id"] ?: return@supervisorScope null.also { ctx.notFound = true }
    val path = ctx.query["path"].orEmpty()
    val file = ctx.query["file"].orEmpty()
    val tab =
        ctx.query["tab"].takeIf {
            it in
                setOf(
                    "code",
                    "commits",
                    "branches",
                    "tags",
                    "pulls",
                    "pipelines",
                    "statuses",
                    "secrets",
                    "settings",
                )
        } ?: "code"
    val offset = ctx.query["offset"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0
    val listHref = safeListHref(ctx.query["return"])
    val needsRefs = tab in setOf("code", "commits", "branches", "tags", "statuses")
    val branchesRequest = async {
        if (needsRefs) ctx.gql.execute(GitUiBranches, GitUiBranches.Variables(id)).git.branches
        else emptyList()
    }
    val tagsRequest = async {
        if (needsRefs) ctx.gql.execute(GitUiTags, GitUiTags.Variables(id, if (tab == "tags") offset else 0, 26, null)).git.tags.map {
            RefOption(it.name, "tag", it.targetSha ?: it.sha, it.message.orEmpty())
        } else emptyList()
    }
    val repositoryRequest = async {
        ctx.gql.execute(GitUiRepository, GitUiRepository.Variables(id)).git.repositoryById
    }
    val pipelineRequest = if (tab == "pipelines") async {
        pipelinePage(id, repositoryHref(id, "", listHref = listHref, tab = tab), repositoryRequest = repositoryRequest)
    } else null
    val repo = repositoryRequest.await() ?: run {
        branchesRequest.cancel()
        tagsRequest.cancel()
        pipelineRequest?.cancel()
        return@supervisorScope null.also { ctx.notFound = true }
    }
    val ref = ctx.query["ref"]?.takeIf(String::isNotBlank) ?: repo.defaultBranch
    val authorRequest = async {
        resolveGitCommitAuthor(
            if (tab == "code" && repo.canEdit && (ctx.query["edit"] == "true" || ctx.query["new"] == "true"))
                ctx.gql.execute(GitUiProfiles, Unit).profiles.current
            else null
        )
    }
    val commitsRequest = async {
        if (tab == "commits")
            ctx.gql
                .execute(
                    GitUiCommits,
                    GitUiCommits.Variables(id, ref, path.ifBlank { null }, offset),
                )
                .git
                .commits
                .map {
                    CommitSummary(
                        it.sha,
                        it.message,
                        "${it.authorName} <${it.authorEmail}>",
                        it.authorDate,
                    )
                }
        else emptyList()
    }
    val branchRefs = branchesRequest.await()
    val branches = branchRefs.map { it.name }
    val loadedTags = tagsRequest.await()
    val tags = loadedTags.take(25)
    val resolvedSha = if (tab == "code" || tab == "branches") {
        ctx.gql.commitShaForRef(
            id, ref, tags.associate { "refs/tags/${it.name}" to it.sha } +
                branchRefs.associate { "refs/heads/${it.name}" to it.sha },
        ).orEmpty()
    } else ""
    val emptyRepository = branches.isEmpty() && tags.isEmpty()
    var content: String? = null
    var fileMessage =
        if (tab == "code" && !emptyRepository && resolvedSha.isBlank()) "The selected ref does not exist." else ""
    var rawHref = ""
    var isImage = false
    var markdownHtml = ""
    var readmeHtml = ""
    val entries =
        if (tab != "code" || fileMessage.isNotBlank()) emptyList()
        else if (file.isNotBlank()) {
            val blob =
                ctx.gql
                    .execute(GitUiBlob, GitUiBlob.Variables(id, resolvedSha.ifBlank { ref }, file))
                    .git
                    .blob
            content = blob?.takeUnless { it.isBinary }?.content
            rawHref = if (blob != null) rawFileHref(repo.cloneUrl, resolvedSha, file) else ""
            isImage =
                blob != null &&
                    file.substringAfterLast('.').lowercase() in
                        setOf("png", "jpg", "jpeg", "gif", "webp", "avif", "bmp")
            fileMessage =
                when {
                    blob == null -> "File not found at this ref."
                    blob.size > 1024 * 1024 && !isImage ->
                        "This file is too large for inline text. Use Download to view the full file."
                    blob.isBinary || blob.content == null ->
                        if (isImage) ""
                        else
                            "This file cannot be displayed as inline text. Use Download to open it."
                    blob.content.startsWith("version https://git-lfs.github.com/spec/v1") ->
                        "This is a Git LFS pointer. Download the file using Git LFS."
                    else -> ""
                }
            if (content != null && isMarkdownPath(file))
                markdownHtml =
                    repositoryMarkdown(content, id, resolvedSha, file, repo.cloneUrl, listHref)
            emptyList()
        } else {
            // Verify directory existence against its parent's entries; an empty folder response
            // alone is ambiguous.
            if (path.isNotBlank() && resolvedSha.isNotBlank()) {
                val parent = path.substringBeforeLast('/', "")
                val exists =
                    ctx.gql
                        .execute(GitUiTree, GitUiTree.Variables(id, resolvedSha, parent))
                        .git
                        .tree
                        .any { it.path == path && it.type == GitTreeEntryType.TREE }
                if (!exists) fileMessage = "This directory does not exist at the selected ref."
            }
            if (fileMessage.isNotBlank()) emptyList()
            else {
                val tree =
                    ctx.gql
                        .execute(
                            GitUiTree,
                            GitUiTree.Variables(id, resolvedSha.ifBlank { ref }, path),
                        )
                        .git
                        .tree
                        .sortedWith(
                            compareBy({ it.type != GitTreeEntryType.TREE }, { it.name.lowercase() })
                        )
                val readme =
                    tree
                        .filter { it.type == GitTreeEntryType.BLOB }
                        .sortedBy { it.name.lowercase() }
                        .firstOrNull {
                            it.name.lowercase() in setOf("readme.md", "readme.markdown", "readme")
                        }
                if (readme != null) {
                    val blob =
                        ctx.gql
                            .execute(
                                GitUiBlob,
                                GitUiBlob.Variables(id, resolvedSha.ifBlank { ref }, readme.path),
                            )
                            .git
                            .blob
                    blob?.content?.let {
                        readmeHtml =
                            repositoryMarkdown(
                                it,
                                id,
                                resolvedSha,
                                readme.path,
                                repo.cloneUrl,
                                listHref,
                            )
                    }
                }
                tree.map { entry ->
                    RepositoryEntry(
                        entry.name,
                        entry.type == GitTreeEntryType.TREE,
                        repositoryHref(
                            id,
                            ref,
                            if (entry.type == GitTreeEntryType.TREE) entry.path else path,
                            if (entry.type == GitTreeEntryType.TREE) "" else entry.path,
                            listHref,
                        ),
                        entry.type.name.lowercase(),
                        entry.size.takeIf { entry.type != GitTreeEntryType.TREE },
                        entry.path,
                        if (entry.type == GitTreeEntryType.TREE) ""
                        else repositoryHref(id, ref, path, entry.path, listHref) + "&edit=true",
                    )
                }
            }
        }
    val commits = commitsRequest.await()
    val author = authorRequest.await()
    RepositoryPage(
        id,
        repo.name,
        repo.description.orEmpty(),
        repo.cloneUrl,
        ref,
        path,
        file,
        branches,
        entries,
        content,
        fileMessage,
        tags,
        commits,
        tab,
        offset,
        resolvedSha,
        rawHref,
        markdownHtml,
        readmeHtml,
        isMarkdownPath(file),
        isImage,
        repo.archived,
        listHref,
        author.first,
        author.second,
        repo.slug,
        repo.visibility.name,
        repo.contentType?.name ?: "GENERAL",
        repo.defaultBranch,
        repo.canEdit,
        repo.canManage,
        repo.canEdit &&
            !repo.archived &&
            branches.contains(ref) &&
            content != null &&
            ctx.query["edit"] == "true",
        repo.canEdit &&
            !repo.archived &&
            (branches.contains(ref) || (emptyRepository && ref == repo.defaultBranch)) &&
            ctx.query["new"] == "true",
        tagsHasMore = loadedTags.size > 25,
        branchRefs = branchRefs.map { RefOption(it.name, "branch", it.sha) },
        pulls =
            if (tab == "pulls")
                pullListPage(id, repositoryHref(id, ref, listHref = listHref, tab = tab), repo)
            else null,
        pipelines =
            if (tab == "pipelines")
                pipelineRequest?.await()?.copy(baseHref = repositoryHref(id, ref, listHref = listHref, tab = tab))
            else null,
        statuses =
            if (tab == "statuses")
                explorePage("statuses", id, repositoryHref(id, ref, listHref = listHref, tab = tab), repo, branches)
            else null,
        secrets =
            if (tab == "secrets")
                repositorySecretsPage(repositoryHref(id, ref, listHref = listHref, tab = tab), repo)
            else null,
        settings =
            if (tab == "settings")
                repositorySettingsPage(repositoryHref(id, ref, listHref = listHref, tab = tab), repo)
            else null,
    )
}

internal fun safeListHref(requested: String?): String =
    requested?.takeIf { it == "/repositories" || it.startsWith("/repositories?") }
        ?: "/repositories"

internal fun repositoryHref(
    id: String,
    ref: String,
    path: String = "",
    file: String = "",
    listHref: String = "/repositories",
    tab: String = "code",
    offset: Long = 0,
): String = buildString {
    append("/repositories/${encode(id)}?ref=${encode(ref)}")
    if (path.isNotBlank()) append("&path=${encode(path)}")
    if (file.isNotBlank()) append("&file=${encode(file)}")
    if (tab != "code") append("&tab=${encode(tab)}")
    if (offset != 0L) append("&offset=$offset")
    if (listHref != "/repositories") append("&return=${encode(listHref)}")
}

internal fun encode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

internal fun formatSize(bytes: Long): String =
    when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0)
        else -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

internal fun isMarkdownPath(path: String): Boolean =
    path.substringAfterLast('.').lowercase() in setOf("md", "markdown") ||
        path.substringAfterLast('/').equals("readme", true)
