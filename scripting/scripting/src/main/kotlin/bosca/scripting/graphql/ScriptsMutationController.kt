@file:OptIn(ExperimentalUuidApi::class)

package bosca.scripting.graphql

import bosca.di.ObjectProvider
import bosca.git.model.SourceRefInput
import bosca.git.service.SourceRefService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.scripting.context.BoscaScriptContext
import bosca.scripting.context.DefaultScriptContext
import bosca.scripting.context.ToolScriptContext
import bosca.scripting.model.Script
import bosca.scripting.model.ScriptInput
import bosca.scripting.service.ScriptExecutionService
import bosca.scripting.service.ScriptService
import bosca.security.service.AuthenticationContext
import bosca.security.model.PermissionInput
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.minutes
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.ExperimentalUuidApi

object ScriptsMutation

@TypeController
class ScriptsMutationController(
    private val scriptService: ScriptService,
    private val scriptExecutionService: ScriptExecutionService,
    private val sourceRefService: ObjectProvider<SourceRefService>,
    private val groups: GroupEvaluator,
    private val json: Json,
) : GraphQLController<ScriptsMutation> {

    @Field
    suspend fun addScript(authentication: AuthenticationContext, script: ScriptInput, sourceRef: SourceRefInput?): Script {
        groups.verifyHasAdminGroup(authentication)
        val created = scriptService.add(script)
        if (sourceRef != null && sourceRefService.exists) {
            sourceRefService.get().setScriptSourceRef(created.id, sourceRef)
        }
        return created
    }

    @Field
    suspend fun editScript(authentication: AuthenticationContext, id: UUID, script: ScriptInput, sourceRef: SourceRefInput?): Script {
        groups.verifyHasAdminGroup(authentication)
        val updated = scriptService.edit(id, script)
        if (sourceRef != null && sourceRefService.exists) {
            sourceRefService.get().setScriptSourceRef(id, sourceRef)
        }
        return updated
    }

    @Field
    suspend fun deleteScript(authentication: AuthenticationContext, id: UUID): Boolean {
        groups.verifyHasAdminGroup(authentication)
        scriptService.delete(id)
        return true
    }

    @Field
    suspend fun enableScript(authentication: AuthenticationContext, id: UUID): Boolean {
        groups.verifyHasAdminGroup(authentication)
        scriptService.enable(id)
        return true
    }

    @Field
    suspend fun disableScript(authentication: AuthenticationContext, id: UUID): Boolean {
        groups.verifyHasAdminGroup(authentication)
        scriptService.disable(id)
        return true
    }

    @Field
    suspend fun executeScript(authentication: AuthenticationContext, id: UUID, input: JsonElement?): JsonElement {
        groups.verifyHasAdminGroup(authentication)
        val script = scriptService.get(id) ?: error("Script not found: $id")
        val parentJob = currentCoroutineContext()[Job]
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob(parentJob))
        val context = if (input is JsonObject) {
            ToolScriptContext(authentication = authentication, scope = scope, input = input, json = json)
        } else {
            DefaultScriptContext(scope = scope, authentication = authentication, json = json)
        }
        try {
            return withTimeout(5.minutes) {
                scriptExecutionService.executeAsJson(script, context)
            }
        } finally {
            scope.cancel()
        }
    }

    @Field
    suspend fun addPermission(authentication: AuthenticationContext, permission: PermissionInput): Boolean {
        groups.verifyHasAdminGroup(authentication)
        scriptService.addPermission(permission.entityId, permission.groupId, permission.action)
        return true
    }

    @Field
    suspend fun deletePermission(authentication: AuthenticationContext, permission: PermissionInput): Boolean {
        groups.verifyHasAdminGroup(authentication)
        scriptService.deletePermission(permission.entityId, permission.groupId, permission.action)
        return true
    }
}
