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
 * A stored script is selected by [scriptId]. Unsaved validation supplies [source]
 * and [expiresAt]; its structured result is retained in private object storage for
 * the requesting server to consume without depending on a live subscription.
 */
@Serializable
data class RemoteScriptCompileJob(
    @Contextual
    val scriptId: UUID? = null,
    val source: String? = null,
    val expiresAt: String? = null,
) : IJobDefinition
