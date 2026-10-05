package bosca.server.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.nio.file.Files

class ApplicationConfigTest {

    private fun configFromYaml(yaml: String): ApplicationConfig {
        return ApplicationConfig.load(yaml.byteInputStream())
    }

    @Test
    fun `property returns value for simple key`() {
        val config = configFromYaml("port: 8080")
        assertEquals("8080", config.property("port").getString())
    }

    @Test
    fun `property returns value for nested key`() {
        val config = configFromYaml("""
            database:
              host: localhost
              port: 5432
        """.trimIndent())
        assertEquals("localhost", config.property("database.host").getString())
        assertEquals("5432", config.property("database.port").getString())
    }

    @Test
    fun `property throws for missing key`() {
        val config = configFromYaml("port: 8080")
        assertFailsWith<IllegalStateException> {
            config.property("missing")
        }
    }

    @Test
    fun `propertyOrNull returns null for missing key`() {
        val config = configFromYaml("port: 8080")
        assertNull(config.propertyOrNull("missing"))
    }

    @Test
    fun `propertyOrNull returns value for existing key`() {
        val config = configFromYaml("port: 8080")
        val value = config.propertyOrNull("port")
        assertNotNull(value)
        assertEquals("8080", value.getString())
    }

    @Test
    fun `getString returns string value`() {
        val config = configFromYaml("name: bosca")
        assertEquals("bosca", config.property("name").getString())
    }

    @Test
    fun `getString returns numeric value as string`() {
        val config = configFromYaml("port: 8080")
        assertEquals("8080", config.property("port").getString())
    }

    @Test
    fun `getList returns list values`() {
        val config = configFromYaml("""
            tags:
              - alpha
              - beta
              - gamma
        """.trimIndent())
        val list = config.property("tags").getList()
        assertEquals(3, list.size)
        assertEquals("alpha", list[0])
        assertEquals("beta", list[1])
        assertEquals("gamma", list[2])
    }

    @Test
    fun `getList on scalar returns single-element list`() {
        val config = configFromYaml("value: hello")
        val list = config.property("value").getList()
        assertEquals(1, list.size)
        assertEquals("hello", list[0])
    }

    @Test
    fun `deeply nested property path`() {
        val config = configFromYaml("""
            a:
              b:
                c:
                  d: deep
        """.trimIndent())
        assertEquals("deep", config.property("a.b.c.d").getString())
    }

    @Test
    fun `unquoted dollar-brace value resolves default and parses as Long`() {
        // Regression: bosca-server/application.yaml uses `jwt.expiration-time: ${JWT_EXPIRATION_TIME:2592000}`
        // with no surrounding quotes. Make sure snakeyaml + env-var substitution + .getString().toLongOrNull()
        // all compose to produce the expected numeric value.
        val config = configFromYaml("""
            jwt:
              expiration-time: ${'$'}{UNLIKELY_ENV_EXP_XYZ:2592000}
        """.trimIndent())
        val raw = config.property("jwt.expiration-time").getString()
        assertEquals("2592000", raw)
        assertEquals(2592000L, raw.toLongOrNull())
    }

    @Test
    fun `environment variable substitution with default using dollar-brace syntax`() {
        val config = configFromYaml("""
            host: "${'$'}{UNLIKELY_ENV_VAR_XYZ_12345:defaulthost}"
        """.trimIndent())
        assertEquals("defaulthost", config.property("host").getString())
    }

    @Test
    fun `environment variable substitution with default using dollar syntax`() {
        val config = configFromYaml("""
            host: "${'$'}UNLIKELY_ENV_VAR_XYZ_12345:defaulthost"
        """.trimIndent())
        assertEquals("defaulthost", config.property("host").getString())
    }

    @Test
    fun `environment variable substitution resolves known env var`() {
        // PATH should be set on virtually all systems
        val config = configFromYaml("""
            path: "${'$'}{PATH:fallback}"
        """.trimIndent())
        val value = config.property("path").getString()
        // PATH should resolve to something other than "fallback"
        assertNotNull(value)
        assertTrue(value.isNotEmpty(), "PATH env var should resolve to a non-empty string")
    }

    @Test
    fun `numeric default gets converted after env var substitution`() {
        val config = configFromYaml("""
            port: "${'$'}{UNLIKELY_ENV_PORT_XYZ:5432}"
        """.trimIndent())
        // After env var resolution, "5432" should be converted to a number
        assertEquals("5432", config.property("port").getString())
    }

    @Test
    fun `boolean value in yaml`() {
        val config = configFromYaml("enabled: true")
        assertEquals("true", config.property("enabled").getString())
    }

    @Test
    fun `propertyOrNull for nested missing intermediate key`() {
        val config = configFromYaml("""
            database:
              host: localhost
        """.trimIndent())
        assertNull(config.propertyOrNull("missing.host"))
    }

