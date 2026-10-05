package bosca.analytics.persistence.room

import androidx.room3.Room
import bosca.analytics.api.AnalyticsElement
import bosca.analytics.api.AnalyticsEvent
import bosca.analytics.api.AnalyticsEventType
import bosca.analytics.persistence.EventQueue
import bosca.analytics.persistence.StoredAnalyticsEvent
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class RoomAnalyticsEventStoreTest {
    @Test
    fun `events join normalized contexts and survive database restart`() = runTest {
        val path = Files.createTempDirectory("bosca-events-room-test").resolve("analytics.db").toString()
        val database = buildAnalyticsDatabase(Room.databaseBuilder<AnalyticsDatabase>(name = path))
        val store = RoomAnalyticsEventStore(database, "client")
        val context = testContext("session-a", "user-a")

        store.add(StoredAnalyticsEvent("context-a", context, testEvent("event-2", 2)))
        store.add(StoredAnalyticsEvent("context-a", context, testEvent("event-1", 1)))
        store.add(
            StoredAnalyticsEvent(
                contextId = "context-b",
                context = testContext("session-b").copy(browser = null),
                event = AnalyticsEvent(
                    clientId = "event-3",
                    type = AnalyticsEventType.IMPRESSION,
                    created = 3,
                    createdMicros = 4,
                    element = AnalyticsElement("minimal", "card"),
                ),
            ),
        )

        assertEquals(2, database.storage().readEvents("client", 2).size)
        assertEquals("context-a", database.storage().readContexts(setOf("context-a")).single().id)
        assertEquals(3, store.size())
        database.close()

        val reopened = buildAnalyticsDatabase(Room.databaseBuilder<AnalyticsDatabase>(name = path))
        val restored = RoomAnalyticsEventStore(reopened, "client").read(100)
        assertEquals(listOf("context-a", "context-b"), restored.map { it.contextId })
        assertEquals(listOf("event-1", "event-2"), restored.first().events.map { it.clientId })
        assertEquals(listOf("event-3"), restored.last().events.map { it.clientId })
        assertEquals(context, restored.first().context)
        assertEquals("/editor", restored.first().events.first().page?.path)
        assertEquals("E1", restored.first().events.first().error?.code)
        assertEquals(null, restored.last().context.browser)
        assertEquals(null, restored.last().events.single().page)
        assertEquals(null, restored.last().events.single().error)
        reopened.close()
    }

    @Test
    fun `queue removal deletes successful events and orphaned contexts`() = runTest {
        val database = openTestDatabase("bosca-queue-room-test")
        val queue = EventQueue(RoomAnalyticsEventStore(database, "client"), 100)
        queue.add("context-a", testContext("session-a"), testEvent("event-1", 1))
        queue.add("context-b", testContext("session-b"), testEvent("event-2", 2))

        val checkout = requireNotNull(queue.get())
        checkout.finish(checkout.groups.single { it.contextId == "context-a" })
        checkout.close()

        assertEquals(1, queue.size())
        assertEquals(listOf("context-b"), database.storage().readContexts(setOf("context-a", "context-b")).map { it.id })
        val remaining = requireNotNull(queue.get())
        remaining.finish(remaining.groups.single())
        remaining.close()
        assertEquals(0, queue.size())
        assertEquals(emptyList(), database.storage().readContexts(setOf("context-a", "context-b")))
        database.close()
    }

    @Test
    fun `event namespaces remain isolated and empty removals are no-ops`() = runTest {
        val database = openTestDatabase("bosca-namespace-room-test")
        val first = RoomAnalyticsEventStore(database, "first")
        val second = RoomAnalyticsEventStore(database, "second")
        first.add(StoredAnalyticsEvent("context-a", testContext("session-a"), testEvent("first-event", 1)))
        second.add(StoredAnalyticsEvent("context-b", testContext("session-b"), testEvent("second-event", 2)))

        first.remove(emptySet())
        first.remove(setOf("first-event"))

        assertEquals(emptyList(), first.read(100))
        assertEquals(listOf("second-event"), second.read(100).single().events.map { it.clientId })
        database.close()
    }

    @Test
    fun `room reads only the requested event batch even when one context has more`() = runTest {
        val database = openTestDatabase("bosca-bounded-room-test")
        val store = RoomAnalyticsEventStore(database, "client")
        val context = testContext("session")
        repeat(10) { index ->
            store.add(
                StoredAnalyticsEvent(
                    "context",
                    context,
                    testEvent("event-$index", index.toLong()),
                ),
            )
        }

        val batch = store.read(3)

        assertEquals(1, batch.size)
        assertEquals(listOf("event-0", "event-1", "event-2"), batch.single().events.map { it.clientId })
        database.close()
    }

    @Test
    fun `room rejects a nonpositive event limit`() = runTest {
        val database = openTestDatabase("bosca-limit-room-test")
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            RoomAnalyticsEventStore(database, "client").read(0)
        }
        database.close()
    }
}
