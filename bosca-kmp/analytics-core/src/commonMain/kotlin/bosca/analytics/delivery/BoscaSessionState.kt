package bosca.analytics.delivery

import bosca.analytics.api.AnalyticsElement
import bosca.analytics.api.AnalyticsEvent
import bosca.analytics.api.AnalyticsEventFactory
import bosca.analytics.api.AnalyticsEventInput
import bosca.analytics.api.AnalyticsEventType
import kotlinx.coroutines.CoroutineScope

internal fun createBoscaSessionState(
    scope: CoroutineScope,
    config: BoscaSinkConfig,
    context: AnalyticsContextState,
    eventFactory: AnalyticsEventFactory,
    logger: AnalyticsLogger,
    add: suspend (AnalyticsEvent) -> Unit,
): SessionState = SessionState(
    scope = scope,
    sessionTimeout = config.sessionTimeout,
    heartbeatInterval = config.heartbeatInterval,
    onSessionStart = {
        val sessionId = context.startSession()
        add(
            eventFactory.createEvent(
                AnalyticsEventInput(
                    AnalyticsEventType.SESSION,
                    AnalyticsElement(sessionId, "session", extras = mapOf("start" to "true")),
                ),
            ),
        )
    },
    onHeartbeat = if (config.heartbeat) {
        suspend {
            val sessionId = context.sessionId()
            add(
                eventFactory.createEvent(
                    AnalyticsEventInput(
                        AnalyticsEventType.HEARTBEAT,
                        AnalyticsElement(sessionId, "session"),
                    ),
                ),
            )
        }
    } else {
        null
    },
    onError = { error -> logger.log("[bosca-analytics] session update failed", error) },
)
