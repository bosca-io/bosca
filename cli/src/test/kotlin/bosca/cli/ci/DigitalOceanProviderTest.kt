package bosca.cli.ci

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class DigitalOceanProviderTest {

    private val agentId = Uuid.parse("11111111-2222-3333-4444-555555555555")
    private val jobId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")

    @Test
    fun `buildUserData with self-destruct token embeds numeric watchdog timeout`() {
        val provider = DigitalOceanProvider(apiToken = "dop_api", selfDestructToken = "dop_sd")

        val script = provider.buildUserData(
            serverUrl = "https://git.bosca.io",
            ephemeralToken = "bga_ephemeral",
            ephemeralAgentId = agentId,
            jobId = jobId,
            timeoutSeconds = 3600,
        )

        assertTrue(script.contains("sleep 4200"), "expected watchdog = timeoutSeconds + 600 baked as a literal:\n$script")
        assertFalse(script.contains("timeoutSeconds"), "kotlin parameter name leaked into bash script:\n$script")
        assertFalse(script.contains("\$(("), "no bash arithmetic should remain for the watchdog:\n$script")
    }

    @Test
    fun `buildUserData with self-destruct token includes metadata fetch and DELETE call`() {
        val provider = DigitalOceanProvider(apiToken = "dop_api", selfDestructToken = "dop_sd_secret")

        val script = provider.buildUserData(
            serverUrl = "https://git.bosca.io",
            ephemeralToken = "bga_ephemeral",
            ephemeralAgentId = agentId,
            jobId = jobId,
            timeoutSeconds = 1800,
        )

        assertTrue(script.contains("169.254.169.254/metadata/v1/id"), "expected DO metadata fetch:\n$script")
        assertTrue(script.contains("curl -sf -X DELETE \"https://api.digitalocean.com/v2/droplets/\$DROPLET_ID\""),
            "expected delete call using runtime DROPLET_ID:\n$script")
        assertTrue(script.contains("Authorization: Bearer dop_sd_secret"),
            "self-destruct token should be baked into the script:\n$script")
        assertTrue(script.contains("sleep 2400"), "watchdog should be 1800 + 600:\n$script")
    }

    @Test
    fun `buildUserData without self-destruct token falls back to shutdown`() {
        val provider = DigitalOceanProvider(apiToken = "dop_api", selfDestructToken = null)

        val script = provider.buildUserData(
            serverUrl = "https://git.bosca.io",
            ephemeralToken = "bga_ephemeral",
            ephemeralAgentId = agentId,
            jobId = jobId,
            timeoutSeconds = 60,
        )

        assertTrue(script.contains("No self-destruct token configured"), "expected fallback comment:\n$script")
        assertTrue(script.contains("sleep 660"), "watchdog should be 60 + 600:\n$script")
        assertTrue(script.contains("shutdown -h now"), "expected shutdown command:\n$script")
        assertFalse(script.contains("DROPLET_ID"), "metadata block should be absent without self-destruct token:\n$script")
        assertFalse(script.contains("api.digitalocean.com"), "no DO API call expected without self-destruct token:\n$script")
    }

    @Test
    fun `buildUserData bakes ephemeral token, server URL, and job id into agent start command`() {
        val provider = DigitalOceanProvider(apiToken = "dop_api", selfDestructToken = null)

        val script = provider.buildUserData(
            serverUrl = "https://git.example.com",
            ephemeralToken = "bga_token_xyz",
            ephemeralAgentId = agentId,
            jobId = jobId,
            timeoutSeconds = 120,
        )

        assertTrue(script.contains("bosca ci agent start --ephemeral"), "expected agent start command:\n$script")
        assertTrue(script.contains("--token \"bga_token_xyz\""), "expected token interpolation:\n$script")
        assertTrue(script.contains("--url \"https://git.example.com\""), "expected server URL interpolation:\n$script")
        assertTrue(script.contains("--agent-id \"$agentId\""), "expected agent id interpolation:\n$script")
        assertTrue(script.contains("--job-id \"$jobId\""), "expected job id interpolation:\n$script")
    }

    @Test
    fun `provider exposes digitalocean as its name`() {
        val provider = DigitalOceanProvider(apiToken = "x", selfDestructToken = null)
        assertEquals("digitalocean", provider.name)
    }

    @Test
    fun `createCloudProvider returns DigitalOceanProvider when configured`() {
        val agentConfig = AgentConfig(
            agentId = "550e8400-e29b-41d4-a716-446655440000",
            token = "bga_test",
            serverUrl = "https://git.bosca.io",
            name = "orch",
            providerApiToken = "dop_v1_test",
            selfDestructToken = "dop_v1_sd",
        )

        val provider = createCloudProvider("digitalocean", agentConfig)
        assertTrue(provider is DigitalOceanProvider)
        assertEquals("digitalocean", provider.name)
    }

    @Test
    fun `createCloudProvider is case insensitive on provider name`() {
        val agentConfig = AgentConfig(
            agentId = "550e8400-e29b-41d4-a716-446655440000",
            token = "bga_test",
            serverUrl = "https://git.bosca.io",
            name = "orch",
            providerApiToken = "dop_v1_test",
            selfDestructToken = null,
        )

        val provider = createCloudProvider("DigitalOcean", agentConfig)
        assertTrue(provider is DigitalOceanProvider)
    }

    @Test
    fun `createCloudProvider throws when providerApiToken is missing`() {
        val agentConfig = AgentConfig(
            agentId = "550e8400-e29b-41d4-a716-446655440000",
            token = "bga_test",
            serverUrl = "https://git.bosca.io",
            name = "orch",
            providerApiToken = null,
            selfDestructToken = null,
        )

        val ex = kotlin.runCatching { createCloudProvider("digitalocean", agentConfig) }.exceptionOrNull()
        assertTrue(ex is CloudProviderException, "expected CloudProviderException, got $ex")
        assertTrue(ex.message!!.contains("providerApiToken"))
    }

    @Test
    fun `createCloudProvider throws on unsupported provider`() {
        val agentConfig = AgentConfig(
            agentId = "550e8400-e29b-41d4-a716-446655440000",
            token = "bga_test",
            serverUrl = "https://git.bosca.io",
            name = "orch",
            providerApiToken = "tok",
        )

        val ex = kotlin.runCatching { createCloudProvider("aws", agentConfig) }.exceptionOrNull()
        assertTrue(ex is CloudProviderException, "expected CloudProviderException, got $ex")
        assertTrue(ex.message!!.contains("Unsupported provider"))
    }
}
