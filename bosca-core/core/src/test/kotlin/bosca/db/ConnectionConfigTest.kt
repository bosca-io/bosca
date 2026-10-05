@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package bosca.db

import bosca.db.ConnectionConfig.Companion.get
import bosca.db.ConnectionConfig.Companion.getOrNull
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.lang.reflect.InvocationTargetException

private val connectionConfigDefaultsJson = Json { encodeDefaults = true }

class ConnectionConfigTest {

    @Test
    fun `serialization preserves optional values and defaults`() {
        val compactJson = Json
        val defaulted = ConnectionConfig("jdbc:test", "user", "password")
        val explicit = ConnectionConfig("jdbc:test", "user", "password", "driver", 12)

        val defaultedEncoded = compactJson.encodeToString(defaulted)
        assertFalse("driverClassName" in defaultedEncoded)
        assertFalse("maxConnections" in defaultedEncoded)
        assertEquals(defaulted, compactJson.decodeFromString<ConnectionConfig>(defaultedEncoded))

        val explicitEncoded = compactJson.encodeToString(explicit)
        assertContains(explicitEncoded, "\"driverClassName\":\"driver\"")
        assertContains(explicitEncoded, "\"maxConnections\":12")
        assertEquals(explicit, compactJson.decodeFromString<ConnectionConfig>(explicitEncoded))

        val encodedDefaults = connectionConfigDefaultsJson.encodeToString(defaulted)
        assertContains(encodedDefaults, "\"driverClassName\":null")
        assertContains(encodedDefaults, "\"maxConnections\":null")
    }

    @Test
    fun `deserialization rejects each missing required connection field and unknown fields`() {
        listOf(
            """{"user":"user","password":"password"}""",
            """{"url":"jdbc:test","password":"password"}""",
            """{"url":"jdbc:test","user":"user"}""",
        ).forEach { encoded ->
            assertFailsWith<MissingFieldException> { Json.decodeFromString<ConnectionConfig>(encoded) }
        }
        assertFailsWith<SerializationException> {
            Json.decodeFromString<ConnectionConfig>(
                """{"url":"jdbc:test","user":"user","password":"password","unexpected":true}""",
            )
        }
    }

    @Test
    fun `configuration lookup supports present and missing database keys`() {
        val app = BoscaApplication(ApplicationConfig.load(
            """
            database:
              primary:
                url: jdbc:postgresql://localhost/test
                user: user
                password: password
                driverClassName: org.postgresql.Driver
                maxConnections: 12
            """.trimIndent().byteInputStream(),
        ))
        val config = app.get("primary")
        assertEquals("jdbc:postgresql://localhost/test", config.url)
        assertEquals(12, config.maxConnections)
        assertEquals(config, app.getOrNull("primary"))
        assertNull(app.getOrNull("missing"))
        assertFailsWith<IllegalStateException> { app.get("missing") }
    }

    @Test
    fun `equality copy defaults and hashes cover every connection field`() {
        val base = ConnectionConfig("jdbc:test", "user", "password", "driver", 10)
        assertEquals(base, base)
        assertEquals(base, base.copy())
        assertEquals(base.hashCode(), base.copy().hashCode())
        assertFalse(base.equals(null))
        assertFalse(base.equals("connection"))
        listOf(
            base.copy(url = "jdbc:other"),
            base.copy(user = "other"),
            base.copy(password = "other"),
            base.copy(driverClassName = null),
            base.copy(maxConnections = null),
        ).forEach { assertNotEquals(base, it) }
        ConnectionConfig("jdbc:test", "user", "password", driverClassName = "driver").hashCode()
        ConnectionConfig("jdbc:test", "user", "password", maxConnections = 10).hashCode()
        ConnectionConfig("jdbc:test", "user", "password").hashCode()
    }

    @Test
    fun `constructor enforces required JDBC fields and factory defaults`() {
        val constructor = ConnectionConfig::class.java.getDeclaredConstructor(
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            Int::class.javaObjectType,
        )
        repeat(3) { nullIndex ->
            val arguments = arrayOf<Any?>("jdbc:test", "user", "password", null, null)
            arguments[nullIndex] = null
            val error = assertFailsWith<InvocationTargetException> {
                constructor.newInstance(*arguments)
            }
            assertTrue(error.cause is NullPointerException)
        }

        val defaultFactory = ConnectionFactoryImpl(ConnectionConfig("jdbc:test", "user", "password"), "default")
        val emptyDriverFactory = ConnectionFactoryImpl(
            ConnectionConfig("jdbc:test", "user", "password", driverClassName = ""),
            "empty",
        )
        val explicitFactory = ConnectionFactoryImpl(
            ConnectionConfig("jdbc:test", "user", "password", driverClassName = "java.lang.String", maxConnections = 7),
            "explicit",
        )
        assertEquals(100, defaultFactory.maxConnections)
        assertEquals(100, emptyDriverFactory.maxConnections)
        assertEquals(7, explicitFactory.maxConnections)
    }
}
