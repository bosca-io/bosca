package bosca.nats.admin.service

import bosca.nats.admin.model.JetStreamConsumerDetail
import bosca.nats.admin.model.JetStreamInfo
import bosca.nats.admin.model.JetStreamStreamDetail
import bosca.nats.admin.model.NatsConnections
import bosca.nats.admin.model.NatsKeyValueEntry
import bosca.nats.admin.model.NatsKeyValueStore
import bosca.nats.admin.model.NatsRoutes
import bosca.nats.admin.model.NatsServerInfo
import bosca.nats.admin.model.NatsStreamMessage
import bosca.nats.admin.model.NatsSubscriptionsInfo
import bosca.service.Service

/**
 * Provides introspection into the NATS server's operational state by querying
 * the NATS HTTP monitoring endpoints and the NATS connection directly.
 * Exposes server statistics, active connections, JetStream stream and consumer details,
 * subscription routing information, KeyValue store contents, and stream messages
 * to administrators.
 */
interface NatsMonitoringService : Service {

    /**
     * Retrieves general server information including version, uptime, connection counts,
     * message throughput, and resource usage from the NATS `/varz` monitoring endpoint.
     */
    suspend fun getServerInfo(): NatsServerInfo

    /**
     * Retrieves active route connections between servers in the NATS cluster from the
     * `/routez` monitoring endpoint, including remote server identity and message throughput
     * for each route.
     */
    suspend fun getClusterRoutes(): NatsRoutes

    /**
     * Retrieves details about currently active client connections from the NATS `/connz`
     * monitoring endpoint with optional pagination and sorting.
     *
     * @param limit maximum number of connections to return
     * @param offset zero-based offset for pagination
     * @param sortBy field to sort connections by (e.g. "cid", "bytes_to", "msgs_to")
     */
    suspend fun getConnections(limit: Int = 100, offset: Int = 0, sortBy: String? = null): NatsConnections

    /**
     * Retrieves JetStream overview statistics from the NATS `/jsz` monitoring endpoint,
     * including total streams, consumers, and resource usage.
     */
    suspend fun getJetStreamInfo(): JetStreamInfo

    /**
     * Retrieves details for all JetStream streams from the NATS `/jsz` monitoring endpoint
     * with stream-level detail enabled, including message counts and storage usage per stream.
     */
    suspend fun getJetStreamStreams(): List<JetStreamStreamDetail>

    /**
     * Retrieves all consumers for a specific JetStream stream, including delivery position,
     * pending acknowledgments, and redelivery counts.
     *
     * @param streamName the name of the stream to query consumers for
     */
    suspend fun getJetStreamConsumers(streamName: String): List<JetStreamConsumerDetail>

    /**
     * Retrieves subscription routing statistics from the NATS `/subsz` monitoring endpoint,
     * including cache performance and fanout characteristics.
     */
    suspend fun getSubscriptionsInfo(): NatsSubscriptionsInfo

    /**
     * Lists all KeyValue store buckets available on the NATS server,
     * returning metadata about each bucket including entry count and storage usage.
     */
    suspend fun getKeyValueStores(): List<NatsKeyValueStore>

    /**
     * Lists entries in a specific KeyValue store bucket with optional key and value filtering.
     *
     * @param bucket the name of the KV bucket to browse
     * @param keyFilter optional glob pattern to filter keys (e.g. "prefix.*")
     * @param valueSearch optional substring to search for within entry values
     */
    suspend fun getKeyValueEntries(
        bucket: String,
        keyFilter: String? = null,
        valueSearch: String? = null,
    ): List<NatsKeyValueEntry>

    /**
     * Retrieves messages from a JetStream stream with optional filtering by subject,
     * sequence range, and payload content search.
     *
     * @param streamName the name of the stream to read messages from
     * @param limit maximum number of messages to return
     * @param subjectFilter optional subject filter (supports NATS wildcards like "jobs.>")
     * @param fromSeq optional starting sequence number (inclusive)
     * @param toSeq optional ending sequence number (inclusive)
     * @param dataSearch optional substring to search for within message payloads
     */
    suspend fun getStreamMessages(
        streamName: String,
        limit: Int = 50,
        subjectFilter: String? = null,
        fromSeq: Long? = null,
        toSeq: Long? = null,
        dataSearch: String? = null,
    ): List<NatsStreamMessage>

    /**
     * Creates or updates an entry in a NATS KeyValue store bucket.
     * If the key already exists, its value is replaced and the revision is incremented.
     *
     * @param bucket the name of the KV bucket
     * @param key the key to create or update
     * @param value the string value to store
     * @return the entry as it was written, including the new revision number
     */
    suspend fun putKeyValueEntry(bucket: String, key: String, value: String): NatsKeyValueEntry

    /**
     * Deletes an entry from a NATS KeyValue store bucket by marking it with a
     * DELETE tombstone. The key will no longer appear in key listings.
     *
     * @param bucket the name of the KV bucket
     * @param key the key to delete
     */
    suspend fun deleteKeyValueEntry(bucket: String, key: String): Boolean

    /**
     * Permanently removes all tombstoned (deleted/purged) entries from a KeyValue store bucket,
     * reclaiming storage while preserving live entries.
     *
     * @param bucket the name of the KV bucket to compact
     */
    suspend fun purgeKeyValueDeletes(bucket: String): Boolean

    /**
     * Removes all messages from a JetStream stream, resetting it to an empty state.
     * The stream configuration and consumers are preserved.
     *
     * @param streamName the name of the stream to purge
     */
    suspend fun purgeStream(streamName: String): Boolean

    /**
     * Iterates through all messages in a JetStream stream and removes those that are
     * KeyValue tombstones (messages with a `KV-Operation` header set to `DEL` or `PURGE`).
     * Live entries are preserved while storage consumed by delete markers is reclaimed.
     *
     * @param streamName the name of the stream to purge tombstones from
     * @return the number of tombstone messages removed
     */
    suspend fun purgeStreamDeletes(streamName: String): Int

    /**
     * Removes a single message from a JetStream stream by its sequence number.
     * The sequence number slot is marked as deleted and the message data is freed.
     *
     * @param streamName the name of the stream containing the message
     * @param sequence the sequence number of the message to delete
     */
    suspend fun deleteStreamMessage(streamName: String, sequence: Long): Boolean
}
