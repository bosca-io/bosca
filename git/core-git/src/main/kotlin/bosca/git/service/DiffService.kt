package bosca.git.service

import bosca.git.model.DiffFile
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Computes structured diffs between two refs in a repository with rename detection.
 * Returns line-level hunk detail suitable for rendering in a code review UI.
 */
interface DiffService : Service {

    /**
     * Computes the diff between [baseRef] and [headRef] (branch names or SHAs)
     * using three-dot merge-base semantics for PR diffs.
     */
    suspend fun computeDiff(repositoryId: UUID, baseRef: String, headRef: String): List<DiffFile>
}
