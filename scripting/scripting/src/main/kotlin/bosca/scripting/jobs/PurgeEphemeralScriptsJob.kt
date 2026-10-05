package bosca.scripting.jobs

import bosca.queue.annotations.IJobDefinition
import kotlinx.serialization.Serializable

/**
 * Job configuration for purging soft-deleted ephemeral scripts that have exceeded
 * the retention period. The [retentionDays] parameter controls how long soft-deleted
 * scripts are kept for diagnostic purposes before being permanently removed.
 */
@Serializable
data class PurgeEphemeralScriptsJob(
    val retentionDays: Int = 7
) : IJobDefinition
