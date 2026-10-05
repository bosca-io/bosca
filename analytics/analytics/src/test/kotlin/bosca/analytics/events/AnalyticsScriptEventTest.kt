package bosca.analytics.events

import bosca.analytics.model.Event
import bosca.analytics.model.EventType
import bosca.analytics.model.Events
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AnalyticsScriptEventTest {

    private fun createEvents(vararg types: EventType): Events {
        val eventList = types.map { type ->
            Event(
                created = 1000L,
                createdMicros = 0L,
                type = type,
                element = null,
                clientId = null
            )
        }
        return Events(
            context = null,
            events = eventList,
            sent = 2000L,
            sentMicros = 0L,
            received = 3000L,
            receivedMicros = 0L
        )
    }

    @Test
    fun `AnalyticsScriptEvent stores events`() {
        val events = createEvents(EventType.Session)
        val scriptEvent = AnalyticsScriptEvent(events)
        assertEquals(events, scriptEvent.events)
    }

    @Test
    fun `AnalyticsScriptEvent implements Event interface`() {
        val events = createEvents(EventType.Interaction)
        val scriptEvent = AnalyticsScriptEvent(events)
        assertIs<bosca.events.Event>(scriptEvent)
    }

    @Test
    fun `AnalyticsScriptEvent with empty event list`() {
        val events = Events(
            context = null,
            events = emptyList(),
            sent = 0L,
            sentMicros = 0L,
            received = 0L,
            receivedMicros = 0L
        )
        val scriptEvent = AnalyticsScriptEvent(events)
        assertEquals(0, scriptEvent.events.events.size)
    }

    @Test
    fun `AnalyticsScriptEvent with multiple events`() {
        val events = createEvents(EventType.Session, EventType.Impression, EventType.Completion)
        val scriptEvent = AnalyticsScriptEvent(events)
        assertEquals(3, scriptEvent.events.events.size)
    }
}
