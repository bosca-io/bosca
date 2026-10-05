package bosca.communications.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PushOptionsTest {

    @Test
    fun `PushOptions all fields default to null`() {
        val options = PushOptions()
        assertNull(options.imageUrl)
        assertNull(options.defaultAction)
        assertTrue(options.actions.isEmpty())
        assertNull(options.priority)
        assertNull(options.sound)
        assertNull(options.badge)
        assertNull(options.ttl)
        assertNull(options.androidChannelId)
        assertNull(options.androidTag)
        assertNull(options.collapseKey)
        assertNull(options.threadId)
        assertNull(options.category)
        assertNull(options.interruptionLevel)
        assertNull(options.relevanceScore)
        assertNull(options.mutableContent)
        assertNull(options.contentAvailable)
        assertNull(options.data)
        assertNull(options.richContent)
    }

    @Test
    fun `PushOptions stores imageUrl`() {
        val options = PushOptions(imageUrl = "https://example.com/image.png")
        assertEquals("https://example.com/image.png", options.imageUrl)
    }

    @Test
    fun `PushOptions stores default and secondary actions`() {
        val options = PushOptions(
            defaultAction = PushAction("open", "Open", "https://app.example.com/item/1"),
            actions = listOf(PushAction("dismiss", "Dismiss")),
        )
        assertEquals("Open", options.defaultAction?.label)
        assertEquals("https://app.example.com/item/1", options.defaultAction?.url)
        assertEquals("dismiss", options.actions.single().id)
    }

    @Test
    fun `PushOptions stores priority`() {
        val options = PushOptions(priority = "HIGH")
        assertEquals("HIGH", options.priority)
    }

    @Test
    fun `PushOptions stores sound and badge`() {
        val options = PushOptions(sound = "default", badge = 5)
        assertEquals("default", options.sound)
        assertEquals(5, options.badge)
    }

    @Test
    fun `PushOptions stores ttl`() {
        val options = PushOptions(ttl = 3600L)
        assertEquals(3600L, options.ttl)
    }

    @Test
    fun `PushOptions stores Android-specific fields`() {
        val options = PushOptions(androidChannelId = "news", androidTag = "breaking")
        assertEquals("news", options.androidChannelId)
        assertEquals("breaking", options.androidTag)
    }

    @Test
    fun `PushOptions stores collapseKey`() {
        val options = PushOptions(collapseKey = "updates")
        assertEquals("updates", options.collapseKey)
    }

    @Test
    fun `PushOptions stores iOS-specific fields`() {
        val options = PushOptions(
            threadId = "thread-1",
            category = "MESSAGE",
            interruptionLevel = "time-sensitive",
            relevanceScore = 0.9
        )
        assertEquals("thread-1", options.threadId)
        assertEquals("MESSAGE", options.category)
        assertEquals("time-sensitive", options.interruptionLevel)
        assertEquals(0.9, options.relevanceScore)
    }

    @Test
    fun `PushOptions stores mutableContent and contentAvailable`() {
        val options = PushOptions(mutableContent = true, contentAvailable = true)
        assertEquals(true, options.mutableContent)
        assertEquals(true, options.contentAvailable)
    }

    @Test
    fun `PushOptions stores data map`() {
        val data = mapOf("key1" to "value1", "key2" to "value2")
        val options = PushOptions(data = data)
        assertEquals(data, options.data)
        assertEquals("value1", options.data!!["key1"])
    }

    @Test
    fun `PushOptions data class equality`() {
        val o1 = PushOptions(priority = "HIGH", badge = 3)
        val o2 = PushOptions(priority = "HIGH", badge = 3)
        assertEquals(o1, o2)
    }

    @Test
    fun `PushOptions data class copy`() {
        val original = PushOptions(priority = "NORMAL", sound = "default")
        val modified = original.copy(priority = "HIGH")
        assertEquals("HIGH", modified.priority)
        assertEquals("default", modified.sound)
    }

    @Test
    fun `PushOptions stores rich attachments and the current conversation message`() {
        val options = PushOptions(
            richContent = PushRichContent(
                attachments = listOf(PushAttachment(url = "https://cdn.example/image.jpg")),
                conversation = PushConversation(
                    id = "channel-1",
                    messageId = "42",
                    senderName = "Ada",
                    body = "Project update",
                ),
            ),
        )

        assertEquals("https://cdn.example/image.jpg", options.richContent?.attachments?.single()?.url)
        assertEquals("Project update", options.richContent?.conversation?.body)
    }
}
