package bosca.git.server

import bosca.server.config.ApplicationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitServerConfigurationTest {
    @Test
    fun `Git owns one listener and links to the separate Atlas UI`() {
        val yaml = requireNotNull(javaClass.getResourceAsStream("/application.yaml"))
            .bufferedReader().use { it.readText() }
            .replace(Regex("""\$\{[^:}]+:([^}]*)}""")) { it.groupValues[1] }
        val config = ApplicationConfig.load(yaml.byteInputStream())
        assertEquals("8091", config.property("bosca.server.port").getString())
        assertNull(config.propertyOrNull("git.ui.server"))
        assertEquals("http://localhost:8095", config.property("git.ui.url").getString())
        assertEquals("http://localhost:8095", config.property("app.url").getString())
    }
}
