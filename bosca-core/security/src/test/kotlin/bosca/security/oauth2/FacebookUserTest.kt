package bosca.security.oauth2

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FacebookUserTest {

    @Test
    fun `FacebookUser stores id`() {
        val user = FacebookUser(id = "12345", name = "John Doe", email = "john@example.com")
        assertEquals("12345", user.id)
    }

    @Test
    fun `FacebookUser stores name`() {
        val user = FacebookUser(id = "1", name = "Jane Smith", email = null)
        assertEquals("Jane Smith", user.name)
    }

    @Test
    fun `FacebookUser stores email`() {
        val user = FacebookUser(id = "1", name = null, email = "test@example.com")
        assertEquals("test@example.com", user.email)
    }

    @Test
    fun `FacebookUser givenName extracts first name`() {
        val user = FacebookUser(id = "1", name = "John Doe", email = null)
        assertEquals("John", user.givenName)
    }

    @Test
    fun `FacebookUser familyName extracts last name`() {
        val user = FacebookUser(id = "1", name = "John Doe", email = null)
        assertEquals("Doe", user.familyName)
    }

    @Test
    fun `FacebookUser givenName with single name`() {
        val user = FacebookUser(id = "1", name = "Madonna", email = null)
        assertEquals("Madonna", user.givenName)
    }

    @Test
    fun `FacebookUser familyName with single name returns empty`() {
        val user = FacebookUser(id = "1", name = "Madonna", email = null)
        assertEquals("", user.familyName)
    }

    @Test
    fun `FacebookUser givenName is null when name is null`() {
        val user = FacebookUser(id = "1", name = null, email = null)
        assertNull(user.givenName)
    }

    @Test
    fun `FacebookUser familyName is null when name is null`() {
        val user = FacebookUser(id = "1", name = null, email = null)
        assertNull(user.familyName)
    }

    @Test
    fun `FacebookUser picture from pictureObject`() {
        val pictureData = FacebookPictureData(url = "https://example.com/photo.jpg")
        val picture = FacebookPicture(data = pictureData)
        val user = FacebookUser(id = "1", name = "Test", email = null, pictureObject = picture)
        assertEquals("https://example.com/photo.jpg", user.picture)
    }

    @Test
    fun `FacebookUser picture defaults to empty url`() {
        val user = FacebookUser(id = "1", name = "Test", email = null)
        assertEquals("", user.picture)
    }

    @Test
    fun `FacebookUser picture is null when pictureObject is null`() {
        val user = FacebookUser(id = "1", name = "Test", email = null, pictureObject = null)
        assertNull(user.picture)
    }

    @Test
    fun `FacebookPicture default data has empty url`() {
        val picture = FacebookPicture()
        assertEquals("", picture.data.url)
    }

    @Test
    fun `FacebookPictureData default url is empty`() {
        val data = FacebookPictureData()
        assertEquals("", data.url)
    }

    @Test
    fun `FacebookUser givenName with multi-word name`() {
        val user = FacebookUser(id = "1", name = "Mary Jane Watson", email = null)
        assertEquals("Mary", user.givenName)
    }

    @Test
    fun `FacebookUser familyName with multi-word name includes all after first`() {
        val user = FacebookUser(id = "1", name = "Mary Jane Watson", email = null)
        assertEquals("Jane Watson", user.familyName)
    }

    @Test
    fun `FacebookUser equality`() {
        val a = FacebookUser(id = "1", name = "A", email = "a@b.com")
        val b = FacebookUser(id = "1", name = "A", email = "a@b.com")
        assertEquals(a, b)
    }

    @Test
    fun `FacebookUser equality compares every property`() {
        val user = FacebookUser(
            id = "1",
            name = "Ada Lovelace",
            email = "ada@example.com",
            pictureObject = FacebookPicture(FacebookPictureData("picture")),
        )

        assertEquals(user, user)
        assertEquals(user, user.copy())
        assertFalse(user.equals(null))
        assertNotEquals<Any>(user, "1")
        assertNotEquals(user, user.copy(id = "2"))
        assertNotEquals(user, user.copy(name = "Grace Hopper"))
        assertNotEquals(user, user.copy(email = "grace@example.com"))
        assertNotEquals(user, user.copy(pictureObject = FacebookPicture(FacebookPictureData("other"))))
    }

    @Test
    fun `Facebook picture equality compares nested properties`() {
        val data = FacebookPictureData("picture")
        val picture = FacebookPicture(data)

        assertEquals(data, data)
        assertEquals(data, data.copy())
        assertFalse(data.equals(null))
        assertNotEquals<Any>(data, "picture")
        assertNotEquals(data, data.copy(url = "other"))
        assertEquals(picture, picture)
        assertEquals(picture, picture.copy())
        assertFalse(picture.equals(null))
        assertNotEquals<Any>(picture, "picture")
        assertNotEquals(picture, picture.copy(data = FacebookPictureData("other")))
    }

    @Test
    fun `Facebook user verification requires a non-blank email`() {
        assertFalse(FacebookUser("1", email = null).emailVerified)
        assertFalse(FacebookUser("1", email = "").emailVerified)
        assertFalse(FacebookUser("1", email = "  ").emailVerified)
        assertTrue(FacebookUser("1", email = "ada@example.com").emailVerified)
    }

    @Test
    fun `hash codes support nullable Facebook user properties`() {
        val nullable = FacebookUser("1", pictureObject = null)
        val populated = FacebookUser("1", "Ada", "ada@example.com", FacebookPicture())

        assertEquals(nullable.hashCode(), nullable.copy().hashCode())
        assertNotEquals(nullable.hashCode(), populated.hashCode())
    }

    @Test
    fun `Facebook models serialize defaults and explicit properties`() {
        val minimal = Json.decodeFromString<FacebookUser>("""{"id":"1"}""")
        val explicit = Json.decodeFromString<FacebookUser>(
            """{"id":"1","name":"Ada Lovelace","email":"ada@example.com","picture":{"data":{"url":"picture"}}}"""
        )
        val encodeDefaults = Json { this.encodeDefaults = true }

        assertEquals(FacebookUser("1"), minimal)
        assertEquals("picture", explicit.picture)
        assertEquals(minimal, Json.decodeFromString(Json.encodeToString(minimal)))
        assertEquals(explicit, Json.decodeFromString(Json.encodeToString(explicit)))
        assertEquals(minimal, encodeDefaults.decodeFromString(encodeDefaults.encodeToString(minimal)))
        assertFailsWith<SerializationException> {
            Json.decodeFromString<FacebookUser>("{}")
        }
        assertFailsWith<SerializationException> {
            Json.decodeFromString<FacebookPictureData>("null")
        }
    }
}
