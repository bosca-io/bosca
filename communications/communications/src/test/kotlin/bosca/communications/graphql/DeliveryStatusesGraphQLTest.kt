package bosca.communications.graphql

import bosca.communications.model.DeliveryStatus
import bosca.communications.model.DeliveryStatusType
import bosca.communications.service.BmlMessageRegistryService
import bosca.communications.service.BmlMessageTemplateRendererService
import bosca.communications.service.DeliveryTrackingService
import bosca.communications.service.NotificationPreferenceMappingService
import bosca.communications.service.NotificationPreferenceService
import bosca.communications.service.NotificationTypeService
import bosca.graphql.Batch
import bosca.profile.attribute.model.ProfileAttribute
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class DeliveryStatusesGraphQLTest {

    @Test
    fun `admin query returns enriched recent delivery statuses and total`() = runBlocking {
        val authentication = mockk<AuthenticationContext>()
        val groups = mockk<GroupEvaluator>(relaxed = true)
        val tracking = mockk<DeliveryTrackingService>()
        val profiles = mockk<ProfileService>()
        val recipientId = UUID.random()
        val delivery = DeliveryStatus(
            messageId = UUID.random(),
            recipientId = recipientId,
            status = DeliveryStatusType.SENT,
            attempts = 1,
        )
        val profile = Profile(
            id = recipientId,
            type = ProfileType.GENERIC,
            name = "Ada Lovelace",
            visibility = ProfileVisibility.USER,
        )
        val email = ProfileAttribute(
            profile = recipientId,
            typeId = "bosca.profiles.email",
            visibility = ProfileVisibility.USER,
            confidence = 100,
            priority = 0,
            source = "test",
            attributes = buildJsonObject { put("email", "ada@example.com") },
        )
        coEvery { tracking.getStatuses(25, 25) } returns listOf(delivery)
        coEvery { tracking.countStatuses() } returns 51
        coEvery { profiles.getAllByIds(listOf(recipientId)) } returns listOf(profile)
        coEvery { profiles.addAttributesToBatch(any()) } coAnswers {
            firstArg<Batch<UUID, List<ProfileAttribute>>>().setData(recipientId, listOf(email))
        }
        val controller = CommunicationsQueriesController(
            json = Json,
            bmlMessageRegistry = mockk<BmlMessageRegistryService>(),
            bmlMessageRenderer = mockk<BmlMessageTemplateRendererService>(),
            deliveryTracking = tracking,
            notificationPreferences = mockk<NotificationPreferenceService>(),
            notificationPreferenceMappings = mockk<NotificationPreferenceMappingService>(),
            notificationTypes = mockk<NotificationTypeService>(),
            groupEvaluator = groups,
            profileService = profiles,
        )

        val result = controller.deliveryStatuses(authentication, offset = 25, limit = 25)

        assertEquals(51, result.total)
        assertEquals(delivery, result.statuses.single().delivery)
        assertEquals("Ada Lovelace", result.statuses.single().recipientName)
        assertEquals("ada@example.com", result.statuses.single().recipientEmail)
        coVerify(exactly = 1) { groups.verifyHasAdminGroup(authentication) }

        val statusesController = DeliveryStatusesController()
        val recipientController = RecipientDeliveryStatusController()
        val recipientStatus = result.statuses.single()
        assertEquals(result.statuses, statusesController.statuses(result))
        assertEquals(51, statusesController.total(result))
        assertEquals(delivery, recipientController.delivery(recipientStatus))
        assertEquals("Ada Lovelace", recipientController.recipientName(recipientStatus))
        assertEquals("ada@example.com", recipientController.recipientEmail(recipientStatus))
    }
}
