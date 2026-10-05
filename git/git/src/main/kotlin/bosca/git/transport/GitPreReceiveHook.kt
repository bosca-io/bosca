package bosca.git.transport

import bosca.db.withConnectionManager
import bosca.git.dfs.BoscaDfsRepository
import bosca.git.service.BranchProtectionService
import bosca.git.service.RepositoryContentValidationError
import bosca.git.service.RepositoryContentValidator
import bosca.git.service.RepositoryContentValidatorRegistry
import bosca.git.service.RepositoryService
import bosca.serialization.UUID
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.PreReceiveHook
import org.eclipse.jgit.transport.ReceiveCommand
import org.eclipse.jgit.transport.ReceivePack
import org.eclipse.jgit.treewalk.TreeWalk
import org.slf4j.LoggerFactory

/**
 * Validates incoming push commands before they are applied to the repository.
 * Two layers of checks:
 *
 * 1. **Branch protection** — blocks direct pushes when PRs are required, rejects
 *    force-pushes and deletions on protected branches, enforces push-access lists.
 *
 * 2. **Repository content validation** — if a [RepositoryContentValidator] is registered
 *    for the repository's [bosca.git.model.RepositoryContentType] (e.g. AGENT_PROJECT),
 *    walks the proposed tree per command, hands the matching files to the validator,
 *    and rejects the command with the structured error list if validation fails.
 *
 * Both [repositoryService] and [validatorRegistry] default to no-op values so existing
 * tests that only exercise branch-protection behavior continue to work with a single-arg
 * constructor.
 */
