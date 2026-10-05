package bosca.analytics.transform.iceberg

import bosca.analytics.iceberg.EventSchema
import bosca.analytics.model.Device
import bosca.analytics.model.Browser
import bosca.analytics.model.Content
import bosca.analytics.model.Element
import bosca.analytics.model.ErrorInfo
import bosca.analytics.model.Event
import bosca.analytics.model.EventContext
import bosca.analytics.model.EventType
import bosca.analytics.model.Events
import bosca.analytics.model.Geo
import bosca.analytics.model.Page
import kotlinx.coroutines.test.runTest
import org.apache.iceberg.data.Record
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IcebergEventsToRecordTransformTest {

    private val transform = IcebergEventsToRecordTransform(EventSchema)

    private fun createDevice() = Device(
        installationId = "inst-1",
        manufacturer = "Test",
        model = "Model",
        platform = "Web",
        primaryLocale = "en-US",
        systemName = "TestOS",
        timezone = "UTC",
        type = "desktop",
        version = "1.0"
    )

    private fun createEvents(vararg events: Event) = Events(
        context = EventContext(
            appId = "test-app",
            appVersion = "1.0.0",
            device = createDevice(),
            sessionId = "session-1"
        ),
        events = events.toList(),
        sent = 1700000000000L,
        sentMicros = 1700000000000000L,
        received = 1700000001000L,
        receivedMicros = 1700000001000000L
    )

    @Test
    fun `transform error event with full ErrorInfo`() = runTest {
        val errorInfo = ErrorInfo(
            message = "Uncaught ReferenceError: foo is not defined",
            type = "ReferenceError",
            stackTrace = "at bar(app.js:10)\nat main(app.js:1)",
            fatal = true,
            code = "ERR_REF"
        )
        val event = Event(
            created = 1700000000000L,
            type = EventType.Error,
            error = errorInfo
        )
        val events = createEvents(event)
        val records = transform.transform(events)

        assertEquals(1, records.size)
        val record = records.first()

        assertEquals("Error", record.getField("type"))
        val errorRecord = record.getField("error") as Record
        assertNotNull(errorRecord)
        assertEquals("Uncaught ReferenceError: foo is not defined", errorRecord.getField("message"))
        assertEquals("ReferenceError", errorRecord.getField("type"))
        assertEquals("at bar(app.js:10)\nat main(app.js:1)", errorRecord.getField("stack_trace"))
        assertEquals(true, errorRecord.getField("fatal"))
        assertEquals("ERR_REF", errorRecord.getField("code"))
    }

    @Test
    fun `transform error event with minimal ErrorInfo`() = runTest {
        val errorInfo = ErrorInfo(message = "something broke")
        val event = Event(
            created = 1700000000000L,
            type = EventType.Error,
            error = errorInfo
        )
        val events = createEvents(event)
        val records = transform.transform(events)

        assertEquals(1, records.size)
        val errorRecord = records.first().getField("error") as Record
        assertEquals("something broke", errorRecord.getField("message"))
        assertNull(errorRecord.getField("type"))
        assertNull(errorRecord.getField("stack_trace"))
        assertEquals(false, errorRecord.getField("fatal"))
        assertNull(errorRecord.getField("code"))
    }

    @Test
    fun `transform event without error sets null`() = runTest {
        val event = Event(
            created = 1700000000000L,
            type = EventType.Impression,
            element = Element(id = "el-1", type = "button")
        )
        val events = createEvents(event)
        val records = transform.transform(events)

        assertEquals(1, records.size)
        assertNull(records.first().getField("error"))
    }

    @Test
    fun `transform multiple events preserves error on correct events`() = runTest {
        val errorEvent = Event(
            created = 1700000000000L,
            type = EventType.Error,
            error = ErrorInfo(message = "error occurred", fatal = true)
        )
        val impressionEvent = Event(
            created = 1700000001000L,
            type = EventType.Impression,
            element = Element(id = "img-1", type = "image")
        )
        val events = createEvents(errorEvent, impressionEvent)
        val records = transform.transform(events)

        assertEquals(2, records.size)

        val errorRecord = records[0].getField("error") as Record
        assertNotNull(errorRecord)
        assertEquals("error occurred", errorRecord.getField("message"))
        assertEquals(true, errorRecord.getField("fatal"))

        assertNull(records[1].getField("error"))
    }

    @Test
    fun `transform error event populates standard fields`() = runTest {
        val event = Event(
            created = 1700000000000L,
            createdMicros = 1700000000000000L,
            type = EventType.Error,
            clientId = "client-xyz",
            error = ErrorInfo(message = "test")
        )
        val events = createEvents(event)
        val records = transform.transform(events)

        val record = records.first()
        assertNotNull(record.getField("id"))
        assertEquals("client-xyz", record.getField("client_id"))
        assertEquals("Error", record.getField("type"))
        assertNotNull(record.getField("sent"))
        assertNotNull(record.getField("received"))
        assertNotNull(record.getField("created"))
    }

    @Test
    fun `transform error event with element and error`() = runTest {
        val event = Event(
            created = 1700000000000L,
            type = EventType.Error,
            element = Element(id = "form-1", type = "form"),
            error = ErrorInfo(
                message = "Form validation failed",
                type = "ValidationError",
                fatal = false
            )
        )
        val events = createEvents(event)
        val records = transform.transform(events)

        val record = records.first()
        val elementRecord = record.getField("element") as Record
        assertNotNull(elementRecord)
        assertEquals("form-1", elementRecord.getField("id"))

        val errorRecord = record.getField("error") as Record
        assertNotNull(errorRecord)
        assertEquals("Form validation failed", errorRecord.getField("message"))
        assertEquals("ValidationError", errorRecord.getField("type"))
        assertEquals(false, errorRecord.getField("fatal"))
    }

    @Test
    fun `transform writes top-level page struct from Event page snapshot`() = runTest {
        val event = Event(
            created = 1700000000000L,
            type = EventType.Interaction,
            element = Element(id = "btn", type = "click"),
            page = Page(
                path = "/checkout",
                url = "https://example.test/checkout?ref=email",
                title = "Checkout · Example",
            ),
        )
        val records = transform.transform(createEvents(event))
        val pageRecord = records.first().getField("page") as Record?
        assertNotNull(pageRecord, "page field must be written when Event.page is non-null")
        assertEquals("/checkout", pageRecord.getField("path"))
        assertEquals("https://example.test/checkout?ref=email", pageRecord.getField("url"))
        assertEquals("Checkout · Example", pageRecord.getField("title"))
    }

    @Test
    fun `transform writes null page struct when Event has no page snapshot`() = runTest {
        // Legacy clients (and non-browser SDKs) emit events without `page`.
        // The transform must accept that and persist a null nested struct
        // rather than fabricating empty strings.
        val event = Event(
            created = 1700000000000L,
            type = EventType.Interaction,
            element = Element(id = "btn", type = "click"),
            page = null,
        )
        val records = transform.transform(createEvents(event))
        assertNull(records.first().getField("page"))
    }

    @Test
    fun `transform preserves error with special characters in message`() = runTest {
        val event = Event(
            created = 1700000000000L,
            type = EventType.Error,
            error = ErrorInfo(
                message = "Error: \"unexpected token\" at <script>",
                stackTrace = "line 1: 'foo' != \"bar\"\n\tat eval()"
            )
        )
        val events = createEvents(event)
        val records = transform.transform(events)

        val errorRecord = records.first().getField("error") as Record
        assertEquals("Error: \"unexpected token\" at <script>", errorRecord.getField("message"))
        assertTrue(errorRecord.getField("stack_trace").toString().contains("'foo' != \"bar\""))
    }

    @Test
    fun `transform writes complete browser geo content and nullable envelope variants`() = runTest {
        val context = EventContext(
            appId = "app",
            appVersion = "2",
            browser = Browser("Bosca Browser"),
            device = createDevice(),
            geo = Geo(
                city = "Chicago",
                country = "US",
                continent = "NA",
                longitude = -87.6,
                latitude = 41.8,
                region = "Illinois",
                regionCode = "IL",
                postalCode = "60601",
                timezone = "America/Chicago",
            ),
            sessionId = "session",
            userId = "user",
        )
        val element = Element(
            id = null,
            type = null,
            content = listOf(Content("content", "article", 4, 0.75)),
            extras = JsonObject(mapOf("flag" to JsonPrimitive(true))),
        )
        val item = Events(
            context = context,
            events = listOf(Event(created = 1, type = EventType.Impression, element = element)),
            sent = 2,
            sentMicros = 3,
            received = null,
            receivedMicros = null,
        )

        val record = transform.transform(item).single()
        val contextRecord = record.getField("context") as Record
        assertEquals("Bosca Browser", (contextRecord.getField("browser") as Record).getField("agent"))
        assertEquals("Chicago", (contextRecord.getField("geo") as Record).getField("city"))
        val elementRecord = record.getField("element") as Record
        assertEquals("null", elementRecord.getField("id"))
        assertEquals("null", elementRecord.getField("type"))
        assertTrue(elementRecord.getField("extras").toString().contains("flag"))
        assertEquals(1, (elementRecord.getField("content") as List<*>).size)
        assertNotNull(record.getField("received"))

        val absent = transform.transform(
            item.copy(
                context = null,
                received = 10,
                events = listOf(Event(created = 1, type = EventType.Session, element = Element(content = null, extras = null))),
            ),
        ).single()
        assertNull(absent.getField("context"))
        assertEquals(emptyList<Record>(), (absent.getField("element") as Record).getField("content"))
        assertEquals("null", (absent.getField("element") as Record).getField("extras"))
    }
}
