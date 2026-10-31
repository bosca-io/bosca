package bosca.cli.swarm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SwarmAtlasCapabilitiesTest {
    @Test
    fun `Atlas defaults enable every capability and standalone defaults create no services`() {
        val config = swarmJson.decodeFromString(SwarmConfig.serializer(), "{}")
        val site = config.sites.first()
        assertEquals(SwarmAtlasServers(true, true, true, true, true), site.atlas.servers)
        assertFalse(site.gitServer.enabled)
        assertFalse(site.artifactsServer.enabled)
        assertFalse(site.atlasUiServer.enabled)
        val json = swarmJson.encodeToString(SwarmConfig.serializer(), config)
        assertTrue(json.contains("\"servers\""))
        assertFalse(json.contains("separateGit"))
        assertFalse(json.contains("runnerEnabled"))
    }

    @Test
    fun `capabilities control Atlas flags routes probes and never enable standalone services`() {
        val defaults = SwarmConfig().withSecrets()
        for (api in listOf(false, true)) for (ui in listOf(false, true))
            for (git in listOf(false, true)) for (artifacts in listOf(false, true)) for (runner in listOf(false, true)) {
                val servers = SwarmAtlasServers(api, ui, git, artifacts, runner)
                val site = defaults.sites.first().copy(atlas = SwarmAtlas(servers = servers))
                val config = defaults.copy(sites = listOf(site))
                if ((ui && !api) || (!api && !git && !artifacts && !runner)) {
                    assertFailsWith<IllegalArgumentException> { config.validate() }
                    continue
                }
                config.validate()
                val services = siteStack(config, site).getValue("services") as Map<*, *>
                assertFalse(services.containsKey("git"))
                assertFalse(services.containsKey("artifacts"))
                assertFalse(services.containsKey("atlas-ui"))
                val atlas = services["atlas"] as Map<*, *>
                val environment = atlas["environment"] as Map<*, *>
                for ((name, enabled) in mapOf("API" to api, "UI" to ui, "GIT" to git, "ARTIFACTS" to artifacts, "RUNNER" to runner)) {
                    assertEquals(enabled.toString(), environment["ATLAS_${name}_ENABLED"], servers.toString())
                }
                val routes = caddyfile(config)
                assertEquals(!ui, routes.contains("respond @atlasUi 404"))
                assertEquals(!git, routes.contains("respond @gitBackend 404"))
                assertEquals(!artifacts, routes.contains("respond @registry 404"))
                val urls = publicReadinessUrls(config)
                assertEquals(ui, "https://${site.hosts.atlas}/login" in urls)
                assertEquals(api, "https://${site.hosts.atlas}/api/v1/ready" in urls)
                val health = atlas["healthcheck"] as Map<*, *>
                assertEquals(ui, health["test"].toString().contains("8095"))
            }
    }

    @Test
    fun `standalone servers are enabled independently of disabled Atlas capabilities`() {
        val defaults = SwarmConfig().withSecrets()
        val site = defaults.sites.first().copy(
            atlas = SwarmAtlas(servers = SwarmAtlasServers(ui = false, git = false, artifacts = false, runner = false)),
            gitServer = SwarmStandaloneServer(true), artifactsServer = SwarmStandaloneServer(true),
            atlasUiServer = SwarmStandaloneServer(true),
        )
        val config = defaults.copy(sites = listOf(site))
        config.validate()
        val services = siteStack(config, site).getValue("services") as Map<*, *>
        assertTrue(services.keys.containsAll(listOf("git", "artifacts", "atlas-ui")))
        val env = (services["atlas"] as Map<*, *>)["environment"] as Map<*, *>
        assertEquals("false", env["ATLAS_UI_ENABLED"])
        assertEquals("false", env["ATLAS_GIT_ENABLED"])
        assertEquals("false", env["ATLAS_ARTIFACTS_ENABLED"])
        val routes = caddyfile(config)
        assertTrue(routes.contains("reverse_proxy @atlasUi ${site.id}-atlas-ui:8095"))
        assertTrue(routes.contains("reverse_proxy @gitBackend ${site.id}-git:8091"))
        assertTrue(routes.contains("reverse_proxy @registry ${site.id}-artifacts:8084"))
        assertTrue("https://${site.hosts.atlas}/login" in publicReadinessUrls(config))
    }
}
