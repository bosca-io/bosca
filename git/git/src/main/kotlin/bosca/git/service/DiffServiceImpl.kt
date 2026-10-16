package bosca.git.service

import bosca.git.dfs.BoscaDfsRepositoryManager
import bosca.git.dfs.GitWorkDispatcher
import bosca.git.model.CommitInfo
import bosca.git.model.DiffChangeType
import bosca.git.model.DiffFile
import bosca.git.model.DiffHunk
import bosca.git.model.DiffLine
import bosca.git.model.DiffLineType
import bosca.git.model.MergeStrategy
import bosca.git.model.PullRequest
import bosca.git.model.PullRequestStatus
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.coroutines.withContext
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.Edit
import org.eclipse.jgit.diff.EditList
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.RawTextComparator
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectReader
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.patch.FileHeader.PatchType
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.revwalk.RevSort
import org.eclipse.jgit.revwalk.filter.RevFilter
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.AbstractTreeIterator
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.util.io.DisabledOutputStream

/**
 * JGit-based diff computation with rename detection, producing structured
 * [DiffFile] objects with line-level hunks for code review rendering.
 */
@ServiceImplementation
class DiffServiceImpl(
    private val dfsManager: BoscaDfsRepositoryManager
) : DiffService {

    override suspend fun computeDiff(repositoryId: UUID, baseRef: String, headRef: String): List<DiffFile> =
        // JGit's DFS reads are synchronous and bridge to storage with runBlocking: keep them off request threads.
        withContext(GitWorkDispatcher) { computeDiffBlocking(repositoryId, baseRef, headRef) }

    override suspend fun listComparisonCommits(
        repositoryId: UUID, baseRef: String, headRef: String, offset: Long, limit: Int,
    ): List<CommitInfo> = withContext(GitWorkDispatcher) {
        require(offset >= 0) { "Commit offset must not be negative." }
        require(limit in 1..100) { "Commit limit must be between 1 and 100." }
        dfsManager.open(repositoryId).use { repo ->
            comparisonWalk(repo, baseRef, headRef).use { walk ->
                var skipped = 0L
                val commits = mutableListOf<CommitInfo>()
                for (commit in walk) {
                    if (skipped < offset) { skipped++; continue }
                    commits.add(CommitInfo(
                        sha = commit.name(), message = commit.fullMessage,
                        authorName = commit.authorIdent.name, authorEmail = commit.authorIdent.emailAddress,
                        authorDate = commit.authorIdent.whenAsInstant.toString(),
                        committerName = commit.committerIdent.name, committerEmail = commit.committerIdent.emailAddress,
                        committerDate = commit.committerIdent.whenAsInstant.toString(),
                        parentShas = commit.parents.map { it.name() },
                    ))
                    if (commits.size == limit) break
                }
                commits
            }
        }
    }

    override suspend fun computeCommitDiff(
        repositoryId: UUID, baseRef: String, headRef: String, commitSha: String,
    ): List<DiffFile> = withContext(GitWorkDispatcher) {
        require(ObjectId.isId(commitSha)) { "Choose a full commit SHA from this pull request." }
        dfsManager.open(repositoryId).use { repo ->
            comparisonWalk(repo, baseRef, headRef).use { walk ->
                val commit = walk.firstOrNull { it.name() == commitSha }
                    ?: throw IllegalArgumentException("Commit does not belong to this pull request comparison.")
                repo.newObjectReader().use { reader ->
                    val base = commit.parents.firstOrNull()?.let {
                        prepareTreeParser(reader, walk.parseCommit(it).tree.id)
                    } ?: EmptyTreeIterator()
                    formatDiff(repo, reader, base, prepareTreeParser(reader, commit.tree.id))
                }
            }
        }
    }

    private fun comparisonWalk(repo: Repository, baseRef: String, headRef: String): RevWalk {
        val base = repo.resolve(baseRef) ?: throw NoSuchElementException("Ref not found: $baseRef")
        val head = repo.resolve(headRef) ?: throw NoSuchElementException("Ref not found: $headRef")
        return RevWalk(repo).also { walk ->
            try {
                walk.sort(RevSort.TOPO)
                walk.sort(RevSort.COMMIT_TIME_DESC, true)
                walk.markStart(walk.parseCommit(head))
                walk.markUninteresting(walk.parseCommit(base))
            } catch (e: Throwable) {
                walk.close()
                throw e
            }
        }
    }

    override suspend fun resolveMergedBase(pullRequest: PullRequest): String? {
        if (pullRequest.status != PullRequestStatus.MERGED) return null
        pullRequest.mergeBaseSha?.takeIf { it.isNotBlank() }?.let { return it }
        val sha = pullRequest.mergeSha?.takeIf { it.isNotBlank() } ?: return null
        if (pullRequest.mergeStrategy == MergeStrategy.REBASE || pullRequest.mergeStrategy == MergeStrategy.FAST_FORWARD) return null
        return withContext(GitWorkDispatcher) {
            dfsManager.open(pullRequest.repositoryId).use { repo ->
                RevWalk(repo).use { walk ->
                    val commit = walk.parseCommit(ObjectId.fromString(sha))
                    if (pullRequest.mergeStrategy == MergeStrategy.SQUASH || commit.parentCount > 1)
                        commit.parents.firstOrNull()?.name()
                    else null
                }
            }
        }
    }

    private fun computeDiffBlocking(repositoryId: UUID, baseRef: String, headRef: String): List<DiffFile> {
        val dfsRepo = dfsManager.open(repositoryId)
        return dfsRepo.use { repo ->
            val revWalk = RevWalk(repo)
            val reader = repo.objectDatabase.newReader()
            try {
                val baseId = repo.resolve(baseRef) ?: throw NoSuchElementException("Ref not found: $baseRef")
                val headId = repo.resolve(headRef) ?: throw NoSuchElementException("Ref not found: $headRef")

                val baseCommit = revWalk.parseCommit(baseId)
                val headCommit = revWalk.parseCommit(headId)

                revWalk.reset()
                revWalk.revFilter = RevFilter.MERGE_BASE
                revWalk.markStart(baseCommit)
                revWalk.markStart(headCommit)
                val mergeBaseCommit = revWalk.next()
                    ?: throw NoSuchElementException("No common ancestor between $baseRef and $headRef")

                val baseTree = prepareTreeParser(reader, mergeBaseCommit.tree.id)
                val headTree = prepareTreeParser(reader, headCommit.tree.id)

                formatDiff(repo, reader, baseTree, headTree)
            } finally {
                reader.close()
                revWalk.dispose()
            }
        }
    }

    private fun formatDiff(
        repo: Repository, reader: ObjectReader, baseTree: AbstractTreeIterator, headTree: AbstractTreeIterator,
    ): List<DiffFile> = DiffFormatter(DisabledOutputStream.INSTANCE).use { formatter ->
        formatter.setRepository(repo)
        formatter.setDiffComparator(RawTextComparator.DEFAULT)
        formatter.isDetectRenames = true
        formatter.scan(baseTree, headTree).map { entry ->
            if (entry.oldMode == FileMode.GITLINK || entry.newMode == FileMode.GITLINK) {
                return@map formatGitlinkDiff(reader, entry)
            }
            val header = formatter.toFileHeader(entry)
            val text = header.patchType == PatchType.UNIFIED
            val oldText = if (text) loadRawText(reader, entry.oldId?.toObjectId()) else null
            val newText = if (text) loadRawText(reader, entry.newId?.toObjectId()) else null
            DiffFile(
                oldPath = if (entry.changeType == DiffEntry.ChangeType.ADD) null else entry.oldPath,
                newPath = if (entry.changeType == DiffEntry.ChangeType.DELETE) null else entry.newPath,
                changeType = entry.changeType.toDiffChangeType(),
                hunks = if (text) unifiedHunks(header.toEditList(), oldText, newText) else emptyList(),
                newLineCount = newText?.size() ?: 0,
            )
        }
    }

    /**
     * Formats a gitlink change without attempting to read the referenced commit as a blob.
     * Gitlinks point to commits in another repository, so their object IDs are not expected
     * to exist in this repository's object database.
     */
    private fun formatGitlinkDiff(reader: ObjectReader, entry: DiffEntry): DiffFile {
        val oldText = loadEntryText(reader, entry.oldId?.toObjectId(), entry.oldMode)
        val newText = loadEntryText(reader, entry.newId?.toObjectId(), entry.newMode)
        val oldCount = oldText?.size() ?: 0
        val newCount = newText?.size() ?: 0
        val lines = buildList {
            for (index in 0 until oldCount) {
                add(DiffLine(DiffLineType.DELETE, index + 1, null, oldText?.getString(index).orEmpty()))
            }
            for (index in 0 until newCount) {
                add(DiffLine(DiffLineType.ADD, null, index + 1, newText?.getString(index).orEmpty()))
            }
        }
        return DiffFile(
            oldPath = if (entry.changeType == DiffEntry.ChangeType.ADD) null else entry.oldPath,
            newPath = if (entry.changeType == DiffEntry.ChangeType.DELETE) null else entry.newPath,
            changeType = entry.changeType.toDiffChangeType(),
            hunks = if (lines.isEmpty()) {
                emptyList()
            } else {
                listOf(
                    DiffHunk(
                        oldStart = if (oldCount == 0) 0 else 1,
                        oldCount = oldCount,
                        newStart = if (newCount == 0) 0 else 1,
                        newCount = newCount,
                        lines = lines
                    )
                )
            },
            newLineCount = newCount,
        )
    }

    private fun loadEntryText(reader: ObjectReader, objectId: ObjectId?, mode: FileMode): RawText? {
        if (objectId == null || objectId == ObjectId.zeroId() || mode == FileMode.MISSING) return null
        if (mode == FileMode.GITLINK) {
            return RawText("Subproject commit ${objectId.name()}\n".toByteArray())
        }
        return loadRawText(reader, objectId)
    }

    private fun prepareTreeParser(reader: ObjectReader, treeId: ObjectId): CanonicalTreeParser {
        val parser = CanonicalTreeParser()
        parser.reset(reader, treeId)
        return parser
    }

    private fun loadRawText(reader: ObjectReader, objectId: ObjectId?): RawText? {
        if (objectId == null || objectId == ObjectId.zeroId()) return null
        val loader = reader.open(objectId, Constants.OBJ_BLOB)
        return RawText(loader.cachedBytes)
    }

    /**
     * Groups [edits] into unified hunks with [CONTEXT_LINES] unchanged lines around each change,
     * merging changes whose separating unchanged region is no longer than the two contexts. A text
     * change with no edits (a pure rename, mode change or empty file) keeps one empty hunk.
     */
    private fun unifiedHunks(edits: EditList, oldText: RawText?, newText: RawText?): List<DiffHunk> {
        if (edits.isEmpty()) return listOf(DiffHunk(0, 0, 0, 0, emptyList()))
        val groups = mutableListOf<MutableList<Edit>>()
        for (edit in edits) {
            val group = groups.lastOrNull()
            if (group != null && edit.beginA - group.last().endA <= 2 * CONTEXT_LINES) group += edit
            else groups += mutableListOf(edit)
        }
        return groups.map { unifiedHunk(it, oldText, newText) }
    }

    private fun unifiedHunk(edits: List<Edit>, oldText: RawText?, newText: RawText?): DiffHunk {
        val first = edits.first()
        val last = edits.last()
        val leading = minOf(CONTEXT_LINES, first.beginA, first.beginB)
        val trailing = minOf(CONTEXT_LINES, (oldText?.size() ?: 0) - last.endA, (newText?.size() ?: 0) - last.endB)
        val oldBegin = first.beginA - leading
        val newBegin = first.beginB - leading
        val oldEnd = last.endA + trailing
        val newEnd = last.endB + trailing
        val lines = mutableListOf<DiffLine>()
        var oldIndex = oldBegin
        var newIndex = newBegin
        fun contextUntil(end: Int) {
            while (oldIndex < end) {
                lines += DiffLine(DiffLineType.CONTEXT, oldIndex + 1, newIndex + 1, oldText?.getString(oldIndex).orEmpty())
                oldIndex++
                newIndex++
            }
        }
        for (edit in edits) {
            contextUntil(edit.beginA)
            for (i in edit.beginA until edit.endA) {
                lines += DiffLine(DiffLineType.DELETE, i + 1, null, oldText?.getString(i).orEmpty())
            }
            for (i in edit.beginB until edit.endB) {
                lines += DiffLine(DiffLineType.ADD, null, i + 1, newText?.getString(i).orEmpty())
            }
            oldIndex = edit.endA
            newIndex = edit.endB
        }
        contextUntil(oldEnd)
        return DiffHunk(
            oldStart = hunkStart(oldBegin, oldEnd - oldBegin),
            oldCount = oldEnd - oldBegin,
            newStart = hunkStart(newBegin, newEnd - newBegin),
            newCount = newEnd - newBegin,
            lines = lines,
        )
    }

    /** Unified-diff start line: one-based, or the preceding line when the side is empty. */
    private fun hunkStart(begin: Int, count: Int): Int = if (count == 0) begin else begin + 1

    private fun DiffEntry.ChangeType.toDiffChangeType(): DiffChangeType = when (this) {
        DiffEntry.ChangeType.ADD -> DiffChangeType.ADD
        DiffEntry.ChangeType.MODIFY -> DiffChangeType.MODIFY
        DiffEntry.ChangeType.DELETE -> DiffChangeType.DELETE
        DiffEntry.ChangeType.RENAME -> DiffChangeType.RENAME
        DiffEntry.ChangeType.COPY -> DiffChangeType.COPY
    }

    private companion object {
        /** Unchanged lines shown around each change, matching Git's default. */
        const val CONTEXT_LINES = 3
    }
}
