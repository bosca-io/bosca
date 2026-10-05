package bosca.analytics.jobs

import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Job payload for syncing analytics queries linked to files in a pushed
 * git commit range. Enqueued from [bosca.analytics.service.AnalyticsGitPushListener]
 * on each `bosca.git.push` event, executed by [AnalyticsQuerySyncExecutor].
 */
@Serializable
data class AnalyticsQuerySyncJob(
    @Contextual val repositoryId: UUID,
    val ref: String,
    val beforeSha: String,
    val afterSha: String,
) : IJobDefinition
