@file:OptIn(ExperimentalUuidApi::class)

package bosca.scripting.graphql

import bosca.di.ObjectProvider
import bosca.ai.agents.model.AgentToolInput
import bosca.ai.agents.service.AgentToolService
import bosca.ai.agents.service.AgentService
import bosca.scripting.model.ScriptType
import bosca.db.transaction
import bosca.git.model.SourceRefInput
import bosca.git.service.SourceRefService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.scripting.security.ScriptPermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.ApiTokenScopes
import bosca.pipelines.node.JsonSchemaValidator
import kotlinx.serialization.json.JsonNull
import bosca.scripting.context.DefaultScriptContext
import bosca.scripting.context.ToolScriptContext
import bosca.scripting.model.Script
import bosca.scripting.model.ScriptInput
import bosca.scripting.model.ScriptValidationResult
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
    private val permissions: ScriptPermissionEvaluator,
    private val tools: ObjectProvider<AgentToolService>,
    private val agents: ObjectProvider<AgentService>,
) : GraphQLController<ScriptsMutation> {

    @Field
    suspend fun addScript(authentication: AuthenticationContext, script: ScriptInput, sourceRef: SourceRefInput?): Script {
        verifyCreate(authentication)
        return transaction {
            val created = scriptService.add(script)
            if (sourceRef != null && sourceRefService.exists) sourceRefService.get().setScriptSourceRef(created.id, sourceRef)
            if (created.type == ScriptType.TOOL) registerTool(created)
            created
        }
    }

    @Field
    suspend fun editScript(authentication: AuthenticationContext, id: UUID, script: ScriptInput, sourceRef: SourceRefInput?, expectedVersion: Int?): Script {
        val existing = find(id)
        permissions.verifyAllowed(authentication, existing, PermissionAction.EDIT)
        val register = script.type == ScriptType.TOOL && existing.type != ScriptType.TOOL
        if (register) verifyCreate(authentication)
        return transaction {
            val updated = scriptService.edit(id, script, expectedVersion ?: existing.version)
            if (sourceRef != null && sourceRefService.exists) sourceRefService.get().setScriptSourceRef(id, sourceRef)
            if (register) registerTool(updated)
            else if (existing.type == ScriptType.TOOL) {
                if (updated.type == ScriptType.TOOL) syncTool(existing, updated) else unregisterTool(existing)
            }
            updated
        }
    }

    @Field
    suspend fun deleteScript(authentication: AuthenticationContext, id: UUID): Boolean {
        val existing = find(id)
        permissions.verifyAllowed(authentication, existing, PermissionAction.DELETE)
        transaction {
            if (existing.type == ScriptType.TOOL) unregisterTool(existing)
            scriptService.delete(id)
        }
        return true
    }

    @Field
    suspend fun enableScript(authentication: AuthenticationContext, id: UUID): Boolean {
        permissions.verifyAllowed(authentication, find(id), PermissionAction.EDIT)
        scriptService.enable(id)
        return true
    }

    @Field
    suspend fun disableScript(authentication: AuthenticationContext, id: UUID): Boolean {
        permissions.verifyAllowed(authentication, find(id), PermissionAction.EDIT)
        scriptService.disable(id)
        return true
    }

    @Field
    suspend fun executeScript(authentication: AuthenticationContext, id: UUID, input: JsonElement?): JsonElement {
        val script = find(id)
        permissions.verifyAllowed(authentication, script, PermissionAction.EXECUTE)
        check(script.enabled && script.deletedAt == null) { "Script is disabled or deleted" }
        val value = input ?: JsonNull
        script.inputSchema?.let { schema ->
            val errors = JsonSchemaValidator.validate(value, schema)
            require(errors.isEmpty()) { "Invalid script input: ${errors.joinToString("; ")}" }
        }
        val parentJob = currentCoroutineContext()[Job]
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob(parentJob))
        val context = if (input is JsonObject) {
            ToolScriptContext(authentication = authentication, scope = scope, input = input, json = json)
        } else {
            DefaultScriptContext(scope = scope, authentication = authentication, input = value, json = json)
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
    suspend fun validateScript(authentication: AuthenticationContext, source: String, compile: Boolean?, id: UUID?): ScriptValidationResult {
        if (id == null) verifyCreate(authentication) else permissions.verifyAllowed(authentication, find(id), PermissionAction.EDIT)
        return scriptExecutionService.validate(source, compile == true)
    }

    private fun verifyCreate(authentication: AuthenticationContext) {
        groups.verifyHasScope(authentication, ApiTokenScopes.SCRIPTS_MANAGE.name)
        val principal = authentication.principal()
        if (principal?.hasGroup("administrators") != true && principal?.hasGroup("sa") != true) groups.throwUnauthorized()
    }

    private suspend fun registerTool(script: Script) {
        val service = tools.get()
        val tool = service.getByScriptId(script.id).firstOrNull() ?: service.add(
            AgentToolInput(key = script.key, name = script.name, description = script.description, scriptId = script.id),
        )
        val agentService = agents.get()
        val agent = agentService.getByKey("kit.script") ?: return
        if (agentService.getTools(agent.id).none { it.id == tool.id }) agentService.addTool(agent.id, tool.id)
    }

    /** Updates the tool registered from [previous]; tools an administrator keyed separately keep their own definition. */
    private suspend fun syncTool(previous: Script, updated: Script) {
        if (previous.key == updated.key && previous.name == updated.name && previous.description == updated.description) return
        val service = tools.get()
        val tool = service.getByScriptId(previous.id).firstOrNull { it.key == previous.key } ?: return
        service.edit(tool.id, AgentToolInput(
            key = updated.key,
            name = updated.name,
            description = updated.description,
            configuration = tool.configuration,
            scriptId = tool.scriptId,
            mcpServerId = tool.mcpServerId,
            graphqlOperation = tool.graphqlOperation,
            graphqlInputTransform = tool.graphqlInputTransform,
            graphqlOutputTransform = tool.graphqlOutputTransform,
            promptId = tool.promptId,
            modelId = tool.modelId,
            agentId = tool.agentId,
        ))
    }

    /** Stops Kit offering [script] and removes the tool registered from it. */
    private suspend fun unregisterTool(script: Script) {
        val service = tools.get()
        val registered = service.getByScriptId(script.id)
        val agentService = agents.get()
        agentService.getByKey("kit.script")?.let { agent ->
            val attached = agentService.getTools(agent.id).map { it.id }.toSet()
            registered.filter { it.id in attached }.forEach { agentService.removeTool(agent.id, it.id) }
        }
        registered.filter { it.key == script.key }.forEach { service.delete(it.id) }
    }

    private suspend fun find(id: UUID): Script = scriptService.get(id) ?: error("Script not found: $id")

    @Field
    suspend fun addPermission(authentication: AuthenticationContext, permission: PermissionInput): Boolean {
        permissions.verifyAllowed(authentication, find(permission.entityId), PermissionAction.MANAGE)
        scriptService.addPermission(permission.entityId, permission.groupId, permission.action)
        return true
    }

    @Field
    suspend fun deletePermission(authentication: AuthenticationContext, permission: PermissionInput): Boolean {
        permissions.verifyAllowed(authentication, find(permission.entityId), PermissionAction.MANAGE)
        scriptService.deletePermission(permission.entityId, permission.groupId, permission.action)
        return true
    }
}
