package bosca.git.jobs

import bosca.di.provide
import bosca.git.dfs.BoscaDfsRepositoryManager
import bosca.git.dfs.GitWorkDispatcher
import bosca.git.model.Repository
import bosca.git.repository.GitRepositoryRepository
import bosca.queue.annotations.JobDefinition
import bosca.search.IndexStorageSystem
import bosca.search.model.SearchFilter
import bosca.search.service.SearchService
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.AbstractJobExecutor
import bosca.storage.model.StorageSystemType
import bosca.storage.service.StorageSystemService
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import org.slf4j.LoggerFactory
import java.security.MessageDigest

/**
 * Applies a single branch transition (initial / incremental / delete) to the git
 * code search index. Documents are content-addressed by `(repositoryId, path, blobSha)`
 * and carry a `branches` overlay array that lists every ref currently pointing at
 * exactly that content at exactly that path. A blob shared across branches is one
 * document tagged with multiple branch names — adding a branch is a no-op once the
 * tag is present, and removing the last branch drops the document.
 *
 * State lives in git: `beforeSha` and `afterSha` come straight from the receive-pack
 * command, so the executor can compute the exact set of `(path, blob)` pairs to tag
 * or untag by walking the two trees in lockstep. There is no parallel snapshot table.
 *
 * Concurrent updates to the same document (e.g., two branches in the same repo
 * touching a shared blob) are serialized by an executor-level distributed lock keyed
 * on `repositoryId` — see [getLockId]. The lock is required because the `branches`
 * array is maintained via read-modify-write against Meilisearch, which has no
 * atomic array-append primitive.
 */
@JobDefinition(FileContentIndexJob::class, "git", "file-content-index")
class FileContentIndexExecutor : AbstractJobExecutor<FileContentIndexJob>(FileContentIndexJob.serializer()) {

    override suspend fun getLockId(): String? {
        val job = getJobDefinition()
        return "$LOCK_PREFIX${job.repositoryId}"
    }

    override suspend fun execute() {
        val job = getJobDefinition()
        if (!job.ref.startsWith(BRANCH_REF_PREFIX)) {
            log.warn("Ignoring non-branch ref {} for repository {}", job.ref, job.repositoryId)
            return
        }
        if (job.beforeSha == null && job.afterSha == null) {
            log.warn("Skipping no-op index job for {} ref {}: both SHAs null", job.repositoryId, job.ref)
            return
        }

        val searchService: SearchService = provide()
        val dfsManager: BoscaDfsRepositoryManager = provide()
        val repoRepository: GitRepositoryRepository = provide()
        val storage = job.storage
        if (storage != null) {
            storage.execute(searchService, dfsManager, repoRepository, job)
        } else {
            val storageService: StorageSystemService = provide()
            storageService.getAll()
                .filter { it.type == StorageSystemType.SEARCH && it.name == STORAGE_SYSTEM_NAME }
                .forEach {
                    IndexStorageSystem(it.id, it.name).execute(searchService, dfsManager, repoRepository, job)
                }
        }
    }

    private suspend fun IndexStorageSystem.execute(
        searchService: SearchService,
        dfsManager: BoscaDfsRepositoryManager,
        repoRepository: GitRepositoryRepository,
        job: FileContentIndexJob
    ) {
        val repositoryId = job.repositoryId
        val branch = job.ref.removePrefix(BRANCH_REF_PREFIX)
        val repo = repoRepository.findById(repositoryId)
        if (repo == null || repo.deleted) {
            // Repo is gone — drop every file doc belonging to it irrespective of branch
            // overlay. Repository-metadata removal is handled by RepositoryIndexJob; this
            // executor owns the `_type=file` slice.
            searchService.deleteByFilter(
                this,
                SearchFilter.and(
                    SearchFilter.eq("repositoryId", repositoryId.toString()),
                    SearchFilter.eq("_type", "file"),
                )
            )
            return
        }

        // Exactly one branch-index mode applies here: the both-SHAs-null no-op is rejected by the
        // caller before dispatch. Capturing and smart-casting the SHAs keeps the branch dispatch
        // null-safe without the redundant `?:` guards the compiler would otherwise force.
        val beforeSha = job.beforeSha
        val afterSha = job.afterSha
        withContext(GitWorkDispatcher) {
            val dfsRepo = dfsManager.open(repositoryId)
            dfsRepo.use { gitRepo ->
                when {
                    // Incremental update on an existing branch.
                    beforeSha != null && afterSha != null ->
                        applyDiff(searchService, gitRepo, beforeSha, afterSha, repositoryId, repo, branch)
                    // Initial index for a newly created branch: walk the new tree and tag this branch
                    // on every (path, blob) doc, creating the doc if it doesn't already exist (it might,
                    // if another branch shares the content).
                    afterSha != null ->
                        tagTree(searchService, gitRepo, afterSha, repositoryId, repo, branch)
                    // Branch deletion: walk the last-known tree and untag this branch everywhere.
                    beforeSha != null ->
                        untagTree(searchService, gitRepo, beforeSha, repositoryId, branch)
                }
            }
        }
    }

