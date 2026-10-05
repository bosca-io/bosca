package bosca.analytics.events

import bosca.analytics.model.Events
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AnalyticsEventCatalogRegistrarTest {

    private val registrar = AnalyticsEventCatalogRegistrar()

    @Test
    fun `catalogs the batch-level transform and notify events`() {
        val expected = setOf(AnalyticsEventNames.TRANSFORM, AnalyticsEventNames.NOTIFY)
        assertEquals(expected, registrar.serializers.keys)
        assertEquals(expected, registrar.events.map { it.fqdn }.toSet())
    }

    @Test
    fun `every name resolves to the AnalyticsScriptEvent serializer`() {
        for (name in AnalyticsEventNames.all) {
            assertEquals(AnalyticsScriptEvent.serializer(), registrar.serializers[name])
        }
    }

    @Test
    fun `the catalogued serializer round-trips an AnalyticsScriptEvent payload`() {
        val json = Json { ignoreUnknownKeys = true }
        val serializer = registrar.serializers[AnalyticsEventNames.NOTIFY]
        assertNotNull(serializer)
        val event = AnalyticsScriptEvent(Events(events = emptyList(), sent = 1L, sentMicros = 2L))

        @Suppress("UNCHECKED_CAST")
        val typed = serializer as kotlinx.serialization.KSerializer<Any>
        val encoded = json.encodeToJsonElement(typed, event)
        val decoded = json.decodeFromJsonElement(typed, encoded) as AnalyticsScriptEvent

        assertEquals(event.events.sent, decoded.events.sent)
    }

    @Test
    fun `descriptors carry display names and descriptions`() {
        assertTrue(registrar.events.all { it.displayName.isNotBlank() && it.description.isNotBlank() })
        assertTrue(registrar.events.all { it.pubsubChannel == null && it.jobNames.isEmpty() })
    }

    @Test
    fun `event names are the batch-level analytics names`() {
        assertEquals("analytics.transform", AnalyticsEventNames.TRANSFORM)
        assertEquals("analytics.notify", AnalyticsEventNames.NOTIFY)
        assertEquals(2, AnalyticsEventNames.all.size)
    }
}
