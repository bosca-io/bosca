package bosca.collaboration.service

import bosca.collaboration.bridge.BridgeBinding
import bosca.collaboration.bridge.BridgeIdentityMapping
import bosca.collaboration.bridge.BridgeMessageMapping
import bosca.collaboration.bridge.BridgePlatform
import bosca.collaboration.bridge.BridgeService
import bosca.collaboration.repository.BridgeBindingRepository
import bosca.configuration.model.ConfigurationInput
import bosca.configuration.service.ConfigurationService
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Database-backed [BridgeService]. Persists bindings, identity mappings, and
 * message id mappings via [BridgeBindingRepository]. Bot tokens are stored
 * encrypted via [ConfigurationService] under a per-binding key so callers
 * never see the plaintext token in the binding row itself.
 */
@ServiceImplementation
class BridgeServiceImpl(
    private val repository: BridgeBindingRepository,
    private val configurationService: ConfigurationService,
) : BridgeService {

    private fun tokenKey(bindingId: UUID) = "bridge.binding.$bindingId.token"

    override suspend fun getBindingsForChannel(channelId: UUID): List<BridgeBinding> =
        repository.getActiveByChannel(channelId)

    override suspend fun getBindingByExternal(
        platform: BridgePlatform,
        externalChannelId: String,
        workspaceId: String,
    ): BridgeBinding? = repository.getByExternal(platform, externalChannelId, workspaceId)

    override suspend fun createBinding(
        channelId: UUID,
        platform: BridgePlatform,
        externalChannelId: String,
        workspaceId: String,
        webhookUrl: String?,
    ): BridgeBinding = repository.createBinding(channelId, platform, externalChannelId, workspaceId, webhookUrl)

    override suspend fun deactivateBinding(id: UUID) {
        repository.deactivate(id)
    }

    override suspend fun resolveProfile(
        platform: BridgePlatform,
        externalUserId: String,
        workspaceId: String,
    ): UUID? = repository.getIdentityByExternal(platform, externalUserId, workspaceId)?.profileId

    override suspend fun resolveExternalUser(
        platform: BridgePlatform,
        profileId: UUID,
        workspaceId: String,
    ): String? = repository.getIdentityByProfile(platform, profileId, workspaceId)?.externalUserId

    override suspend fun recordMessageMapping(
        channelId: UUID,
        sequence: Long,
        platform: BridgePlatform,
        externalMessageId: String,
    ) {
        repository.recordMessageMapping(channelId, sequence, platform, externalMessageId)
    }

    override suspend fun getExternalMessageId(
        channelId: UUID,
        sequence: Long,
        platform: BridgePlatform,
    ): String? = repository.getBySequence(channelId, sequence, platform)?.externalMessageId

    override suspend fun getByExternalMessageId(
        platform: BridgePlatform,
        externalMessageId: String,
    ): BridgeMessageMapping? = repository.getByExternalMessageId(platform, externalMessageId)

    override suspend fun mapIdentity(
        platform: BridgePlatform,
        externalUserId: String,
        workspaceId: String,
        displayName: String,
        email: String?,
        profileId: UUID?,
    ): BridgeIdentityMapping = repository.upsertIdentity(
        platform = platform,
        externalUserId = externalUserId,
        workspaceId = workspaceId,
        profileId = profileId,
        displayName = displayName,
        email = email,
    )

    override suspend fun listIdentities(platform: BridgePlatform, workspaceId: String): List<BridgeIdentityMapping> =
        repository.listIdentities(platform, workspaceId)

    override suspend fun deleteIdentity(id: UUID) {
        repository.deleteIdentity(id)
    }

    override suspend fun setBotToken(bindingId: UUID, token: String?) {
        val key = tokenKey(bindingId)
        val existing = configurationService.getByKey(key)
        if (token == null) {
            // No first-class delete in ConfigurationService; storing JsonNull
            // effectively voids the secret while leaving the configuration row
            // in place for audit purposes.
            if (existing != null) {
                configurationService.setValue(existing.id, JsonNull)
            }
            return
        }
        val payload = buildJsonObject { put("token", JsonPrimitive(token)) }
        if (existing != null) {
            configurationService.setValue(existing.id, payload)
        } else {
            configurationService.setConfiguration(
                ConfigurationInput(
                    key = key,
                    description = "Encrypted bot token for bridge binding $bindingId",
                    value = payload,
                    public = false,
                    permissions = emptyList(),
                )
            )
        }
    }

    override suspend fun getBotToken(bindingId: UUID): String? {
        val configuration = configurationService.getByKey(tokenKey(bindingId)) ?: return null
        val value = configurationService.getValue(configuration.id) ?: return null
        if (value is JsonNull) return null
        return runCatching { value.jsonObject["token"]?.jsonPrimitive?.content }.getOrNull()
    }
}
