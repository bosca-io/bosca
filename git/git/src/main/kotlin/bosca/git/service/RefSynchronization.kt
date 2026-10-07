package bosca.git.service

import bosca.db.connectionOrNull
import bosca.git.dfs.BoscaDfsRepositoryManager
import bosca.git.dfs.GitWorkDispatcher
import bosca.git.model.GitHubSyncResult
import bosca.lock.DistributedLockFactory
import bosca.serialization.UUID
import bosca.security.service.SecurityException
import kotlinx.coroutines.withContext
import org.eclipse.jgit.lib.NullProgressMonitor
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.TagOpt
import org.eclipse.jgit.transport.Transport
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider

internal suspend fun compareRefs(
    repositoryId: UUID, remoteUrl: String, token: String, manager: BoscaDfsRepositoryManager,
): List<RefComparison> = withContext(GitWorkDispatcher) {
    manager.open(repositoryId).use { repo ->
        val local = repo.refDatabase.getRefsByPrefix("refs/heads/", "refs/tags/").associate { it.name to it.objectId?.name() }
        Transport.open(repo, URIish(remoteUrl)).use { transport ->
            transport.credentialsProvider = UsernamePasswordCredentialsProvider("x-access-token", token)
            transport.timeout = 30
            val remote = transport.openFetch().use { connection -> connection.refs.filter {
                Repository.isValidRefName(it.name) && (it.name.startsWith("refs/heads/") || it.name.startsWith("refs/tags/"))
            }.associate { it.name to it.objectId?.name() } }
            (local.keys + remote.keys).sorted().map { RefComparison(it, local[it], remote[it]) }
        }
    }
}

