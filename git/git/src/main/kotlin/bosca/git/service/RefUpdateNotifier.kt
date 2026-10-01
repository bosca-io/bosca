package bosca.git.service

import bosca.serialization.UUID
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository

/**
 * A single ref moving from [oldId] to [newId]. [ObjectId.zeroId] in [oldId]
 * means the ref was created; in [newId] it means the ref was deleted.
 */
data class RefChange(
    val refName: String,
    val oldId: ObjectId,
    val newId: ObjectId,
)

/**
 * Runs every downstream side effect of a ref update once and in one place:
 * disk-size accounting, webhook dispatch, task-key extraction, the internal
 * [bosca.git.model.PushEvent], search indexing, and pipeline trigger
 * evaluation.
 *
 * Both the native push path ([bosca.git.transport.GitPostReceiveHook]) and the
 * API/UI write path ([RepositoryWriteService]) funnel through here, so a commit
 * made through the web editor behaves identically to a `git push` — including
 * re-syncing pipeline YAML and creating pipeline runs.
 */
interface RefUpdateNotifier {

    /**
     * @param repository an open JGit repository positioned at the updated refs,
     *   used to walk commit messages for task-key extraction.
     * @param updates the refs that changed in this push/commit.
     * @param pusherPrincipalId the initiating principal that produced the change, if known.
     *   The notifier resolves principals to primary profiles for communication events while retaining
     *   the initiating principal expected by Git pipeline runs.
     */
    suspend fun notifyRefsUpdated(
        repository: Repository,
        repositoryId: UUID,
        updates: List<RefChange>,
        pusherPrincipalId: UUID? = null,
    )
}