    @Test
    fun `getAs deserializes to typed value`() {
        val config = configFromYaml("count: 42")
        val value = config.property("count").getAs<Int>()
        assertEquals(42, value)
    }

    @Test
    fun `getAs deserializes string`() {
        val config = configFromYaml("name: bosca")
        val value = config.property("name").getAs<String>()
        assertEquals("bosca", value)
    }

    @Test
    fun `getAs deserializes boolean`() {
        val config = configFromYaml("enabled: true")
        val value = config.property("enabled").getAs<Boolean>()
        assertEquals(true, value)
    }

    @Test
    fun `getAs deserializes list of strings`() {
        val config = configFromYaml("""
            items:
              - one
              - two
        """.trimIndent())
        val value = config.property("items").getAs<List<String>>()
        assertEquals(listOf("one", "two"), value)
    }

    @Test
    fun `empty yaml produces config with no properties`() {
        val config = configFromYaml("")
        assertNull(config.propertyOrNull("anything"))
    }

    @Test
    fun `classpath and filesystem loaders handle success and missing files`() {
        val classpath = ApplicationConfig.loadFromClasspath("application-config-test.yaml")
        assertEquals("classpath", classpath.property("name").getString())
        assertEquals(true, classpath.property("nested.enabled").getAs<Boolean>())
        assertFailsWith<IllegalStateException> {
            ApplicationConfig.loadFromClasspath("missing-application-config.yaml")
        }

        val file = Files.createTempFile("application-config", ".yaml").toFile()
        try {
            file.writeText("source: filesystem")
            assertEquals("filesystem", ApplicationConfig.loadFromFile(file.path).property("source").getString())
        } finally {
            file.delete()
        }
        assertFailsWith<IllegalStateException> { ApplicationConfig.loadFromFile(file.path) }
    }

    @Test
    fun `environment substitutions support embedded values multiple values and absent defaults`() {
        val config = configFromYaml(
            """
            embedded: "prefix-${'$'}{UNLIKELY_ENV_ONE_XYZ:left}-${'$'}{UNLIKELY_ENV_TWO_XYZ:right}"
            absentBrace: "${'$'}{UNLIKELY_ENV_THREE_XYZ}"
            absentDollar: "${'$'}UNLIKELY_ENV_FOUR_XYZ"
            falseValue: "${'$'}{UNLIKELY_ENV_FIVE_XYZ:false}"
            doubleValue: "${'$'}{UNLIKELY_ENV_SIX_XYZ:1.25}"
            knownDollar: "${'$'}PATH"
            knownBraceWithoutDefault: "${'$'}{PATH}"
            """.trimIndent(),
        )

        assertEquals("prefix-left-right", config.property("embedded").getString())
        assertEquals("", config.property("absentBrace").getString())
        assertEquals("", config.property("absentDollar").getString())
        assertEquals(false, config.property("falseValue").getAs<Boolean>())
        assertEquals(1.25, config.property("doubleValue").getAs<Double>())
        assertTrue(config.property("knownDollar").getString().isNotEmpty())
        assertTrue(config.property("knownBraceWithoutDefault").getString().isNotEmpty())
    }

    @Test
    fun `nested structures nulls and unusual values convert to JSON`() {
        val config = configFromYaml(
            """
            object:
              child: value
            values:
              - plain
              - child: nested
              - null
            nullValue: null
            numericKey:
              7: seven
            dateValue: 2020-01-01
            """.trimIndent(),
        )

        assertEquals("{\"child\":\"value\"}", config.property("object").getString())
        assertEquals(
            listOf("plain", "{\"child\":\"nested\"}", "null"),
            config.property("values").getList(),
        )
        assertEquals(listOf("{\"child\":\"value\"}"), config.property("object").getList())
        assertEquals("null", config.property("nullValue").getString())
        assertEquals("seven", config.property("numericKey.7").getString())
        assertTrue(config.property("dateValue").getString().isNotEmpty())
    }

    @Test
    fun `scalar YAML root and paths through scalar values have no properties`() {
        assertNull(configFromYaml("just-a-value").propertyOrNull("anything"))
        val config = configFromYaml("value: leaf")
        assertNull(config.propertyOrNull("value.child"))
    }

    @Test
    fun `literal strings remain strings and case insensitive booleans are coerced`() {
        val config = configFromYaml(
            """
            literal: no-template
            upperTrue: "${'$'}{UNLIKELY_ENV_SEVEN_XYZ:TRUE}"
            upperFalse: "${'$'}{UNLIKELY_ENV_EIGHT_XYZ:FALSE}"
            """.trimIndent(),
        )
        assertEquals("no-template", config.property("literal").getString())
        assertTrue(config.property("upperTrue").getAs<Boolean>())
        assertEquals(false, config.property("upperFalse").getAs<Boolean>())
    }
}
