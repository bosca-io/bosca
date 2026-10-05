package bosca.nats.admin.model

/**
 * Represents a NATS KeyValue store bucket with its name
 * and the number of entries it contains.
 */
data class NatsKeyValueStore(
    val bucket: String,
    val entryCount: Long,
    val bytes: Long,
    val ttl: Long,
    val maxBytes: Long,
    val maxValueSize: Long,
    val history: Long,
)

/**
 * Represents a single entry in a NATS KeyValue store bucket,
 * with the key, string value, revision, and creation timestamp.
 */
data class NatsKeyValueEntry(
    val key: String,
    val value: String?,
    val revision: Long,
    val created: String,
    val operation: String,
)

/**
 * Represents a message in a NATS JetStream stream, including
 * the subject, sequence number, timestamp, and payload data.
 */
data class NatsStreamMessage(
    val subject: String,
    val sequence: Long,
    val timestamp: String,
    val data: String?,
    val headers: List<NatsHeader>,
)

/**
 * A key-value pair representing a single NATS message header.
 */
data class NatsHeader(
    val key: String,
    val values: List<String>,
)
