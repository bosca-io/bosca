package bosca.calendar.graphql

import bosca.calendar.repository.ScheduledContentEventRepository
import bosca.calendar.repository.ScheduledJobEventRepository
import bosca.calendar.repository.ScheduledPublishEventRepository
import bosca.calendar.repository.SyntheticEvent
import bosca.calendar.service.CalendarService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CalendarsControllerSyntheticEventsTest {

    private val calendarService = mockk<CalendarService>(relaxed = true)
    private val contentRepo = mockk<ScheduledContentEventRepository>(relaxed = true)
    private val jobRepo = mockk<ScheduledJobEventRepository>(relaxed = true)
    private val publishRepo = mockk<ScheduledPublishEventRepository>(relaxed = true)
    private val groupEvaluator = mockk<GroupEvaluator>(relaxed = true)
    private val auth = mockk<AuthenticationContext>(relaxed = true)

    private val controller = CalendarsController(calendarService, contentRepo, jobRepo, publishRepo, groupEvaluator)

    private val from = OffsetDateTime.of(2026, 4, 1, 0, 0, 0, 0, ZoneOffset.UTC)
    private val to = OffsetDateTime.of(2026, 5, 1, 0, 0, 0, 0, ZoneOffset.UTC)

    private fun event(completed: Boolean) = SyntheticEvent(
        id = UUID.random(),
        title = "event",
        description = "desc",
        startsAt = from.plusDays(5),
        endsAt = from.plusDays(5).plusMinutes(15),
        completed = completed,
    )

    @Test
    fun `scheduledPublishEvents returns both pending and completed events`() = runTest {
        val pending = event(completed = false)
        val done = event(completed = true)
        coEvery { publishRepo.getInRange(from, to) } returns listOf(pending, done)

        val result = controller.scheduledPublishEvents(auth, from, to)

        assertEquals(2, result.size)
        assertFalse(result[0].event.completed)
        assertTrue(result[1].event.completed)
        result.forEach { assertEquals(SyntheticEventSource.SCHEDULED_PUBLISH, it.source) }
        coVerify { groupEvaluator.verifyHasAdminGroup(auth) }
    }

    @Test
    fun `scheduledJobEvents returns both upcoming and historical runs`() = runTest {
        val upcoming = event(completed = false)
        val historical = event(completed = true)
        coEvery { jobRepo.getInRange(from, to) } returns listOf(upcoming, historical)

        val result = controller.scheduledJobEvents(auth, from, to)

        assertEquals(2, result.size)
        assertFalse(result[0].event.completed)
        assertTrue(result[1].event.completed)
        result.forEach { assertEquals(SyntheticEventSource.SCHEDULED_JOB, it.source) }
    }

    @Test
    fun `scheduledContentEvents includes completed campaigns`() = runTest {
        val active = event(completed = false)
        val ended = event(completed = true)
        coEvery { contentRepo.getInRange(from, to) } returns listOf(active, ended)

        val result = controller.scheduledContentEvents(auth, from, to)

        assertEquals(2, result.size)
        assertFalse(result[0].event.completed)
        assertTrue(result[1].event.completed)
        result.forEach { assertEquals(SyntheticEventSource.CAMPAIGN, it.source) }
    }

    @Test
    fun `scheduledPublishEvents returns empty list when no events in range`() = runTest {
        coEvery { publishRepo.getInRange(from, to) } returns emptyList()
        val result = controller.scheduledPublishEvents(auth, from, to)
        assertTrue(result.isEmpty())
    }
}
