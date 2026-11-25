package bosca.ai.kit.harness

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** Content reference shared through Bosca permissions rather than a writable child filesystem. */
@Serializable
data class KitDelegationArtifact(@Contextual val metadataId: UUID, val label: String = "")