class GitPreReceiveHook(
    private val branchProtectionService: BranchProtectionService,
    private val repositoryService: RepositoryService? = null,
    private val validatorRegistry: RepositoryContentValidatorRegistry = RepositoryContentValidatorRegistry(),
    /** The principal pushing; checked against branch push-access lists. Null for anonymous pushes. */
    val pusherId: UUID? = null,
) : PreReceiveHook {

    /**
     * Returns a hook for one push by [pusherId]. The route's hook is shared by concurrent pushes,
     * so the pusher is bound per push instead of being set on the shared instance.
     */
    fun forPusher(pusherId: UUID?): GitPreReceiveHook =
        GitPreReceiveHook(branchProtectionService, repositoryService, validatorRegistry, pusherId)

    override fun onPreReceive(rp: ReceivePack, commands: MutableCollection<ReceiveCommand>) {
        val boscaRepo = rp.repository as? BoscaDfsRepository ?: return
        val repositoryId = boscaRepo.repositoryId

        try {
            applyBranchProtection(repositoryId, commands)
            applyContentValidation(repositoryId, rp, commands)
        } catch (e: Throwable) {
            if (
                e is kotlinx.coroutines.CancellationException ||
                (e is Error && e !is LinkageError)
            ) {
                throw e
            }
            // JGit does not translate exceptions thrown by a PreReceiveHook into a receive-pack
            // status report. Letting this escape closes the chunked HTTP response mid-stream and
            // leaves the client with curl 18 / "remote end hung up" instead of a Git rejection.
            log.error("Push validation failed for repository {}", repositoryId, e)
            commands
                .filter { it.result == ReceiveCommand.Result.NOT_ATTEMPTED }
                .forEach {
                    it.setResult(
                        ReceiveCommand.Result.REJECTED_OTHER_REASON,
                        VALIDATION_UNAVAILABLE_MESSAGE,
                    )
                }
        }
    }

    private fun applyBranchProtection(repositoryId: UUID, commands: MutableCollection<ReceiveCommand>) {
        for (command in commands) {
            if (command.result != ReceiveCommand.Result.NOT_ATTEMPTED) continue

            val refName = command.refName
            if (!refName.startsWith("refs/heads/")) continue
            val branchName = refName.removePrefix("refs/heads/")

            val rule = runBlocking {
                withConnectionManager {
                    branchProtectionService.findMatchingRule(repositoryId, branchName)
                }
            } ?: continue

            val isDelete = command.newId == ObjectId.zeroId()
            val isForceUpdate = command.type == ReceiveCommand.Type.UPDATE_NONFASTFORWARD

            if (isDelete && !rule.allowDeletion) {
                command.setResult(
                    ReceiveCommand.Result.REJECTED_OTHER_REASON,
                    "Branch '$branchName' is protected: deletion is not allowed"
                )
                continue
            }

            if (isForceUpdate && !rule.allowForcePush) {
                command.setResult(
                    ReceiveCommand.Result.REJECTED_OTHER_REASON,
                    "Branch '$branchName' is protected: force push is not allowed"
                )
                continue
            }

            if (rule.requirePullRequest && !isMergeCommitFromPR(command)) {
                command.setResult(
                    ReceiveCommand.Result.REJECTED_OTHER_REASON,
                    "Branch '$branchName' is protected: changes must be made through a pull request"
                )
                continue
            }

            if (rule.restrictPushAccess.isNotEmpty() && pusherId !in rule.restrictPushAccess) {
                command.setResult(
                    ReceiveCommand.Result.REJECTED_OTHER_REASON,
                    "Branch '$branchName' is protected: you are not in the push access list"
                )
                continue
            }
        }
    }

    private fun applyContentValidation(
        repositoryId: UUID,
        rp: ReceivePack,
        commands: MutableCollection<ReceiveCommand>,
    ) {
        val service = repositoryService ?: return
        val repository = runBlocking {
            withConnectionManager { service.findById(repositoryId) }
        } ?: return
        val contentType = repository.contentType ?: return
        val validator = validatorRegistry.get(contentType) ?: return

        for (command in commands) {
            if (command.result != ReceiveCommand.Result.NOT_ATTEMPTED) continue
            if (command.newId == ObjectId.zeroId()) continue // deletes have no tree to validate

            val files = try {
                readFiles(rp, command.newId, validator.pathPrefixes)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Failed to read tree for {} during content validation: {}", command.newId.name(), e.message, e)
                command.setResult(
                    ReceiveCommand.Result.REJECTED_OTHER_REASON,
                    "Failed to read proposed tree for content validation: ${e.message}"
                )
                continue
            }

            val errors: List<RepositoryContentValidationError> = try {
                runBlocking { withConnectionManager { validator.validate(repositoryId, files) } }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Content validator {} threw for repo {}: {}", contentType, repositoryId, e.message, e)
                command.setResult(
                    ReceiveCommand.Result.REJECTED_OTHER_REASON,
                    "Content validation failed: ${e.message}"
                )
                continue
            }

            if (errors.isNotEmpty()) {
                val message = "Push rejected: $contentType validation failed:\n" +
                    errors.joinToString("\n") { " - ${it.path}: ${it.message}" }
                command.setResult(ReceiveCommand.Result.REJECTED_OTHER_REASON, message)
            }
        }
    }

    private fun readFiles(rp: ReceivePack, commitId: ObjectId, pathPrefixes: List<String>): Map<String, String> {
        val files = mutableMapOf<String, String>()
        val revWalk = RevWalk(rp.repository)
        try {
            val commit = revWalk.parseCommit(commitId)
            val treeWalk = TreeWalk(rp.repository)
            treeWalk.addTree(commit.tree)
            treeWalk.isRecursive = true
            while (treeWalk.next()) {
                val path = treeWalk.pathString
                if (pathPrefixes.isNotEmpty() && pathPrefixes.none { path.startsWith(it) }) continue
                val objectId = treeWalk.getObjectId(0)
                val loader = rp.repository.objectDatabase.open(objectId)
                files[path] = loader.bytes.toString(Charsets.UTF_8)
            }
        } finally {
            revWalk.dispose()
        }
        return files
    }

    private fun isMergeCommitFromPR(command: ReceiveCommand): Boolean {
        return command.result == ReceiveCommand.Result.NOT_ATTEMPTED &&
            command.refName.startsWith("refs/heads/") &&
            command.message?.startsWith("Merge pull request") == true
    }

    companion object {
        private val log = LoggerFactory.getLogger(GitPreReceiveHook::class.java)
        internal const val VALIDATION_UNAVAILABLE_MESSAGE =
            "Push validation could not be completed; please retry"
    }
}
