package bosca.hubspot.configuration

import bosca.communications.service.ExternalNotificationPreferenceProvider
import bosca.di.ObjectProvider
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.hubspot.HubSpotNotificationPreferenceProvider
import bosca.hubspot.client.HubSpot
import bosca.hubspot.transformer.HubSpotEntityTransformer
import bosca.profile.organization.service.OrganizationService
import bosca.profile.profile.service.ProfileService
import bosca.security.service.SecurityService
import kotlinx.serialization.json.Json

@Providers
class Configuration {

    @Provider(singleton = true)
    fun transformer(
        profileService: ProfileService,
        organizationService: OrganizationService,
        securityService: SecurityService,
        json: Json
    ) = HubSpotEntityTransformer(
        profileService,
        organizationService,
        securityService,
        json
    )

    @Provider(singleton = true, name = "hubspot")
    fun provider(
        hubspot: ObjectProvider<HubSpot>,
        profiles: ProfileService,
    ): ExternalNotificationPreferenceProvider = HubSpotNotificationPreferenceProvider(hubspot = hubspot, profiles = profiles)
}