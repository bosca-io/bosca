package bosca.calendar.graphql

import bosca.calendar.repository.SyntheticEvent
import bosca.serialization.UUID
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyntheticEventControllerTest {

    private val controller = SyntheticEventController()

    private fun at(hour: Int) = OffsetDateTime.of(2026, 4, 15, hour, 0, 0, 0, ZoneOffset.UTC)

    private fun event(completed: Boolean = false) = SyntheticEvent(
        id = UUID.random(),
        title = "Test Event",
        description = "desc",
        startsAt = at(9),
        endsAt = at(10),
        completed = completed,
    )

    @Test
    fun `completed returns false for pending events`() {
        val wrapper = SyntheticEventAndSource(event(completed = false), SyntheticEventSource.SCHEDULED_PUBLISH)
        assertFalse(controller.completed(wrapper))
    }

    @Test
    fun `completed returns true for finished events`() {
        val wrapper = SyntheticEventAndSource(event(completed = true), SyntheticEventSource.SCHEDULED_PUBLISH)
        assertTrue(controller.completed(wrapper))
    }

    @Test
    fun `all scalar fields delegate to the event`() {
        val e = event()
        val wrapper = SyntheticEventAndSource(e, SyntheticEventSource.CAMPAIGN)
        assertEquals(e.id, controller.id(wrapper))
        assertEquals(e.title, controller.title(wrapper))
        assertEquals(e.description, controller.description(wrapper))
        assertEquals(e.startsAt, controller.startsAt(wrapper))
        assertEquals(e.endsAt, controller.endsAt(wrapper))
        assertEquals(SyntheticEventSource.CAMPAIGN, controller.source(wrapper))
    }

    @Test
    fun `completed defaults to false when not specified`() {
        val defaultEvent = SyntheticEvent(
            id = UUID.random(),
            title = "t",
            startsAt = at(9),
            endsAt = at(10),
        )
        assertFalse(defaultEvent.completed)
    }
}
