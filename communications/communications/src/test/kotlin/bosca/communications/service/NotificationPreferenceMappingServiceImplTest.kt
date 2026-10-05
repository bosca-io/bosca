package bosca.communications.service

import bosca.communications.model.DeliveryChannel
import bosca.communications.model.NotificationPreferenceMapping
import bosca.communications.model.NotificationType
import bosca.communications.repository.NotificationPreferenceMappingRepository
import bosca.communications.repository.NotificationPreferenceRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationPreferenceMappingServiceImplTest {

    private val types = mockk<NotificationTypeService>()
    private val mappings = mockk<NotificationPreferenceMappingRepository>()
    private val preferences = mockk<NotificationPreferenceRepository>(relaxed = true)
    private val service = NotificationPreferenceMappingServiceImpl(types, mappings, preferences)

    @Test
    fun `list and get delegate to the mapping repository`() = runBlocking {
        val mapping = NotificationPreferenceMapping("marketing", DeliveryChannel.EMAIL, "hubspot", "42")
        coEvery { mappings.list() } returns listOf(mapping)
        coEvery { mappings.get("marketing", DeliveryChannel.EMAIL) } returns mapping

        assertEquals(listOf(mapping), service.list())
        assertEquals(mapping, service.get("marketing", DeliveryChannel.EMAIL))
    }

    @Test
    fun `set validates and replaces the mapping while clearing local rows`() = runBlocking {
        val mapping = NotificationPreferenceMapping("marketing", DeliveryChannel.EMAIL, "hubspot", "42")
        coEvery { types.get("marketing") } returns NotificationType("marketing", "Marketing")
        coEvery { mappings.upsert("marketing", DeliveryChannel.EMAIL, "hubspot", "42") } returns mapping

        assertEquals(
            mapping,
            service.set("marketing", DeliveryChannel.EMAIL, "hubspot", "42"),
        )
        coVerify(exactly = 1) {
            preferences.deleteByChannelAndType(DeliveryChannel.EMAIL, "marketing")
        }
    }

    @Test
    fun `set rejects unknown and non-optional types`() {
        coEvery { types.get("missing") } returns null
        coEvery { types.get("security") } returns NotificationType("security", "Security", optional = false)

        assertFailsWith<IllegalArgumentException> {
            runBlocking { service.set("missing", DeliveryChannel.EMAIL, "hubspot", "42") }
        }
        assertFailsWith<IllegalArgumentException> {
            runBlocking { service.set("security", DeliveryChannel.EMAIL, "hubspot", "42") }
        }
    }

    @Test
    fun `set rejects malformed provider and external identifiers`() {
        coEvery { types.get("marketing") } returns NotificationType("marketing", "Marketing")

        for (provider in listOf("", "HubSpot", "9hubspot", "hub spot")) {
            assertFailsWith<IllegalArgumentException>("provider: $provider") {
                runBlocking {
                    service.set("marketing", DeliveryChannel.EMAIL, provider, "42")
                }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            runBlocking { service.set("marketing", DeliveryChannel.EMAIL, "hubspot", " ") }
        }
        assertFailsWith<IllegalArgumentException> {
            runBlocking { service.set("marketing", DeliveryChannel.EMAIL, "hubspot", "x".repeat(256)) }
        }
    }

    @Test
    fun `delete reports whether a mapping existed`() = runBlocking {
        coEvery { mappings.delete("marketing", DeliveryChannel.EMAIL) } returnsMany listOf(1, 0)

        assertTrue(service.delete("marketing", DeliveryChannel.EMAIL))
        assertFalse(service.delete("marketing", DeliveryChannel.EMAIL))
    }
}
