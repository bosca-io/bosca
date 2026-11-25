@file:OptIn(ExperimentalUuidApi::class)

package bosca.scripting.service

import bosca.pubsub.PubSubService
import bosca.scripting.engine.Engine
import bosca.scripting.jobs.RemoteScriptCompileJob
import bosca.scripting.model.ScriptValidationResult
import bosca.serialization.OffsetDateTime
import bosca.storage.service.ObjectNotFoundException
import kotlinx.coroutines.delay
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
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.security.service.SecurityService
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.DeserializationStrategy
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
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
    private val json: Json,
    private val security: SecurityService,
    private val engine: Engine,
) : ScriptExecutionService {

    override val isRemoteEnvironment = true

    override suspend fun validate(source: String, compile: Boolean): ScriptValidationResult {
        val checked = validateScriptSource(engine, source, false)
        if (!checked.valid || !compile) return checked
        val job = RemoteScriptCompileJob(source = source, expiresAt = OffsetDateTime.now().plusSeconds(VALIDATION_EXPIRY.inWholeSeconds).toString()).enqueue()
        val path = StringObjectPath("script-validation/${job.getId()}")
        // The worker never writes after expiresAt, so polling slightly past it observes every result it can write.
        var unreadable: SerializationException? = null
        val result = withTimeoutOrNull(VALIDATION_EXPIRY + VALIDATION_WRITE_GRACE) {
            var result: ScriptValidationResult? = null
            while (result == null) {
                result = try {
                    json.decodeFromString(ScriptValidationResult.serializer(), objectStorageService.getString(path))
                } catch (_: ObjectNotFoundException) {
                    null
                } catch (e: SerializationException) {
                    // Object storage writes are not atomic; a result can be observed before it is fully written.
                    unreadable = e
                    null
                }
                if (result == null) delay(250)
            }
            result
        }
        if (result == null) {
            throw IllegalStateException("Remote script validation did not produce a readable result for job ${job.getId()}", unreadable)
        }
        objectStorageService.delete(path)
        return result
    }

    override suspend fun <T> execute(script: Script, context: ScriptContext, deserializer: DeserializationStrategy<T>?): T? {
        val result = executeAsJson(script, context)
        if (result is JsonNull) return null
        if (deserializer == null) return result as T
        return json.decodeFromJsonElement<T>(deserializer, result)
    }

    override suspend fun executeAsJson(script: Script, context: ScriptContext): JsonElement {
        val principal = context.authentication.principal()
        val scoped = principal as? ScopedAuthenticatedPrincipal
        val job = RemoteScriptExecutionJob(
            scriptId = script.id,
            scriptVersion = script.version,
            contextType = resolveContextType(context),
            groupIds = principal?.let { security.getPrincipalGroups(it.id).filter { group -> it.hasGroup(group.id) }.map { group -> group.id } },
            scopes = scoped?.scopes?.sorted(),
            credentialId = scoped?.credentialId,
            input = (context as BoscaScriptContext).input,
            principalId = principal?.id,
            eventName = (context as? TriggerContext)?.eventName,
            eventPayload = (context as? TriggerContext)?.eventPayload,
            sessionBoundCredential = scoped?.sessionBoundCredential == true,
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

    private companion object {
        val VALIDATION_EXPIRY = 5.minutes
        val VALIDATION_WRITE_GRACE = 10.seconds
    }
}
