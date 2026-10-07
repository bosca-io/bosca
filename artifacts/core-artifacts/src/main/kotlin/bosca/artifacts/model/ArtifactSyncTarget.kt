package bosca.artifacts.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** One selected destination and the published Docker tag to copy through a pipeline. */
@Serializable
data class ArtifactSyncTarget(
    @Contextual val destinationId: UUID,
    @Contextual val versionId: UUID,
    val tagName: String,
    val manifestDigest: String,
)
