package bosca.cli.swarm

import java.nio.file.Files
import java.nio.file.Path
import org.yaml.snakeyaml.Yaml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Suppress("UNCHECKED_CAST")
class SwarmAtlasTest {
    @Test
    fun `combined Atlas retains Git and registry origins with isolated configuration`() {
        val config = SwarmConfig().withSecrets()
        val site = config.sites.first()
        val stack = siteStack(config, site)
        val services = stack.getValue("services") as Map<String, Map<String, Any>>
        assertFalse("git" in services)
        assertFalse("artifacts" in services)
        assertFalse("atlas-ui" in services)
        val env = services.getValue("atlas").getValue("environment") as Map<String, String>
        assertEquals("https://${site.hosts.git}", env["GIT_URL"])
        assertEquals("https://${site.hosts.atlas}", env["APP_URL"])
        assertEquals("https://${site.hosts.artifacts}", env["ATLAS_ARTIFACTS_URL"])
        assertEquals("false", env["ATLAS_RUNNER_ENABLED"])
        assertEquals("true", env["ATLAS_UI_ENABLED"])
        assertEquals(site.secrets.pipeline, env["PIPELINE_SECRET_KEY"])
        val trainer = services.getValue("recommendation-trainer").getValue("environment") as Map<String, String>
        assertEquals("http://${site.id}-atlas:8094/_artifacts", trainer["ARTIFACTS_URL"])
        val directory = Files.createTempDirectory("atlas-swarm-config-")
        try {
            renderSwarm(config, directory.resolve("bundle"))
            val yaml = Yaml().load<Map<String, Any>>(Files.readString(directory.resolve("bundle/config/sites/${site.id}/atlas.yaml")))
            val passwords = (yaml.getValue("initialization") as Map<String, Any>).getValue("password") as Map<String, String>
            assertEquals("\$INIT_ADMIN_PASSWORD", passwords["admin"])
            assertEquals("\$INIT_SA_PASSWORD", passwords["sa"])
            assertTrue(publicReadinessUrls(config).contains("https://${site.hosts.atlas}/login"))
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun `UI Git registry and embedded workers can be selected independently`() {
        val defaults = SwarmConfig().withSecrets()
        val site = defaults.sites.first().copy(domain = "example.org", atlas = SwarmAtlas(
            domain = "build.example.org", separateUi = true, separateGit = true, separateArtifacts = true, runnerEnabled = true,
        ))
        val config = defaults.copy(sites = listOf(site))
        config.validate()
        val services = siteStack(config, site).getValue("services") as Map<String, Map<String, Any>>
        assertTrue(services.keys.containsAll(listOf("atlas", "atlas-ui", "git", "artifacts")))
        val backend = services.getValue("atlas").getValue("environment") as Map<String, String>
        assertEquals("false", backend["ATLAS_UI_ENABLED"])
        assertEquals("true", backend["ATLAS_RUNNER_ENABLED"])
        val ui = services.getValue("atlas-ui").getValue("environment") as Map<String, String>
        assertEquals("http://${site.id}-atlas:8094/graphql", ui["BML_GRAPHQL_ENDPOINT"])
        val routes = caddyfile(config)
        assertTrue(routes.contains("reverse_proxy @atlasUi ${site.id}-atlas-ui:8095"))
        assertTrue(routes.contains("reverse_proxy @gitBackend ${site.id}-git:8091"))
        assertTrue(routes.contains("reverse_proxy @registry ${site.id}-artifacts:8084"))
        assertEquals("http://${site.id}-artifacts:8084", ui["BML_ARTIFACTS_ENDPOINT"])
        assertTrue(routes.contains("reverse_proxy ${site.id}-artifacts:8084"))
    }

    @Test
    fun `retirement removes only replaced managed services and preserves independent hosts`() {
        val config = SwarmConfig().withSecrets().let { config -> config.copy(sites = listOf(
            config.sites[0], config.sites[1].copy(atlas = SwarmAtlas(separateUi = true, separateGit = true, separateArtifacts = true)),
        )) }
        val previous = remoteShell
        val removals = mutableListOf<String>()
        try {
            remoteShell = RemoteShell { _, _, input, _ ->
                val script = input?.decodeToString().orEmpty()
                if ("docker service rm" in script) removals += script
                "site1_git\nsite1_git_extra\nsite1_artifacts\nsite2_git\nsite2_artifacts\nsite2_atlas-ui\n"
            }
            retireReplacedAtlasServices(config)
            assertEquals(listOf("sudo -n docker service rm 'site1_git'", "sudo -n docker service rm 'site1_artifacts'"), removals)
        } finally { remoteShell = previous }
    }

    @Test
    fun `every imported BML page has an ingress match without swallowing clone URLs`() {
        val pattern = Regex(atlasUiPathPattern())
        val workspace = Path.of("..").toAbsolutePath()
        listOf("git/git-ui", "artifacts/artifacts-ui", "server/atlas-ui").forEach { component ->
            Files.list(workspace.resolve("$component/src/main/bml/pages")).use { pages ->
                pages.filter { it.toString().endsWith(".bml") }.forEach { file ->
                    val route = requireNotNull(Regex("route=\"([^\"]+)\"").find(Files.readString(file))).groupValues[1]
                        .replace("{id}", "382b5ae6-f5f4-47b7-8216-fa76e707e588")
                    assertTrue(pattern.matches(route), route)
                }
            }
        }
        listOf("/artifacts/project.git/info/refs", "/raw/project.git/git-upload-pack", "/v2/", "/graphql", "/oauth2/google/callback")
            .forEach { assertFalse(pattern.matches(it), it) }
        listOf("deployments/local/Caddyfile", "deployments/bosca-compose/config/nginx.conf.template").forEach {
            assertTrue(Files.readString(workspace.resolve(it)).contains(atlasUiPathPattern()), it)
        }
    }
}
