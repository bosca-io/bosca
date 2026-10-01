package bosca.core.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PushModelsTest {

    @Test
    fun `standalone Android image and badge are decoded for native presentation`() {
        val message = PushMessage(
            id = "1",
            title = "Title",
            body = "Body",
            data = mapOf(
                ANDROID_NOTIFICATION_IMAGE_KEY to "https://cdn.example/image.jpg",
                ANDROID_NOTIFICATION_BADGE_KEY to "7",
            ),
        )

        assertEquals("https://cdn.example/image.jpg", message.presentationImage()?.url)
        assertEquals(7, message.presentationBadge())
    }

    @Test
    fun `action envelope decodes default and secondary actions`() {
        val actions = decodePushNotificationActionSet(
            """{"defaultAction":{"id":"open","label":"Open","url":"https://example.com"},"actions":[{"id":"dismiss","label":"Dismiss","destructive":true}]}""",
        )

        assertEquals("https://example.com", actions?.defaultAction?.url)
        assertEquals("dismiss", actions?.actions?.single()?.id)
        assertEquals(true, actions?.actions?.single()?.destructive)
    }

    @Test
    fun `malformed action envelope is ignored`() {
        assertNull(decodePushNotificationActionSet("not-json"))
    }

    @Test
    fun `ambiguous action envelope is ignored`() {
        assertNull(
            decodePushNotificationActionSet(
                """{"defaultAction":{"id":"open"},"actions":[{"id":"open"}]}""",
            ),
        )
    }

    @Test
    fun `rich envelope decodes exactly one current conversation message`() {
        val message = PushMessage(
            id = "provider-id",
            title = "Planning",
            body = "Ada sent a message",
            data = mapOf(
                RICH_PUSH_CONTENT_KEY to """{"attachments":[{"url":"https://cdn.example/image.jpg"}],"conversation":{"id":"planning","title":"Planning","messageId":"42","senderId":"ada","senderName":"Ada","body":"Project update","sentAtEpochMilliseconds":1234,"groupConversation":true}}""",
            ),
        )

        val rich = message.richContent()
        assertEquals("42", rich?.conversation?.messageId)
        assertEquals("Project update", rich?.conversation?.body)
        assertEquals(true, rich?.conversation?.groupConversation)
        assertEquals("https://cdn.example/image.jpg", rich?.attachments?.single()?.url)
    }

    @Test
    fun `rich envelope with malformed conversation identity is ignored`() {
        val message = PushMessage(
            id = null,
            title = null,
            body = null,
            data = mapOf(
                RICH_PUSH_CONTENT_KEY to """{"conversation":{"id":"","messageId":"","body":"x"}}""",
            ),
        )

        assertNull(message.richContent())
    }
}
