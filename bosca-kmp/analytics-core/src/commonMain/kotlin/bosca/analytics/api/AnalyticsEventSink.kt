package bosca.analytics.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Destination for analytics events. */
abstract class AnalyticsEventSink {
    private val interceptors = MutableStateFlow<List<AnalyticsEventInterceptor>>(emptyList())

    fun addInterceptor(interceptor: AnalyticsEventInterceptor) {
        interceptors.update { it + interceptor }
    }

    fun removeInterceptor(interceptor: AnalyticsEventInterceptor) {
        interceptors.update { current -> current.filterNot { it === interceptor } }
    }

    suspend fun add(event: AnalyticsEvent) {
        var intercepted = event.copy(
            element = event.element.copy(
                content = event.element.content.map { it.copy() },
                extras = event.element.extras.toMap(),
            ),
        )
        interceptors.value.forEach { interceptor -> intercepted = interceptor.intercept(intercepted) }
        onAdd(event, intercepted)
    }

    /** Delivers any events currently buffered by this sink. */
    open suspend fun flush() = Unit

    /** Flushes and releases sink-owned lifecycle work without closing injected dependencies. */
    open suspend fun close() {
        flush()
    }

    protected abstract suspend fun onAdd(original: AnalyticsEvent, event: AnalyticsEvent)
}
