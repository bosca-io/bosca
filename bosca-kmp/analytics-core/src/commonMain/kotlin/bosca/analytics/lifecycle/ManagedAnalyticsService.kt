package bosca.analytics.lifecycle

import bosca.analytics.api.AnalyticsService

/** Adds DI-resource ownership to the analytics service implementation. */
internal class ManagedAnalyticsService(
    private val delegate: AnalyticsService,
    private val resources: AnalyticsResources,
) : AnalyticsService by delegate {
    override suspend fun close() {
        try {
            delegate.close()
        } finally {
            resources.close()
        }
    }
}
