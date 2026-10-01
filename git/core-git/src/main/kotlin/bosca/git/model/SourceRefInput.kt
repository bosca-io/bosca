package bosca.git.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Client-supplied fields for linking an entity (script, query) to a file in a
 * git repository. The [ref] defaults to "main" when omitted.
 */
@Serializable
data class SourceRefInput(
    @Contextual val repositoryId: UUID,
    val path: String,
    val ref: String = "main"
)
