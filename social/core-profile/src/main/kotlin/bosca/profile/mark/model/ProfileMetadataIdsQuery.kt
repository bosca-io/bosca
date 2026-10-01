package bosca.profile.mark.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Query parameters for looking up profile marks by a profile and a set of metadata identifiers.
 */
@Serializable
data class ProfileMetadataIdsQuery(
    @Contextual
    val profileId: UUID,
    val metadataIds: List<@Contextual UUID>
)
