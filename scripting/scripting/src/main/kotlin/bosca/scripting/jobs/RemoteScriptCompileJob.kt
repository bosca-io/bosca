@file:OptIn(ExperimentalUuidApi::class)

package bosca.scripting.jobs

import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi

/**
 * Job payload for compiling a script on a worker that has the Kotlin scripting
 * engine available. This validates that a script compiles successfully without
 * executing it, useful for save-time validation from builds that exclude the
 * scripting engine (e.g. GraalVM native images).
 *
 * The worker fetches the full [bosca.scripting.model.Script] from the database
 * using [scriptId] and attempts compilation. Success or failure is communicated
 * back via the job status notification.
 */
@Serializable
data class RemoteScriptCompileJob(
    @Contextual
    val scriptId: UUID
) : IJobDefinition
