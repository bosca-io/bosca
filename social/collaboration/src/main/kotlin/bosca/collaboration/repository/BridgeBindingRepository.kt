package bosca.collaboration.repository

import bosca.collaboration.bridge.BridgeBinding
import bosca.collaboration.bridge.BridgeIdentityMapping
import bosca.collaboration.bridge.BridgeMessageMapping
import bosca.collaboration.bridge.BridgePlatform
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

/**
 * Data access for bridge bindings, identity mappings, and message id
 * mappings stored in the `collaboration` PostgreSQL schema. Bot tokens
 * (the encrypted byte columns) are intentionally not exposed via this
 * repository — they are managed by the bridge service alongside the
 * encryption layer.
 */
@Repository
interface BridgeBindingRepository {

    @Query("select id, channel_id, platform, external_channel_id, workspace_id, webhook_url, active, created_at from collaboration.bridge_bindings where channel_id = :channelId and active = true order by created_at desc")
    suspend fun getActiveByChannel(channelId: UUID): List<BridgeBinding>

    @Query("select id, channel_id, platform, external_channel_id, workspace_id, webhook_url, active, created_at from collaboration.bridge_bindings where id = :id")
    suspend fun getById(id: UUID): BridgeBinding?

    @Query("select id, channel_id, platform, external_channel_id, workspace_id, webhook_url, active, created_at from collaboration.bridge_bindings where platform = :platform and external_channel_id = :externalChannelId and workspace_id = :workspaceId and active = true limit 1")
    suspend fun getByExternal(platform: BridgePlatform, externalChannelId: String, workspaceId: String): BridgeBinding?

    @Query("insert into collaboration.bridge_bindings (channel_id, platform, external_channel_id, workspace_id, webhook_url) values (:channelId, :platform, :externalChannelId, :workspaceId, :webhookUrl) returning id, channel_id, platform, external_channel_id, workspace_id, webhook_url, active, created_at")
    suspend fun createBinding(
        channelId: UUID,
        platform: BridgePlatform,
        externalChannelId: String,
        workspaceId: String,
        webhookUrl: String?,
    ): BridgeBinding

    @Query("update collaboration.bridge_bindings set active = false where id = :id")
    suspend fun deactivate(id: UUID)

    @Query("select id, platform, external_user_id, workspace_id, profile_id, display_name, email from collaboration.bridge_identity_map where platform = :platform and external_user_id = :externalUserId and workspace_id = :workspaceId limit 1")
    suspend fun getIdentityByExternal(platform: BridgePlatform, externalUserId: String, workspaceId: String): BridgeIdentityMapping?

    @Query("select id, platform, external_user_id, workspace_id, profile_id, display_name, email from collaboration.bridge_identity_map where platform = :platform and profile_id = :profileId and workspace_id = :workspaceId limit 1")
    suspend fun getIdentityByProfile(platform: BridgePlatform, profileId: UUID, workspaceId: String): BridgeIdentityMapping?

    @Query("select id, platform, external_user_id, workspace_id, profile_id, display_name, email from collaboration.bridge_identity_map where platform = :platform and workspace_id = :workspaceId order by display_name asc")
    suspend fun listIdentities(platform: BridgePlatform, workspaceId: String): List<BridgeIdentityMapping>

    @Query("delete from collaboration.bridge_identity_map where id = :id")
    suspend fun deleteIdentity(id: UUID)

    @Query("insert into collaboration.bridge_identity_map (platform, external_user_id, workspace_id, profile_id, display_name, email) values (:platform, :externalUserId, :workspaceId, :profileId, :displayName, :email) on conflict (platform, external_user_id, workspace_id) do update set profile_id = excluded.profile_id, display_name = excluded.display_name, email = excluded.email returning id, platform, external_user_id, workspace_id, profile_id, display_name, email")
    suspend fun upsertIdentity(
        platform: BridgePlatform,
        externalUserId: String,
        workspaceId: String,
        profileId: UUID?,
        displayName: String,
        email: String?,
    ): BridgeIdentityMapping

    @Query("insert into collaboration.bridge_message_map (channel_id, sequence, platform, external_message_id) values (:channelId, :sequence, :platform, :externalMessageId) on conflict do nothing")
    suspend fun recordMessageMapping(channelId: UUID, sequence: Long, platform: BridgePlatform, externalMessageId: String)

    @Query("select id, channel_id, sequence, platform, external_message_id from collaboration.bridge_message_map where channel_id = :channelId and sequence = :sequence and platform = :platform limit 1")
    suspend fun getBySequence(channelId: UUID, sequence: Long, platform: BridgePlatform): BridgeMessageMapping?

    @Query("select id, channel_id, sequence, platform, external_message_id from collaboration.bridge_message_map where platform = :platform and external_message_id = :externalMessageId limit 1")
    suspend fun getByExternalMessageId(platform: BridgePlatform, externalMessageId: String): BridgeMessageMapping?
}
