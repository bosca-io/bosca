package bosca.communications.graphql

import bosca.communications.model.DeliveryChannel
import bosca.communications.model.DeliveryStatus
import bosca.communications.model.DeliveryStatusType
import bosca.communications.model.BmlMessageTemplateRender
import bosca.graphql.Batch
import bosca.profile.attribute.model.ProfileAttribute
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class DeliveryStatusControllerTest {

    @Test
    fun `scalar delivery fields are exposed`() {
        val profiles = mockk<ProfileService>()
        val controller = DeliveryStatusController(profiles)
        val created = OffsetDateTime.parse("2026-07-29T12:00:00Z")
        val updated = OffsetDateTime.parse("2026-07-29T12:05:00Z")
        val template = BmlMessageTemplateRender(
            project = "bosca-messages",
            templateKey = "welcome",
            version = "20260804-v1",
            parameters = buildJsonObject { put("name", "Ada") },
        )
        val status = DeliveryStatus(
            messageId = UUID.random(),
            recipientId = UUID.random(),
            channel = DeliveryChannel.PUSH,
            status = DeliveryStatusType.CLICKED,
            attempts = 3,
            lastAttemptAt = created,
            deliveredAt = created,
            bouncedAt = updated,
            openedAt = updated,
            clickedAt = updated,
            errorCode = "provider-code",
            errorMessage = "provider message",
            bmlTemplate = template,
            createdAt = created,
            updatedAt = updated,
        )

        assertEquals(status.messageId, controller.messageId(status))
        assertEquals(status.recipientId, controller.recipientId(status))
        assertEquals(DeliveryChannel.PUSH, controller.channel(status))
        assertEquals(status.status, controller.status(status))
        assertEquals(3, controller.attempts(status))
        assertEquals(created, controller.lastAttemptAt(status))
        assertEquals(created, controller.deliveredAt(status))
        assertEquals(updated, controller.bouncedAt(status))
        assertEquals(updated, controller.openedAt(status))
        assertEquals(updated, controller.clickedAt(status))
        assertEquals("provider-code", controller.errorCode(status))
        assertEquals("provider message", controller.errorMessage(status))
        assertEquals(template, controller.bmlTemplate(status))
        assertEquals(created, controller.createdAt(status))
        assertEquals(updated, controller.updatedAt(status))

        val templateController = BmlMessageTemplateRenderController()
        assertEquals("bosca-messages", templateController.project(template))
        assertEquals("welcome", templateController.templateKey(template))
        assertEquals("20260804-v1", templateController.version(template))
        assertEquals(template.parameters, templateController.parameters(template))
    }

    @Test
    fun `recipient identity fields are resolved in batches`() = runBlocking {
        val recipientId = UUID.random()
        val profiles = mockk<ProfileService>()
        coEvery { profiles.getAllByIds(listOf(recipientId)) } returns listOf(
            Profile(
                id = recipientId,
                type = ProfileType.GENERIC,
                name = "Ada Lovelace",
                visibility = ProfileVisibility.USER,
            ),
        )
        coEvery { profiles.addAttributesToBatch(any()) } coAnswers {
            firstArg<Batch<UUID, List<ProfileAttribute>>>().setData(
                recipientId,
                listOf(
                    ProfileAttribute(
                        profile = recipientId,
                        typeId = "bosca.profiles.email",
                        visibility = ProfileVisibility.USER,
                        confidence = 100,
                        priority = 0,
                        source = "test",
                        attributes = buildJsonObject { put("email", "ada@example.com") },
                    ),
                ),
            )
        }
        val controller = DeliveryStatusController(profiles)
        val names = Batch<UUID, String>(listOf(recipientId))
        val emails = Batch<UUID, String>(listOf(recipientId))

        controller.recipientName(names)
        controller.recipientEmail(emails)

        assertEquals(listOf("Ada Lovelace"), names.getResults())
        assertEquals(listOf("ada@example.com"), emails.getResults())
    }
}
