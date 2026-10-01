package bosca.communications.service

import bosca.communications.model.DeliveryChannel
import bosca.communications.model.NotificationPreferenceMapping
import bosca.communications.repository.NotificationPreferenceMappingRepository
import bosca.communications.repository.NotificationPreferenceRepository
import bosca.service.annotation.ServiceImplementation

@ServiceImplementation
class NotificationPreferenceMappingServiceImpl(
    private val types: NotificationTypeService,
    private val mappings: NotificationPreferenceMappingRepository,
    private val preferences: NotificationPreferenceRepository,
) : NotificationPreferenceMappingService {

    override suspend fun list(): List<NotificationPreferenceMapping> = mappings.list()

    override suspend fun get(type: String, channel: DeliveryChannel): NotificationPreferenceMapping? =
        mappings.get(type, channel)

    override suspend fun set(
        type: String,
        channel: DeliveryChannel,
        provider: String,
        externalId: String,
    ): NotificationPreferenceMapping {
        val definition = requireNotNull(types.get(type)) { "unknown notification type: $type" }
        require(definition.optional) { "type $type cannot be mapped because it cannot be opted out" }
        require(PROVIDER_KEY.matches(provider)) {
            "provider must be a lowercase slug, got: $provider"
        }
        require(externalId.isNotBlank()) { "externalId must not be blank" }
        require(externalId.length <= 255) { "externalId must be at most 255 characters" }

        val mapping = mappings.upsert(type, channel, provider, externalId)
        preferences.deleteByChannelAndType(channel, type)
        return mapping
    }

    override suspend fun delete(type: String, channel: DeliveryChannel): Boolean =
        mappings.delete(type, channel) > 0

    companion object {
        private val PROVIDER_KEY = Regex("^[a-z][a-z0-9_-]{0,63}$")
    }
}
