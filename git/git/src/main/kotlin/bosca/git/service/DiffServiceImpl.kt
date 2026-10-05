package bosca.git.service

import bosca.git.dfs.BoscaDfsRepositoryManager
import bosca.git.dfs.GitWorkDispatcher
import bosca.git.model.DiffChangeType
import bosca.git.model.DiffFile
import bosca.git.model.DiffHunk
import bosca.git.model.DiffLine
import bosca.git.model.DiffLineType
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.coroutines.withContext
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.RawTextComparator
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectReader
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.revwalk.filter.RevFilter
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import java.io.ByteArrayOutputStream

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

                val out = ByteArrayOutputStream()
                val formatter = DiffFormatter(out)
                formatter.setRepository(repo)
                formatter.setDiffComparator(RawTextComparator.DEFAULT)
                formatter.isDetectRenames = true

                val entries = formatter.scan(baseTree, headTree)

                entries.map { entry ->
                    if (entry.oldMode == FileMode.GITLINK || entry.newMode == FileMode.GITLINK) {
                        return@map formatGitlinkDiff(reader, entry)
                    }
                    formatter.format(entry)
                    val header = formatter.toFileHeader(entry)
                    val oldText = loadRawText(reader, entry.oldId?.toObjectId())
                    val newText = loadRawText(reader, entry.newId?.toObjectId())
                    DiffFile(
                        oldPath = if (entry.changeType == DiffEntry.ChangeType.ADD) null else entry.oldPath,
                        newPath = if (entry.changeType == DiffEntry.ChangeType.DELETE) null else entry.newPath,
                        changeType = entry.changeType.toDiffChangeType(),
                        hunks = header.hunks.map { hunk ->
                            parseHunk(hunk, oldText, newText)
                        }
                    )
                }
            } finally {
                reader.close()
                revWalk.dispose()
            }
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
            }
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

    private fun parseHunk(hunk: org.eclipse.jgit.patch.HunkHeader, oldText: RawText?, newText: RawText?): DiffHunk {
        val lines = mutableListOf<DiffLine>()
        var oldIdx = hunk.oldImage.startLine
        var newIdx = hunk.newStartLine

        for (edit in hunk.toEditList()) {
            while (oldIdx < edit.beginA) {
                val content = oldText?.getString(oldIdx) ?: ""
                lines.add(DiffLine(DiffLineType.CONTEXT, oldIdx + 1, newIdx + 1, content))
                oldIdx++
                newIdx++
            }

            for (i in edit.beginA until edit.endA) {
                val content = oldText?.getString(i) ?: ""
                lines.add(DiffLine(DiffLineType.DELETE, i + 1, null, content))
                oldIdx++
            }

            for (i in edit.beginB until edit.endB) {
                val content = newText?.getString(i) ?: ""
                lines.add(DiffLine(DiffLineType.ADD, null, i + 1, content))
                newIdx++
            }
        }

        return DiffHunk(
            oldStart = hunk.oldImage.startLine + 1,
            oldCount = hunk.oldImage.lineCount,
            newStart = hunk.newStartLine + 1,
            newCount = hunk.newLineCount,
            lines = lines
        )
    }

    private fun DiffEntry.ChangeType.toDiffChangeType(): DiffChangeType = when (this) {
        DiffEntry.ChangeType.ADD -> DiffChangeType.ADD
        DiffEntry.ChangeType.MODIFY -> DiffChangeType.MODIFY
        DiffEntry.ChangeType.DELETE -> DiffChangeType.DELETE
        DiffEntry.ChangeType.RENAME -> DiffChangeType.RENAME
        DiffEntry.ChangeType.COPY -> DiffChangeType.COPY
    }
}
