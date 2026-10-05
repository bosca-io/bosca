package bosca.segmentation.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class CampaignContentTest {

    @Test
    fun `PushCampaignContent has all null defaults`() {
        val content = PushCampaignContent()
        assertNull(content.title)
        assertNull(content.body)
        assertNull(content.imageId)
        assertNull(content.imageName)
        assertNull(content.defaultAction)
        assertTrue(content.actions.isEmpty())
        assertNull(content.priority)
        assertNull(content.sound)
        assertNull(content.badge)
        assertNull(content.ttl)
        assertNull(content.androidChannelId)
        assertNull(content.androidTag)
        assertNull(content.collapseKey)
        assertNull(content.threadId)
        assertNull(content.category)
        assertNull(content.interruptionLevel)
        assertNull(content.relevanceScore)
        assertNull(content.mutableContent)
        assertNull(content.contentAvailable)
        assertNull(content.data)
    }

    @Test
    fun `PushCampaignContent with all fields`() {
        val content = PushCampaignContent(
            title = "New Update",
            body = "Check out the latest changes",
            imageId = "img-123",
            imageName = "banner.png",
            defaultAction = PushCampaignAction("open", "Open", "https://example.com"),
            actions = listOf(PushCampaignAction("dismiss", "Dismiss")),
            priority = "high",
            sound = "default",
            badge = 1,
            ttl = 86400,
            androidChannelId = "updates",
            androidTag = "update-1",
            collapseKey = "updates",
            threadId = "thread-1",
            category = "NEWS",
            interruptionLevel = "active",
            relevanceScore = 0.8,
            mutableContent = true,
            contentAvailable = false,
            data = mapOf("key1" to "value1", "key2" to "value2")
        )
        assertEquals("New Update", content.title)
        assertEquals("Check out the latest changes", content.body)
        assertEquals("img-123", content.imageId)
        assertEquals("https://example.com", content.defaultAction?.url)
        assertEquals("dismiss", content.actions.single().id)
        assertEquals(1, content.badge)
        assertEquals(86400, content.ttl)
        assertEquals(0.8, content.relevanceScore)
        assertTrue(content.mutableContent!!)
        assertEquals(2, content.data?.size)
    }

    @Test
    fun `EmailCampaignContent has all null defaults`() {
        val content = EmailCampaignContent()
        assertNull(content.project)
        assertNull(content.templateKey)
        assertNull(content.payload)
        assertNull(content.subject)
        assertNull(content.textBody)
        assertNull(content.htmlBody)
    }

    @Test
    fun `EmailCampaignContent holds BML template reference and payload`() {
        val payload = JsonObject(mapOf("courseName" to JsonPrimitive("Exploring Truth")))
        val content = EmailCampaignContent(
            project = "product-emails",
            templateKey = "weekly-newsletter",
            payload = payload
        )
        assertEquals("product-emails", content.project)
        assertEquals("weekly-newsletter", content.templateKey)
        assertEquals(payload, content.payload)
    }

    @Test
    fun `EmailCampaignContent preserves legacy fields`() {
        val a = EmailCampaignContent(subject = "s", textBody = "t", htmlBody = "h")
        val b = EmailCampaignContent(subject = "s", textBody = "t", htmlBody = "h")
        assertEquals(a, b)
        assertEquals("s", a.subject)
        assertEquals("t", a.textBody)
        assertEquals("h", a.htmlBody)
    }

    @Test
    fun `PushCampaignContent equality`() {
        val a = PushCampaignContent(title = "t", body = "b")
        val b = PushCampaignContent(title = "t", body = "b")
        assertEquals(a, b)
    }
}
