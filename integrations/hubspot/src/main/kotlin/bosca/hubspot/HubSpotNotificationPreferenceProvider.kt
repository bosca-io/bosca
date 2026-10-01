package bosca.hubspot

import bosca.communications.model.DeliveryChannel
import bosca.communications.service.ExternalNotificationPreferenceProvider
import bosca.di.ObjectProvider
import bosca.hubspot.client.HubSpot
import bosca.profile.attribute.model.getAttributeString
import bosca.profile.profile.service.ProfileService
import bosca.serialization.UUID

/**
 * Stores mapped Bosca email preferences in HubSpot communication subscriptions.
 *
 * HubSpot identifies subscription status by email, so the profile's canonical email attribute is
 * resolved for every on-demand read or write.
 */
class HubSpotNotificationPreferenceProvider(
    private val hubspot: ObjectProvider<HubSpot>,
    private val profiles: ProfileService,
) : ExternalNotificationPreferenceProvider {

    override val key: String = KEY

    override suspend fun getOptOuts(
        profileId: UUID,
        channel: DeliveryChannel,
        externalIds: Set<String>,
    ): Map<String, Boolean> {
        requireEmailChannel(channel)
        if (externalIds.isEmpty()) return emptyMap()
        val subscriptions = client().getSubscriptions(email(profileId))
            .associateBy { it.subscriptionId }
        return externalIds.mapNotNull { externalId ->
            val subscriptionId = subscriptionId(externalId)
            subscriptions[subscriptionId]?.let { externalId to !it.subscribed }
        }.toMap()
    }

    override suspend fun setOptOut(
        profileId: UUID,
        channel: DeliveryChannel,
        externalId: String,
        optedOut: Boolean,
    ) {
        requireEmailChannel(channel)
        val result = client().setSubscription(
            email = email(profileId),
            subscriptionId = subscriptionId(externalId),
            subscribed = !optedOut,
        )
        check(result == "ok") {
            "HubSpot did not persist communication subscription $externalId: $result"
        }
    }

    private suspend fun client(): HubSpot {
        check(hubspot.exists) { "HubSpot is not configured" }
        return hubspot.get()
    }

    private suspend fun email(profileId: UUID): String =
        profiles.getAttributes(profileId)
            .getAttributeString("bosca.profiles.email", "email")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: error("No email found for profile $profileId")

    private fun subscriptionId(externalId: String): Int =
        externalId.toIntOrNull()
            ?: throw IllegalArgumentException("HubSpot subscription id must be numeric, got: $externalId")

    private fun requireEmailChannel(channel: DeliveryChannel) {
        require(channel == DeliveryChannel.EMAIL) {
            "HubSpot communication subscriptions only support the EMAIL channel"
        }
    }

    companion object {
        const val KEY = "hubspot"
    }
}
