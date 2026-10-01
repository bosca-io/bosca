package bosca.workops.jobs

import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** Config for [WaitForHealthyExecutor] — the environment deployment to watch until it reports HEALTHY. */
@Serializable
data class WaitForHealthyJob(
    @Contextual val deploymentId: UUID,
) : IJobDefinition
