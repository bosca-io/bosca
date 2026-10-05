package bosca.scripting.routes

import bosca.routes.Route
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.scripting.context.DefaultScriptContext
import bosca.scripting.model.ScriptType
import bosca.scripting.security.ScriptPermissionEvaluator
import bosca.scripting.service.ScriptExecutionService
import bosca.scripting.service.ScriptService
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.server.ServerCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.time.Duration.Companion.seconds

/**
 * Resolves, authorizes, and executes an API script by key, returning its JSON result.
 *
 * Only scripts with type [ScriptType.API] that are enabled can be invoked.
 * Public scripts are accessible to anyone; non-public scripts require the caller
 * to have [PermissionAction.EXECUTE] permission via a group grant.
 */
private suspend fun executeScript(
    call: ServerCall,
    authenticationContext: AuthenticationContext,
    input: JsonElement,
    scriptService: ScriptService,
    scriptExecutionService: ScriptExecutionService,
    permissionEvaluator: ScriptPermissionEvaluator,
    json: Json,
): JsonElement {
    val key = call.pathParameters["key"] ?: error("Missing script key")
    val script = scriptService.getByKey(key) ?: throw NoSuchElementException("Script not found: $key")
    if (script.type != ScriptType.API) throw NoSuchElementException("Script not found: $key")
    if (!script.enabled) throw NoSuchElementException("Script not found: $key")
    if (!script.public) {
        permissionEvaluator.verifyAllowed(authenticationContext, script, PermissionAction.EXECUTE)
    }
    val parentJob = currentCoroutineContext()[Job]
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob(parentJob))
    val context = DefaultScriptContext(
        authentication = authenticationContext,
        scope = scope,
        input = input,
        json = json,
    )
    try {
        return withTimeout(30.seconds) {
            scriptExecutionService.executeAsJson(script, context)
        }
    } finally {
        scope.cancel()
    }
}

/**
 * Executes an API script via POST. The request body is passed as JSON input
 * to the script's execution context.
 */
@RouteController("/api/v1/s/{key}", method = RouteMethod.POST)
open class ExecuteApiScriptPost(
    private val scriptService: ScriptService,
    private val scriptExecutionService: ScriptExecutionService,
    private val permissionEvaluator: ScriptPermissionEvaluator,
    private val json: Json,
) : Route<JsonElement>() {

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): JsonElement {
        val input = call.receive<JsonElement>()
        return executeScript(call, authenticationContext, input, scriptService, scriptExecutionService, permissionEvaluator, json)
    }
}

/**
 * Executes an API script via GET. Query parameters are converted to a JSON
 * object and passed as input to the script's execution context.
 */
@RouteController("/api/v1/s/{key}", method = RouteMethod.GET)
open class ExecuteApiScriptGet(
    private val scriptService: ScriptService,
    private val scriptExecutionService: ScriptExecutionService,
    private val permissionEvaluator: ScriptPermissionEvaluator,
    private val json: Json,
) : Route<JsonElement>() {

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): JsonElement {
        val input = call.request.queryParameters.toJsonElement()
        return executeScript(call, authenticationContext, input, scriptService, scriptExecutionService, permissionEvaluator, json)
    }
}