/** Uses a source-only fetch so no incidental tracking ref or tag update emits a second push. */
internal suspend fun synchronizeRef(
    input: RefSynchronizationInput,
    manager: BoscaDfsRepositoryManager,
    notifier: RefUpdateNotifier,
    locks: DistributedLockFactory,
): RefSynchronizationResult = withContext(GitWorkDispatcher) {
    require(Repository.isValidRefName(input.ref) &&
        (input.ref.startsWith("refs/heads/") || input.ref.startsWith("refs/tags/"))) { "Invalid synchronized ref" }
    val before = input.beforeSha?.let(ObjectId::fromString)
    val after = input.afterSha?.let(ObjectId::fromString)
    val baseline = input.synchronizedSha?.let(ObjectId::fromString)
    withRefSynchronizationLock(input.repositoryId, locks) { lock ->
        val connection = connectionOrNull()?.takeIf { it.inTransaction }
        (if (connection == null) manager.open(input.repositoryId) else manager.open(input.repositoryId, connection)).use { repo ->
            lock.fence(repo)
            Transport.open(repo, URIish(input.remoteUrl)).use { transport ->
                transport.credentialsProvider = UsernamePasswordCredentialsProvider("x-access-token", input.token)
                transport.timeout = 30
                transport.tagOpt = TagOpt.NO_TAGS
                transport.isCheckFetchedObjects = true
                val advertised = transport.openFetch().use { it.getRef(input.ref)?.objectId }
                val remote = if (advertised == null) null else {
                    transport.fetch(NullProgressMonitor.INSTANCE, listOf(RefSpec(input.ref)))
                        .getAdvertisedRef(input.ref)?.objectId
                        ?: error("Remote ref disappeared during fetch")
                }
                val local = repo.exactRef(input.ref)?.objectId
                val inbound = input.direction == RefSynchronizationDirection.INBOUND
                val source = if (inbound) remote else local
                val target = if (inbound) local else remote
                fun result(status: GitHubSyncResult, updated: Boolean = false) = RefSynchronizationResult(
                    status,
                    (if (updated && inbound) source else local)?.name(),
                    (if (updated && !inbound) source else remote)?.name(),
                    if (updated) target?.name() else null,
                )
                if (source != after) return@withRefSynchronizationLock result(GitHubSyncResult.STALE)
                if (input.resolveConflict && target != before) return@withRefSynchronizationLock result(GitHubSyncResult.STALE)
                if (source == target) {
                    val principal = input.principalId
                    val unattributedBefore = input.unattributedBeforeSha
                    if (inbound && source != null && principal != null && unattributedBefore != null) {
                        notifier.enqueuePipelineTriggers(repo, input.repositoryId, listOf(RefChange(
                            input.ref, ObjectId.fromString(unattributedBefore), source,
                        )), principal)
                    }
                    return@withRefSynchronizationLock result(GitHubSyncResult.UNCHANGED)
                }

                val expectedTarget = if (input.hasSynchronized) baseline else before
                val unchangedTarget = target == expectedTarget && (input.hasSynchronized || before != null)
                val fastForward = source != null && target != null && input.ref.startsWith("refs/heads/") &&
                    RevWalk(repo).use { walk -> walk.isMergedInto(walk.parseCommit(target), walk.parseCommit(source)) }
                val creation = target == null && expectedTarget == null && (input.hasSynchronized || before == null) &&
                    (input.hasSynchronized || !input.hasConflict)
                val safe = if (source == null) unchangedTarget else creation || unchangedTarget || fastForward
                if (!safe && !input.resolveConflict) return@withRefSynchronizationLock result(GitHubSyncResult.CONFLICT)

                if (inbound) input.protection?.let { rule ->
                    val branch = input.ref.removePrefix("refs/heads/")
                    fun reject(reason: String): Nothing = throw SecurityException("Branch '$branch' is protected: $reason")
                    if (source == null && !rule.allowDeletion) reject("deletion is not allowed")
                    if (source != null && target != null && !fastForward && !rule.allowForcePush) reject("force pushes are not allowed")
                    if (rule.requirePullRequest && !input.pullRequestMerge) reject("changes must be made through a pull request")
                    if (rule.restrictPushAccess.isNotEmpty() && input.principalId !in rule.restrictPushAccess) reject("you are not in the push access list")
                    if (source != null && rule.requireLinearHistory) {
                        RevWalk(repo).use { walk ->
                            walk.markStart(walk.parseCommit(source))
                            target?.let { walk.markUninteresting(walk.parseCommit(it)) }
                            if (walk.any { it.parentCount > 1 }) reject("linear history is required")
                        }
                    }
                }
                lock.ensureHeld()
                if (inbound) {
                    val update = repo.updateRef(input.ref)
                    update.setExpectedOldObjectId(target ?: ObjectId.zeroId())
                    update.isForceUpdate = true
                    val status = if (source == null) update.delete() else {
                        update.setNewObjectId(source)
                        update.update()
                    }
                    if (status !in setOf(RefUpdate.Result.NEW, RefUpdate.Result.FORCED, RefUpdate.Result.FAST_FORWARD,
                            RefUpdate.Result.NO_CHANGE)) {
                        error("Synchronized ref update failed: $status")
                    }
                    notifier.notifyRefsUpdated(repo, input.repositoryId, listOf(RefChange(
                        input.ref, target ?: ObjectId.zeroId(), source ?: ObjectId.zeroId(),
                    )), input.principalId.takeIf { input.triggerBuild })
                } else {
                    val update = RemoteRefUpdate(repo, source?.name(), input.ref, true, null, target ?: ObjectId.zeroId())
                    val pushed = transport.push(NullProgressMonitor.INSTANCE, listOf(update))
                    when (update.status) {
                        RemoteRefUpdate.Status.OK, RemoteRefUpdate.Status.UP_TO_DATE -> Unit
                        RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED -> return@withRefSynchronizationLock RefSynchronizationResult(
                            GitHubSyncResult.CONFLICT, local?.name(), pushed.getAdvertisedRef(input.ref)?.objectId?.name(),
                        )
                        else -> error("GitHub ref update failed: ${update.status}")
                    }
                }
                result(GitHubSyncResult.APPLIED, updated = true)
            }
        }
    }
}
