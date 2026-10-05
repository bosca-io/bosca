package bosca.analytics.jobs

import bosca.queue.annotations.IJobDefinition
import kotlinx.serialization.Serializable

/**
 * Job payload for the periodic analytics query refresh sweep. Runs on the cron
 * schedule seeded into `scheduler.scheduled_jobs` by the analytics migration,
 * prunes idle cache entries, and enqueues an [AnalyticsQueryRefreshJob] for
 * each query whose refresh interval has elapsed.
 */
@Serializable
class AnalyticsQueryRefreshSweepJob : IJobDefinition
