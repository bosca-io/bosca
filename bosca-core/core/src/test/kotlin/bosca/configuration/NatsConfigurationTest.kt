@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package bosca.configuration

import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse

private val natsConfigDefaultsJson = Json { encodeDefaults = true }

class NatsConfigurationTest {

    @Test
    fun `NatsConnectionConfig serialization preserves optional values and defaults`() {
        val defaulted = NatsConnectionConfig("nats://localhost:4222", "token")
        val explicit = defaulted.copy(maxConnections = 25)

        val defaultedEncoded = Json.encodeToString(defaulted)
        assertFalse("maxConnections" in defaultedEncoded)
        assertEquals(defaulted, Json.decodeFromString<NatsConnectionConfig>(defaultedEncoded))

        val explicitEncoded = Json.encodeToString(explicit)
        assertContains(explicitEncoded, "\"maxConnections\":25")
        assertEquals(explicit, Json.decodeFromString<NatsConnectionConfig>(explicitEncoded))

        val encodedDefaults = natsConfigDefaultsJson.encodeToString(defaulted)
        assertContains(encodedDefaults, "\"maxConnections\":null")
    }

    @Test
    fun `NatsConnectionConfig deserialization accepts account credentials and rejects unknown fields`() {
        assertFailsWith<MissingFieldException> {
            Json.decodeFromString<NatsConnectionConfig>("""{"token":"token"}""")
        }
        val account = Json.decodeFromString<NatsConnectionConfig>(
            """{"url":"nats://localhost:4222","username":"sitea","password":"secret"}""",
        )
        assertNull(account.token)
        assertEquals("sitea", account.username)
        assertFailsWith<SerializationException> {
            Json.decodeFromString<NatsConnectionConfig>(
                """{"url":"nats://localhost:4222","token":"token","unexpected":true}""",
            )
        }
    }

    @Test
    fun `NatsConnectionConfig stores all properties`() {
        val config = NatsConnectionConfig(url = "nats://localhost:4222", token = "secret", maxConnections = 100)
        assertEquals("nats://localhost:4222", config.url)
        assertEquals("secret", config.token)
        assertEquals(100, config.maxConnections)
    }

    @Test
    fun `NatsConnectionConfig maxConnections defaults to null`() {
        val config = NatsConnectionConfig(url = "nats://localhost:4222", token = "t")
        assertNull(config.maxConnections)
    }

    @Test
    fun `NatsConnectionConfig equality is based on all fields`() {
        val c1 = NatsConnectionConfig(url = "nats://localhost:4222", token = "t", maxConnections = 50)
        val c2 = NatsConnectionConfig(url = "nats://localhost:4222", token = "t", maxConnections = 50)
        assertEquals(c1, c2)
        assertEquals(c1.hashCode(), c2.hashCode())
    }

    @Test
    fun `NatsConnectionConfig inequality when url differs`() {
        val c1 = NatsConnectionConfig(url = "nats://host1:4222", token = "t")
        val c2 = NatsConnectionConfig(url = "nats://host2:4222", token = "t")
        assertNotEquals(c1, c2)
    }

    @Test
    fun `NatsConnectionConfig inequality when token differs`() {
        val c1 = NatsConnectionConfig(url = "nats://localhost:4222", token = "a")
        val c2 = NatsConnectionConfig(url = "nats://localhost:4222", token = "b")
        assertNotEquals(c1, c2)
    }

    @Test
    fun `NatsConnectionConfig inequality when maxConnections differs`() {
        val c1 = NatsConnectionConfig(url = "nats://localhost:4222", token = "t", maxConnections = 10)
        val c2 = NatsConnectionConfig(url = "nats://localhost:4222", token = "t", maxConnections = 20)
        assertNotEquals(c1, c2)
    }

    @Test
    fun `NatsConnectionConfig copy allows changing fields`() {
        val config = NatsConnectionConfig(url = "nats://localhost:4222", token = "old", maxConnections = 10)
        val copied = config.copy(token = "new", maxConnections = 100)
        assertEquals("nats://localhost:4222", copied.url)
        assertEquals("new", copied.token)
        assertEquals(100, copied.maxConnections)
        assertEquals(config, config)
        assertFalse(config.equals(Any()))
        assertFalse(config.equals(null))
    }
}
