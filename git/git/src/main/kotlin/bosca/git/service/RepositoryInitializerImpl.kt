package bosca.git.service

import bosca.git.dfs.BoscaDfsRepositoryManager
import bosca.git.dfs.GitWorkDispatcher
import bosca.git.model.CreateRepositoryInput
import bosca.lock.DistributedLockFactory
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.coroutines.withContext
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.lib.TreeFormatter

/**
 * Populates newly-created DFS repositories with an initial commit containing
 * optional README, .gitignore, and LICENSE files loaded from classpath templates.
 */
@ServiceImplementation
class RepositoryInitializerImpl(
    private val dfsManager: BoscaDfsRepositoryManager,
    private val lockFactory: DistributedLockFactory
) : RepositoryInitializer {

    override suspend fun initialize(repositoryId: UUID, input: CreateRepositoryInput): Long {
        if (!input.initializeWithReadme && input.gitignoreTemplate == null && input.licenseTemplate == null) {
            // Even a repository created without an initial commit must have HEAD
            // linked to its default branch. Protocol-v2 `ls-refs` only advertises
            // HEAD once the symref exists, and single-branch clones (`git clone
            // --depth`/`--single-branch`) pick their branch FROM the advertised
            // HEAD — without this link the first push lands fine but those clones
            // silently produce an empty repository. A dangling target is the same
            // unborn-HEAD state a fresh `git init --bare` leaves behind.
            withContext(GitWorkDispatcher) { linkHead(repositoryId, input.defaultBranch) }
            return 0L
        }

        // The initial commit writes a pack and creates the default branch on a
        // repository row that scheduled GC can already see, so it holds the
        // write lock like every other pack-store writer (see RepositoryWriteLock).
        return lockFactory.withRepositoryWriteLock(repositoryId, RepositoryWriteLock.API_WRITE_WAIT_MILLIS) { lockHandle ->
            withContext(GitWorkDispatcher) { initializeLocked(repositoryId, input, lockHandle) }
        } ?: throw RepositoryWriteBusyException(repositoryId)
    }

    private fun initializeLocked(repositoryId: UUID, input: CreateRepositoryInput, lockHandle: RepositoryWriteLockHandle): Long {
        val dfsRepo = dfsManager.open(repositoryId)
        lockHandle.fence(dfsRepo)
        return dfsRepo.use { repo ->
            val inserter = repo.objectDatabase.newInserter()
            val tree = TreeFormatter()
            var totalBytes = 0L

            val gitignore = input.gitignoreTemplate
            if (gitignore != null) {
                val content = loadGitignoreTemplate(gitignore)
                if (content != null) {
                    val blobId = inserter.insert(Constants.OBJ_BLOB, content)
                    tree.append(".gitignore", FileMode.REGULAR_FILE, blobId)
                    totalBytes += content.size
                }
            }

            val license = input.licenseTemplate
            if (license != null) {
                val content = loadLicenseTemplate(license, input.name)
                if (content != null) {
                    val blobId = inserter.insert(Constants.OBJ_BLOB, content)
                    tree.append("LICENSE", FileMode.REGULAR_FILE, blobId)
                    totalBytes += content.size
                }
            }

            if (input.initializeWithReadme) {
                val content = buildReadme(input.name, input.description)
                val blobId = inserter.insert(Constants.OBJ_BLOB, content)
                tree.append("README.md", FileMode.REGULAR_FILE, blobId)
                totalBytes += content.size
            }

            val treeId = inserter.insert(tree)

            val commit = CommitBuilder()
            commit.setTreeId(treeId)
            commit.setAuthor(SYSTEM_AUTHOR)
            commit.setCommitter(SYSTEM_AUTHOR)
            commit.setMessage("Initial commit")
            val commitId = inserter.insert(commit)
            inserter.flush()

            val refUpdate = repo.refDatabase.newUpdate("refs/heads/${input.defaultBranch}", true)
            refUpdate.setNewObjectId(commitId)
            refUpdate.setExpectedOldObjectId(ObjectId.zeroId())
            refUpdate.update()

            val headUpdate = repo.refDatabase.newUpdate(Constants.HEAD, true)
            headUpdate.link("refs/heads/${input.defaultBranch}")

            totalBytes
        }
    }

    /**
     * Links HEAD to the default branch on a repository created without an
     * initial commit. The target starts dangling — the same unborn-HEAD state a
     * bare `git init` produces — and resolves once the first push creates the
     * branch.
     */
    private fun linkHead(repositoryId: UUID, defaultBranch: String) {
        val dfsRepo = dfsManager.open(repositoryId)
        dfsRepo.use { repo ->
            val headUpdate = repo.refDatabase.newUpdate(Constants.HEAD, true)
            headUpdate.link("refs/heads/$defaultBranch")
        }
    }

    private fun buildReadme(name: String, description: String?): ByteArray {
        val sb = StringBuilder()
        sb.appendLine("# $name")
        if (description != null) {
            sb.appendLine()
            sb.appendLine(description)
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun loadGitignoreTemplate(templateName: String): ByteArray? {
        val path = "templates/gitignore/$templateName.gitignore"
        return javaClass.classLoader.getResourceAsStream(path)?.readBytes()
    }

    private fun loadLicenseTemplate(templateName: String, repoName: String): ByteArray? {
        val path = "templates/license/$templateName"
        val raw = javaClass.classLoader.getResourceAsStream(path)?.readBytes() ?: return null
        val text = String(raw, Charsets.UTF_8)
            .replace("[year]", java.time.Year.now().toString())
            .replace("[fullname]", repoName)
        return text.toByteArray(Charsets.UTF_8)
    }

    companion object {
        private val SYSTEM_AUTHOR = PersonIdent("Bosca", "noreply@bosca.io")
    }
}
