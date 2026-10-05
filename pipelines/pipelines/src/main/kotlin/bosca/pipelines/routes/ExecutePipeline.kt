package bosca.pipelines.routes

import bosca.pipelines.model.Pipeline
import bosca.pipelines.model.PipelineRunStatus
import bosca.pipelines.run.resolvePipelineRunInput
import bosca.pipelines.security.PipelinePermissionEvaluator
import bosca.pipelines.service.PipelineRunService
import bosca.pipelines.service.PipelineService
import bosca.routes.Route
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.server.ServerCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Resolves and authorizes an endpoint-exposed pipeline by key — mirrors the API-script endpoint.
 * Only pipelines with [Pipeline.api] set are invokable; non-exposed and unknown keys are
 * indistinguishable (not found), so the flag is not probeable. Public pipelines are open to
 * anyone; non-public ones require [PermissionAction.EXECUTE] via a group grant (admins and
 * service accounts always pass).
 */
private suspend fun resolvePipeline(
    call: ServerCall,
    authenticationContext: AuthenticationContext,
    pipelineService: PipelineService,
    permissionEvaluator: PipelinePermissionEvaluator,
): Pipeline {
    val key = call.pathParameters["key"] ?: error("Missing pipeline key")
    val pipeline = pipelineService.getByKey(key) ?: throw NoSuchElementException("Pipeline not found: $key")
    if (!pipeline.api) throw NoSuchElementException("Pipeline not found: $key")
    if (!pipeline.public) {
        permissionEvaluator.verifyAllowed(authenticationContext, pipeline, PermissionAction.EXECUTE)
    }
    return pipeline
}

/**
 * Runs the pipeline as a durable on-demand run under the caller's principal; the Output-node value is
 * the response body ([JsonNull] for side-effect-only pipelines). The run lands in run history as an
 * `api` run; a failed run surfaces as an error response. A pipeline that *suspends* (a timer/
 * awaited job) past the brief on-demand block returns a `{ runId, status: "SUSPENDED" }` handle
 * instead — the caller tracks it rather than holding the HTTP connection.
 */
private suspend fun executePipeline(
    pipeline: Pipeline,
    authenticationContext: AuthenticationContext,
    input: JsonElement,
    runService: PipelineRunService,
    json: Json,
): JsonElement {
    val resolved = resolvePipelineRunInput(pipeline, eventType = null, payload = input, json = json)
    val run = runService.start(
        pipeline = pipeline,
        input = resolved,
        eventName = "api",
        inputCreated = java.time.OffsetDateTime.now(),
        authentication = authenticationContext,
    ) ?: error("Pipeline '${pipeline.key}' shed: at its concurrency or rate cap")
    return when (run.status) {
        PipelineRunStatus.OK -> run.output ?: JsonNull
        PipelineRunStatus.SUSPENDED -> buildJsonObject {
            put("runId", run.id.toString())
            put("status", "SUSPENDED")
        }
        else -> error("Pipeline '${pipeline.key}' failed: ${run.error}")
    }
}

/**
 * Runs an endpoint-exposed pipeline via POST. The request body is the pipeline's input — validated
 * against the input schema for JSON-input pipelines, or decoded as the accepted event type.
 */
@RouteController("/api/v1/p/{key}", method = RouteMethod.POST)
open class ExecutePipelinePost(
    private val pipelineService: PipelineService,
    private val permissionEvaluator: PipelinePermissionEvaluator,
    private val runService: PipelineRunService,
    private val json: Json,
) : Route<JsonElement>() {

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): JsonElement {
        val pipeline = resolvePipeline(call, authenticationContext, pipelineService, permissionEvaluator)
        val input = call.receive<JsonElement>()
        return executePipeline(pipeline, authenticationContext, input, runService, json)
    }
}

/**
 * Runs an endpoint-exposed pipeline via GET. Query parameters are converted to a JSON object and
 * passed as the pipeline's input.
 */
@RouteController("/api/v1/p/{key}", method = RouteMethod.GET)
open class ExecutePipelineGet(
    private val pipelineService: PipelineService,
    private val permissionEvaluator: PipelinePermissionEvaluator,
    private val runService: PipelineRunService,
    private val json: Json,
) : Route<JsonElement>() {

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): JsonElement {
        val pipeline = resolvePipeline(call, authenticationContext, pipelineService, permissionEvaluator)
        val input = call.request.queryParameters.toJsonElement()
        return executePipeline(pipeline, authenticationContext, input, runService, json)
    }
}
