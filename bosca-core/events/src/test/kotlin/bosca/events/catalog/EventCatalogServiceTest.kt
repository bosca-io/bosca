package bosca.events.catalog

import bosca.di.provides
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventCatalogServiceTest {

    private fun registrar(vararg events: EventDescriptor) = object : EventCatalogRegistrar {
        override val events: List<EventDescriptor> = events.toList()
    }

    private fun descriptor(fqdn: String) =
        EventDescriptor(fqdn, fqdn.substringAfterLast('.'), "", null, emptyList(), emptyList())

    @Test
    fun `list aggregates registrars from every module, sorted by fqdn`() = runTest {
        // Each module contributes one named EventCatalogRegistrar provider.
        provides<EventCatalogRegistrar>(name = "svc-modBeta", singleton = true) {
            registrar(descriptor("z.beta.Two"), descriptor("z.beta.One"))
        }
        provides<EventCatalogRegistrar>(name = "svc-modAlpha", singleton = true) {
            registrar(descriptor("z.alpha.Zero"))
        }

        val catalog = EventCatalogServiceImpl().list().filter { it.fqdn.startsWith("z.") }

        assertEquals(listOf("z.alpha.Zero", "z.beta.One", "z.beta.Two"), catalog.map { it.fqdn })
    }

    @Test
    fun `list de-duplicates the same event contributed by more than one registrar`() = runTest {
        provides<EventCatalogRegistrar>(name = "svc-dupA", singleton = true) {
            registrar(descriptor("dup.Shared"))
        }
        provides<EventCatalogRegistrar>(name = "svc-dupB", singleton = true) {
            registrar(descriptor("dup.Shared"))
        }

        val matches = EventCatalogServiceImpl().list().filter { it.fqdn == "dup.Shared" }

        assertEquals(1, matches.size)
    }

    @Test
    fun `get returns the matching descriptor and null for unknown fqdn`() = runTest {
        provides<EventCatalogRegistrar>(name = "svc-get", singleton = true) {
            registrar(descriptor("get.Known"))
        }

        val service = EventCatalogServiceImpl()

        assertEquals("get.Known", service.get("get.Known")?.fqdn)
        assertNull(service.get("get.Unknown"))
    }

    @Test
    fun `list carries through display name, channel, jobs and fields`() = runTest {
        val full = EventDescriptor(
            fqdn = "rich.Event",
            displayName = "Rich Event",
            description = "fires on rich things",
            pubsubChannel = "rich.channel",
            jobNames = listOf("RichJob"),
            fields = listOf(EventField("id", "String")),
        )
        provides<EventCatalogRegistrar>(name = "svc-rich", singleton = true) { registrar(full) }

        val found = EventCatalogServiceImpl().get("rich.Event")

        assertTrue(found != null)
        assertEquals("Rich Event", found.displayName)
        assertEquals("rich.channel", found.pubsubChannel)
        assertEquals(listOf("RichJob"), found.jobNames)
        assertEquals(listOf(EventField("id", "String")), found.fields)
    }
}
