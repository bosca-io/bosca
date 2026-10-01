@file:OptIn(ExperimentalUuidApi::class)

package bosca.scripting.jobs

import bosca.queue.annotations.JobDefinition
import bosca.scripting.configuration.JobQueueNames
import bosca.scripting.engine.Engine
import bosca.scripting.service.ScriptExecutionService
import bosca.scripting.service.ScriptService
import bosca.sharedqueue.jobs.AbstractJobExecutor
import bosca.sharedqueue.jobs.DelayException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi

/**
 * Compiles a script on a worker that has the Kotlin scripting engine available,
 * validating that the script source is syntactically and semantically correct
 * without executing it.
 *
 * This enables GraalVM native image builds (which exclude the scripting engine)
 * to request compilation validation via the job queue. Success or failure is
 * communicated back through the job status notification.
 */
@JobDefinition(
    definition = RemoteScriptCompileJob::class,
    queue = JobQueueNames.scriptingJobQueue,
    name = "remote-script-compile"
)
class RemoteScriptCompileJobExecutor(
    private val scriptService: ScriptService,
    private val executionService: ScriptExecutionService,
    private val engine: Engine
) : AbstractJobExecutor<RemoteScriptCompileJob>(RemoteScriptCompileJob.serializer()) {

    private val log = LoggerFactory.getLogger(RemoteScriptCompileJobExecutor::class.java)

    override suspend fun execute() {
        if (executionService.isRemoteEnvironment) {
            throw DelayException(1.seconds)
        }

        val jobDef = getJobDefinition()
        val script = scriptService.get(jobDef.scriptId)
            ?: error("Script not found: ${jobDef.scriptId}")

        log.info("Compiling script {} (key={}, version={})", script.id, script.key, script.version)
        engine.compile<Any>(script.key, script.version, script.source)
        setContext(buildJsonObject { put("success", JsonPrimitive(true)) })
        log.info("Script {} compiled successfully", script.key)
    }
}
