package bosca.analytics.graphql

import bosca.analytics.model.ErrorGroup
import bosca.analytics.model.ErrorGroupConnection
import bosca.analytics.model.ErrorGroupStatus
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class ErrorGroupControllerTest {

    private val controller = ErrorGroupController()
    private val connectionController = ErrorGroupConnectionController()

    private val now: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)
    private val assigneeId = Uuid.random()
    private val group = ErrorGroup(
        fingerprint = "fp-1",
        appId = "app-1",
        type = "java.lang.IllegalStateException",
        message = "boom",
        fatal = true,
        status = ErrorGroupStatus.OPEN,
        assigneeId = assigneeId,
        firstSeen = now.minusHours(2),
        lastSeen = now,
        eventCount = 17L,
        sampleEventId = "client-uuid",
        sampleStack = "at com.example.Foo.bar(Foo.kt:42)",
        aiSummary = "root cause is x",
        aiSummaryAt = now.minusMinutes(5),
        created = now.minusHours(2),
        modified = now,
    )

    @Test fun `fingerprint is exposed`() = assertEquals("fp-1", controller.fingerprint(group))
    @Test fun `appId is exposed`() = assertEquals("app-1", controller.appId(group))
    @Test fun `type is exposed`() = assertEquals("java.lang.IllegalStateException", controller.type(group))
    @Test fun `message is exposed`() = assertEquals("boom", controller.message(group))
    @Test fun `fatal is exposed`() = assertEquals(true, controller.fatal(group))
    @Test fun `status is exposed`() = assertEquals(ErrorGroupStatus.OPEN, controller.status(group))
    @Test fun `assigneeId is exposed`() = assertEquals(assigneeId, controller.assigneeId(group))
    @Test fun `firstSeen is exposed`() = assertEquals(group.firstSeen, controller.firstSeen(group))
    @Test fun `lastSeen is exposed`() = assertEquals(group.lastSeen, controller.lastSeen(group))
    @Test fun `eventCount is exposed`() = assertEquals(17L, controller.eventCount(group))
    @Test fun `sampleEventId is exposed`() = assertEquals("client-uuid", controller.sampleEventId(group))
    @Test fun `sampleStack is exposed`() = assertEquals("at com.example.Foo.bar(Foo.kt:42)", controller.sampleStack(group))
    @Test fun `aiSummary is exposed`() = assertEquals("root cause is x", controller.aiSummary(group))
    @Test fun `aiSummaryAt is exposed`() = assertEquals(group.aiSummaryAt, controller.aiSummaryAt(group))
    @Test fun `created is exposed`() = assertEquals(group.created, controller.created(group))
    @Test fun `modified is exposed`() = assertEquals(group.modified, controller.modified(group))

    @Test
    fun `nullable fields return null when not set`() {
        val empty = group.copy(
            assigneeId = null, sampleEventId = null, sampleStack = null,
            aiSummary = null, aiSummaryAt = null,
        )
        assertNull(controller.assigneeId(empty))
        assertNull(controller.sampleEventId(empty))
        assertNull(controller.sampleStack(empty))
        assertNull(controller.aiSummary(empty))
        assertNull(controller.aiSummaryAt(empty))
    }

    @Test
    fun `connection edges and total are exposed`() {
        val connection = ErrorGroupConnection(edges = listOf(group, group), total = 2L)
        assertEquals(listOf(group, group), connectionController.edges(connection))
        assertEquals(2L, connectionController.total(connection))
    }
}
