package bosca.cli.swarm

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SwarmRegistryLoginTest {
    private val config = SwarmConfig().withSecrets()
    private val auth = SwarmRegistryAuth(server = "registry.example:5000", username = "operator", password = "secret")

    @AfterTest
    fun restoreShell() { remoteShell = SshRemoteShell }

    @Test
    fun `unused credentials and disabled service images do not trigger login or require passwords`() {
        val unused = config.copy(
            registryAuth = auth.copy(password = "", passwordEnv = "BOSCA_UNUSED_REGISTRY_PASSWORD"),
            images = config.images + ("artifacts" to "${auth.server}/artifacts:1"),
        )
        assertTrue(deploymentRegistryAuths(unused).isEmpty())
        remoteShell = RemoteShell { _, _, _, _ -> error("Unused registry must not be contacted") }
        registryLogin(unused)
        val enabled = unused.copy(sites = unused.sites.mapIndexed { index, site ->
            if (index == 0) site.copy(artifactsServer = site.artifactsServer.copy(enabled = true)) else site
        })
        assertEquals(listOf(unused.registryAuth), deploymentRegistryAuths(enabled))
    }

    @Test
    fun `active infrastructure application and root images select their credentials`() {
        for (key in listOf("server", "postgres", "caddy")) {
            val active = config.copy(registryAuth = auth, images = config.images + (key to "${auth.server}/$key@sha256:abc"))
            assertEquals(listOf(auth), deploymentRegistryAuths(active), key)
        }
        val root = config.copy(sites = config.sites.mapIndexed { index, site ->
            if (index == 0) site.copy(rootImage = "${auth.server}/website:1", rootRegistryAuth = auth) else site
        })
        assertEquals(listOf(auth), deploymentRegistryAuths(root))
        var calls = 0
        remoteShell = RemoteShell { target, command, input, _ ->
            calls++
            assertEquals(config.manager.ssh, target)
            assertTrue(command.contains("docker login"))
            assertTrue(command.endsWith("'${auth.server}'"))
            assertEquals("secret\n", input?.toString(Charsets.UTF_8))
            ""
        }
        registryLogin(root)
        assertEquals(1, calls)
    }

    @Test
    fun `Docker Hub implicit images match Docker login aliases`() {
        for (server in listOf("docker.io", "index.docker.io", "https://index.docker.io/v1/")) {
            val active = config.copy(registryAuth = auth.copy(server = server))
            assertEquals(listOf(active.registryAuth), deploymentRegistryAuths(active))
        }
    }

    @Test
    fun `required registry failures still stop deployment`() {
        val active = config.copy(registryAuth = auth, images = config.images + ("server" to "${auth.server}/server:1"))
        remoteShell = RemoteShell { _, _, _, _ -> error("registry unavailable") }
        assertEquals("registry unavailable", assertFailsWith<IllegalStateException> { registryLogin(active) }.message)
        assertFailsWith<IllegalArgumentException> {
            registryLogin(active.copy(registryAuth = auth.copy(password = "", passwordEnv = "BOSCA_UNUSED_REGISTRY_PASSWORD")))
        }
    }
}
