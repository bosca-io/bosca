package bosca.collaboration.bridge

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Maps an external platform user identity to a Bosca profile, enabling
 * user attribution and mention translation across bridge boundaries.
 */
@Serializable
data class BridgeIdentityMapping(
    @Contextual
    val id: UUID,
    val platform: BridgePlatform,
    @ColumnName("external_user_id")
    val externalUserId: String,
    @ColumnName("workspace_id")
    val workspaceId: String,
    @ColumnName("profile_id")
    @Contextual
    val profileId: UUID? = null,
    @ColumnName("display_name")
    val displayName: String,
    val email: String? = null,
)
