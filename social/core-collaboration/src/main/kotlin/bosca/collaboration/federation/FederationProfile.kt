package bosca.collaboration.federation

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Maps a user profile from a remote federated peer to a local ghost profile,
 * enabling sender attribution for federated messages.
 */
@Serializable
data class FederationProfile(
    @ColumnName("peer_id")
    @Contextual
    val peerId: UUID,
    @ColumnName("remote_profile_id")
    @Contextual
    val remoteProfileId: UUID,
    @ColumnName("local_profile_id")
    @Contextual
    val localProfileId: UUID? = null,
    @ColumnName("display_name")
    val displayName: String,
    @ColumnName("avatar_url")
    val avatarUrl: String? = null,
)
