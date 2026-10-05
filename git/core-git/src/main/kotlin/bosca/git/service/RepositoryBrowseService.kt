package bosca.git.service

import bosca.git.model.BlameLine
import bosca.git.model.Blob
import bosca.git.model.BranchInfo
import bosca.git.model.CodeSearchResponse
import bosca.git.model.CommitInfo
import bosca.git.model.RepositorySearchResponse
import bosca.git.model.ComparisonResult
import bosca.git.model.RepoStats
import bosca.git.model.SearchResult
import bosca.git.model.TagInfo
import bosca.git.model.TreeEntry
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Provides read-only browsing operations on a Bosca-hosted git repository:
 * tree listing, blob reading, commit history, branch/tag enumeration, blame,
 * and statistics. All operations resolve refs through JGit's RevWalk and
 * object database — no shelling out to git.
 */
interface RepositoryBrowseService : Service {

    /**
     * Lists the tree entries at the given path within a commit resolved from [ref].
     */
    suspend fun listTree(repositoryId: UUID, ref: String, path: String?): List<TreeEntry>

    /**
     * Reads a blob at the given [path] within the commit resolved from [ref].
     * Returns null if the path does not exist. Files larger than 1MB are returned
     * with null content and [Blob.isBinary] set to true.
     */
    suspend fun readBlob(repositoryId: UUID, ref: String, path: String): Blob?

    /**
     * Lists commits reachable from [ref], optionally filtered to those touching [path].
     */
    suspend fun listCommits(
        repositoryId: UUID,
        ref: String,
        path: String?,
        limit: Int,
        offset: Long
    ): List<CommitInfo>

    /**
     * Retrieves a single commit by its full SHA hex string.
     */
    suspend fun getCommit(repositoryId: UUID, sha: String): CommitInfo?

    /**
     * Lists all branches in the repository with ahead/behind counts relative to HEAD.
     */
    suspend fun listBranches(repositoryId: UUID): List<BranchInfo>

    /**
     * Lists all tags in the repository, peeling annotated tags to their target.
     */
    suspend fun listTags(repositoryId: UUID): List<TagInfo>

    /**
     * Computes per-line blame information for a file at the given [ref].
     */
    suspend fun blame(repositoryId: UUID, ref: String, path: String): List<BlameLine>

    /**
     * Computes aggregate repository statistics: commits, branches, tags,
     * contributors, and approximate disk size.
     */
    suspend fun getStats(repositoryId: UUID): RepoStats

    /**
     * Computes a structured comparison between [baseRef] and [headRef], including
     * the list of commits on head but not base, diff files, and aggregate statistics.
     */
    suspend fun compare(
        repositoryId: UUID,
        baseRef: String,
        headRef: String,
        diffService: DiffService
    ): ComparisonResult

    /**
     * Returns the set of paths whose content differs between [beforeSha] and
     * [afterSha]. Includes added, modified, deleted, and renamed paths. Returns
     * an empty set when either side is the zero object id (branch creation or
     * deletion) — callers decide how to interpret those cases.
     */
    suspend fun listChangedPaths(
        repositoryId: UUID,
        beforeSha: String,
        afterSha: String
    ): Set<String>

    /**
     * Resolves a symbolic [ref] (branch name, tag, "HEAD", or short ref) to a
     * full commit SHA. Returns null if the ref does not exist.
     */
    suspend fun resolveRef(repositoryId: UUID, ref: String): String?

    /**
     * Searches file paths matching [query] as a case-insensitive substring at the
     * given [ref]. Returns up to [limit] results.
     */
    suspend fun searchPaths(
        repositoryId: UUID,
        query: String,
        ref: String = "HEAD",
        limit: Int = 50
    ): List<TreeEntry>

    /**
     * Searches file content for [query] at the given [ref]. Scans text files and
     * returns matching lines with surrounding context. Bounded to [limit] results
     * to prevent excessive I/O on large repositories.
     */
    suspend fun searchContent(
        repositoryId: UUID,
        query: String,
        ref: String = "HEAD",
        limit: Int = 20
    ): List<SearchResult>

    /**
     * Searches across all indexed repositories for files matching [query] via the
     * Meilisearch-backed search index. Results can be filtered by [repositoryId] to
     * scope to a single repo, or by [language] to filter by file type. Returns both
     * repository metadata and file content matches.
     */
    suspend fun searchCode(
        query: String,
        repositoryId: UUID? = null,
        language: String? = null,
        offset: Int = 0,
        limit: Int = 20
    ): CodeSearchResponse

    /**
     * Searches the Meilisearch index for repositories matching [query]. Results
     * are filtered to `_type = "repository"` documents and can be further narrowed
     * by [visibility] or [archived] status.
     */
    suspend fun searchRepositories(
        query: String,
        visibility: String? = null,
        archived: Boolean? = null,
        offset: Int = 0,
        limit: Int = 20
    ): RepositorySearchResponse
}
