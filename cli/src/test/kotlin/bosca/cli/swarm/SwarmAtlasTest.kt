package bosca.cli.swarm

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        assertEquals("true", env["ATLAS_RUNNER_ENABLED"])
        assertEquals("true", env["ATLAS_UI_ENABLED"])
        assertEquals(
            listOf("-XX:MaxRAMPercentage=75.0", "-config=/config/application.yaml"),
            services.getValue("atlas")["command"],
        )
        assertEquals(site.secrets.pipeline, env["PIPELINE_SECRET_KEY"])
        val trainer = services.getValue("recommendation-trainer").getValue("environment") as Map<String, String>
        assertEquals("http://${site.id}-atlas:8094/_artifacts", trainer["ARTIFACTS_URL"])
        val directory = Files.createTempDirectory("atlas-swarm-config-")
        try {
            renderSwarm(config, directory.resolve("bundle"))
            val yaml = Yaml().load<Map<String, Any>>(Files.readString(directory.resolve("bundle/config/sites/${site.id}/atlas.yaml")))
            val rendered = Yaml().load<Map<String, Any>>(Files.readString(directory.resolve("bundle/stacks/${site.id}.yml")))
            val renderedServices = rendered.getValue("services") as Map<String, Map<String, Any>>
            assertEquals(
                listOf("-XX:MaxRAMPercentage=75.0", "-config=/config/application.yaml"),
                renderedServices.getValue("atlas")["command"],
            )
            val passwords = (yaml.getValue("initialization") as Map<String, Any>).getValue("password") as Map<String, String>
            assertEquals("\$INIT_ADMIN_PASSWORD", passwords["admin"])
            assertEquals("\$INIT_SA_PASSWORD", passwords["sa"])
            assertTrue(publicReadinessUrls(config).contains("https://${site.hosts.atlas}/login"))
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun `UI Git registry and embedded workers can be selected independently`() {
        val defaults = SwarmConfig().withSecrets()
        for (git in listOf(false, true)) for (artifacts in listOf(false, true))
            for (separateUi in listOf(false, true)) for (runner in listOf(false, true)) {
                val site = defaults.sites.first().copy(domain = "example.org", atlas = SwarmAtlas(
                    domain = "build.example.org", servers = SwarmAtlasServers(runner = runner),
                ), atlasUiServer = SwarmStandaloneServer(separateUi), gitServer = SwarmStandaloneServer(git),
                    artifactsServer = SwarmStandaloneServer(artifacts))
                val config = defaults.copy(sites = listOf(site))
                config.validate()
                val services = siteStack(config, site).getValue("services") as Map<String, Map<String, Any>>
                assertEquals(git, "git" in services)
                assertEquals(artifacts, "artifacts" in services)
                assertEquals(separateUi, "atlas-ui" in services)
                val backend = services.getValue("atlas").getValue("environment") as Map<String, String>
                assertEquals("true", backend["ATLAS_UI_ENABLED"])
                assertEquals(runner.toString(), backend["ATLAS_RUNNER_ENABLED"])
                val registry = if (artifacts) "http://${site.id}-artifacts:8084" else ""
                assertEquals(registry, backend["BML_ARTIFACTS_ENDPOINT"])
                if (separateUi) {
                    val ui = services.getValue("atlas-ui").getValue("environment") as Map<String, String>
                    assertEquals("http://${site.id}-atlas:8094/graphql", ui["BML_GRAPHQL_ENDPOINT"])
                    assertEquals(registry, ui["BML_ARTIFACTS_ENDPOINT"])
                }
                val routes = caddyfile(config)
                assertTrue(routes.contains("reverse_proxy @atlasUi ${site.id}-${if (separateUi) "atlas-ui" else "atlas"}:8095"))
                assertTrue(routes.contains("reverse_proxy @gitBackend ${site.id}-${if (git) "git:8091" else "atlas:8094"}"))
                assertTrue(routes.contains("reverse_proxy @registry ${site.id}-${if (artifacts) "artifacts:8084" else "atlas:8094"}"))
            }
    }

    @Test
    fun `standalone UI resolves the shared collaboration backend from its packaged configuration`() {
        val defaults = SwarmConfig().withSecrets()
        for (backend in listOf("nats", "redis")) {
            val site = defaults.sites.first().copy(pubsubBackend = backend, atlasUiServer = SwarmStandaloneServer(true))
            val services = siteStack(defaults.copy(sites = listOf(site)), site).getValue("services") as Map<String, Map<String, Any>>
            val environment = services.getValue("atlas-ui").getValue("environment") as Map<String, String>
            val keys = listOf("pubsub.type", "nats.url", "nats.username", "nats.password",
                "redis.host", "redis.port", "redis.database", "redis.namespace")
            val process = ProcessBuilder(listOf(
                Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-cp", System.getProperty("java.class.path"), SwarmBackendProbe::class.java.name,
                Path.of("../server/atlas-ui-server/src/main/resources/atlas-ui-server.yaml").toAbsolutePath().toString(),
            ) + keys).redirectErrorStream(true).apply {
                environment().keys.removeAll(setOf("PUBSUB_TYPE", "NATS_URL", "NATS_USERNAME", "NATS_PASSWORD",
                    "REDIS_HOST", "REDIS_PORT", "REDIS_DATABASE", "REDIS_NAMESPACE"))
                environment().putAll(environment)
            }.start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor(), output)
            val resolved = swarmJson.parseToJsonElement(output.lineSequence()
                .single { it.startsWith("BACKEND=") }.substringAfter("=")).jsonObject
                .mapValues { it.value.jsonPrimitive.content }
            assertEquals(mapOf("pubsub.type" to backend, "nats.url" to "nats://nats:4222",
                "nats.username" to site.id, "nats.password" to site.secrets.nats,
                "redis.host" to "dragonfly", "redis.port" to "6379",
                "redis.database" to site.redisDatabase.toString(), "redis.namespace" to "${site.id}_"), resolved)
            assertTrue(environment.keys.none { it.startsWith("DATABASE_") || it.startsWith("JWT_") || it == "SECURITY_ENCRYPTION_KEY" })
        }
    }

    @Test
    fun `retirement removes only replaced managed services and preserves independent hosts`() {
        val config = SwarmConfig().withSecrets().let { config -> config.copy(sites = listOf(
            config.sites[0], config.sites[1].copy(atlasUiServer = SwarmStandaloneServer(true), gitServer = SwarmStandaloneServer(true), artifactsServer = SwarmStandaloneServer(true)),
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
        listOf("git/git-ui", "artifacts/artifacts-ui", "server/atlas-ui", "admin-support/system-ui", "workops/workops-ui").forEach { component ->
            Files.walk(workspace.resolve("$component/src/main/bml/pages")).use { pages ->
                pages.filter { it.toString().endsWith(".bml") }.forEach { file ->
                    val route = requireNotNull(Regex("route=\"([^\"]+)\"").find(Files.readString(file))).groupValues[1]
                        .replace("{id}", "382b5ae6-f5f4-47b7-8216-fa76e707e588")
                        .replace("{type}", "content")
                    assertTrue(pattern.matches(route), route)
                    if (route != "/") assertTrue(pattern.matches("$route/"), "$route/")
                }
            }
        }
        listOf("/artifacts/project.git/info/refs", "/raw/project.git/git-upload-pack", "/v2/", "/graphql", "/oauth2/google/callback",
            "/system/project.git/info/refs", "/system/project/raw/main/readme.md",
            "/workops/project.git/info/refs", "/workops/project.git/info/lfs/objects/batch",
            "/workops/tasks/raw/main/readme.md", "/workops/specs/raw/main/readme.md")
            .forEach { assertFalse(pattern.matches(it), it) }
        listOf("/collaboration", "/collaboration/", "/workops/tasks/UI-42", "/workops/specs/UI-SPEC-7",
            "/workops/requirements/UI-REQ-9").forEach { assertTrue(pattern.matches(it), it) }
        listOf("deployments/local/Caddyfile", "deployments/bosca-compose/config/nginx.conf.template").forEach {
            assertTrue(Files.readString(workspace.resolve(it)).contains(atlasUiPathPattern()), it)
        }
    }
}
