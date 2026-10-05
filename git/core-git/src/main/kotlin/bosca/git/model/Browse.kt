package bosca.git.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** Entry type within a git tree (directory listing). */
@Serializable
enum class TreeEntryType {
    BLOB, TREE, SUBMODULE, SYMLINK
}

/** A single entry in a directory listing at a specific ref. */
@Serializable
data class TreeEntry(
    val name: String,
    val path: String,
    val type: TreeEntryType,
    val mode: Int,
    val sha: String,
    val size: Long? = null
)

/** File content at a specific ref. */
@Serializable
data class Blob(
    val content: String? = null,
    val size: Long,
    val sha: String,
    val isBinary: Boolean = false,
    val mimeType: String? = null
)

/** Commit metadata. */
@Serializable
data class CommitInfo(
    val sha: String,
    val message: String,
    val authorName: String,
    val authorEmail: String,
    val authorDate: String,
    val committerName: String,
    val committerEmail: String,
    val committerDate: String,
    val parentShas: List<String> = emptyList()
)

/** Branch metadata with ahead/behind counts relative to default branch. */
@Serializable
data class BranchInfo(
    val name: String,
    val sha: String,
    val ahead: Int = 0,
    val behind: Int = 0
)

/** Tag metadata. */
@Serializable
data class TagInfo(
    val name: String,
    val sha: String,
    val targetSha: String? = null,
    val taggerName: String? = null,
    val taggerEmail: String? = null,
    val message: String? = null,
    val isAnnotated: Boolean = false
)

/** Per-line blame attribution. */
@Serializable
data class BlameLine(
    val lineNumber: Int,
    val commitSha: String,
    val authorName: String,
    val authorEmail: String,
    val content: String
)

/** Repository-level statistics. */
@Serializable
data class RepoStats(
    val commitCount: Long,
    val branchCount: Int,
    val tagCount: Int,
    val contributorCount: Int,
    val diskSizeBytes: Long
)

/** Structured comparison between two refs showing commits, diff files, and statistics. */
@Serializable
data class ComparisonResult(
    val baseRef: String,
    val headRef: String,
    val commits: List<CommitInfo>,
    val files: List<DiffFile>,
    val filesChanged: Int,
    val insertions: Int,
    val deletions: Int
)

/** A match from a content search across repository files. */
@Serializable
data class SearchResult(
    val filePath: String,
    val lineNumber: Int,
    val snippet: String
)

/** A match from a cross-repo code search backed by the search index. */
@Serializable
data class CodeSearchResult(
    @Contextual
    val repositoryId: UUID,
    val repositoryName: String,
    val repositorySlug: String,
    val filePath: String,
    val language: String,
    val snippet: String
)

/** Paginated code search response. */
@Serializable
data class CodeSearchResponse(
    val results: List<CodeSearchResult>,
    val estimatedHits: Long
)

/** A repository match from the search index. */
@Serializable
data class RepositorySearchResult(
    @Contextual
    val id: UUID,
    val name: String,
    val slug: String,
    val description: String,
    @Contextual
    val ownerId: UUID,
    val visibility: String,
    val defaultBranch: String,
    val archived: Boolean
)

/** Paginated repository search response. */
@Serializable
data class RepositorySearchResponse(
    val results: List<RepositorySearchResult>,
    val estimatedHits: Long
)
