package bosca.nats.admin.service

import bosca.di.ObjectProvider
import bosca.nats.NatsConnectionPool
import bosca.nats.admin.configuration.NatsMonitoringConfig
import bosca.nats.admin.model.JetStreamConsumerDetail
import bosca.nats.admin.model.JetStreamInfo
import bosca.nats.admin.model.JetStreamStreamDetail
import bosca.nats.admin.model.NatsConnections
import bosca.nats.admin.model.NatsHeader
import bosca.nats.admin.model.NatsKeyValueEntry
import bosca.nats.admin.model.NatsKeyValueStore
import bosca.nats.admin.model.NatsRoutes
import bosca.nats.admin.model.NatsServerInfo
import bosca.nats.admin.model.NatsStreamMessage
import bosca.nats.admin.model.NatsSubscriptionsInfo
import bosca.service.annotation.ServiceImplementation
import io.nats.client.api.KeyValueStatus
import io.nats.client.api.StreamInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

/**
 * Queries the NATS HTTP monitoring endpoints and the NATS connection directly to retrieve
 * server state, connection details, JetStream stream/consumer information, subscription
 * statistics, KeyValue store contents, and stream messages. Uses the JDK HttpClient for
 * monitoring HTTP calls, kotlinx.serialization for JSON deserialization, and NatsConnectionPool
 * for direct NATS access.
 */
