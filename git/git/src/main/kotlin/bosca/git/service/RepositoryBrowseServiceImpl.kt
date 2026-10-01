package bosca.git.service

import bosca.cache.ServiceCache
import bosca.cache.serializers.StringKeySerializer
import bosca.git.dfs.BoscaDfsRepositoryManager
import bosca.git.dfs.GitWorkDispatcher
import bosca.git.installer.GitSearchIndexInstaller
import bosca.git.model.BlameLine
import bosca.git.model.Blob
import bosca.git.model.BranchInfo
import bosca.git.model.CodeSearchResponse
import bosca.git.model.CodeSearchResult
import bosca.git.model.CommitInfo
import bosca.git.model.RepositorySearchResponse
import bosca.git.model.RepositorySearchResult
import bosca.git.model.ComparisonResult
import bosca.git.model.DiffLineType
import bosca.git.model.RepoStats
import bosca.git.model.SearchResult
import bosca.git.model.TagInfo
import bosca.git.model.TreeEntry
import bosca.git.model.TreeEntryType
import bosca.search.model.SearchQuery
import bosca.search.service.SearchService
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import org.eclipse.jgit.api.BlameCommand
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.PathFilter

/**
 * JGit-backed implementation of repository browsing operations using the
 * DFS object database for tree listing, blob reading, commit history,
 * branch/tag enumeration, blame, and content search.
 */
