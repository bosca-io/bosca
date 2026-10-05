package bosca.analytics.jobs

import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Job payload for refreshing the cached results of a single analytics query.
 * Re-executes the query's tracked parameter combinations and replaces their
 * cached records. Enqueued by [AnalyticsQueryRefreshSweepExecutor] for queries
 * whose refresh interval has elapsed (with [onlyStale] set), and by the
 * on-demand `refresh` GraphQL mutation (refreshing every combination).
 */
@Serializable
data class AnalyticsQueryRefreshJob(
    @Contextual val queryId: UUID,
    val onlyStale: Boolean = false,
) : IJobDefinition
