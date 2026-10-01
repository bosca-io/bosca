package bosca.collaboration.repository

import bosca.collaboration.federation.FederatedChannel
import bosca.collaboration.federation.FederationPeer
import bosca.collaboration.federation.FederationProfile
import bosca.collaboration.federation.FederationSyncDirection
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

/**
 * Data access for federation peers, channel links, and ghost profile
 * mappings stored in the `collaboration` PostgreSQL schema. Shared
 * secrets are managed outside this repository — they are persisted
 * via the configuration service so encryption is handled uniformly
 * with bridge bot tokens.
 */
@Repository
interface FederationRepository {

    @Query("select id, name, nats_url, api_url, active, created_at from collaboration.federation_peers where active = true order by created_at desc")
    suspend fun getActivePeers(): List<FederationPeer>

    @Query("select id, name, nats_url, api_url, active, created_at from collaboration.federation_peers where id = :id")
    suspend fun getPeer(id: UUID): FederationPeer?

    @Query("insert into collaboration.federation_peers (name, nats_url, api_url) values (:name, :natsUrl, :apiUrl) returning id, name, nats_url, api_url, active, created_at")
    suspend fun createPeer(name: String, natsUrl: String, apiUrl: String): FederationPeer

    @Query("update collaboration.federation_peers set active = false where id = :id")
    suspend fun deactivatePeer(id: UUID)

    @Query("select local_channel_id, peer_id, remote_channel_id, sync_direction from collaboration.federated_channels where local_channel_id = :localChannelId")
    suspend fun getFederatedChannelsForLocal(localChannelId: UUID): List<FederatedChannel>

    @Query("select local_channel_id, peer_id, remote_channel_id, sync_direction from collaboration.federated_channels")
    suspend fun getAllFederatedChannels(): List<FederatedChannel>

    @Query("select local_channel_id, peer_id, remote_channel_id, sync_direction from collaboration.federated_channels where peer_id = :peerId")
    suspend fun getFederatedChannelsForPeer(peerId: UUID): List<FederatedChannel>

    @Query("insert into collaboration.federated_channels (local_channel_id, peer_id, remote_channel_id, sync_direction) values (:localChannelId, :peerId, :remoteChannelId, :syncDirection) on conflict (local_channel_id, peer_id) do update set remote_channel_id = excluded.remote_channel_id, sync_direction = excluded.sync_direction")
    suspend fun upsertFederatedChannel(
        localChannelId: UUID,
        peerId: UUID,
        remoteChannelId: UUID,
        syncDirection: FederationSyncDirection,
    )

    @Query("delete from collaboration.federated_channels where local_channel_id = :localChannelId and peer_id = :peerId")
    suspend fun deleteFederatedChannel(localChannelId: UUID, peerId: UUID)

    @Query("select peer_id, remote_profile_id, local_profile_id, display_name, avatar_url from collaboration.federation_profiles where peer_id = :peerId and remote_profile_id = :remoteProfileId limit 1")
    suspend fun getFederationProfile(peerId: UUID, remoteProfileId: UUID): FederationProfile?

    @Query("insert into collaboration.federation_profiles (peer_id, remote_profile_id, local_profile_id, display_name, avatar_url) values (:peerId, :remoteProfileId, :localProfileId, :displayName, :avatarUrl) on conflict (peer_id, remote_profile_id) do update set local_profile_id = excluded.local_profile_id, display_name = excluded.display_name, avatar_url = excluded.avatar_url returning peer_id, remote_profile_id, local_profile_id, display_name, avatar_url")
    suspend fun upsertFederationProfile(
        peerId: UUID,
        remoteProfileId: UUID,
        localProfileId: UUID?,
        displayName: String,
        avatarUrl: String?,
    ): FederationProfile
}
