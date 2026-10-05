@file:OptIn(ExperimentalUuidApi::class)

package bosca.scripting.service

import bosca.pubsub.PubSubService
import bosca.scripting.context.BoscaScriptContext
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.StringObjectPath
import bosca.scripting.context.ScriptContext
import bosca.scripting.context.TriggerContext
import bosca.scripting.jobs.RemoteScriptContextType
import bosca.scripting.jobs.RemoteScriptExecutionJob
import bosca.scripting.jobs.enqueue
import bosca.sharedqueue.jobs.JobCallback
import bosca.sharedqueue.jobs.JobStatus
import bosca.sharedqueue.jobs.listeners.JOB_STATUS_CHANNEL
import bosca.sharedqueue.jobs.listeners.JobStatusNotification
import bosca.sharedqueue.jobs.listeners.NotifyJobStatusListener
import bosca.scripting.model.Script
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.DeserializationStrategy
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.ExperimentalUuidApi

/**
 * Remote-delegating implementation of [ScriptExecutionService] used when the Kotlin
 * scripting engine is excluded from the build (e.g. GraalVM native images).
 *
 * Instead of compiling and executing scripts locally, this implementation enqueues
 * a [RemoteScriptExecutionJob] to the scripting job queue and awaits the result
 * via PubSub notification from a worker that has the scripting engine available.
 */
class RemoteScriptExecutionServiceImpl(
    private val pubsub: PubSubService,
    private val objectStorageService: ObjectStorageService,
    private val json: Json
) : ScriptExecutionService {

    override val isRemoteEnvironment = true

    override suspend fun <T> execute(script: Script, context: ScriptContext, deserializer: DeserializationStrategy<T>?): T? {
        val result = executeAsJson(script, context)
        if (result is JsonNull) return null
        if (deserializer == null) return result as T
        return json.decodeFromJsonElement<T>(deserializer, result)
    }

    override suspend fun executeAsJson(script: Script, context: ScriptContext): JsonElement {
        val job = RemoteScriptExecutionJob(
            scriptId = script.id,
            contextType = resolveContextType(context),
            input = (context as BoscaScriptContext).input,
            principalId = context.authentication.principal()?.id,
            eventName = (context as? TriggerContext)?.eventName,
            eventPayload = (context as? TriggerContext)?.eventPayload
        ).enqueue {
            addCallback(JobCallback(listener = NotifyJobStatusListener::class))
        }

        val jobId = job.getId()
        return withTimeout(5.minutes) {
            val notification = pubsub.subscribe(JOB_STATUS_CHANNEL, JobStatusNotification.serializer())
                .firstOrNull { it.message.jobId == jobId }
                ?: error("Remote script execution notification not received for job $jobId")

            val status = notification.message
            // The status stream now publishes only terminal outcomes (COMPLETE / FAILED_AND_COMPLETE).
            if (status.status == JobStatus.FAILED_AND_COMPLETE) {
                error("Remote script execution failed: ${status.errorMessage}")
            }
            val resultPath = status.context.jsonObject["resultPath"]?.jsonPrimitive?.content
                ?: error("No result path in remote execution response for job $jobId")
            val storagePath = StringObjectPath(resultPath)
            val resultJson = objectStorageService.getString(storagePath)
            objectStorageService.delete(storagePath)
            Json.parseToJsonElement(resultJson)
        }
    }

    override fun invalidateCache(key: String, version: Int) {
        // No local cache in remote-delegation mode
    }

    override fun invalidateAllCaches() {
        // No local cache in remote-delegation mode
    }

    private fun resolveContextType(context: ScriptContext): RemoteScriptContextType {
        return when (context) {
            is TriggerContext -> RemoteScriptContextType.TRIGGER
            is bosca.scripting.context.ToolScriptContext -> RemoteScriptContextType.TOOL
            else -> RemoteScriptContextType.DEFAULT
        }
    }
}