    private suspend fun IndexStorageSystem.tagTree(
        searchService: SearchService,
        gitRepo: org.eclipse.jgit.lib.Repository,
        sha: String,
        repositoryId: UUID,
        repo: Repository,
        branch: String,
    ) {
        val revWalk = RevWalk(gitRepo)
        try {
            val commit = try {
                revWalk.parseCommit(ObjectId.fromString(sha))
            } catch (e: Exception) {
                log.warn("Cannot parse commit {} for repository {}", sha, repositoryId, e)
                return
            }
            val treeWalk = TreeWalk(gitRepo)
            treeWalk.addTree(commit.tree)
            treeWalk.isRecursive = true
            var tagged = 0
            while (treeWalk.next()) {
                if (!treeWalk.fileMode.isIndexableFile()) continue
                val path = treeWalk.pathString
                val blobSha = treeWalk.getObjectId(0)
                val bytes = loadIndexableBytes(gitRepo, blobSha) ?: continue
                tagBranch(searchService, repo, branch, path, blobSha, bytes)
                tagged++
            }
            log.info("Tagged {} files on branch {} of repository {}", tagged, branch, repositoryId)
        } finally {
            revWalk.dispose()
        }
    }

    private suspend fun IndexStorageSystem.untagTree(
        searchService: SearchService,
        gitRepo: org.eclipse.jgit.lib.Repository,
        sha: String,
        repositoryId: UUID,
        branch: String,
    ) {
        val revWalk = RevWalk(gitRepo)
        try {
            val commit = try {
                revWalk.parseCommit(ObjectId.fromString(sha))
            } catch (e: Exception) {
                log.warn("Cannot parse commit {} for repository {}", sha, repositoryId, e)
                return
            }
            val treeWalk = TreeWalk(gitRepo)
            treeWalk.addTree(commit.tree)
            treeWalk.isRecursive = true
            var untagged = 0
            while (treeWalk.next()) {
                if (!treeWalk.fileMode.isIndexableFile()) continue
                val path = treeWalk.pathString
                val blobSha = treeWalk.getObjectId(0)
                // No need to load bytes; if we never tagged this doc (binary, oversize),
                // the fetch in untagBranch returns null and the call is a no-op.
                untagBranch(searchService, repositoryId, branch, path, blobSha)
                untagged++
            }
            log.info("Untagged branch {} from {} tree entries of repository {}", branch, untagged, repositoryId)
        } finally {
            revWalk.dispose()
        }
    }