@ServiceImplementation
class RepositoryBrowseServiceImpl(
    private val dfsManager: BoscaDfsRepositoryManager,
    private val packRepository: bosca.git.repository.DfsPackRepository,
    private val searchService: SearchService
) : RepositoryBrowseService {

    private val treeCache = ServiceCache<String, List<TreeEntry>>(
        cacheName = "git:tree",
        serializer = StringKeySerializer,
    ) { key ->
        val parts = key.split(":", limit = 3)
        val repositoryId = UUID.parse(parts[0])
        val commitSha = parts[1]
        val path = parts[2].ifEmpty { null }
        loadTree(repositoryId, commitSha, path)
    }

    override suspend fun listTree(repositoryId: UUID, ref: String, path: String?): List<TreeEntry> {
        val commitSha = resolveToSha(repositoryId, ref) ?: return emptyList()
        val cacheKey = "$repositoryId:$commitSha:${path.orEmpty()}"
        return treeCache.get(cacheKey) ?: emptyList()
    }

    override suspend fun resolveRef(repositoryId: UUID, ref: String): String? {
        return resolveToSha(repositoryId, ref)
    }

    private suspend fun resolveToSha(repositoryId: UUID, ref: String): String? =
        withRepository(repositoryId) { it.resolve(ref)?.name() }

    private suspend fun loadTree(repositoryId: UUID, commitSha: String, path: String?): List<TreeEntry> {
        return withRepository(repositoryId) {
            val commitId = ObjectId.fromString(commitSha)
            val revWalk = RevWalk(it)
            val commit = revWalk.parseCommit(commitId)
            val tree = commit.tree

            val subtree = if (path != null && path.isNotEmpty()) {
                val pathWalk = TreeWalk.forPath(it, path, tree) ?: return@withRepository emptyList()
                if (pathWalk.fileMode != FileMode.TREE) return@withRepository emptyList()
                pathWalk.getObjectId(0)
            } else {
                tree
            }

            val entries = mutableListOf<TreeEntry>()
            val treeWalk = TreeWalk(it)
            treeWalk.addTree(subtree)
            treeWalk.isRecursive = false

            while (treeWalk.next()) {
                entries.add(treeWalk.toEntry(path))
            }

            revWalk.dispose()
            entries
        }
    }

    override suspend fun readBlob(repositoryId: UUID, ref: String, path: String): Blob? {
        return withRepository(repositoryId) {
            val commitId = it.resolve(ref) ?: return@withRepository null
            val objectId = RevWalk(it).use { revWalk ->
                val commit = revWalk.parseCommit(commitId)
                TreeWalk.forPath(it, path, commit.tree)?.use { treeWalk -> treeWalk.getObjectId(0) }
            } ?: return@withRepository null
            val loader = it.objectDatabase.open(objectId)
            val size = loader.size
            val sha = objectId.name()

            if (size > MAX_INLINE_SIZE) {
                return@withRepository Blob(content = null, size = size, sha = sha, isBinary = true)
            }

            val bytes = loader.cachedBytes
            val isBinary = isBinaryContent(bytes)

            Blob(
                content = if (isBinary) null else String(bytes, Charsets.UTF_8),
                size = size,
                sha = sha,
                isBinary = isBinary,
                mimeType = guessMimeType(path)
            )
        }
    }

    override suspend fun listCommits(
        repositoryId: UUID,
        ref: String,
        path: String?,
        limit: Int,
        offset: Long
    ): List<CommitInfo> {
        return withRepository(repositoryId) {
            val commitId = it.resolve(ref) ?: return@withRepository emptyList()
            val revWalk = RevWalk(it)
            revWalk.markStart(revWalk.parseCommit(commitId))

            if (path != null && path.isNotEmpty()) {
                revWalk.setTreeFilter(org.eclipse.jgit.treewalk.filter.AndTreeFilter.create(
                    PathFilter.create(path),
                    org.eclipse.jgit.treewalk.filter.TreeFilter.ANY_DIFF
                ))
            }

            val commits = mutableListOf<CommitInfo>()
            var skipped = 0L
            for (commit in revWalk) {
                if (skipped < offset) { skipped++; continue }
                if (commits.size >= limit) break
                commits.add(commit.toCommitInfo())
            }
            revWalk.dispose()
            commits
        }
    }

    override suspend fun getCommit(repositoryId: UUID, sha: String): CommitInfo? {
        return withRepository(repositoryId) {
            val objectId = ObjectId.fromString(sha)
            val revWalk = RevWalk(it)
            try {
                val commit = revWalk.parseCommit(objectId)
                commit.toCommitInfo()
            } catch (_: Exception) {
                null
            } finally {
                revWalk.dispose()
            }
        }
    }

    override suspend fun listBranches(repositoryId: UUID): List<BranchInfo> {
        return withRepository(repositoryId) {
            val refs = it.refDatabase.getRefsByPrefix(Constants.R_HEADS)
            val defaultRef = it.refDatabase.findRef(Constants.HEAD)
            val defaultId = defaultRef?.objectId

            refs.map { ref ->
                val name = ref.name.removePrefix(Constants.R_HEADS)
                val (ahead, behind) = if (defaultId != null && ref.objectId != defaultId) {
                    countAheadBehind(it, ref.objectId, defaultId)
                } else {
                    0 to 0
                }
                BranchInfo(name = name, sha = ref.objectId.name(), ahead = ahead, behind = behind)
            }
        }
    }

    override suspend fun listTags(repositoryId: UUID): List<TagInfo> {
        return withRepository(repositoryId) {
            val refs = it.refDatabase.getRefsByPrefix(Constants.R_TAGS)
            val revWalk = RevWalk(it)
            val tags = refs.map { ref ->
                val name = ref.name.removePrefix(Constants.R_TAGS)
                val peeledRef = it.refDatabase.peel(ref)
                val peeledId = peeledRef.peeledObjectId

                if (peeledId != null) {
                    try {
                        val tagObj = revWalk.parseTag(ref.objectId)
                        TagInfo(
                            name = name,
                            sha = ref.objectId.name(),
                            targetSha = peeledId.name(),
                            taggerName = tagObj.taggerIdent?.name,
                            taggerEmail = tagObj.taggerIdent?.emailAddress,
                            message = tagObj.fullMessage,
                            isAnnotated = true
                        )
                    } catch (_: Exception) {
                        TagInfo(name = name, sha = ref.objectId.name(), targetSha = peeledId.name(), isAnnotated = true)
                    }
                } else {
                    TagInfo(name = name, sha = ref.objectId.name(), isAnnotated = false)
                }
            }
            revWalk.dispose()
            tags
        }
    }

    override suspend fun blame(repositoryId: UUID, ref: String, path: String): List<BlameLine> {
        return withRepository(repositoryId) {
            val commitId = it.resolve(ref) ?: return@withRepository emptyList()
            val blameCommand = BlameCommand(it)
            blameCommand.setStartCommit(commitId)
            blameCommand.setFilePath(path)

            val result = blameCommand.call() ?: return@withRepository emptyList()
            val lines = mutableListOf<BlameLine>()
            for (i in 0 until result.resultContents.size()) {
                val sourceCommit = result.getSourceCommit(i)
                lines.add(BlameLine(
                    lineNumber = i + 1,
                    commitSha = sourceCommit?.name() ?: "",
                    authorName = result.getSourceAuthor(i)?.name ?: "",
                    authorEmail = result.getSourceAuthor(i)?.emailAddress ?: "",
                    content = result.resultContents.getString(i)
                ))
            }
            lines
        }
    }

    override suspend fun getStats(repositoryId: UUID): RepoStats {
        val stats = withRepository(repositoryId) {
            val branches = it.refDatabase.getRefsByPrefix(Constants.R_HEADS)
            val tags = it.refDatabase.getRefsByPrefix(Constants.R_TAGS)
            val headRef = it.refDatabase.findRef(Constants.HEAD)
                ?: it.refDatabase.getRefsByPrefix(Constants.R_HEADS).firstOrNull()

            var commitCount = 0L
            val contributors = mutableSetOf<String>()

            if (headRef != null) {
                RevWalk(it).use { revWalk ->
                    revWalk.markStart(revWalk.parseCommit(headRef.objectId))
                    for (commit in revWalk) {
                        commitCount++
                        contributors.add(commit.authorIdent.emailAddress)
                        if (commitCount > 10000) break
                    }
                }
            }

            RepoStats(
                commitCount = commitCount,
                branchCount = branches.size,
                tagCount = tags.size,
                contributorCount = contributors.size,
                diskSizeBytes = 0,
            )
        }
        // The pack-size query runs after the repository is closed and its IO thread released.
        return stats.copy(diskSizeBytes = packRepository.sumPackSizeBytes(repositoryId))
    }

    override suspend fun compare(
        repositoryId: UUID,
        baseRef: String,
        headRef: String,
        diffService: DiffService
    ): ComparisonResult {
        val files = diffService.computeDiff(repositoryId, baseRef, headRef)
        // A ref that doesn't resolve yields null from the block; the function then returns early.
        val commits = withRepository(repositoryId) {
            val baseId = it.resolve(baseRef) ?: return@withRepository null
            val headId = it.resolve(headRef) ?: return@withRepository null
            val revWalk = RevWalk(it)
            try {
                revWalk.markStart(revWalk.parseCommit(headId))
                revWalk.markUninteresting(revWalk.parseCommit(baseId))
                val result = mutableListOf<CommitInfo>()
                for (commit in revWalk) {
                    result.add(commit.toCommitInfo())
                    if (result.size >= 250) break
                }
                result
            } finally {
                revWalk.dispose()
            }
        } ?: return ComparisonResult(baseRef, headRef, emptyList(), files, files.size, 0, 0)

        var insertions = 0
        var deletions = 0
        for (file in files) {
            for (hunk in file.hunks) {
                for (line in hunk.lines) {
                    when (line.type) {
                        DiffLineType.ADD -> insertions++
                        DiffLineType.DELETE -> deletions++
                        else -> {}
                    }
                }
            }
        }

        return ComparisonResult(
            baseRef = baseRef,
            headRef = headRef,
            commits = commits,
            files = files,
            filesChanged = files.size,
            insertions = insertions,
            deletions = deletions
        )
    }

    override suspend fun listChangedPaths(
        repositoryId: UUID,
        beforeSha: String,
        afterSha: String
    ): Set<String> {
        val zero = ObjectId.zeroId().name()
        if (beforeSha == zero || afterSha == zero) return emptySet()

        return withRepository(repositoryId) {
            val revWalk = RevWalk(it)
            try {
                val oldCommit = revWalk.parseCommit(ObjectId.fromString(beforeSha))
                val newCommit = revWalk.parseCommit(ObjectId.fromString(afterSha))
                val formatter = org.eclipse.jgit.diff.DiffFormatter(
                    org.eclipse.jgit.util.io.DisabledOutputStream.INSTANCE
                )
                formatter.setRepository(it)
                val entries = formatter.scan(oldCommit.tree, newCommit.tree)
                buildSet {
                    for (entry in entries) {
                        val newPath = entry.newPath
                        val oldPath = entry.oldPath
                        if (newPath != org.eclipse.jgit.diff.DiffEntry.DEV_NULL) add(newPath)
                        if (oldPath != org.eclipse.jgit.diff.DiffEntry.DEV_NULL && oldPath != newPath) add(oldPath)
                    }
                }
            } finally {
                revWalk.dispose()
            }
        }
    }

    override suspend fun searchPaths(
        repositoryId: UUID,
        query: String,
        ref: String,
        limit: Int
    ): List<TreeEntry> {
        return withRepository(repositoryId) {
            val commitId = it.resolve(ref) ?: return@withRepository emptyList()
            val revWalk = RevWalk(it)
            val commit = revWalk.parseCommit(commitId)
            val results = mutableListOf<TreeEntry>()
            val treeWalk = TreeWalk(it)
            treeWalk.addTree(commit.tree)
            treeWalk.isRecursive = true
            val lowerQuery = query.lowercase()
            while (treeWalk.next() && results.size < limit) {
                if (treeWalk.pathString.lowercase().contains(lowerQuery)) {
                    results.add(treeWalk.toEntry())
                }
            }
            revWalk.dispose()
            results
        }
    }

    override suspend fun searchContent(
        repositoryId: UUID,
        query: String,
        ref: String,
        limit: Int
    ): List<SearchResult> {
        if (query.isBlank() || limit <= 0) return emptyList()

        // `ref` from callers is typically a short branch name (e.g. "main") but may
        // arrive fully-qualified ("refs/heads/main"). Strip the prefix so we always
        // filter on the branch's short name — which is what FileContentIndexExecutor
        // writes into the `branches` overlay. Tags and raw commit SHAs are not in
        // scope for now (the index only tracks branch tips), so they fall through to
        // a query that returns no matches.
        val branch = ref.removePrefix("refs/heads/")
        if (branch.isEmpty()) return emptyList()

        val filters = listOf(
            "_type = \"file\"",
            "repositoryId = \"${repositoryId.toString().sanitizeFilterValue()}\"",
            "branches = \"${branch.sanitizeFilterValue()}\"",
        )
        val searchQuery = SearchQuery(
            query = query,
            storageSystemName = GitSearchIndexInstaller.STORAGE_SYSTEM_NAME,
            offset = 0,
            limit = limit,
            filter = filters,
        )

        val raw = searchService.searchRaw(searchQuery)
        return raw.hits.mapNotNull { hit ->
            val path = hit.str("filePath") ?: return@mapNotNull null
            val content = hit.str("content").orEmpty()
            val match = findFirstMatch(content, query) ?: return@mapNotNull null
            SearchResult(filePath = path, lineNumber = match.first, snippet = match.second)
        }
    }

    /**
     * Opens [repositoryId] and runs [block] on [GitWorkDispatcher]. JGit's DFS reads are
     * synchronous and bridge to storage with `runBlocking`, so they must never run on a request
     * thread or on `Dispatchers.IO`. The block is deliberately not `suspend`: it holds a thread and
     * an open repository, so it must only do blocking JGit work.
     */
    private suspend fun <T> withRepository(repositoryId: UUID, block: (Repository) -> T): T =
        withContext(GitWorkDispatcher) { dfsManager.open(repositoryId).use { block(it) } }

    /**
     * Walks the indexed `content` field for the first line that contains [query]
     * (case-insensitive) and returns its 1-based line number alongside a snippet
     * capped at 200 characters. Returns null when no line contains the query —
     * which happens when Meilisearch matched on a different field (e.g. `filePath`
     * or `fileName`) or when the indexed content was truncated past the match.
     */
    private fun findFirstMatch(content: String, query: String): Pair<Int, String>? {
        if (content.isEmpty()) return null
        var lineNumber = 0
        for (line in content.lineSequence()) {
            lineNumber++
            if (line.contains(query, ignoreCase = true)) {
                return lineNumber to line.take(200)
            }
        }
        return null
    }

    private fun countAheadBehind(
        repo: org.eclipse.jgit.lib.Repository,
        branchId: ObjectId,
        defaultId: ObjectId
    ): Pair<Int, Int> {
        val revWalk = RevWalk(repo)
        try {
            val branchCommit = revWalk.parseCommit(branchId)
            val defaultCommit = revWalk.parseCommit(defaultId)

            revWalk.reset()
            revWalk.markStart(branchCommit)
            revWalk.markUninteresting(defaultCommit)
            var ahead = 0
            for (c in revWalk) ahead++

            revWalk.reset()
            revWalk.markStart(defaultCommit)
            revWalk.markUninteresting(branchCommit)
            var behind = 0
            for (c in revWalk) behind++

            return ahead to behind
        } catch (_: Exception) {
            return 0 to 0
        } finally {
            revWalk.dispose()
        }
    }

    private fun TreeWalk.toEntry(parentPath: String? = null): TreeEntry {
        val mode = fileMode
        val type = when {
            mode == FileMode.TREE -> TreeEntryType.TREE
            mode == FileMode.GITLINK -> TreeEntryType.SUBMODULE
            mode == FileMode.SYMLINK -> TreeEntryType.SYMLINK
            else -> TreeEntryType.BLOB
        }
        val fullPath = if (parentPath != null) "$parentPath/$nameString" else pathString
        return TreeEntry(
            name = nameString,
            path = fullPath,
            type = type,
            mode = mode.bits,
            sha = getObjectId(0).name(),
            size = null
        )
    }

    private fun RevCommit.toCommitInfo() = CommitInfo(
        sha = name(),
        message = fullMessage,
        authorName = authorIdent.name,
        authorEmail = authorIdent.emailAddress,
        authorDate = authorIdent.whenAsInstant.toString(),
        committerName = committerIdent.name,
        committerEmail = committerIdent.emailAddress,
        committerDate = committerIdent.whenAsInstant.toString(),
        parentShas = parents.map { it.name() }
    )

    override suspend fun searchCode(
        query: String,
        repositoryId: UUID?,
        language: String?,
        offset: Int,
        limit: Int
    ): CodeSearchResponse {
        val filters = mutableListOf("_type = \"file\"")
        if (repositoryId != null) filters.add("repositoryId = \"${repositoryId.toString().replace("\"", "")}\"")
        if (language != null) {
            val sanitized = language.replace("\"", "").replace("\\", "")
            filters.add("language = \"$sanitized\"")
        }

        val searchQuery = SearchQuery(
            query = query,
            storageSystemName = GitSearchIndexInstaller.STORAGE_SYSTEM_NAME,
            offset = offset,
            limit = limit,
            filter = filters,
        )

        val result = searchService.searchRaw(searchQuery)
        val codeResults = result.hits.mapNotNull { hit ->
            val repoId = hit.str("repositoryId") ?: return@mapNotNull null
            CodeSearchResult(
                repositoryId = UUID.parse(repoId),
                repositoryName = hit.str("repositoryName") ?: hit.str("name") ?: "",
                repositorySlug = hit.str("repositorySlug") ?: hit.str("slug") ?: "",
                filePath = hit.str("filePath") ?: "",
                language = hit.str("language") ?: "",
                snippet = hit.str("content")?.take(500) ?: ""
            )
        }
        return CodeSearchResponse(results = codeResults, estimatedHits = result.estimatedHits)
    }

    override suspend fun searchRepositories(
        query: String,
        visibility: String?,
        archived: Boolean?,
        offset: Int,
        limit: Int
    ): RepositorySearchResponse {
        val filters = mutableListOf("_type = \"repository\"")
        if (visibility != null) {
            val sanitized = visibility.replace("\"", "").replace("\\", "")
            filters.add("visibility = \"$sanitized\"")
        }
        if (archived != null) {
            filters.add("archived = $archived")
        }

        val searchQuery = SearchQuery(
            query = query,
            storageSystemName = GitSearchIndexInstaller.STORAGE_SYSTEM_NAME,
            offset = offset,
            limit = limit,
            filter = filters,
        )

        val result = searchService.searchRaw(searchQuery)
        val repoResults = result.hits.mapNotNull { hit ->
            val id = hit.str("id") ?: return@mapNotNull null
            RepositorySearchResult(
                id = UUID.parse(id),
                name = hit.str("name") ?: "",
                slug = hit.str("slug") ?: "",
                description = hit.str("description") ?: "",
                ownerId = UUID.parse(hit.str("ownerId") ?: return@mapNotNull null),
                visibility = hit.str("visibility") ?: "",
                defaultBranch = hit.str("defaultBranch") ?: "",
                archived = hit.str("archived")?.toBooleanStrictOrNull() ?: false
            )
        }
        return RepositorySearchResponse(results = repoResults, estimatedHits = result.estimatedHits)
    }

    companion object {
        private const val MAX_INLINE_SIZE = 1024L * 1024L

        private fun JsonObject.str(key: String): String? =
            this[key]?.jsonPrimitive?.contentOrNull

        /**
         * Escapes characters that are significant inside a Meilisearch double-quoted
         * filter value so an attacker-controlled `repositoryId`/`branch` cannot break
         * out of the quoted string and inject extra filter clauses.
         */
        private fun String.sanitizeFilterValue(): String =
            replace("\\", "\\\\").replace("\"", "\\\"")

        private fun isBinaryContent(bytes: ByteArray): Boolean {
            val checkLen = minOf(bytes.size, 8192)
            for (i in 0 until checkLen) {
                if (bytes[i] == 0.toByte()) return true
            }
            return false
        }

        private fun guessMimeType(path: String): String? {
            val ext = path.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "kt", "java", "py", "js", "ts", "rs", "go", "c", "cpp", "h" -> "text/plain"
                "md" -> "text/markdown"
                "json" -> "application/json"
                "yaml", "yml" -> "text/yaml"
                "xml" -> "application/xml"
                "html", "htm" -> "text/html"
                "css" -> "text/css"
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "svg" -> "image/svg+xml"
                "pdf" -> "application/pdf"
                else -> null
            }
        }
    }
}
