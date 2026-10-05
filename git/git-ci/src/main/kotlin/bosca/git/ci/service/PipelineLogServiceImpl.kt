package bosca.git.ci.service

import bosca.git.service.LogLine
import bosca.git.service.LogStream
import bosca.git.service.PipelineLogService
import bosca.pubsub.PubSubService
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.storage.service.ObjectNotFoundException
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.StringObjectPath
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.time.Duration

@Serializable
data class PipelineLogEvent(
    val lineNumber: Int,
    val timestamp: String,
    val content: String,
    val stream: String,
)

@ServiceImplementation
class PipelineLogServiceImpl(
    private val objectStorage: ObjectStorageService,
    private val pubSubService: PubSubService,
    private val json: Json
) : PipelineLogService {

    // Per-step mutex: appendLog reads-modifies-writes a single S3 object, which
    // is a lost-update race under concurrent calls. Serializing per stepId
    // closes the race within a pod. Bounded by Caffeine so the map cannot grow
    // unboundedly across many runs; a step's mutex is evicted ~10 min after
    // its last use. Cross-pod safety relies on the agent claim model giving a
    // step a single writer.
    private val stepLocks = Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterAccess(Duration.ofMinutes(10))
        .build<UUID, Mutex>()

    override suspend fun appendLog(repositoryId: UUID, runId: UUID, jobId: UUID, stepId: UUID, lines: List<LogLine>) {
        val mutex = stepLocks.get(stepId) { Mutex() }
        mutex.withLock {
            appendLogLocked(repositoryId, runId, jobId, stepId, lines)
        }
    }

    private suspend fun appendLogLocked(repositoryId: UUID, runId: UUID, jobId: UUID, stepId: UUID, lines: List<LogLine>) {
        val path = logPath(repositoryId, runId, jobId, stepId)
        val ndjson = lines.joinToString("\n") { line ->
            buildJsonObject {
                put("ln", line.lineNumber)
                put("ts", line.timestamp)
                put("content", line.content)
                put("stream", line.stream.name.lowercase())
            }.toString()
        } + "\n"

        val bytes = ndjson.toByteArray()
        // Only a missing object means "no log yet". Any other read failure must fail the append:
        // treating it as empty would overwrite the stored history with just these lines.
        val existing = try {
            withContext(Dispatchers.IO) { objectStorage.getString(StringObjectPath(path)) }
        } catch (_: ObjectNotFoundException) {
            null
        }

        val combined = if (existing != null) {
            (existing + ndjson).toByteArray()
        } else {
            bytes
        }

        withContext(Dispatchers.IO) {
            objectStorage.setInputStream(
                StringObjectPath(path),
                ByteArrayInputStream(combined),
                combined.size.toLong()
            )
        }

        for (line in lines) {
            val event = PipelineLogEvent(
                lineNumber = line.lineNumber,
                timestamp = line.timestamp,
                content = line.content,
                stream = line.stream.name.lowercase(),
            )
            try {
                pubSubService.publish(logChannel(stepId), PipelineLogEvent.serializer(), event)
            } catch (e: Exception) {
                log.warn("Failed to publish log line to pub/sub for step {}: {}", stepId, e.message)
            }
        }
    }

    override suspend fun getLogs(repositoryId: UUID, runId: UUID, jobId: UUID, stepId: UUID, offset: Int, limit: Int, tail: Boolean, beforeLine: Int?): List<LogLine> {
        val path = logPath(repositoryId, runId, jobId, stepId)
        val content = try {
            withContext(Dispatchers.IO) { objectStorage.getString(StringObjectPath(path)) }
        } catch (_: ObjectNotFoundException) {
            return emptyList()
        }

        val all = content.lines().filter { it.isNotBlank() }
        if (beforeLine != null) {
            // Page backwards by stored line number, not file position:
            // line numbers can have gaps (the agent drops a batch after
            // upload-retry exhaustion), so positional windows computed
            // from `lineNumber - 1` would repeat already-seen lines.
            return all.mapNotNull { line -> parseLogLine(line) }
                .filter { it.lineNumber < beforeLine }
                .takeLast(limit)
        }
        val window = if (tail) all.takeLast(limit) else all.drop(offset).take(limit)
        return window.mapNotNull { line -> parseLogLine(line) }
    }

    override suspend fun deleteRunLogs(repositoryId: UUID, runId: UUID) {
        log.info("Deleting logs for run {} in repository {}", runId, repositoryId)
    }

    override suspend fun deleteExpiredLogs(repositoryId: UUID, retentionDays: Int) {
        log.info("Cleaning up logs older than {} days for repository {}", retentionDays, repositoryId)
    }

    private fun parseLogLine(raw: String): LogLine? {
        return try {
            val obj = json.decodeFromString<JsonObject>(raw)
            LogLine(
                lineNumber = obj["ln"]?.jsonPrimitive?.int ?: 0,
                timestamp = obj["ts"]?.jsonPrimitive?.content ?: "",
                content = obj["content"]?.jsonPrimitive?.content ?: "",
                stream = try {
                    LogStream.valueOf(obj["stream"]?.jsonPrimitive?.content?.uppercase() ?: "STDOUT")
                } catch (_: Exception) {
                    LogStream.STDOUT
                }
            )
        } catch (_: Exception) {
            null
        }
    }

    override fun subscribe(stepId: UUID): Flow<LogLine> {
        return pubSubService.subscribe(logChannel(stepId), PipelineLogEvent.serializer())
            .map { message ->
                LogLine(
                    lineNumber = message.message.lineNumber,
                    timestamp = message.message.timestamp,
                    content = message.message.content,
                    stream = try {
                        LogStream.valueOf(message.message.stream.uppercase())
                    } catch (_: Exception) {
                        LogStream.STDOUT
                    }
                )
            }
    }

    override suspend fun deleteStepLog(repositoryId: UUID, runId: UUID, jobId: UUID, stepId: UUID) {
        withContext(Dispatchers.IO) {
            objectStorage.delete(StringObjectPath(logPath(repositoryId, runId, jobId, stepId)))
        }
        stepLocks.invalidate(stepId)
    }

    private fun logPath(repositoryId: UUID, runId: UUID, jobId: UUID, stepId: UUID): String {
        return "git/$repositoryId/ci/logs/$runId/$jobId/$stepId.log"
    }

    private fun logChannel(stepId: UUID): String {
        return "bosca.git.ci.logs.$stepId"
    }

    companion object {
        private val log = LoggerFactory.getLogger(PipelineLogServiceImpl::class.java)
    }
}