    private suspend fun IndexStorageSystem.applyDiff(
        searchService: SearchService,
        gitRepo: org.eclipse.jgit.lib.Repository,
        beforeSha: String,
        afterSha: String,
        repositoryId: UUID,
        repo: Repository,
        branch: String,
    ) {
        if (beforeSha == afterSha) return
        val revWalk = RevWalk(gitRepo)
        try {
            val beforeTree = try {
                revWalk.parseCommit(ObjectId.fromString(beforeSha)).tree
            } catch (e: Exception) {
                log.warn("Cannot parse before commit {} for repository {}", beforeSha, repositoryId, e)
                return
            }
            val afterTree = try {
                revWalk.parseCommit(ObjectId.fromString(afterSha)).tree
            } catch (e: Exception) {
                log.warn("Cannot parse after commit {} for repository {}", afterSha, repositoryId, e)
                return
            }
            val treeWalk = TreeWalk(gitRepo)
            treeWalk.addTree(beforeTree) // index 0
            treeWalk.addTree(afterTree)  // index 1
            treeWalk.isRecursive = true

            var added = 0
            var modified = 0
            var removed = 0

            while (treeWalk.next()) {
                val beforeMode = treeWalk.getFileMode(0)
                val afterMode = treeWalk.getFileMode(1)
                val beforeId = treeWalk.getObjectId(0)
                val afterId = treeWalk.getObjectId(1)
                val path = treeWalk.pathString

                val beforeEligible = beforeMode.isIndexableFile() && !beforeId.equals(ObjectId.zeroId())
                val afterEligible = afterMode.isIndexableFile() && !afterId.equals(ObjectId.zeroId())

                // Unchanged blob at the same path — branch tag is already correct.
                if (beforeEligible && afterEligible && beforeId == afterId) continue

                if (beforeEligible) {
                    untagBranch(searchService, repositoryId, branch, path, beforeId)
                    if (!afterEligible) removed++
                }
                if (afterEligible) {
                    val bytes = loadIndexableBytes(gitRepo, afterId)
                    if (bytes != null) {
                        tagBranch(searchService, repo, branch, path, afterId, bytes)
                        if (beforeEligible) modified++ else added++
                    }
                }
            }
            log.info(
                "Diff applied to branch {} of repository {}: +{} ~{} -{}",
                branch, repositoryId, added, modified, removed
            )
        } finally {
            revWalk.dispose()
        }
    }

    /**
     * Idempotently ensures [branch] appears in the `branches` overlay of the document
     * for `(repositoryId, path, blobSha)`, creating the document from [bytes] if it
     * does not yet exist. Safe to call repeatedly with the same arguments.
     */
    private suspend fun IndexStorageSystem.tagBranch(
        searchService: SearchService,
        repo: Repository,
        branch: String,
        path: String,
        blobSha: ObjectId,
        bytes: ByteArray,
    ) {
        val docId = documentId(repo.id.toString(), path, blobSha.name())
        val existing = searchService.fetch(this, docId) as? JsonObject
        val branches = existing?.get("branches")?.jsonArray?.toBranchList().orEmpty()
        if (branch in branches) return
        val newBranches = branches + branch
        val updated = if (existing != null) {
            buildJsonObject {
                for ((key, value) in existing) {
                    if (key != "branches") put(key, value)
                }
                putJsonArray("branches") { newBranches.forEach { add(it) } }
            }
        } else {
            buildFileDocument(
                docId = docId,
                repo = repo,
                path = path,
                bytes = bytes,
                branches = newBranches,
            )
        }
        searchService.index(this, updated)
    }

    /**
     * Idempotently removes [branch] from the `branches` overlay of the document for
     * `(repositoryId, path, blobSha)`. Deletes the document when the overlay becomes
     * empty. No-op if no such document exists (e.g., the blob was binary or oversize
     * and was never indexed in the first place).
     */
    private suspend fun IndexStorageSystem.untagBranch(
        searchService: SearchService,
        repositoryId: UUID,
        branch: String,
        path: String,
        blobSha: ObjectId,
    ) {
        val docId = documentId(repositoryId.toString(), path, blobSha.name())
        val existing = searchService.fetch(this, docId) as? JsonObject ?: return
        val branches = existing["branches"]?.jsonArray?.toBranchList().orEmpty()
        if (branch !in branches) return
        val remaining = branches.filter { it != branch }
        if (remaining.isEmpty()) {
            searchService.delete(this, docId)
        } else {
            val updated = buildJsonObject {
                for ((key, value) in existing) {
                    if (key != "branches") put(key, value)
                }
                putJsonArray("branches") { remaining.forEach { add(it) } }
            }
            searchService.index(this, updated)
        }
    }

    private fun buildFileDocument(
        docId: String,
        repo: Repository,
        path: String,
        bytes: ByteArray,
        branches: List<String>,
    ): JsonElement {
        val content = String(bytes, Charsets.UTF_8)
        return buildJsonObject {
            put("id", docId)
            put("_type", "file")
            put("repositoryId", repo.id.toString())
            put("repositoryName", repo.name)
            put("repositorySlug", repo.slug)
            put("ownerId", repo.ownerId.toString())
            put("visibility", repo.visibility.name)
            put("filePath", path)
            put("fileName", path.substringAfterLast('/'))
            put("language", guessLanguage(path))
            put("content", content.take(MAX_CONTENT_LENGTH))
            putJsonArray("branches") { branches.forEach { add(it) } }
        }
    }