@ServiceImplementation
class NatsMonitoringServiceImpl(
    private val config: NatsMonitoringConfig,
    private val connectionPool: ObjectProvider<NatsConnectionPool>,
) : NatsMonitoringService {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getServerInfo(): NatsServerInfo = withContext(Dispatchers.IO) {
        json.decodeFromString<NatsServerInfo>(fetch("/varz"))
    }

    override suspend fun getClusterRoutes(): NatsRoutes = withContext(Dispatchers.IO) {
        json.decodeFromString<NatsRoutes>(fetch("/routez"))
    }

    override suspend fun getConnections(limit: Int, offset: Int, sortBy: String?): NatsConnections =
        withContext(Dispatchers.IO) {
            val params = buildString {
                append("?limit=$limit&offset=$offset")
                if (sortBy != null) append("&sort=$sortBy")
            }
            json.decodeFromString<NatsConnections>(fetch("/connz$params"))
        }

    override suspend fun getJetStreamInfo(): JetStreamInfo = withContext(Dispatchers.IO) {
        json.decodeFromString<JetStreamInfo>(fetch("/jsz"))
    }

    override suspend fun getJetStreamStreams(): List<JetStreamStreamDetail> = withContext(Dispatchers.IO) {
        val info = json.decodeFromString<JetStreamInfo>(fetch("/jsz?streams=true"))
        info.accountDetails?.flatMap { it.streamDetail ?: emptyList() } ?: emptyList()
    }

    override suspend fun getJetStreamConsumers(streamName: String): List<JetStreamConsumerDetail> =
        withContext(Dispatchers.IO) {
            fetchConsumersForStream(streamName)
        }

    override suspend fun getSubscriptionsInfo(): NatsSubscriptionsInfo = withContext(Dispatchers.IO) {
        json.decodeFromString<NatsSubscriptionsInfo>(fetch("/subsz"))
    }

    override suspend fun getKeyValueStores(): List<NatsKeyValueStore> = withContext(Dispatchers.IO) {
        val connection = connectionPool.get().systemConnection()
        val kvm = connection.keyValueManagement()
        val bucketNames = kvm.bucketNames
        bucketNames.map { bucketName ->
            val status: KeyValueStatus = kvm.getStatus(bucketName)
            NatsKeyValueStore(
                bucket = bucketName,
                entryCount = status.entryCount,
                bytes = status.byteCount,
                ttl = status.ttl?.toMillis() ?: 0,
                maxBytes = status.maxBucketSize,
                maxValueSize = @Suppress("DEPRECATION") status.maxValueSize,
                history = status.maxHistoryPerKey,
            )
        }
    }

    override suspend fun getKeyValueEntries(
        bucket: String,
        keyFilter: String?,
        valueSearch: String?,
    ): List<NatsKeyValueEntry> = withContext(Dispatchers.IO) {
        val connection = connectionPool.get().systemConnection()
        val kv = connection.keyValue(bucket)
        val keys = try {
            if (keyFilter != null) kv.keys(keyFilter) else kv.keys()
        } catch (_: Exception) {
            // kv.keys() throws when bucket has no live keys (only tombstones/purged entries)
            emptyList()
        }
        val entries = mutableListOf<NatsKeyValueEntry>()
        for (key in keys) {
            if (entries.size >= 500) break
            val entry = kv.get(key) ?: continue
            val value = entry.valueAsString
            if (valueSearch != null && (value == null || !value.contains(valueSearch, ignoreCase = true))) {
                continue
            }
            entries.add(
                NatsKeyValueEntry(
                    key = key,
                    value = value,
                    revision = entry.revision,
                    created = entry.created.toString(),
                    operation = entry.operation.name,
                )
            )
        }
        entries
    }

    override suspend fun getStreamMessages(
        streamName: String,
        limit: Int,
        subjectFilter: String?,
        fromSeq: Long?,
        toSeq: Long?,
        dataSearch: String?,
    ): List<NatsStreamMessage> = withContext(Dispatchers.IO) {
        val connection = connectionPool.get().systemConnection()
        val jsm = connection.jetStreamManagement()
        val streamInfo: StreamInfo = jsm.getStreamInfo(streamName)
        val state = streamInfo.streamState
        val rangeEnd = toSeq?.coerceAtMost(state.lastSequence) ?: state.lastSequence
        val rangeStart = fromSeq?.coerceAtLeast(state.firstSequence)
            ?: maxOf(state.firstSequence, rangeEnd - limit * 2 + 1)

        val messages = mutableListOf<NatsStreamMessage>()
        for (seq in rangeStart..rangeEnd) {
            if (messages.size >= limit) break
            try {
                val msgInfo = jsm.getMessage(streamName, seq) ?: continue
                val subject = msgInfo.subject ?: ""
                if (subjectFilter != null && !matchesSubjectFilter(subject, subjectFilter)) continue
                val payload = msgInfo.data?.let { String(it, Charsets.UTF_8) }
                if (dataSearch != null && (payload == null || !payload.contains(dataSearch, ignoreCase = true))) continue
                messages.add(
                    NatsStreamMessage(
                        subject = subject,
                        sequence = seq,
                        timestamp = msgInfo.time?.toString() ?: "",
                        data = payload,
                        headers = msgInfo.headers?.entrySet()?.map { (key, values) ->
                            NatsHeader(key = key, values = values)
                        } ?: emptyList(),
                    )
                )
            } catch (_: Exception) {
                // Skip deleted or unavailable messages
            }
        }
        messages
    }

    override suspend fun putKeyValueEntry(
        bucket: String,
        key: String,
        value: String,
    ): NatsKeyValueEntry = withContext(Dispatchers.IO) {
        val connection = connectionPool.get().systemConnection()
        val kv = connection.keyValue(bucket)
        kv.put(key, value.toByteArray(Charsets.UTF_8))
        val entry = kv.get(key) ?: error("Failed to read back entry after put")
        NatsKeyValueEntry(
            key = entry.key,
            value = entry.valueAsString,
            revision = entry.revision,
            created = entry.created.toString(),
            operation = entry.operation.name,
        )
    }

    override suspend fun deleteKeyValueEntry(bucket: String, key: String): Boolean =
        withContext(Dispatchers.IO) {
            val connection = connectionPool.get().systemConnection()
            val kv = connection.keyValue(bucket)
            kv.delete(key)
            true
        }

    override suspend fun purgeKeyValueDeletes(bucket: String): Boolean = withContext(Dispatchers.IO) {
        val connection = connectionPool.get().systemConnection()
        val kv = connection.keyValue(bucket)
        kv.purgeDeletes()
        true
    }

    override suspend fun purgeStream(streamName: String): Boolean = withContext(Dispatchers.IO) {
        val connection = connectionPool.get().systemConnection()
        val jsm = connection.jetStreamManagement()
        jsm.purgeStream(streamName)
        true
    }

    override suspend fun purgeStreamDeletes(streamName: String): Int = withContext(Dispatchers.IO) {
        val connection = connectionPool.get().systemConnection()
        if (streamName.startsWith("KV_")) {
            val bucket = streamName.removePrefix("KV_")
            val kv = connection.keyValue(bucket)
            kv.purgeDeletes()
            -1
        } else {
            val jsm = connection.jetStreamManagement()
            val state = jsm.getStreamInfo(streamName).streamState
            var deleted = 0
            for (seq in state.firstSequence..state.lastSequence) {
                try {
                    val msg = jsm.getMessage(streamName, seq) ?: continue
                    val operation = msg.headers?.getFirst("KV-Operation")
                    if (operation == "DEL" || operation == "PURGE") {
                        jsm.deleteMessage(streamName, seq)
                        deleted++
                    }
                } catch (_: Exception) {
                    // Skip already-deleted or unavailable messages
                }
            }
            deleted
        }
    }

    override suspend fun deleteStreamMessage(streamName: String, sequence: Long): Boolean =
        withContext(Dispatchers.IO) {
            val connection = connectionPool.get().systemConnection()
            val jsm = connection.jetStreamManagement()
            jsm.deleteMessage(streamName, sequence)
        }

    /**
     * Matches a NATS subject against a filter pattern supporting NATS wildcards:
     * `*` matches a single token, `>` matches one or more tokens at the end.
     */
    private fun matchesSubjectFilter(subject: String, filter: String): Boolean {
        val subjectTokens = subject.split(".")
        val filterTokens = filter.split(".")
        for (i in filterTokens.indices) {
            val ft = filterTokens[i]
            if (ft == ">") return i < subjectTokens.size
            if (i >= subjectTokens.size) return false
            if (ft != "*" && ft != subjectTokens[i]) return false
        }
        return subjectTokens.size == filterTokens.size
    }

    private fun fetchConsumersForStream(streamName: String): List<JetStreamConsumerDetail> {
        val body = fetch("/jsz?streams=true&consumers=true")
        val element = json.parseToJsonElement(body)
        val root = element as? JsonObject ?: return emptyList()
        val accountDetails = root["account_details"] as? JsonArray ?: return emptyList()
        for (account in accountDetails) {
            val accountObj = account as? JsonObject ?: continue
            val streams = accountObj["stream_detail"] as? JsonArray ?: continue
            for (stream in streams) {
                val streamObj = stream as? JsonObject ?: continue
                val name = (streamObj["name"] as? JsonPrimitive)?.contentOrNull
                if (name == streamName) {
                    val consumers = streamObj["consumer_detail"] as? JsonArray ?: return emptyList()
                    return consumers.map { json.decodeFromJsonElement(JetStreamConsumerDetail.serializer(), it) }
                }
            }
        }
        return emptyList()
    }

    private fun fetch(path: String): String {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("${config.monitoringUrl}$path"))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: HttpTimeoutException) {
            // Connect or request timeout — the monitoring endpoint never answered.
            // A timeout (rather than a refusal) usually means the host is reachable
            // but nothing is listening on the monitoring port, e.g. the NATS HTTP
            // monitor (8222) is disabled or not exposed on the Service.
            throw IOException(unreachableMessage(path), e)
        } catch (e: ConnectException) {
            // Connection actively refused or otherwise rejected during connect.
            throw IOException(unreachableMessage(path), e)
        }
        if (response.statusCode() !in 200..299) {
            throw IOException("NATS monitoring request failed: ${response.statusCode()} for $path")
        }
        return response.body()
    }

    private fun unreachableMessage(path: String): String =
        "NATS monitoring endpoint unreachable at ${config.monitoringUrl}$path " +
            "(is the NATS HTTP monitor port 8222 enabled and exposed?)"
}
