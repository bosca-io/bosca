package bosca.git.model

import bosca.queue.annotations.IJobDefinition
import kotlinx.serialization.Serializable

/**
 * Periodic cleanup of expired or orphaned transient agents.
 *
 * A parameterless tick — the scheduler's `job_parameters` is `{}` and the executor reads nothing
 * from the payload.
 */
@Serializable
class TransientAgentCleanupJob : IJobDefinition
