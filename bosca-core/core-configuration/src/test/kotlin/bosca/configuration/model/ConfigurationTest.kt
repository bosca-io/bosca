package bosca.configuration.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class ConfigurationTest {

    @Test
    fun `Configuration stores id, key, description, and public flag`() {
        val id = Uuid.random()
        val config = Configuration(id = id, key = "smtp", description = "SMTP settings", public = false)
        assertEquals(id, config.id)
        assertEquals("smtp", config.key)
        assertEquals("SMTP settings", config.description)
        assertFalse(config.public)
    }

    @Test
    fun `Configuration default id is NIL`() {
        val config = Configuration(key = "test", description = "test", public = true)
        assertEquals(Uuid.NIL, config.id)
    }

    @Test
    fun `Configuration PermissibleEntity publicList is always false`() {
        val config = Configuration(key = "test", description = "test", public = true)
        assertFalse(config.publicList)
    }

    @Test
    fun `Configuration PermissibleEntity publicContent is always false`() {
        val config = Configuration(key = "test", description = "test", public = true)
        assertFalse(config.publicContent)
    }

    @Test
    fun `Configuration PermissibleEntity publicSupplementary is always false`() {
        val config = Configuration(key = "test", description = "test", public = true)
        assertFalse(config.publicSupplementary)
    }

    @Test
    fun `Configuration PermissibleEntity isPublished is always true`() {
        val config = Configuration(key = "test", description = "test", public = false)
        assertTrue(config.isPublished)
    }

    @Test
    fun `Configuration PermissibleEntity isAdvertised is always false`() {
        val config = Configuration(key = "test", description = "test", public = true)
        assertFalse(config.isAdvertised)
    }

    @Test
    fun `Configuration PermissibleEntity isDeleted is always false`() {
        val config = Configuration(key = "test", description = "test", public = true)
        assertFalse(config.isDeleted)
    }

    @Test
    fun `Configuration data class equality`() {
        val id = Uuid.random()
        val c1 = Configuration(id = id, key = "key", description = "desc", public = true)
        val c2 = Configuration(id = id, key = "key", description = "desc", public = true)
        assertEquals(c1, c2)
    }

    @Test
    fun `Configuration data class copy`() {
        val config = Configuration(key = "old", description = "desc", public = false)
        val modified = config.copy(key = "new")
        assertEquals("new", modified.key)
        assertEquals("desc", modified.description)
    }

    @Test
    fun `OpenAIConfiguration stores key`() {
        val config = OpenAIConfiguration(key = "sk-test-key")
        assertEquals("sk-test-key", config.key)
    }

    @Test
    fun `OpenAIConfiguration KEY constant is openai`() {
        assertEquals("openai", OpenAIConfiguration.KEY)
    }

    @Test
    fun `OpenAIConfiguration data class equality`() {
        val c1 = OpenAIConfiguration(key = "test")
        val c2 = OpenAIConfiguration(key = "test")
        assertEquals(c1, c2)
    }

    @Test
    fun `ConfigurationValue stores configurationId, value, and nonce`() {
        val id = Uuid.random()
        val value = "encrypted".toByteArray()
        val nonce = "nonce".toByteArray()
        val cv = ConfigurationValue(configurationId = id, value = value, nonce = nonce)
        assertEquals(id, cv.configurationId)
        assertTrue(value.contentEquals(cv.value!!))
        assertTrue(nonce.contentEquals(cv.nonce))
    }

    @Test
    fun `ConfigurationValue default configurationId is NIL`() {
        val cv = ConfigurationValue(nonce = "nonce".toByteArray())
        assertEquals(Uuid.NIL, cv.configurationId)
    }

    @Test
    fun `ConfigurationValue value can be null`() {
        val cv = ConfigurationValue(nonce = "nonce".toByteArray(), value = null)
        assertEquals(null, cv.value)
    }
}
