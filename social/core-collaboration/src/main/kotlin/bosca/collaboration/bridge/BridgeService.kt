package bosca.collaboration.bridge

import bosca.serialization.UUID
import bosca.service.Service

/**
 * Manages bridge bindings between Bosca chat channels and external messaging
 * platforms (Slack, Teams). Handles identity mapping, message tracking, and
 * binding lifecycle.
 */
interface BridgeService : Service {

    /** Returns all active bridge bindings for a channel */
    suspend fun getBindingsForChannel(channelId: UUID): List<BridgeBinding>

    /** Finds a binding by its external platform and channel ID */
    suspend fun getBindingByExternal(platform: BridgePlatform, externalChannelId: String, workspaceId: String): BridgeBinding?

    /** Creates a new bridge binding */
    suspend fun createBinding(channelId: UUID, platform: BridgePlatform, externalChannelId: String, workspaceId: String, webhookUrl: String?): BridgeBinding

    /** Deactivates a bridge binding */
    suspend fun deactivateBinding(id: UUID)

    /** Resolves an external user to a Bosca profile ID, or null if unmapped */
    suspend fun resolveProfile(platform: BridgePlatform, externalUserId: String, workspaceId: String): UUID?

    /** Resolves a Bosca profile to an external user ID, or null if unmapped */
    suspend fun resolveExternalUser(platform: BridgePlatform, profileId: UUID, workspaceId: String): String?

    /** Records the mapping between a Bosca message and its external platform ID */
    suspend fun recordMessageMapping(channelId: UUID, sequence: Long, platform: BridgePlatform, externalMessageId: String)

    /** Retrieves the external message ID for a Bosca message */
    suspend fun getExternalMessageId(channelId: UUID, sequence: Long, platform: BridgePlatform): String?

    /** Retrieves the Bosca channel and sequence for an external message ID */
    suspend fun getByExternalMessageId(platform: BridgePlatform, externalMessageId: String): BridgeMessageMapping?

    /** Creates or updates an identity mapping between an external user and a Bosca profile */
    suspend fun mapIdentity(platform: BridgePlatform, externalUserId: String, workspaceId: String, displayName: String, email: String?, profileId: UUID?): BridgeIdentityMapping

    /**
     * Returns every identity mapping recorded for a particular external
     * workspace, ordered by display name. Used by the admin UI to render
     * the per-workspace identity table.
     */
    suspend fun listIdentities(platform: BridgePlatform, workspaceId: String): List<BridgeIdentityMapping>

    /**
     * Removes a previously recorded identity mapping. Already-delivered
     * messages keep their attribution to whichever profile was the
     * sender at delivery time; subsequent inbound messages from the
     * same external user fall back to the placeholder sender until the
     * mapping is re-created.
     */
    suspend fun deleteIdentity(id: UUID)

    /**
     * Stores or rotates the bot token used to authenticate outbound calls for
     * a binding. Tokens are persisted via the configuration service which
     * handles encryption at rest. Pass `null` to clear an existing token.
     */
    suspend fun setBotToken(bindingId: UUID, token: String?)

    /**
     * Retrieves the bot token previously stored for a binding, decrypted
     * via the configuration service. Returns `null` when no token has been
     * configured.
     */
    suspend fun getBotToken(bindingId: UUID): String?
}
