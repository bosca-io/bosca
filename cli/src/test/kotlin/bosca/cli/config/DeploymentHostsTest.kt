package bosca.cli.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DeploymentHostsTest {

    @Test
    fun `services live beside the base domain the API is served from`() {
        assertEquals("artifacts.example.com", DeploymentHosts.artifactsRegistry("https://example.com/graphql"))
        assertEquals("git.example.com", DeploymentHosts.gitHost("https://example.com/graphql"))
    }

    @Test
    fun `API subdomains resolve to their base domain`() {
        for (prefix in listOf("api", "studio", "www", "ws", "upload")) {
            assertEquals("artifacts.example.com", DeploymentHosts.artifactsRegistry("https://$prefix.example.com/graphql"))
            assertEquals("git.example.com", DeploymentHosts.gitHost("https://$prefix.example.com/graphql"))
        }
    }

    @Test
    fun `other subdomains are kept as the deployment domain`() {
        assertEquals("artifacts.staging.example.com", DeploymentHosts.artifactsRegistry("https://staging.example.com/graphql"))
        assertEquals("artifacts.api-v2.example.com", DeploymentHosts.artifactsRegistry("https://api-v2.example.com/graphql"))
    }

    @Test
    fun `host case and ports are normalized away`() {
        assertEquals("git.example.com", DeploymentHosts.gitHost("https://API.Example.com:8443/graphql"))
    }

    @Test
    fun `local endpoints map to the development ports`() {
        assertEquals("localhost:8084", DeploymentHosts.artifactsRegistry("http://localhost:8080/graphql"))
        assertEquals("localhost:8091", DeploymentHosts.gitHost("http://127.0.0.1:8080/graphql"))
        assertEquals("localhost:8091", DeploymentHosts.gitHost("http://[::1]:8080/graphql"))
    }

    @Test
    fun `an endpoint without a host fails clearly`() {
        val error = assertFailsWith<IllegalArgumentException> { DeploymentHosts.artifactsRegistry("not a url") }
        assertEquals("Cannot derive the artifacts host from endpoint 'not a url'", error.message)
    }
}
