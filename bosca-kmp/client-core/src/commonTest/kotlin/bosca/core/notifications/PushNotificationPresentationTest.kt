package bosca.core.notifications

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PushNotificationPresentationTest {
    private val message = PushMessage(
        id = "message-1",
        title = "Title",
        body = "Body",
        data = mapOf("channel_id" to "channel-1"),
    )

    @AfterTest
    fun resetPolicy() {
        PushNotificationPresentation.setPolicy(null)
    }

    @Test
    fun `messages are presented when no policy is installed`() {
        assertTrue(PushNotificationPresentation.shouldPresent(message))
    }

    @Test
    fun `installed policy controls native presentation`() {
        PushNotificationPresentation.setPolicy { it.data["channel_id"] != "channel-1" }

        assertFalse(PushNotificationPresentation.shouldPresent(message))
    }

    @Test
    fun `policy failures fall back to presentation`() {
        PushNotificationPresentation.setPolicy { error("broken policy") }

        assertTrue(PushNotificationPresentation.shouldPresent(message))
    }
}
