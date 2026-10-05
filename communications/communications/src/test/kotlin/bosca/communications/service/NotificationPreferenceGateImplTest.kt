package bosca.communications.service

import bosca.communications.model.DeliveryChannel
import bosca.communications.model.DeliveryEvent
import bosca.communications.model.DeliveryStatus
import bosca.communications.model.NotificationPreference
import bosca.communications.model.NotificationSettings
import bosca.communications.model.NotificationTypeKeys
import bosca.serialization.UUID
import kotlinx.coroutines.runBlocking
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Verifies gate composition (suppression list → preference →
 * quiet hours), the bypass rules (high priority, security type,
 * transactional default), fail-open behavior on bad stored values,
 * and the quiet-window time math including midnight-crossing
 * windows in [NotificationPreferenceGateImpl].
 */
class NotificationPreferenceGateImplTest {

    private val profileId = UUID.random()
    private val preferences = FakePreferences()
    private val deliveryTracking = FakeDeliveryTracking()

    /** Fixed "now": 2026-07-07 23:30 in Chicago (inside a 22:00–07:00 window). */
    private var fixedNow: ZonedDateTime =
        ZonedDateTime.of(2026, 7, 7, 23, 30, 0, 0, ZoneId.of("America/Chicago"))

    private val gate = object : NotificationPreferenceGateImpl(preferences, deliveryTracking) {
        override fun now(zone: ZoneId): ZonedDateTime = fixedNow.withZoneSameInstant(zone)
    }

    /**
     * With nothing configured, everything is allowed on both
     * channels.
     */
    @Test
    fun evaluate_allowsByDefault() = runBlocking {
        assertEquals(GateDecision.Allow, gate.evaluate(profileId, DeliveryChannel.EMAIL, NotificationTypeKeys.MARKETING, "a@b.c"))
        assertEquals(GateDecision.Allow, gate.evaluate(profileId, DeliveryChannel.PUSH, NotificationTypeKeys.MARKETING))
    }

    /**
     * A suppressed email address blocks delivery before any
     * preference is consulted, regardless of type.
     */
    @Test
    fun evaluate_suppressedAddressBlocksEmailEvenForTransactional() = runBlocking {
        deliveryTracking.suppress("bounced@example.com", "hard_bounce")
        val decision = gate.evaluate(profileId, DeliveryChannel.EMAIL, null, "bounced@example.com")
        assertIs<GateDecision.Suppressed>(decision)
        assertEquals(GateReasons.SUPPRESSED_ADDRESS, decision.reason)
    }

    /**
     * An opted-out (profile, channel, type) suppresses with the
     * preference reason.
     */
    @Test
    fun evaluate_optOutSuppressesOnThatChannel() = runBlocking {
        preferences.optOut(profileId, DeliveryChannel.EMAIL, NotificationTypeKeys.MARKETING)
        val decision = gate.evaluate(profileId, DeliveryChannel.EMAIL, NotificationTypeKeys.MARKETING, "a@b.c")
        assertIs<GateDecision.Suppressed>(decision)
        assertEquals(GateReasons.PREFERENCE, decision.reason)
        assertEquals(GateDecision.Allow, gate.evaluate(profileId, DeliveryChannel.PUSH, NotificationTypeKeys.MARKETING))
    }

    /**
     * A null type means transactional, which is never opted out.
     */
    @Test
    fun evaluate_nullTypeDefaultsToTransactionalAndAllows() = runBlocking {
        preferences.optOut(profileId, DeliveryChannel.EMAIL, NotificationTypeKeys.TRANSACTIONAL)
        assertEquals(GateDecision.Allow, gate.evaluate(profileId, DeliveryChannel.EMAIL, null, "a@b.c"))
    }

    /**
     * Push inside the quiet window defers until the window end in
     * the recipient's timezone.
     */
    @Test
    fun evaluate_pushInsideQuietWindowDefers() = runBlocking {
        preferences.quietHours(profileId, "America/Chicago", "22:00", "07:00")
        val decision = gate.evaluate(profileId, DeliveryChannel.PUSH, NotificationTypeKeys.MARKETING)
        assertIs<GateDecision.Deferred>(decision)
        // 23:30 is before midnight, so the window ends 07:00 the next day.
        val expected = fixedNow.plusDays(1).with(LocalTime.of(7, 0)).toOffsetDateTime()
        assertEquals(expected, decision.until)
    }

