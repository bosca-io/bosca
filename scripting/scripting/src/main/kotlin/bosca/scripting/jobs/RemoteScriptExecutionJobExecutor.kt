@file:OptIn(ExperimentalUuidApi::class)

package bosca.scripting.jobs

import bosca.queue.annotations.JobDefinition
import bosca.scripting.configuration.JobQueueNames
import bosca.scripting.context.DefaultScriptContext
import bosca.scripting.context.ToolScriptContext
import bosca.scripting.context.TriggerContext
import bosca.scripting.engine.ScriptingSecurityConfiguration
import bosca.scripting.service.ScriptExecutionService
import bosca.scripting.service.ScriptService
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.security.service.SecurityService
import bosca.security.service.impersonate
import bosca.sharedqueue.jobs.AbstractJobExecutor
import bosca.sharedqueue.jobs.DelayException
import bosca.sharedqueue.jobs.job
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.StringObjectPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.Job as CoroutineJob

/**
 * Executes a remotely enqueued script on a worker that has the Kotlin scripting engine
 * available. This enables GraalVM native image builds (which exclude the scripting engine)
 * to delegate script execution to JVM-based workers via the job queue.
 *
 * The executor fetches the script from the database, reconstructs the appropriate
 * [bosca.scripting.context.ScriptContext], and delegates to [ScriptExecutionService]
 * for compilation and execution.
 */
@JobDefinition(
    definition = RemoteScriptExecutionJob::class,
    queue = JobQueueNames.scriptingJobQueue,
    name = "remote-script-execution"
)
class RemoteScriptExecutionJobExecutor(
    private val scriptService: ScriptService,
    private val scriptExecutionService: ScriptExecutionService,
    private val securityService: SecurityService,
    private val objectStorageService: ObjectStorageService,
    private val json: Json,
    private val config: ScriptingSecurityConfiguration
) : AbstractJobExecutor<RemoteScriptExecutionJob>(RemoteScriptExecutionJob.serializer()) {

    private val log = LoggerFactory.getLogger(RemoteScriptExecutionJobExecutor::class.java)

    override suspend fun execute() {
        if (scriptExecutionService.isRemoteEnvironment) {
            throw DelayException(1.seconds)
        }

        val jobDef = getJobDefinition()
        val script = scriptService.get(jobDef.scriptId)
            ?: error("Script not found: ${jobDef.scriptId}")

        if (!script.enabled) {
            log.info("Script {} is disabled, skipping remote execution", script.key)
            return
        }

        val authentication = resolveAuthentication(jobDef)
        val supervisorJob = SupervisorJob(currentCoroutineContext()[CoroutineJob])
        val scope = CoroutineScope(Dispatchers.Default + supervisorJob)

        try {
            val context = when (jobDef.contextType) {
                RemoteScriptContextType.DEFAULT -> DefaultScriptContext(
                    authentication = authentication,
                    scope = scope,
                    input = jobDef.input,
                    json = json
                )
                RemoteScriptContextType.TRIGGER -> TriggerContext(
                    authentication = authentication,
                    scope = scope,
                    json = json,
                    eventName = jobDef.eventName ?: error("eventName required for TRIGGER context"),
                    eventPayload = jobDef.eventPayload ?: error("eventPayload required for TRIGGER context")
                )
                RemoteScriptContextType.TOOL -> ToolScriptContext(
                    authentication = authentication,
                    scope = scope,
                    input = jobDef.input as? JsonObject ?: error("Tool context requires JsonObject input"),
                    json = json
                )
            }

            log.info("Executing remote script {} (context={})", script.key, jobDef.contextType)
            val result = scriptExecutionService.executeAsJson(script, context)
            val resultPath = "script-results/${job().getId()}"
            val resultBytes = result.toString().encodeToByteArray()
            objectStorageService.setInputStream(
                StringObjectPath(resultPath),
                resultBytes.inputStream(),
                resultBytes.size.toLong()
            )
            setContext(buildJsonObject { put("resultPath", resultPath) })
            log.info("Remote script {} completed", script.key)
        } finally {
            supervisorJob.cancel()
        }
    }

    private suspend fun resolveAuthentication(jobDef: RemoteScriptExecutionJob): ImpersonatedAuthenticationContext {
        if (jobDef.principalId != null) {
            val principal = securityService.getPrincipalById(jobDef.principalId)
                ?: error("Principal not found: ${jobDef.principalId}")
            val groups = securityService.getPrincipalGroups(jobDef.principalId)
            return ImpersonatedAuthenticationContext(principal, groups)
        }
        return securityService.impersonate(config.triggerServiceAccount)
    }
}
