package bosca.analytics.api

import bosca.analytics.platform.currentMicros
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Default portable event factory. */
class DefaultAnalyticsEventFactory(
    private val currentPageProvider: CurrentPageProvider,
) : AnalyticsEventFactory {
    override suspend fun createEvent(event: AnalyticsEventInput): AnalyticsEvent = AnalyticsEvent(
        clientId = Uuid.random().toString(),
        type = event.type,
        created = Clock.System.now().toEpochMilliseconds(),
        createdMicros = currentMicros(),
        element = event.element.copy(
            content = event.element.content.map { it.copy() },
            extras = event.element.extras.toMap(),
        ),
        page = event.page ?: currentPageProvider.currentPage(),
        error = event.error,
    )
}