    /**
     * The same wall-clock instant is outside the window for a
     * recipient in a timezone where it is already morning.
     */
    @Test
    fun evaluate_quietWindowUsesRecipientTimezone() = runBlocking {
        // 23:30 Chicago = 06:30 next day in London? No — 05:30. Use a zone where it's past 07:00: Moscow (UTC+3) → 07:30.
        preferences.quietHours(profileId, "Europe/Moscow", "22:00", "07:00")
        assertEquals(GateDecision.Allow, gate.evaluate(profileId, DeliveryChannel.PUSH, NotificationTypeKeys.MARKETING))
    }

    /**
     * Push after the window on the same day is allowed (post-midnight
     * segment check).
     */
    @Test
    fun evaluate_pushOutsideQuietWindowAllows() = runBlocking {
        fixedNow = ZonedDateTime.of(2026, 7, 7, 12, 0, 0, 0, ZoneId.of("America/Chicago"))
        preferences.quietHours(profileId, "America/Chicago", "22:00", "07:00")
        assertEquals(GateDecision.Allow, gate.evaluate(profileId, DeliveryChannel.PUSH, NotificationTypeKeys.MARKETING))
    }

    /**
     * Inside the post-midnight segment of a midnight-crossing
     * window, the deferral ends the same day.
     */
    @Test
    fun evaluate_postMidnightSegmentDefersUntilSameDayEnd() = runBlocking {
        fixedNow = ZonedDateTime.of(2026, 7, 8, 2, 0, 0, 0, ZoneId.of("America/Chicago"))
        preferences.quietHours(profileId, "America/Chicago", "22:00", "07:00")
        val decision = gate.evaluate(profileId, DeliveryChannel.PUSH, NotificationTypeKeys.MARKETING)
        assertIs<GateDecision.Deferred>(decision)
        assertEquals(fixedNow.with(LocalTime.of(7, 0)).toOffsetDateTime(), decision.until)
    }

    /**
     * High-priority push bypasses quiet hours.
     */
    @Test
    fun evaluate_highPriorityBypassesQuietHours() = runBlocking {
        preferences.quietHours(profileId, "America/Chicago", "22:00", "07:00")
        assertEquals(
            GateDecision.Allow,
            gate.evaluate(profileId, DeliveryChannel.PUSH, NotificationTypeKeys.MARKETING, highPriority = true),
        )
    }

    /**
     * Security notifications bypass quiet hours unconditionally.
     */
    @Test
    fun evaluate_securityTypeBypassesQuietHours() = runBlocking {
        preferences.quietHours(profileId, "America/Chicago", "22:00", "07:00")
        assertEquals(
            GateDecision.Allow,
            gate.evaluate(profileId, DeliveryChannel.PUSH, NotificationTypeKeys.SECURITY),
        )
    }

    /**
     * Quiet hours never apply to email.
     */
    @Test
    fun evaluate_quietHoursDoNotApplyToEmail() = runBlocking {
        preferences.quietHours(profileId, "America/Chicago", "22:00", "07:00")
        assertEquals(GateDecision.Allow, gate.evaluate(profileId, DeliveryChannel.EMAIL, NotificationTypeKeys.MARKETING, "a@b.c"))
    }

    /**
     * Malformed stored quiet-hours values fail open: the message
     * sends rather than being silently swallowed.
     */
    @Test
    fun evaluate_failsOpenOnMalformedStoredValues() = runBlocking {
        preferences.quietHours(profileId, "Mars/Olympus_Mons", "22:00", "07:00")
        assertEquals(GateDecision.Allow, gate.evaluate(profileId, DeliveryChannel.PUSH, NotificationTypeKeys.MARKETING))

        preferences.quietHours(profileId, "America/Chicago", "quite-late", "07:00")
        assertEquals(GateDecision.Allow, gate.evaluate(profileId, DeliveryChannel.PUSH, NotificationTypeKeys.MARKETING))
    }

    /**
     * Same-day quiet windows (start before end) match only between
     * the two times.
     */
    @Test
    fun isInQuietWindow_sameDayWindow() {
        val start = LocalTime.of(9, 0)
        val end = LocalTime.of(17, 0)
        assertTrue(NotificationPreferenceGateImpl.isInQuietWindow(LocalTime.of(12, 0), start, end))
        assertTrue(NotificationPreferenceGateImpl.isInQuietWindow(LocalTime.of(9, 0), start, end))
        assertFalse(NotificationPreferenceGateImpl.isInQuietWindow(LocalTime.of(17, 0), start, end))
        assertFalse(NotificationPreferenceGateImpl.isInQuietWindow(LocalTime.of(8, 59), start, end))
    }

