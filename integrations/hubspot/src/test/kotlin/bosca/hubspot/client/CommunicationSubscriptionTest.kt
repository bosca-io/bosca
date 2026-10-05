package bosca.hubspot.client

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.time.OffsetDateTime as JOffsetDateTime

class CommunicationSubscriptionTest {

    @Test
    fun testIsModifiedRecentlyWithNullTimestamp() {
        val subscription = CommunicationSubscription(1, true, null)
        assertFalse(subscription.isModifiedRecently)
    }

    @Test
    fun testIsModifiedRecentlyWithRecentTimestamp() {
        val subscription = CommunicationSubscription(1, true, JOffsetDateTime.now())
        assertTrue(subscription.isModifiedRecently)
    }

    @Test
    fun testIsModifiedRecentlyWithOldTimestamp() {
        val subscription = CommunicationSubscription(1, true, JOffsetDateTime.now().minusDays(3))
        assertFalse(subscription.isModifiedRecently)
    }

    @Test
    fun testIsModifiedRecentlyWithOneDayAgoTimestamp() {
        val subscription = CommunicationSubscription(1, true, JOffsetDateTime.now().minusDays(1))
        assertTrue(subscription.isModifiedRecently)
    }
}
