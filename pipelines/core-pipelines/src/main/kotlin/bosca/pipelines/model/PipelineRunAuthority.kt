package bosca.pipelines.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** Captured authority for a durable run. Contains credential identity and restrictions, never a token. */
@Serializable
data class PipelineRunAuthority(
    /** Only platform-triggered/scheduled runs execute as the pipelines service account. */
    val system: Boolean,
    /** Effective groups at submission; null only for system or anonymous runs. */
    val groupIds: List<@Contextual UUID>? = null,
    /** Null means unrestricted; an empty list grants no scopes. */
    val scopes: List<String>? = null,
    val credentialId: Long? = null,
    /** The credential is retired with a runtime session, so its absence does not end the run. */
    val sessionBoundCredential: Boolean = false,
)
