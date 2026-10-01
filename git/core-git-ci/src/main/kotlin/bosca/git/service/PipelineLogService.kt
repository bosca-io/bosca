package bosca.git.service

import bosca.serialization.UUID
import bosca.service.Service
import kotlinx.coroutines.flow.Flow

/**
 * Manages pipeline step logs stored in ObjectStorageService (S3).
 * Logs are stored as NDJSON files at:
 *   `git/{repositoryId}/ci/logs/{runId}/{jobId}/{stepId}.log`
 *
 * Real-time log streaming happens via pub/sub on
 * `bosca.git.ci.logs.{stepId}`; this service handles both persistence
 * and real-time streaming of log lines.
 */
interface PipelineLogService : Service {

    /**
     * Appends log lines to a step's log file in object storage
     * and publishes them to the real-time stream.
     */
    suspend fun appendLog(repositoryId: UUID, runId: UUID, jobId: UUID, stepId: UUID, lines: List<LogLine>)

    /**
     * Retrieves log lines for a completed step from object storage.
     * When [tail] is true the last [limit] lines are returned and
     * [offset] is ignored — failure output (e.g. a gradle build error)
     * lives at the end of the log, so viewers should start there and
     * page backwards using the returned line numbers.
     *
     * When [beforeLine] is set, the last [limit] lines whose
     * `lineNumber` is strictly below it are returned (ascending) and
     * [offset]/[tail] are ignored. Viewers page backwards with this
     * rather than positional offsets because stored line numbers can
     * have gaps (the agent drops a batch after upload-retry
     * exhaustion), which makes positional windows repeat lines.
     */
    suspend fun getLogs(repositoryId: UUID, runId: UUID, jobId: UUID, stepId: UUID, offset: Int = 0, limit: Int = 1000, tail: Boolean = false, beforeLine: Int? = null): List<LogLine>

    /**
     * Subscribes to real-time log lines for an active pipeline step.
     * The flow emits lines as they arrive via pub/sub.
     */
    fun subscribe(stepId: UUID): Flow<LogLine>

    /**
     * Deletes the persisted log for one pipeline step.
     *
     * Object storage exposes exact-key deletion rather than prefix deletion, so callers deleting
     * a run enumerate its persisted jobs and steps and invoke this method before removing them.
     */
    suspend fun deleteStepLog(repositoryId: UUID, runId: UUID, jobId: UUID, stepId: UUID)

    /**
     * Deletes all logs for a pipeline run.
     */
    suspend fun deleteRunLogs(repositoryId: UUID, runId: UUID)

    /**
     * Deletes logs older than the retention period for a repository.
     */
    suspend fun deleteExpiredLogs(repositoryId: UUID, retentionDays: Int)
}

/**
 * A single log line from a pipeline step execution.
 */
data class LogLine(
    val lineNumber: Int,
    val timestamp: String,
    val content: String,
    val stream: LogStream = LogStream.STDOUT
)

enum class LogStream { STDOUT, STDERR }
