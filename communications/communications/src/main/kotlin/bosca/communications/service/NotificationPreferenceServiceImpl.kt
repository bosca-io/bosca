package bosca.communications.service

import bosca.communications.model.DeliveryChannel
import bosca.communications.model.NotificationPreference
import bosca.communications.model.NotificationSettings
import bosca.communications.model.NotificationType
import bosca.communications.repository.NotificationPreferenceRepository
import bosca.communications.repository.NotificationSettingsRepository
import bosca.communications.repository.UnsubscribeTokenRepository
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import java.security.SecureRandom
import java.time.ZoneId
import java.util.Base64

@ServiceImplementation
class NotificationPreferenceServiceImpl(
    private val types: NotificationTypeService,
    private val mappings: NotificationPreferenceMappingService,
    private val preferences: NotificationPreferenceRepository,
    private val settings: NotificationSettingsRepository,
    private val tokens: UnsubscribeTokenRepository,
) : NotificationPreferenceService {

    override suspend fun getPreferences(profileId: UUID): List<NotificationPreference> {
        val catalog = types.list()
        val stored = preferences.getByProfileId(profileId)
            .associateBy { it.channel to it.type }
        val external = getExternalOptOuts(profileId, catalog.associateBy { it.key })
        return DeliveryChannel.entries.flatMap { channel ->
            catalog.map { type ->
                external[channel to type.key]?.let { optedOut ->
                    NotificationPreference(profileId, channel, type.key, optedOut)
                } ?: stored[channel to type.key]
                ?: NotificationPreference(
                    profileId = profileId,
                    channel = channel,
                    type = type.key,
                    optedOut = type.optional && !type.defaultEnabled(channel),
                )
            }
        }
    }

    override suspend fun isOptedOut(profileId: UUID, channel: DeliveryChannel, type: String): Boolean {
        val definition = types.get(type) ?: return false
        if (!definition.optional) return false
        val mapping = mappings.get(type, channel)
        if (mapping != null) {
            return externalProvider(mapping.provider)
                .getOptOuts(profileId, channel, setOf(mapping.externalId))[mapping.externalId]
                ?: !definition.defaultEnabled(channel)
        }
        return preferences.get(profileId, channel, type)?.optedOut ?: !definition.defaultEnabled(channel)
    }

    override suspend fun setOptOut(
        profileId: UUID,
        channel: DeliveryChannel,
        type: String,
        optedOut: Boolean,
    ): NotificationPreference {
        val definition = requireNotNull(types.get(type)) { "unknown notification type: $type" }
        require(!optedOut || definition.optional) { "type $type cannot be opted out" }
        val mapping = mappings.get(type, channel)
        if (mapping != null) {
            externalProvider(mapping.provider)
                .setOptOut(profileId, channel, mapping.externalId, optedOut)
            return NotificationPreference(profileId, channel, type, optedOut)
        }
        return preferences.upsert(profileId, channel, type, optedOut)
    }

    override suspend fun getSettings(profileId: UUID): NotificationSettings =
        settings.get(profileId) ?: NotificationSettings(profileId)

    override suspend fun setQuietHours(
        profileId: UUID,
        timeZone: String?,
        dndStartLocal: String?,
        dndEndLocal: String?,
    ): NotificationSettings {
        val values = listOf(timeZone, dndStartLocal, dndEndLocal)
        require(values.all { it == null } || values.none { it == null }) {
            "quiet hours require timeZone, dndStartLocal, and dndEndLocal together, or all null to clear"
        }
        if (timeZone != null) {
            require(runCatching { ZoneId.of(timeZone) }.isSuccess) { "unknown time zone: $timeZone" }
            require(dndStartLocal != null && LOCAL_TIME.matches(dndStartLocal)) {
                "dndStartLocal must be HH:MM, got: $dndStartLocal"
            }
            require(dndEndLocal != null && LOCAL_TIME.matches(dndEndLocal)) {
                "dndEndLocal must be HH:MM, got: $dndEndLocal"
            }
        }
        return settings.upsert(profileId, timeZone, dndStartLocal, dndEndLocal)
    }

    override suspend fun unsubscribeByToken(token: String): Boolean {
        val record = tokens.get(token) ?: return false
        val optOutTypes = record.type?.let { key -> listOfNotNull(types.get(key)) }
            ?: types.list()
        optOutTypes
            .filter { it.optional }
            .forEach { setOptOut(record.profileId, DeliveryChannel.EMAIL, it.key, true) }
        return true
    }

    override suspend fun profileIdForToken(token: String): UUID? =
        tokens.get(token)?.profileId

    override suspend fun generateUnsubscribeToken(profileId: UUID, type: String?): String {
        if (type != null) {
            requireNotNull(types.get(type)) { "unknown notification type: $type" }
        }
        val token = generateSecureToken()
        tokens.insert(token, profileId, type)
        return token
    }

    private suspend fun getExternalOptOuts(
        profileId: UUID,
        catalog: Map<String, NotificationType>,
    ): Map<Pair<DeliveryChannel, String>, Boolean> {
        val result = mutableMapOf<Pair<DeliveryChannel, String>, Boolean>()
        mappings.list()
            .filter { catalog[it.type]?.optional == true }
            .groupBy { it.provider to it.channel }
            .forEach { (providerAndChannel, providerMappings) ->
                val (providerKey, channel) = providerAndChannel
                val byExternalId = externalProvider(providerKey).getOptOuts(
                    profileId,
                    channel,
                    providerMappings.mapTo(linkedSetOf()) { it.externalId },
                )
                providerMappings.forEach { mapping ->
                    val defaultOptedOut = !catalog.getValue(mapping.type).defaultEnabled(channel)
                    result[channel to mapping.type] = byExternalId[mapping.externalId] ?: defaultOptedOut
                }
            }
        return result
    }

    @OptIn(InternalDI::class)
    private suspend fun externalProvider(key: String): ExternalNotificationPreferenceProvider {
        val matches = ProviderRegistry.findAll(ExternalNotificationPreferenceProvider::class)
            .filter { it.exists }
            .map { it.get() }
            .filter { it.key == key }
        check(matches.size == 1) {
            "notification preference provider '$key' is not available"
        }
        return matches.single()
    }

    companion object {
        private val LOCAL_TIME = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
        private val random = SecureRandom()

        private fun generateSecureToken(): String {
            val bytes = ByteArray(32)
            random.nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}
