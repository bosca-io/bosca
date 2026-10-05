package bosca.ide.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoscaNotificationDeduplicatorTest {
    @Test
    fun `replayed event fingerprints are suppressed inside the reconnect window`() {
        var now = 1_000L
        val deduplicator = BoscaNotificationDeduplicator(ttlMillis = 30_000) { now }

        assertTrue(deduplicator.firstOccurrence("server-a:repo-a:event"))
        now += 5_000
        assertFalse(deduplicator.firstOccurrence("server-a:repo-a:event"))
    }

    @Test
    fun `same entity on another server remains a distinct notification`() {
        val deduplicator = BoscaNotificationDeduplicator()

        assertTrue(deduplicator.firstOccurrence("server-a:same-repo:same-run:RUNNING"))
        assertTrue(deduplicator.firstOccurrence("server-b:same-repo:same-run:RUNNING"))
    }

    @Test
    fun `fingerprint can notify again after the replay window`() {
        var now = 1_000L
        val deduplicator = BoscaNotificationDeduplicator(ttlMillis = 30_000) { now }
        assertTrue(deduplicator.firstOccurrence("event"))

        now += 30_001

        assertTrue(deduplicator.firstOccurrence("event"))
    }
}
