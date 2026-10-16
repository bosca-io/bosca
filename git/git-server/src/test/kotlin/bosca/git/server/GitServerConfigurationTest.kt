package bosca.git.server

import bosca.server.config.ApplicationConfig
import java.io.File
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class GitServerConfigurationTest {
    private fun defaults(resource: String): ApplicationConfig {
        val yaml = requireNotNull(javaClass.getResourceAsStream(resource))
            .bufferedReader().use { it.readText() }
            .replace(Regex("""\$\{[^:}]+:([^}]*)}""")) { it.groupValues[1] }
        return ApplicationConfig.load(yaml.byteInputStream())
    }

    @Test
    fun `default UI listener avoids workspace services and matches authentication origins`() {
        val api = defaults("/application.yaml")
        val ui = defaults("/git-ui-server.yaml")
        val apiPort = api.property("bosca.server.port").getString().toInt()
        val uiPort = api.property("git.ui.server.port").getString().toInt()
        val dependencies = ApplicationConfig.loadFromFile(
            File(requireNotNull(System.getProperty("workspaceRoot")),
                "server/services/docker-compose.yaml").absolutePath,
        )
        val embeddingPort = dependencies.property("services.text-embeddings-inference.ports")
            .getList().single().substringBefore(':').toInt()

        assertNotEquals(apiPort, uiPort)
        assertNotEquals(embeddingPort, uiPort)
        assertEquals(uiPort, ui.property("bosca.server.port").getString().toInt())
        val origin = "http://localhost:$uiPort"
        assertEquals(origin, api.property("app.url").getString())
        assertEquals(listOf(origin), api.property("app.allowed-origins").getList())
        assertEquals(listOf(origin), api.property("oauth2.redirects").getList())
        assertEquals(origin, api.property("git.ui.url").getString())
        assertEquals(apiPort, URI(api.property("git.url").getString()).port)
        assertEquals(apiPort, URI(ui.property("bml.graphqlEndpoint").getString()).port)
    }
}