    /**
     * Loads a blob's bytes if it is eligible for indexing — small enough and
     * non-binary. Returns null when the blob should be skipped (oversize, binary,
     * or unreadable), in which case the caller treats it as an absent file.
     */
    private fun loadIndexableBytes(gitRepo: org.eclipse.jgit.lib.Repository, blobSha: ObjectId): ByteArray? {
        val loader = try {
            gitRepo.objectDatabase.open(blobSha)
        } catch (e: Exception) {
            log.warn("Failed to open blob {}: {}", blobSha.name(), e.message)
            return null
        }
        if (loader.size > MAX_FILE_SIZE) return null
        val bytes = loader.cachedBytes
        if (isBinaryContent(bytes)) return null
        return bytes
    }

    private fun JsonArray.toBranchList(): List<String> = mapNotNull { it.jsonPrimitive.contentOrNull }

    private fun FileMode.isIndexableFile(): Boolean =
        this == FileMode.REGULAR_FILE || this == FileMode.EXECUTABLE_FILE

    companion object {
        private val log = LoggerFactory.getLogger(FileContentIndexExecutor::class.java)

        const val STORAGE_SYSTEM_NAME = "git-code"

        private const val MAX_FILE_SIZE = 256L * 1024L
        private const val MAX_CONTENT_LENGTH = 50_000
        private const val LOCK_PREFIX = "git-file-content-index-"
        private const val BRANCH_REF_PREFIX = "refs/heads/"

        /**
         * Document primary key: namespaced under the repository UUID, derived from
         * `path` and `blobSha` so the same content at the same path on multiple
         * branches collapses to a single document. The `\u0000` separator removes
         * ambiguity between e.g. path "a/b" + blob "c" and path "a" + blob "b/c".
         * Meilisearch primary keys only allow `[a-zA-Z0-9_-]`, so the digest is hex.
         */
        internal fun documentId(repositoryId: String, path: String, blobSha: String): String {
            val digest = MessageDigest.getInstance("SHA-256").apply {
                update(path.toByteArray(Charsets.UTF_8))
                update(0)
                update(blobSha.toByteArray(Charsets.UTF_8))
            }.digest()
            val hex = StringBuilder(digest.size * 2)
            for (b in digest) {
                val v = b.toInt() and 0xff
                hex.append(HEX_CHARS[v ushr 4])
                hex.append(HEX_CHARS[v and 0x0f])
            }
            return "${repositoryId}_$hex"
        }

        private val HEX_CHARS = "0123456789abcdef".toCharArray()

        private fun isBinaryContent(bytes: ByteArray): Boolean {
            val checkLen = minOf(bytes.size, 8192)
            for (i in 0 until checkLen) {
                if (bytes[i] == 0.toByte()) return true
            }
            return false
        }

        private fun guessLanguage(path: String): String {
            val ext = path.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "kt" -> "kotlin"
                "java" -> "java"
                "py" -> "python"
                "js" -> "javascript"
                "ts" -> "typescript"
                "tsx" -> "typescript"
                "jsx" -> "javascript"
                "rs" -> "rust"
                "go" -> "go"
                "rb" -> "ruby"
                "c", "h" -> "c"
                "cpp", "cc", "cxx", "hpp" -> "cpp"
                "cs" -> "csharp"
                "swift" -> "swift"
                "sql" -> "sql"
                "sh", "bash", "zsh" -> "shell"
                "yaml", "yml" -> "yaml"
                "json" -> "json"
                "xml" -> "xml"
                "html", "htm" -> "html"
                "css" -> "css"
                "scss", "sass" -> "scss"
                "md" -> "markdown"
                "toml" -> "toml"
                "gradle" -> "gradle"
                "kts" -> "kotlin"
                "dockerfile" -> "dockerfile"
                else -> ext.ifEmpty { "text" }
            }
        }
    }
}
