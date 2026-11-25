@file:OptIn(ExperimentalUuidApi::class)

package bosca.scripting.jobs

import bosca.queue.annotations.JobDefinition
import bosca.scripting.configuration.JobQueueNames
import bosca.scripting.context.DefaultScriptContext
import bosca.scripting.context.ToolScriptContext
import bosca.scripting.context.TriggerContext
import bosca.scripting.service.ScriptExecutionService
import bosca.scripting.service.ScriptService
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.security.service.SecurityService
import bosca.security.service.ApiTokenService
import bosca.security.service.AuthenticationContext
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.security.model.ApiTokenCredentialAttributes
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
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
import kotlinx.serialization.json.decodeFromJsonElement
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
    private val tokens: ApiTokenService,
) : AbstractJobExecutor<RemoteScriptExecutionJob>(RemoteScriptExecutionJob.serializer()) {

    private val log = LoggerFactory.getLogger(RemoteScriptExecutionJobExecutor::class.java)

    override suspend fun execute() {
        if (scriptExecutionService.isRemoteEnvironment) {
            throw DelayException(1.seconds)
        }

        val jobDef = getJobDefinition()
        val script = scriptService.get(jobDef.scriptId)
            ?: error("Script not found: ${jobDef.scriptId}")
        check(script.version == jobDef.scriptVersion) { "Script changed after execution was requested" }
        check(script.enabled && script.deletedAt == null) { "Script is disabled or deleted" }

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

    private suspend fun resolveAuthentication(jobDef: RemoteScriptExecutionJob): AuthenticationContext {
        if (jobDef.principalId != null) {
            val principal = securityService.getPrincipalById(jobDef.principalId)
                ?: error("Principal not found: ${jobDef.principalId}")
            check(principal.deletedAt == null) { "Script caller was deleted" }
            val ceiling = checkNotNull(jobDef.groupIds) { "Script caller group restrictions are missing" }.toSet()
            val groups = securityService.getPrincipalGroups(jobDef.principalId).filter { it.id in ceiling }
            check(groups.map { it.id }.containsAll(ceiling)) { "Script caller group membership changed" }
            val credentialId = jobDef.credentialId
            if (credentialId == null) {
                check(jobDef.scopes == null) { "Scoped script authority requires a credential" }
                return ImpersonatedAuthenticationContext(principal, groups)
            }
            // A session-bound credential is retired with its session; the caller and captured limits above still apply.
            if (!jobDef.sessionBoundCredential) {
                val credential = checkNotNull(tokens.getTokenById(credentialId)) { "Script caller credential no longer exists" }
                check(credential.principal == principal.id) { "Script caller credential belongs to another principal" }
                val attributes = json.decodeFromJsonElement(ApiTokenCredentialAttributes.serializer(), credential.attributesJson)
                check(attributes.revokedAt == null && attributes.expiresAt?.let { OffsetDateTime.parse(it).isAfter(OffsetDateTime.now()) } != false) {
                    "Script caller credential was revoked or expired"
                }
                val allowedGroups = attributes.allowedGroups?.map(UUID::parse)?.toSet()
                check(allowedGroups == null || allowedGroups.containsAll(ceiling)) { "Script caller credential groups changed" }
                val currentScopes = attributes.scopes
                check(currentScopes == null || jobDef.scopes?.all { it in currentScopes } == true) { "Script caller credential scopes changed" }
            }
            val scoped = ScopedAuthenticatedPrincipal(principal, groups, jobDef.scopes, ceiling, credentialId, jobDef.sessionBoundCredential)
            return object : AuthenticationContext(null, null) { override fun principal() = scoped }
        }
        check(jobDef.credentialId == null && jobDef.scopes == null && jobDef.groupIds == null) { "Script authority requires a caller" }
        return AuthenticationContext(null, null)
    }
}
