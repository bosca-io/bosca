package bosca.cli.api

import bosca.graphql.client.execute
import bosca.graphql.gen.*
import kotlinx.serialization.json.JsonElement
import kotlin.uuid.Uuid

/** Script discovery, management and execution under the CLI caller's existing authentication. */
class ScriptingApi(network: NetworkClient) : Api(network) {
    suspend fun list(type: ScriptType?, includeDeleted: Boolean, offset: Int, limit: Int) = network.boscaGraphql.execute(
        ListManagedScripts, ListManagedScripts.Variables(type, includeDeleted, offset, limit),
    )
    suspend fun get(id: Uuid) = network.boscaGraphql.execute(GetManagedScript, GetManagedScript.Variables(id)).scripts.script
    suspend fun get(key: String) = network.boscaGraphql.execute(GetManagedScriptByKey, GetManagedScriptByKey.Variables(key)).scripts.scriptByKey
    suspend fun add(script: ScriptInput) = network.boscaGraphql.execute(AddManagedScript, AddManagedScript.Variables(script))
    suspend fun edit(id: Uuid, script: ScriptInput, expectedVersion: Int) = network.boscaGraphql.execute(
        EditManagedScript, EditManagedScript.Variables(id, script, expectedVersion),
    )
    suspend fun delete(id: Uuid) = network.boscaGraphql.execute(DeleteManagedScript, DeleteManagedScript.Variables(id))
    suspend fun enable(id: Uuid) = network.boscaGraphql.execute(EnableManagedScript, EnableManagedScript.Variables(id))
    suspend fun disable(id: Uuid) = network.boscaGraphql.execute(DisableManagedScript, DisableManagedScript.Variables(id))
    suspend fun execute(id: Uuid, input: JsonElement?) = network.boscaGraphql.execute(ExecuteManagedScript, ExecuteManagedScript.Variables(id, input))
    suspend fun validate(source: String, compile: Boolean, id: Uuid? = null) =
        network.boscaGraphql.execute(ValidateManagedScript, ValidateManagedScript.Variables(source, compile, id))
}
