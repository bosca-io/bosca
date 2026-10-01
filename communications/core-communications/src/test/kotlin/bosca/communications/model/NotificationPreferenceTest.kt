package bosca.communications.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class NotificationPreferenceTest {

    @Test
    fun `DeliveryChannel covers email and push`() {
        assertEquals(
            listOf(DeliveryChannel.EMAIL, DeliveryChannel.PUSH),
            DeliveryChannel.entries.toList(),
        )
    }

    @Test
    fun `NotificationPreference stores all fields and defaults to opted in`() {
        val profileId = Uuid.random()
        val preference = NotificationPreference(
            profileId = profileId,
            channel = DeliveryChannel.PUSH,
            type = NotificationTypeKeys.MARKETING,
        )
        assertEquals(profileId, preference.profileId)
        assertEquals(DeliveryChannel.PUSH, preference.channel)
        assertEquals(NotificationTypeKeys.MARKETING, preference.type)
        assertFalse(preference.optedOut)
    }

    @Test
    fun `NotificationPreference data class equality`() {
        val profileId = Uuid.random()
        val updatedAt = java.time.OffsetDateTime.now()
        val p1 = NotificationPreference(profileId, DeliveryChannel.EMAIL, NotificationTypeKeys.DIGEST, true, updatedAt)
        val p2 = NotificationPreference(profileId, DeliveryChannel.EMAIL, NotificationTypeKeys.DIGEST, true, updatedAt)
        assertEquals(p1, p2)
    }

    @Test
    fun `NotificationTypeKeys values match the legacy email categories`() {
        // The values are the migration contract with the pre-catalog
        // email_preferences rows — they must never change.
        assertEquals("transactional", NotificationTypeKeys.TRANSACTIONAL)
        assertEquals("digest", NotificationTypeKeys.DIGEST)
        assertEquals("marketing", NotificationTypeKeys.MARKETING)
        assertEquals("security", NotificationTypeKeys.SECURITY)
        assertEquals("git_activity", NotificationTypeKeys.GIT_ACTIVITY)
        assertEquals("workops_activity", NotificationTypeKeys.WORKOPS_ACTIVITY)
        assertEquals("social_activity", NotificationTypeKeys.SOCIAL_ACTIVITY)
    }

    @Test
    fun `NotificationType defaults to an optional non-system type`() {
        val type = NotificationType(key = "order-updates", name = "Order updates")
        assertEquals("order-updates", type.key)
        assertEquals("Order updates", type.name)
        assertNull(type.description)
        assertTrue(type.optional)
        assertFalse(type.system)
        assertTrue(type.defaultEmailEnabled)
        assertTrue(type.defaultPushEnabled)
        assertFalse(type.hidden)
        assertEquals(0, type.displayOrder)
    }

    @Test
    fun `NotificationType stores all fields`() {
        val type = NotificationType(
            key = "security",
            name = "Security alerts",
            description = "Sign-in alerts.",
            optional = false,
            system = true,
            defaultEmailEnabled = true,
            defaultPushEnabled = true,
            displayOrder = 1,
            hidden = true,
        )
        assertEquals("security", type.key)
        assertEquals("Security alerts", type.name)
        assertEquals("Sign-in alerts.", type.description)
        assertFalse(type.optional)
        assertTrue(type.system)
        assertTrue(type.defaultEmailEnabled)
        assertTrue(type.defaultPushEnabled)
        assertTrue(type.hidden)
        assertEquals(1, type.displayOrder)
    }

    @Test
    fun `NotificationType resolves defaults independently by channel`() {
        val type = NotificationType(
            key = "announcements",
            name = "Announcements",
            defaultEmailEnabled = false,
            defaultPushEnabled = true,
        )

        assertFalse(type.defaultEnabled(DeliveryChannel.EMAIL))
        assertTrue(type.defaultEnabled(DeliveryChannel.PUSH))
    }

    @Test
    fun `NotificationSettings defaults to no quiet hours`() {
        val profileId = Uuid.random()
        val settings = NotificationSettings(profileId)
        assertEquals(profileId, settings.profileId)
        assertNull(settings.timeZone)
        assertNull(settings.dndStartLocal)
        assertNull(settings.dndEndLocal)
    }

    @Test
    fun `NotificationSettings stores quiet hours`() {
        val settings = NotificationSettings(
            profileId = Uuid.random(),
            timeZone = "America/Chicago",
            dndStartLocal = "22:00",
            dndEndLocal = "07:00",
        )
        assertEquals("America/Chicago", settings.timeZone)
        assertEquals("22:00", settings.dndStartLocal)
        assertEquals("07:00", settings.dndEndLocal)
    }

    @Test
    fun `UnsubscribeToken stores type scope and defaults to unscoped`() {
        val profileId = Uuid.random()
        val unscoped = UnsubscribeToken(token = "abc", profileId = profileId)
        assertEquals("abc", unscoped.token)
        assertEquals(profileId, unscoped.profileId)
        assertNull(unscoped.type)

        val scoped = UnsubscribeToken(token = "def", profileId = profileId, type = NotificationTypeKeys.MARKETING)
        assertEquals(NotificationTypeKeys.MARKETING, scoped.type)
    }
}
