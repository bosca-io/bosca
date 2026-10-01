package bosca.communications.graphql

import bosca.communications.model.DeliveryChannel
import bosca.communications.model.NotificationPreferenceMapping
import bosca.communications.service.BmlMessageRegistryService
import bosca.communications.service.BmlMessageTemplateRendererService
import bosca.communications.service.DeliveryTrackingService
import bosca.communications.service.NotificationPreferenceMappingService
import bosca.communications.service.NotificationPreferenceService
import bosca.communications.service.NotificationTypeService
import bosca.profile.profile.service.ProfileService
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationPreferenceMappingGraphQLTest {

    private val authentication = mockk<AuthenticationContext>()
    private val mappings = mockk<NotificationPreferenceMappingService>()
    private val groups = mockk<GroupEvaluator>(relaxed = true)
    private val mapping = NotificationPreferenceMapping(
        type = "marketing",
        channel = DeliveryChannel.EMAIL,
        provider = "hubspot",
        externalId = "42",
    )

    @Test
    fun `mapping controller exposes every schema field`() {
        val controller = NotificationPreferenceMappingController()

        assertEquals("marketing", controller.type(mapping))
        assertEquals(DeliveryChannel.EMAIL, controller.channel(mapping))
        assertEquals("hubspot", controller.provider(mapping))
        assertEquals("42", controller.externalId(mapping))
    }

    @Test
    fun `admin query authorizes and lists mappings`() = runBlocking {
        coEvery { mappings.list() } returns listOf(mapping)
        val controller = CommunicationsQueriesController(
            json = Json,
            bmlMessageRegistry = mockk<BmlMessageRegistryService>(),
            bmlMessageRenderer = mockk<BmlMessageTemplateRendererService>(),
            deliveryTracking = mockk<DeliveryTrackingService>(),
            notificationPreferences = mockk<NotificationPreferenceService>(),
            notificationPreferenceMappings = mappings,
            notificationTypes = mockk<NotificationTypeService>(),
            groupEvaluator = groups,
            profileService = mockk<ProfileService>(),
        )

        assertEquals(listOf(mapping), controller.notificationPreferenceMappings(authentication))
        coVerify(exactly = 1) { groups.verifyHasAdminGroup(authentication) }
    }

    @Test
    fun `admin mutations authorize and set or delete mappings`() = runBlocking {
        coEvery {
            mappings.set("marketing", DeliveryChannel.EMAIL, "hubspot", "42")
        } returns mapping
        coEvery { mappings.delete("marketing", DeliveryChannel.EMAIL) } returnsMany listOf(true, false)
        val controller = CommunicationsMutationsController(
            bmlMessageRegistry = mockk<BmlMessageRegistryService>(),
            notificationPreferences = mockk<NotificationPreferenceService>(),
            notificationPreferenceMappings = mappings,
            notificationTypes = mockk<NotificationTypeService>(),
            groupEvaluator = groups,
            profileService = mockk<ProfileService>(),
            profilePermissionEvaluator = mockk<ProfilePermissionEvaluator>(),
        )

        assertEquals(
            mapping,
            controller.setNotificationPreferenceMapping(
                authentication,
                "marketing",
                DeliveryChannel.EMAIL,
                "hubspot",
                "42",
            ),
        )
        assertTrue(
            controller.deleteNotificationPreferenceMapping(
                authentication,
                "marketing",
                DeliveryChannel.EMAIL,
            ),
        )
        assertFalse(
            controller.deleteNotificationPreferenceMapping(
                authentication,
                "marketing",
                DeliveryChannel.EMAIL,
            ),
        )
        coVerify(exactly = 3) { groups.verifyHasAdminGroup(authentication) }
    }
}
