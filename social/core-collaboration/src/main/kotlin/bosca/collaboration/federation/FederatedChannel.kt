package bosca.collaboration.federation

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Links a local chat channel to a remote channel on a federated peer,
 * defining the sync direction for message relay.
 */
@Serializable
data class FederatedChannel(
    @ColumnName("local_channel_id")
    @Contextual
    val localChannelId: UUID,
    @ColumnName("peer_id")
    @Contextual
    val peerId: UUID,
    @ColumnName("remote_channel_id")
    @Contextual
    val remoteChannelId: UUID,
    @ColumnName("sync_direction")
    val syncDirection: FederationSyncDirection,
)
