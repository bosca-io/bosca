@file:OptIn(ExperimentalUuidApi::class)

package bosca.scripting.jobs

import bosca.queue.annotations.JobDefinition
import bosca.scripting.configuration.JobQueueNames
import bosca.scripting.engine.Engine
import bosca.scripting.model.ScriptValidationResult
import bosca.scripting.service.validateScriptSource
import bosca.serialization.OffsetDateTime
import bosca.sharedqueue.jobs.job
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.StringObjectPath
import kotlinx.serialization.json.Json
import bosca.scripting.service.ScriptExecutionService
import bosca.scripting.service.ScriptService
import bosca.sharedqueue.jobs.AbstractJobExecutor
import bosca.sharedqueue.jobs.DelayException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.slf4j.LoggerFactory
import java.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration
import kotlin.uuid.ExperimentalUuidApi

/**
 * Compiles a script on a worker that has the Kotlin scripting engine available,
 * validating that the script source is syntactically and semantically correct
 * without executing it.
 *
 * This enables GraalVM native image builds (which exclude the scripting engine)
 * to request compilation validation via the job queue. Unsaved-source requests
 * return diagnostics through private object storage; stored-script jobs retain
 * their ordinary job context and status callbacks.
 */
@JobDefinition(
    definition = RemoteScriptCompileJob::class,
    queue = JobQueueNames.scriptingJobQueue,
    name = "remote-script-compile"
)
class RemoteScriptCompileJobExecutor(
    private val scriptService: ScriptService,
    private val executionService: ScriptExecutionService,
    private val engine: Engine,
    private val storage: ObjectStorageService,
    private val json: Json,
) : AbstractJobExecutor<RemoteScriptCompileJob>(RemoteScriptCompileJob.serializer()) {

    private val log = LoggerFactory.getLogger(RemoteScriptCompileJobExecutor::class.java)

    override suspend fun execute() {
        if (executionService.isRemoteEnvironment) {
            throw DelayException(1.seconds)
        }

        val jobDef = getJobDefinition()
        if (jobDef.source != null) {
            val expiresAt = OffsetDateTime.parse(requireNotNull(jobDef.expiresAt))
            val path = StringObjectPath("script-validation/${job().getId()}")
            // Past expiry the requester has stopped polling, so nothing else can remove an unread result.
            if (!expiresAt.isAfter(OffsetDateTime.now())) {
                storage.delete(path)
                return
            }
            val result = validateScriptSource(engine, jobDef.source, true)
            if (!expiresAt.isAfter(OffsetDateTime.now())) return
            val bytes = json.encodeToString(ScriptValidationResult.serializer(), result).encodeToByteArray()
            storage.setInputStream(path, bytes.inputStream(), bytes.size.toLong())
            val remaining = Duration.between(OffsetDateTime.now(), expiresAt).toKotlinDuration()
            throw DelayException(remaining + RESULT_RETENTION_GRACE)
        }
        val script = scriptService.get(requireNotNull(jobDef.scriptId))
            ?: error("Script not found: ${jobDef.scriptId}")

        log.info("Compiling script {} (key={}, version={})", script.id, script.key, script.version)
        engine.compile<Any>(script.key, script.version, script.source)
        setContext(buildJsonObject { put("success", JsonPrimitive(true)) })
        log.info("Script {} compiled successfully", script.key)
    }

    private companion object {
        /** Exceeds the requester's polling grace, so a result is never removed while it may still be read. */
        val RESULT_RETENTION_GRACE = 1.minutes
    }
}
