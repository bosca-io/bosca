package bosca.artifacts.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** A completed artifact version selected for one publication destination. */
@Serializable
data class ArtifactPublicationTarget(
    @Contextual val destinationId: UUID,
    @Contextual val versionId: UUID,
    val commitSha: String,
    /** An explicit publication request verifies the original publication again. */
    val reverify: Boolean = false,
)
