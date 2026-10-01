package bosca.analytics.transform.geo

import bosca.analytics.model.EventPipelineContext
import bosca.analytics.model.Events
import bosca.analytics.model.Geo

class CloudflareGeoTransform : GeoPipelineTransform {

    override suspend fun transform(context: EventPipelineContext, events: Events): Events {
        val currentGeo = events.context?.geo ?: Geo()
        // Only overwrite a field when Cloudflare actually supplies a value for it.
        // Absent headers must leave existing values intact: the collector enriches
        // from live request headers, but downstream consumers (processor / runner)
        // re-run this chain against empty headers, where this stage must be a true
        // no-op rather than wiping the enrichment the collector already applied.
        val geo = currentGeo.copy(
            city = context.getHeaderValue("cf-ipcity") ?: currentGeo.city,
            country = context.getHeaderValue("cf-ipcountry") ?: currentGeo.country,
            continent = context.getHeaderValue("cf-ipcontinent") ?: currentGeo.continent,
            region = context.getHeaderValue("cf-region") ?: currentGeo.region,
            regionCode = context.getHeaderValue("cf-region-code") ?: currentGeo.regionCode,
            postalCode = context.getHeaderValue("cf-postal-code") ?: currentGeo.postalCode,
            timezone = context.getHeaderValue("cf-timezone") ?: currentGeo.timezone,
            longitude = context.getHeaderValue("cf-iplongitude")?.toDoubleOrNull() ?: currentGeo.longitude,
            latitude = context.getHeaderValue("cf-iplatitude")?.toDoubleOrNull() ?: currentGeo.latitude,
        )
        return events.copy(context = events.context?.copy(geo = geo))
    }
}