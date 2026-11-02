package bosca.cli.swarm

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SwarmRunnerTest {
    @Test
    fun `saved per-site runner settings control rendered stacks and readiness`() {
        val directory = Files.createTempDirectory("swarm-runner-test-")
        try {
            val defaults = SwarmConfig().withSecrets()
            val config = defaults.copy(sites = defaults.sites.mapIndexed { index, site ->
                site.copy(runnerServer = SwarmStandaloneServer(index != 0),
                    oauthRedirects = listOf("https://${site.hosts.studio}"))
            })
            val path = directory.resolve("config.json")
            saveConfig(path, config)
            val loaded = loadConfig(path)
            assertFalse(loaded.sites[0].runnerServer.enabled)
            assertTrue(loaded.sites[1].runnerServer.enabled)
            val bundle = renderSwarm(loaded, directory.resolve("bundle"))
            val expected = expectedServices(loaded)
            for (site in loaded.sites) {
                val stack = swarmJson.parseToJsonElement(Files.readString(bundle.resolve("stacks/${site.id}.yml"))).jsonObject
                assertEquals(site.runnerServer.enabled, "runner" in stack.getValue("services").jsonObject)
                assertEquals(site.runnerServer.enabled, "runner-application" in stack.getValue("configs").jsonObject)
                assertEquals(site.runnerServer.enabled, Files.exists(bundle.resolve("config/sites/${site.id}/runner.yaml")))
                assertEquals(site.runnerServer.enabled, "${site.id}_runner" in expected)
                assertTrue("${site.id}_atlas" in expected)
            }
            val enabled = loaded.copy(sites = loaded.sites.map { it.copy(runnerServer = SwarmStandaloneServer(true)) })
            renderSwarm(enabled, bundle)
            assertTrue(Files.exists(bundle.resolve("config/sites/site1/runner.yaml")))
            renderSwarm(loaded, bundle)
            assertFalse(Files.exists(bundle.resolve("config/sites/site1/runner.yaml")))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `Atlas owns worker listeners sync and scheduling only without a standard runner`() {
        val defaults = SwarmConfig().withSecrets()
        assertTrue(defaults.sites.all { it.runnerServer.enabled })
        for (standard in listOf(false, true)) for (atlas in listOf(false, true)) {
            val site = defaults.sites.first().copy(
                runnerServer = SwarmStandaloneServer(standard),
                atlas = SwarmAtlas(servers = SwarmAtlasServers(runner = atlas)),
            )
            val config = defaults.copy(sites = listOf(site))
            config.validate()
            val services = siteStack(config, site).getValue("services") as Map<*, *>
            val environment = (services["atlas"] as Map<*, *>)["environment"] as Map<*, *>
            assertEquals(standard, "runner" in services)
            assertEquals(atlas.toString(), environment["ATLAS_RUNNER_ENABLED"])
            for (key in listOf("ATLAS_RUNNER_LISTENERS_ENABLED", "ATLAS_SYNC_LISTENERS_ENABLED", "ATLAS_SCHEDULER_ENABLED")) {
                assertEquals((atlas && !standard).toString(), environment[key], "$key for $standard/$atlas")
            }
        }
    }

    @Test
    fun `retirement removes only existing disabled standard runners`() {
        val defaults = SwarmConfig().withSecrets()
        val config = defaults.copy(sites = listOf(
            defaults.sites[0].copy(runnerServer = SwarmStandaloneServer(false)), defaults.sites[1],
        ))
        val previous = remoteShell
        try {
            for (existing in listOf("site1_runner_extra\nsite2_runner\nunrelated_runner", "site1_runner\nsite2_runner\nunrelated_runner")) {
                val removed = mutableListOf<String>()
                remoteShell = RemoteShell { _, _, input, _ ->
                    val body = input?.decodeToString().orEmpty()
                    if ("docker service rm" in body) removed += body
                    existing
                }
                retireReplacedAtlasServices(config)
                val expected = if ("site1_runner" in existing.lineSequence()) listOf("sudo -n docker service rm 'site1_runner'") else emptyList()
                assertEquals(expected, removed)
            }
        } finally {
            remoteShell = previous
        }
    }

    @Test
    fun `image updates skip disabled runners unless explicitly selected`() = runBlocking {
        val defaults = SwarmConfig().withSecrets()
        for (enabled in listOf(false, true)) {
            val config = defaults.copy(sites = defaults.sites.map { it.copy(runnerServer = SwarmStandaloneServer(enabled)) })
            val repository = config.images.getValue("runner").substringBeforeLast(':')
            val lookups = mutableListOf<String>()
            val updated = updateSwarmImages(config, null, emptySet()) { lookups += it; "9.0.0" }
            assertEquals(enabled, repository in lookups)
            assertEquals(if (enabled) "$repository:9.0.0" else config.images["runner"], updated.images["runner"])
            val explicit = updateSwarmImages(config, null, setOf("runner")) { "9.1.0" }
            assertEquals("$repository:9.1.0", explicit.images["runner"])
        }
    }
}
