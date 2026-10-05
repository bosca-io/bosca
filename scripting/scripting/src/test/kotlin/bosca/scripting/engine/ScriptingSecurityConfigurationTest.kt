package bosca.scripting.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Validates the default values and construction behaviour of [ScriptingSecurityConfiguration],
 * ensuring that the allowlist defaults are sensible and that custom values override them.
 */
class ScriptingSecurityConfigurationTest {

    @Test
    fun `default allowed prefixes include bosca and kotlin`() {
        val defaults = ScriptingSecurityConfiguration.DEFAULT_ALLOWED_PREFIXES
        assertTrue(defaults.contains("bosca."))
        assertTrue(defaults.contains("kotlin."))
        assertTrue(defaults.contains("kotlinx."))
    }

    @Test
    fun `default allowed prefixes include standard java packages`() {
        val defaults = ScriptingSecurityConfiguration.DEFAULT_ALLOWED_PREFIXES
        assertTrue(defaults.contains("java.lang."))
        assertTrue(defaults.contains("java.util."))
        assertTrue(defaults.contains("java.time."))
        assertTrue(defaults.contains("java.math."))
        assertTrue(defaults.contains("java.text."))
    }

    @Test
    fun `default allowed prefixes include SLF4J Logger`() {
        val defaults = ScriptingSecurityConfiguration.DEFAULT_ALLOWED_PREFIXES
        assertTrue(defaults.contains("org.slf4j.Logger"))
    }

    @Test
    fun `default allowed prefixes do not include java io or java net`() {
        val defaults = ScriptingSecurityConfiguration.DEFAULT_ALLOWED_PREFIXES
        assertTrue(defaults.none { it.startsWith("java.io") })
        assertTrue(defaults.none { it.startsWith("java.net") })
    }

    @Test
    fun `default configuration uses standard values`() {
        val config = ScriptingSecurityConfiguration()
        assertEquals(ScriptingSecurityConfiguration.DEFAULT_ALLOWED_PREFIXES, config.allowedPrefixes)
        assertEquals(600L, config.executionTimeoutSeconds)
        assertEquals("sa", config.triggerServiceAccount)
    }

    @Test
    fun `custom configuration overrides defaults`() {
        val custom = ScriptingSecurityConfiguration(
            allowedPrefixes = listOf("com.example."),
            executionTimeoutSeconds = 30L,
            triggerServiceAccount = "admin"
        )
        assertEquals(listOf("com.example."), custom.allowedPrefixes)
        assertEquals(30L, custom.executionTimeoutSeconds)
        assertEquals("admin", custom.triggerServiceAccount)
    }
}
