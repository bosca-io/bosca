@file:OptIn(ExperimentalUuidApi::class)

package bosca.scripting.jobs

import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlin.uuid.ExperimentalUuidApi

/**
 * Describes the kind of [bosca.scripting.context.ScriptContext] to reconstruct
 * on the worker that executes a remotely enqueued script.
 */
@Serializable
enum class RemoteScriptContextType {
    DEFAULT,
    TRIGGER,
    TOOL
}

/**
 * Job payload for executing a script on a worker that has the Kotlin scripting
 * engine available. This is enqueued by builds that exclude the scripting engine
 * (e.g. GraalVM native images) so that script execution can still occur remotely.
 *
 * The worker fetches the full [bosca.scripting.model.Script] from the database
 * using [scriptId], reconstructs the appropriate [bosca.scripting.context.ScriptContext]
 * from [contextType] and its associated fields, then delegates to the
 * [bosca.scripting.service.ScriptExecutionService].
 */
@Serializable
data class RemoteScriptExecutionJob(
    @Contextual
    val scriptId: UUID,
    val scriptVersion: Int,
    val contextType: RemoteScriptContextType,
    /** Effective caller groups at submission; the worker intersects these with current membership. */
    val groupIds: List<@Contextual UUID>?,
    /** API-token scopes at submission; null means unrestricted, including non-token callers. */
    val scopes: List<String>?,
    /** API-token credential identity for current revocation and restriction checks, never the raw token. */
    val credentialId: Long?,
    val input: JsonElement = JsonNull,
    @Contextual
    val principalId: UUID? = null,
    val eventName: String? = null,
    val eventPayload: JsonElement? = null,
    /** The credential is retired with a runtime session, so its absence does not end this execution. */
    val sessionBoundCredential: Boolean = false,
) : IJobDefinition