    /**
     * Midnight-crossing windows match both the late-evening and
     * early-morning segments.
     */
    @Test
    fun isInQuietWindow_midnightCrossingWindow() {
        val start = LocalTime.of(22, 0)
        val end = LocalTime.of(7, 0)
        assertTrue(NotificationPreferenceGateImpl.isInQuietWindow(LocalTime.of(23, 0), start, end))
        assertTrue(NotificationPreferenceGateImpl.isInQuietWindow(LocalTime.of(2, 0), start, end))
        assertTrue(NotificationPreferenceGateImpl.isInQuietWindow(LocalTime.of(22, 0), start, end))
        assertFalse(NotificationPreferenceGateImpl.isInQuietWindow(LocalTime.of(7, 0), start, end))
        assertFalse(NotificationPreferenceGateImpl.isInQuietWindow(LocalTime.of(12, 0), start, end))
    }

    /**
     * The window end lands today when still ahead, otherwise
     * tomorrow.
     */
    @Test
    fun quietWindowEnd_picksNextOccurrence() {
        val zone = ZoneId.of("America/Chicago")
        val end = LocalTime.of(7, 0)
        val beforeMidnight = ZonedDateTime.of(2026, 7, 7, 23, 30, 0, 0, zone)
        assertEquals(
            beforeMidnight.plusDays(1).with(end),
            NotificationPreferenceGateImpl.quietWindowEnd(beforeMidnight, end),
        )
        val afterMidnight = ZonedDateTime.of(2026, 7, 8, 2, 0, 0, 0, zone)
        assertEquals(
            afterMidnight.with(end),
            NotificationPreferenceGateImpl.quietWindowEnd(afterMidnight, end),
        )
    }

    /**
     * Minimal in-memory [NotificationPreferenceService] exposing
     * just what the gate consults.
     */
    private class FakePreferences : NotificationPreferenceService {
        private val optedOut = mutableSetOf<Triple<UUID, DeliveryChannel, String>>()
        private val settings = mutableMapOf<UUID, NotificationSettings>()

        fun optOut(profileId: UUID, channel: DeliveryChannel, type: String) {
            optedOut.add(Triple(profileId, channel, type))
        }

        fun quietHours(profileId: UUID, timeZone: String, start: String, end: String) {
            settings[profileId] = NotificationSettings(profileId, timeZone, start, end)
        }

        override suspend fun isOptedOut(profileId: UUID, channel: DeliveryChannel, type: String): Boolean =
            (type != NotificationTypeKeys.TRANSACTIONAL && type != NotificationTypeKeys.SECURITY) &&
                Triple(profileId, channel, type) in optedOut

        override suspend fun getSettings(profileId: UUID): NotificationSettings =
            settings[profileId] ?: NotificationSettings(profileId)

        override suspend fun getPreferences(profileId: UUID): List<NotificationPreference> = emptyList()
        override suspend fun setOptOut(profileId: UUID, channel: DeliveryChannel, type: String, optedOut: Boolean): NotificationPreference =
            NotificationPreference(profileId, channel, type, optedOut)
        override suspend fun setQuietHours(profileId: UUID, timeZone: String?, dndStartLocal: String?, dndEndLocal: String?): NotificationSettings =
            NotificationSettings(profileId, timeZone, dndStartLocal, dndEndLocal)
        override suspend fun unsubscribeByToken(token: String): Boolean = false
        override suspend fun profileIdForToken(token: String): UUID? = null
        override suspend fun generateUnsubscribeToken(profileId: UUID, type: String?): String = "token"
    }

    /**
     * Minimal in-memory [DeliveryTrackingService] with a working
     * suppression list.
     */
    private class FakeDeliveryTracking : DeliveryTrackingService {
        private val suppressed = mutableSetOf<String>()

        override suspend fun recordEvent(event: DeliveryEvent) {}
        override suspend fun getStatus(messageId: UUID, recipientId: UUID): DeliveryStatus? = null
        override suspend fun getStatusesForMessage(messageId: UUID): List<DeliveryStatus> = emptyList()
        override suspend fun getStatuses(offset: Long, limit: Int): List<DeliveryStatus> = emptyList()
        override suspend fun countStatuses(): Long = 0
        override suspend fun getHistoryForRecipient(recipientId: UUID, offset: Long, limit: Int): List<DeliveryStatus> = emptyList()
        override suspend fun getEventsForRecipient(recipientId: UUID, offset: Long, limit: Int): List<DeliveryEvent> = emptyList()
        override suspend fun isSuppressed(email: String): Boolean = email in suppressed
        override suspend fun suppress(email: String, reason: String, providerCode: String?) {
            suppressed.add(email)
        }
    }
}
