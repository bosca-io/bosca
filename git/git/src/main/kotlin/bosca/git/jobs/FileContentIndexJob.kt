package bosca.git.jobs

import bosca.queue.annotations.IJobDefinition
import bosca.search.IndexStorageSystem
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

/**
 * Drives incremental updates to the git code search index for a single branch
 * transition on a single repository. The before/after SHAs come straight from
 * the receive-pack command that produced the push, so git remains the
 * authoritative source for "what changed on this branch" — the executor
 * computes the affected paths by walking the two trees and adjusts the
 * `branches` overlay on each content-addressed file document accordingly.
 *
 * Modes (selected by the SHA combination):
 *  - `beforeSha == null && afterSha != null` — initial index of a new branch.
 *  - `beforeSha != null && afterSha != null` — incremental update for a push.
 *  - `beforeSha != null && afterSha == null` — branch deletion; untag every
 *    document that was tagged with this ref.
 */
@Serializable
data class FileContentIndexJob(
    val storage: IndexStorageSystem? = null,
    val repositoryId: UUID,
    val ref: String,
    val beforeSha: String? = null,
    val afterSha: String? = null,
) : IJobDefinition
