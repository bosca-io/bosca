package bosca.communications.mailers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the behavior and structure of [MailerConfiguration],
 * [MailerEmail], and [MailerType] used to configure outbound
 * email delivery.
 */
class MailerConfigurationTest {

    /**
     * A [MailerConfiguration] should preserve the mailer type and
     * sender identity exactly as provided during construction.
     */
    @Test
    fun mailerConfiguration_preservesTypeAndFrom() {
        val from = MailerEmail(name = "Support", email = "support@example.com")
        val config = MailerConfiguration(type = MailerType.SENDGRID, from = from)
        assertEquals(MailerType.SENDGRID, config.type)
        assertEquals("Support", config.from.name)
        assertEquals("support@example.com", config.from.email)
    }

    /**
     * Structural equality must hold for [MailerConfiguration] instances
     * with identical type and sender values.
     */
    @Test
    fun mailerConfiguration_equalityForIdenticalInstances() {
        val from = MailerEmail(name = "Noreply", email = "noreply@example.com")
        val a = MailerConfiguration(type = MailerType.SENDGRID, from = from)
        val b = MailerConfiguration(type = MailerType.SENDGRID, from = from)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    /**
     * Copying a [MailerConfiguration] should allow changing the sender
     * while preserving the mailer type.
     */
    @Test
    fun mailerConfiguration_copySetsSpecifiedFields() {
        val original = MailerConfiguration(
            type = MailerType.SENDGRID,
            from = MailerEmail(name = "Old", email = "old@example.com")
        )
        val newFrom = MailerEmail(name = "New", email = "new@example.com")
        val copied = original.copy(from = newFrom)
        assertEquals(MailerType.SENDGRID, copied.type)
        assertEquals("New", copied.from.name)
    }

    /**
     * [MailerEmail] structural equality must hold for instances
     * with identical name and email values.
     */
    @Test
    fun mailerEmail_equalityForIdenticalInstances() {
        val a = MailerEmail(name = "Test", email = "test@example.com")
        val b = MailerEmail(name = "Test", email = "test@example.com")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    /**
     * The [MailerType] enum should contain every supported mail delivery provider.
     */
    @Test
    fun mailerType_containsSupportedProviders() {
        val values = MailerType.entries
        assertEquals(2, values.size)
        assertTrue(values.contains(MailerType.SENDGRID))
        assertTrue(values.contains(MailerType.MAILGUN))
    }

    /**
     * Copying a [MailerEmail] should produce a new instance with
     * only the specified fields changed.
     */
    @Test
    fun mailerEmail_copyModifiesSpecifiedFields() {
        val original = MailerEmail(name = "Alice", email = "alice@example.com")
        val copied = original.copy(email = "bob@example.com")
        assertEquals("Alice", copied.name)
        assertEquals("bob@example.com", copied.email)
    }
}
