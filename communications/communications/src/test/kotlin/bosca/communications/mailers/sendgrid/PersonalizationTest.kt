package bosca.communications.mailers.sendgrid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PersonalizationTest {

    @Test
    fun fieldPreservation() {
        val to = listOf(SendGridEmail("Alice", "alice@test.com"))
        val p = Personalization(subject = "Hello", to = to)
        assertEquals("Hello", p.subject)
        assertEquals(to, p.to)
    }

    @Test
    fun equality() {
        val to = listOf(SendGridEmail("Bob", "bob@test.com"))
        val a = Personalization("subj", to)
        val b = Personalization("subj", to)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun inequality() {
        val to = listOf(SendGridEmail("Bob", "bob@test.com"))
        val a = Personalization("subj1", to)
        val b = Personalization("subj2", to)
        assertNotEquals(a, b)
    }

    @Test
    fun emptyToList() {
        val p = Personalization("subj", emptyList())
        assertTrue(p.to.isEmpty())
    }

    @Test
    fun multipleRecipients() {
        val to = listOf(
            SendGridEmail("A", "a@test.com"),
            SendGridEmail("B", "b@test.com"),
            SendGridEmail("C", "c@test.com")
        )
        val p = Personalization("subj", to)
        assertEquals(3, p.to.size)
    }

    @Test
    fun copy() {
        val original = Personalization("original", listOf(SendGridEmail("A", "a@test.com")))
        val copied = original.copy(subject = "copied")
        assertEquals("copied", copied.subject)
        assertEquals(original.to, copied.to)
    }
}
