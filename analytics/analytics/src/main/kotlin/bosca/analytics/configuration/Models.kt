package bosca.analytics.configuration

import kotlinx.serialization.Serializable

/**
 * Tuning knobs for the in-memory event processing pipeline that sits between
 * the HTTP ingestion endpoint and the downstream [EventRepository][bosca.analytics.repository.EventRepository].
 */
@Serializable
class EventProcessingConfiguration(
    /** Number of coroutine workers draining the processing channel. */
    val workerCount: Int = 16,
    /** Bounded channel capacity; acts as a backpressure buffer for bursts. */
    val channelCapacity: Int = 5000,
    /** Number of messages to pull from NATS in a single fetch call. */
    val natsBatchSize: Int = 100,
    /** Timeout in seconds when fetching messages from NATS JetStream. */
    val natsFetchTimeoutSeconds: Long = 5,
    /**
     * Identifier of the service account the inline analytics script transform impersonates when it
     * runs bound scripts (see [AnalyticsScriptTransform][bosca.analytics.transform.AnalyticsScriptTransform]).
     */
    val scriptServiceAccount: String = "sa",
)

/**
 * Operational limits for analytics query result caching.
 */
@Serializable
data class AnalyticsQueryCacheConfiguration(
    /** Maximum active parameter combinations retained for one query generation. */
    val maxEntriesPerQuery: Int = 100,
    /** Number of caller-idle days after which a combination is removed. */
    val idleRetentionDays: Int = 7,
    /**
     * Legacy configuration retained for deployment compatibility. Cached values
     * now remain available as explicitly stale last-known-good results until a
     * successful refresh replaces them.
     */
    val maxStaleIntervals: Int = 3,
    /** Maximum distinct queries scheduled by one background refresh sweep. */
    val refreshQueryBatchSize: Int = 500,
    /** Maximum rows removed by one pruning sweep. */
    val pruneBatchSize: Int = 500,
)

@Serializable
class IcebergDatabaseConfiguration(
    val uri: String,
    val username: String,
    val password: String,
)

@Serializable
class IcebergS3Configuration(
    @Suppress("unused")
    val bucket: String?,
    val endpoint: String?,
    val pathStyleAccess: Boolean?,
    val accessKeyId: String?,
    val secretAccessKey: String?,
    val region: String?
)

@Serializable
class IcebergConfiguration(
    val catalog: String,
    val fileIO: String,
    val localPath: String,
    val namespace: String,
    val table: String,
    val database: IcebergDatabaseConfiguration,
    val warehouseLocation: String?,
    val s3: IcebergS3Configuration? = null
)
