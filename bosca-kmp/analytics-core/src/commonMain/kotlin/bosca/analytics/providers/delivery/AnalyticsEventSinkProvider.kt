package bosca.analytics.providers.delivery

import bosca.analytics.api.AnalyticsEventSink
import bosca.analytics.delivery.BoscaSink
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers

/** Exposes collector delivery through the event-sink contract used by the API layer. */
@Providers
class AnalyticsEventSinkProvider {
    @Provider(singleton = true)
    fun eventSink(sink: BoscaSink): AnalyticsEventSink = sink
}
