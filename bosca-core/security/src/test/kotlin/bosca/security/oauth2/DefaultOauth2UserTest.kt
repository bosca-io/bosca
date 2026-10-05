package bosca.security.oauth2

import kotlinx.serialization.json.Json
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DefaultOauth2UserTest {

    private val user = DefaultOauth2User(
        id = "subject",
        name = "Ada Lovelace",
        givenName = "Ada",
        familyName = "Lovelace",
        picture = "https://example.com/ada.png",
        email = "ada@example.com",
        emailVerifiedClaim = true,
        verifiedEmailClaim = true,
    )

    @Test
    fun `email is verified by either supported claim`() {
        assertFalse(DefaultOauth2User("subject").emailVerified)
        assertTrue(DefaultOauth2User("subject", emailVerifiedClaim = true).emailVerified)
        assertTrue(DefaultOauth2User("subject", verifiedEmailClaim = true).emailVerified)
        assertTrue(user.emailVerified)
    }

    @Test
    fun `equality compares every property`() {
        assertEquals(user, user)
        assertEquals(user, user.copy())
        assertFalse(user.equals(null))
        assertNotEquals<Any>(user, "subject")
        assertNotEquals(user, user.copy(id = "other"))
        assertNotEquals(user, user.copy(name = "Grace Hopper"))
        assertNotEquals(user, user.copy(givenName = "Grace"))
        assertNotEquals(user, user.copy(familyName = "Hopper"))
        assertNotEquals(user, user.copy(picture = "https://example.com/grace.png"))
        assertNotEquals(user, user.copy(email = "grace@example.com"))
        assertNotEquals(user, user.copy(emailVerifiedClaim = false))
        assertNotEquals(user, user.copy(verifiedEmailClaim = false))
    }

    @Test
    fun `hash code supports nullable properties`() {
        val nullable = DefaultOauth2User(id = "subject")

        assertEquals(nullable.hashCode(), nullable.copy().hashCode())
        assertNotEquals(nullable.hashCode(), user.hashCode())
    }

    @Test
    fun `serialization handles default and explicit claims`() {
        val minimal = Json.decodeFromString<DefaultOauth2User>("""{"id":"subject"}""")
        val explicit = Json.decodeFromString<DefaultOauth2User>(
            """{"id":"subject","name":"Ada","given_name":"Ada","family_name":"Lovelace","picture":"p","email":"e","email_verified":"true","verified_email":1}"""
        )
        val verifiedEmail = Json.decodeFromString<DefaultOauth2User>(
            """{"id":"subject","verified_email":true}"""
        )
        val encodeDefaults = Json { this.encodeDefaults = true }

        assertEquals(DefaultOauth2User("subject"), minimal)
        assertTrue(explicit.emailVerifiedClaim)
        assertFalse(explicit.verifiedEmailClaim)
        assertTrue(verifiedEmail.verifiedEmailClaim)
        assertEquals(explicit, Json.decodeFromString(Json.encodeToString(explicit)))
        assertEquals(minimal, Json.decodeFromString(Json.encodeToString(minimal)))
        assertEquals(minimal, encodeDefaults.decodeFromString(encodeDefaults.encodeToString(minimal)))
        assertFailsWith<SerializationException> {
            Json.decodeFromString<DefaultOauth2User>("{}")
        }
    }
}
