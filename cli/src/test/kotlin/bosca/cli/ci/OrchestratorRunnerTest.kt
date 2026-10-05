package bosca.cli.ci

import bosca.cli.api.NetworkClient
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class OrchestratorRunnerTest {

    private val agentId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")
    private val jobId = Uuid.parse("11111111-2222-3333-4444-555555555555")

    private fun runner(): OrchestratorRunner =
        OrchestratorRunner(
            api = CiApi(NetworkClient("http://localhost:0")),
            agentId = agentId,
            labels = listOf("linux"),
        )

    @Test
    fun `buildGenericUserData bakes numeric watchdog and interpolated params`() {
        val script = runner().buildGenericUserData(
            serverUrl = "https://git.bosca.io",
            token = "bga_xyz",
            ephemeralAgentId = agentId,
            jobId = jobId,
            timeoutSeconds = 900,
        )

        assertTrue(script.contains("sleep 1500"), "watchdog should be 900 + 600:\n$script")
        assertFalse(script.contains("timeoutSeconds"), "kotlin parameter name leaked into bash:\n$script")
        assertFalse(script.contains("\$(("), "no unevaluated bash arithmetic should remain:\n$script")
        assertTrue(script.contains("--token \"bga_xyz\""), "token should be interpolated:\n$script")
        assertTrue(script.contains("--url \"https://git.bosca.io\""), "server URL should be interpolated:\n$script")
        assertTrue(script.contains("--agent-id \"$agentId\""), "agent id should be interpolated:\n$script")
        assertTrue(script.contains("--job-id \"$jobId\""), "job id should be interpolated:\n$script")
        assertTrue(script.startsWith("#!/bin/bash"), "script should start with bash shebang:\n$script")
    }

    @Test
    fun `parseRunnerProfiles returns empty on null`() {
        assertTrue(runner().parseRunnerProfiles(null).isEmpty())
    }

    @Test
    fun `parseRunnerProfiles returns empty on JsonNull`() {
        assertTrue(runner().parseRunnerProfiles(JsonNull).isEmpty())
    }

    @Test
    fun `parseRunnerProfiles parses fully-specified profiles`() {
        val json = buildJsonObject {
            put("gpu", buildJsonObject {
                put("region", "nyc3")
                put("size", "gpu-h100x1-80gb")
                put("image", "ubuntu-24-04-x64")
            })
            put("default", buildJsonObject {
                put("region", "sfo3")
                put("size", "s-4vcpu-8gb")
                put("image", "ubuntu-24-04-x64")
            })
        }

        val profiles = runner().parseRunnerProfiles(json)

        assertEquals(2, profiles.size)
        assertEquals(VmProfile("nyc3", "gpu-h100x1-80gb", "ubuntu-24-04-x64"), profiles["gpu"])
        assertEquals(VmProfile("sfo3", "s-4vcpu-8gb", "ubuntu-24-04-x64"), profiles["default"])
    }

    @Test
    fun `parseRunnerProfiles tolerates missing fields as empty strings`() {
        val json = buildJsonObject {
            put("partial", buildJsonObject {
                put("region", "nyc3")
            })
        }

        val profiles = runner().parseRunnerProfiles(json)

        assertEquals(VmProfile("nyc3", "", ""), profiles["partial"])
    }

    @Test
    fun `resolveProfile returns hardcoded defaults when no config is loaded`() {
        val resolved = runner().resolveProfile("gpu")

        assertEquals("nyc3", resolved.region)
        assertEquals("s-2vcpu-4gb", resolved.size)
        assertEquals("ubuntu-24-04-x64", resolved.image)
    }

    @Test
    fun `resolveProfile prefers profile values over defaults`() {
        val r = runner()
        r.config = OrchestratorConfig(
            provider = "digitalocean",
            maxConcurrentVms = 5,
            maxJobTimeoutMinutes = 60,
            maxVmLifetimeMinutes = 90,
            defaults = VmProfile("nyc3", "s-2vcpu-4gb", "ubuntu-24-04-x64"),
            runnerProfiles = mapOf(
                "gpu" to VmProfile("sfo3", "gpu-h100x1-80gb", "ubuntu-22-04-x64"),
            ),
        )

        val resolved = r.resolveProfile("gpu")

        assertEquals(VmProfile("sfo3", "gpu-h100x1-80gb", "ubuntu-22-04-x64"), resolved)
    }

    @Test
    fun `resolveProfile falls back to defaults for unknown labels`() {
        val r = runner()
        r.config = OrchestratorConfig(
            provider = "digitalocean",
            maxConcurrentVms = 5,
            maxJobTimeoutMinutes = 60,
            maxVmLifetimeMinutes = 90,
            defaults = VmProfile("ams3", "s-1vcpu-2gb", "ubuntu-22-04-x64"),
            runnerProfiles = mapOf(
                "gpu" to VmProfile("sfo3", "gpu-h100x1-80gb", "ubuntu-24-04-x64"),
            ),
        )

        val resolved = r.resolveProfile("linux")

        assertEquals(VmProfile("ams3", "s-1vcpu-2gb", "ubuntu-22-04-x64"), resolved)
    }

    @Test
    fun `resolveProfile fills empty profile fields from defaults per-field`() {
        val r = runner()
        r.config = OrchestratorConfig(
            provider = "digitalocean",
            maxConcurrentVms = 5,
            maxJobTimeoutMinutes = 60,
            maxVmLifetimeMinutes = 90,
            defaults = VmProfile("ams3", "s-1vcpu-2gb", "ubuntu-22-04-x64"),
            runnerProfiles = mapOf(
                // region set, size and image empty -> only region overrides defaults
                "partial" to VmProfile("nyc3", "", ""),
            ),
        )

        val resolved = r.resolveProfile("partial")

        assertEquals("nyc3", resolved.region)
        assertEquals("s-1vcpu-2gb", resolved.size)
        assertEquals("ubuntu-22-04-x64", resolved.image)
    }
}
