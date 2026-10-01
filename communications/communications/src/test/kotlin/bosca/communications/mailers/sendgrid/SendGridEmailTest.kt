package bosca.communications.mailers.sendgrid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SendGridEmailTest {

    @Test
    fun fieldPreservation() {
        val email = SendGridEmail(name = "Alice", email = "alice@example.com")
        assertEquals("Alice", email.name)
        assertEquals("alice@example.com", email.email)
    }

    @Test
    fun equality() {
        val a = SendGridEmail("Bob", "bob@test.com")
        val b = SendGridEmail("Bob", "bob@test.com")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun inequality() {
        val a = SendGridEmail("Alice", "alice@test.com")
        val b = SendGridEmail("Bob", "bob@test.com")
        assertNotEquals(a, b)
    }

    @Test
    fun copy() {
        val original = SendGridEmail("Alice", "alice@test.com")
        val copied = original.copy(name = "Bob")
        assertEquals("Bob", copied.name)
        assertEquals("alice@test.com", copied.email)
    }

    @Test
    fun implementsEmailInterface() {
        val email: bosca.communications.mailers.Email = SendGridEmail("Test", "test@test.com")
        assertEquals("Test", email.name)
        assertEquals("test@test.com", email.email)
    }
}
