package bosca.atlas.server

import bosca.cache.withRequestCache
import bosca.configuration.service.ConfigurationService
import bosca.db.withConnectionManager
import bosca.di.provide
import bosca.di.providerMissing
import bosca.di.provides
import bosca.hubspot.client.HubSpot
import bosca.hubspot.configuration.HubSpotConfiguration
import bosca.server.BoscaApplication
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Selects the optional integration at process startup, matching the API and runner composition. */
internal suspend fun BoscaApplication.configureAtlasHubSpot() {
    providerMissing<HubSpot>()
    withConnectionManager {
        withRequestCache {
            val configurations = provide<ConfigurationService>()
            val configuration = configurations.getByKey("hubspot") ?: return@withRequestCache
            val value = configurations.getValue(configuration.id) ?: return@withRequestCache
            val json = provide<Json>()
            val settings = try {
                json.decodeFromJsonElement(HubSpotConfiguration.serializer(), value)
            } catch (error: SerializationException) {
                log.warn("HubSpot configuration could not be loaded; update its settings and restart this process", error)
                return@withRequestCache
            }
            if (settings.token.isNotBlank()) {
                provides(singleton = true) { HubSpot(settings.token, configurationService = configurations, json = json) }
            }
        }
    }
}
