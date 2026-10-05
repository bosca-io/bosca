package bosca.segmentation.service

import bosca.communications.model.MessageChannel
import bosca.communications.model.MessageContentType
import bosca.segmentation.model.Campaign
import bosca.segmentation.model.EmailCampaignContent
import bosca.segmentation.model.NotificationChannel
import bosca.segmentation.model.PushCampaignContent
import bosca.segmentation.model.PushCampaignAction
import bosca.serialization.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CampaignMessageBuilderTest {

    private val json = Json
    private val builder = CampaignMessageBuilder(json)
    private val recipients = listOf(UUID.random())

    @Test
    fun `BML email campaign builds send-time template reference`() {
        val payload = JsonObject(mapOf("courseName" to JsonPrimitive("Exploring Truth")))
        val campaign = emailCampaign(
            EmailCampaignContent(
                project = "product-emails",
                templateKey = "weekly-newsletter",
                payload = payload
            )
        )

        val message = builder.buildCampaignMessage(campaign, recipients)!!

        assertEquals(listOf(MessageChannel.EMAIL), message.channels)
        assertEquals("", message.subject)
        assertEquals(emptyList(), message.content)
        assertEquals("product-emails", message.bmlTemplate?.project)
        assertEquals("weekly-newsletter", message.bmlTemplate?.templateKey)
        assertEquals(payload, message.bmlTemplate?.payload)
    }

    @Test
    fun `legacy email campaign keeps multipart content`() {
        val campaign = emailCampaign(
            EmailCampaignContent(
                subject = "Weekly Newsletter",
                textBody = "Plain text version",
                htmlBody = "<h1>HTML version</h1>"
            )
        )

        val message = builder.buildCampaignMessage(campaign, recipients)!!

        assertEquals("Weekly Newsletter", message.subject)
        assertEquals(listOf(MessageContentType.TEXT, MessageContentType.HTML), message.content.map { it.type })
        assertEquals(listOf("Plain text version", "<h1>HTML version</h1>"), message.content.map { it.content })
        assertNull(message.bmlTemplate)
    }

    @Test
    fun `legacy email campaign without bodies supplies empty text content`() {
        val campaign = emailCampaign(EmailCampaignContent(subject = "Subject"))

        val message = builder.buildCampaignMessage(campaign, recipients)!!

        assertEquals(1, message.content.size)
        assertEquals(MessageContentType.TEXT, message.content.single().type)
        assertEquals("", message.content.single().content)
    }

    @Test
    fun `email campaign rejects incomplete BML template reference`() {
        val campaign = emailCampaign(EmailCampaignContent(project = "product-emails"))

        assertFailsWith<IllegalStateException> {
            builder.buildCampaignMessage(campaign, recipients)
        }
    }

    @Test
    fun `email campaign rejects template without BML project`() {
        val campaign = emailCampaign(EmailCampaignContent(templateKey = "newsletter"))

        assertFailsWith<IllegalStateException> {
            builder.buildCampaignMessage(campaign, recipients)
        }
    }

    @Test
    fun `legacy email campaign requires subject`() {
        val campaign = emailCampaign(EmailCampaignContent(textBody = "Body"))

        assertFailsWith<IllegalStateException> {
            builder.buildCampaignMessage(campaign, recipients)
        }
    }

    @Test
    fun `email campaign requires content`() {
        val campaign = Campaign(name = "Email Campaign", channel = NotificationChannel.EMAIL)

        assertFailsWith<IllegalStateException> {
            builder.buildCampaignMessage(campaign, recipients)
        }
    }

    @Test
    fun `new email campaign validation requires BML template reference`() {
        val legacyContent = json.encodeToJsonElement(
            EmailCampaignContent.serializer(),
            EmailCampaignContent(subject = "Legacy", textBody = "Body")
        )

        assertFailsWith<IllegalArgumentException> {
            builder.validateEmailCampaignContent(legacyContent)
        }
    }

    @Test
    fun `new email campaign validation requires template key after project`() {
        val content = json.encodeToJsonElement(
            EmailCampaignContent.serializer(),
            EmailCampaignContent(project = "product-emails")
        )

        assertFailsWith<IllegalArgumentException> {
            builder.validateEmailCampaignContent(content)
        }
    }

    @Test
    fun `new email campaign validation requires content`() {
        assertFailsWith<IllegalStateException> {
            builder.validateEmailCampaignContent(null)
        }
    }

    @Test
    fun `new email campaign validation accepts complete BML template reference`() {
        val content = json.encodeToJsonElement(
            EmailCampaignContent.serializer(),
            EmailCampaignContent(project = "product-emails", templateKey = "newsletter")
        )

        builder.validateEmailCampaignContent(content)
    }

    @Test
    fun `push campaign maps content and delivery options`() {
        val content = PushCampaignContent(
            title = "Update",
            body = "A new update is ready",
            defaultAction = PushCampaignAction("open", "Open", "https://example.com/updates"),
            priority = "high",
            badge = 2,
            data = mapOf("route" to "/updates")
        )
        val campaign = Campaign(
            name = "Push Update",
            channel = NotificationChannel.PUSH,
            content = json.encodeToJsonElement(PushCampaignContent.serializer(), content)
        )

        val message = builder.buildCampaignMessage(campaign, recipients)!!

        assertEquals(listOf(MessageChannel.PUSH), message.channels)
        assertEquals("Update", message.subject)
        assertEquals("A new update is ready", message.content.single().content)
        assertEquals("high", message.pushOptions?.priority)
        assertEquals("https://example.com/updates", message.pushOptions?.defaultAction?.url)
        assertEquals(2, message.pushOptions?.badge)
        assertEquals(mapOf("route" to "/updates"), message.pushOptions?.data)
    }

    @Test
    fun `push campaign without options omits push options`() {
        val content = PushCampaignContent(title = "Update")
        val campaign = Campaign(
            name = "Push Update",
            channel = NotificationChannel.PUSH,
            content = json.encodeToJsonElement(PushCampaignContent.serializer(), content)
        )

        val message = builder.buildCampaignMessage(campaign, recipients)!!

        assertNull(message.pushOptions)
        assertEquals("", message.content.single().content)
    }

    @Test
    fun `push campaign requires content`() {
        val campaign = Campaign(name = "Push Update", channel = NotificationChannel.PUSH)

        assertFailsWith<IllegalStateException> {
            builder.buildCampaignMessage(campaign, recipients)
        }
    }

    @Test
    fun `push campaign requires title`() {
        val campaign = Campaign(
            name = "Push Update",
            channel = NotificationChannel.PUSH,
            content = json.encodeToJsonElement(PushCampaignContent.serializer(), PushCampaignContent(body = "Body"))
        )

        assertFailsWith<IllegalStateException> {
            builder.buildCampaignMessage(campaign, recipients)
        }
    }

    @Test
    fun `banner campaign does not build a communications message`() {
        val campaign = Campaign(name = "Banner", channel = NotificationChannel.BANNER)

        assertNull(builder.buildCampaignMessage(campaign, recipients))
    }

    private fun emailCampaign(content: EmailCampaignContent): Campaign = Campaign(
        name = "Email Campaign",
        channel = NotificationChannel.EMAIL,
        content = json.encodeToJsonElement(EmailCampaignContent.serializer(), content)
    )
}
