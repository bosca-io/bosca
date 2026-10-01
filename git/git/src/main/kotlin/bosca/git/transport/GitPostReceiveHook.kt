package bosca.git.transport

import bosca.cache.withRequestCache
import bosca.db.withConnectionManager
import bosca.git.dfs.BoscaDfsRepository
import bosca.git.service.RefChange
import bosca.git.service.RefUpdateNotifier
import bosca.serialization.UUID
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.transport.PostReceiveHook
import org.eclipse.jgit.transport.ReceiveCommand
import org.eclipse.jgit.transport.ReceivePack
import org.slf4j.LoggerFactory

/**
 * Runs after a push has been accepted and refs updated. Delegates to
 * [RefUpdateNotifier], which performs the same side effects (disk-size metrics,
 * webhook events, task-key extraction, internal push event, search indexing,
 * and pipeline triggers) regardless of whether the ref moved via a native push
 * or an API/UI commit.
 */
class GitPostReceiveHook(
    private val refUpdateNotifier: RefUpdateNotifier,
    private val initiatingPrincipalId: UUID? = null,
) : PostReceiveHook {

    /**
     * Returns a request-scoped hook that attributes ref-update side effects to [principalId].
     *
     * The configured hook is shared by the route, so the request identity is carried by an
     * immutable copy instead of mutable state that concurrent pushes could overwrite.
     */
    fun withInitiatingPrincipal(principalId: UUID?): GitPostReceiveHook =
        GitPostReceiveHook(refUpdateNotifier, principalId)

    override fun onPostReceive(rp: ReceivePack, commands: MutableCollection<ReceiveCommand>) {
        val boscaRepo = rp.repository as? BoscaDfsRepository ?: return
        val repositoryId = boscaRepo.repositoryId

        val successfulCommands = commands.filter { it.result == ReceiveCommand.Result.OK }
        if (successfulCommands.isEmpty()) return

        log.info("Post-receive: {} successful ref updates for repository {}", successfulCommands.size, repositoryId)

        runBlocking {
            withRequestCache {
                withConnectionManager {
                    refUpdateNotifier.notifyRefsUpdated(
                        repository = boscaRepo,
                        repositoryId = repositoryId,
                        updates = successfulCommands.map { RefChange(it.refName, it.oldId, it.newId) },
                        pusherPrincipalId = initiatingPrincipalId,
                    )
                }
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(GitPostReceiveHook::class.java)
    }
}
