package bosca.cli.swarm

import bosca.security.service.SecurityConfigurationImpl
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.yaml.snakeyaml.Yaml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SwarmOAuthRedirectsTest {
    @Test
    fun `redirects survive config saves and configure server and runner through generated YAML lists`() {
        val directory = Files.createTempDirectory("swarm-oauth-test-")
        try {
            val defaults = SwarmConfig().withSecrets()
            val redirects = listOf(
                "https://studio.example.org", "https://accounts.example.org/login?next=a,b", "http://localhost:3000",
                "thepursuit://", "thepursuit://oauth/callback", "io.example.app:/oauth/callback",
            )
            val config = defaults.copy(sites = defaults.sites.mapIndexed { index, site ->
                if (index == 0) site.copy(oauthRedirects = redirects) else site
            })
            val path = directory.resolve("swarm.json")
            saveConfig(path, config)
            val loaded = loadConfig(path)
            assertEquals(redirects, loaded.sites[0].oauthRedirects)
            val bundle = renderSwarm(loaded, directory.resolve("generated"))
            loaded.sites.forEach { site ->
                val stack = swarmJson.parseToJsonElement(Files.readString(bundle.resolve("stacks/${site.id}.yml"))).jsonObject
                val services = stack.getValue("services").jsonObject
                val expected = site.oauthRedirects ?: listOf("https://${site.hosts.studio}", "http://localhost:3000")
                listOf("server", "runner").forEach { service ->
                    val definition = services.getValue(service).jsonObject
                    val environment = definition.getValue("environment").jsonObject
                        .mapValues { it.value.jsonPrimitive.content }
                    assertFalse("OAUTH2_ALLOWED_REDIRECTS" in environment)
                    val template = Path.of("../server/bosca-$service/src/main/resources/application.yaml").toAbsolutePath()
                    val application = if (site.oauthRedirects == null) {
                        assertFalse("command" in definition)
                        assertFalse("configs" in definition)
                        assertFalse(Files.exists(bundle.resolve("config/sites/${site.id}")))
                        template
                    } else {
                        assertEquals(listOf("-config=/config/application.yaml"),
                            definition.getValue("command").jsonArray.map { it.jsonPrimitive.content })
                        val mount = definition.getValue("configs").jsonArray.single().jsonObject
                        assertEquals("/config/application.yaml", mount.getValue("target").jsonPrimitive.content)
                        assertEquals("292", mount.getValue("mode").jsonPrimitive.content)
                        val resource = stack.getValue("configs").jsonObject
                            .getValue(mount.getValue("source").jsonPrimitive.content).jsonObject
                        val relative = "config/sites/${site.id}/$service.yaml"
                        assertEquals("${config.dataRoot}/$relative", resource.getValue("file").jsonPrimitive.content)
                        bundle.resolve(relative).also { generated ->
                            val original = Yaml().load<Map<String, Any>>(Files.readString(template))
                            val rendered = Yaml().load<Map<String, Any>>(Files.readString(generated))
                            assertEquals(original.filterKeys { it != "oauth2" }, rendered.filterKeys { it != "oauth2" })
                            val originalOAuth2 = original.getValue("oauth2") as Map<*, *>
                            val renderedOAuth2 = rendered.getValue("oauth2") as Map<*, *>
                            assertEquals(originalOAuth2.filterKeys { it != "redirects" }, renderedOAuth2.filterKeys { it != "redirects" })
                            assertEquals(redirects, renderedOAuth2["redirects"])
                        }
                    }
                    assertRedirectConfiguration(application, environment, expected)
                }
            }
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    @Test
    fun `empty redirect lists roll both services and removing the setting restores packaged defaults`() {
        val directory = Files.createTempDirectory("swarm-oauth-test-")
        try {
            val defaults = SwarmConfig().withSecrets()
            val config = defaults.copy(sites = defaults.sites.map { it.copy(oauthRedirects = listOf("https://${it.hosts.studio}")) })
            val bundle = renderSwarm(config, directory.resolve("generated"))
            fun stack() = swarmJson.parseToJsonElement(Files.readString(bundle.resolve("stacks/site1.yml"))).jsonObject
            val originalConfigs = stack().getValue("configs").jsonObject
            val disabled = config.copy(sites = config.sites.map { it.copy(oauthRedirects = emptyList()) })
            renderSwarm(disabled, bundle)
            val updated = stack()
            listOf("server", "runner").forEach { service ->
                assertFalse(originalConfigs.getValue("$service-application") == updated.getValue("configs").jsonObject.getValue("$service-application"))
                val environment = updated.getValue("services").jsonObject.getValue(service).jsonObject
                    .getValue("environment").jsonObject.mapValues { it.value.jsonPrimitive.content }
                assertRedirectConfiguration(bundle.resolve("config/sites/site1/$service.yaml"), environment, emptyList())
            }
            renderSwarm(defaults, bundle)
            assertFalse(Files.exists(bundle.resolve("config/sites")))
            listOf("server", "runner").forEach { service ->
                val definition = stack().getValue("services").jsonObject.getValue(service).jsonObject
                assertFalse("command" in definition)
                assertFalse("configs" in definition)
                val environment = definition.getValue("environment").jsonObject.mapValues { it.value.jsonPrimitive.content }
                assertRedirectConfiguration(Path.of("../server/bosca-$service/src/main/resources/application.yaml").toAbsolutePath(),
                    environment, listOf("https://${defaults.sites[0].hosts.studio}", "http://localhost:3000"))
            }
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    @Test
    fun `custom schemes and HTTP URLs pass redirect validation`() {
        val config = SwarmConfig().withSecrets()
        listOf(
            "thepursuit://", "thepursuit://oauth/callback", "thepursuit:///oauth/callback",
            "io.example.app:/oauth/callback", "example-app+oauth://callback?source=login",
            "THEPURSUIT://", "HTTPS://example.org/callback", "http://localhost:3000",
        ).forEach { redirect ->
            config.copy(sites = listOf(config.sites[0].copy(oauthRedirects = listOf(redirect))) + config.sites.drop(1)).validate()
        }
    }

    @Test
    fun `invalid redirect URLs fail validation before deployment`() {
        val config = SwarmConfig().withSecrets()
        listOf("", "/login", "//example.org", "ftp://example.org", "https://user:password@example.org",
            "https://", "https:///callback", "https:/callback", "https://example.org:0", "https://example.org:65536",
            " https://example.org", "https://example.org\n", " thepursuit://", "thepursuit://\n",
            "thepursuit://user:password@callback", "thepursuit://user@invalid_host", "thepursuit://callback:invalid",
            "thepursuit://callback:0", "thepursuit://callback:65536", "thepursuit://callback/%zz",
            "1invalid://", "thepursuit:", "thepursuit:callback", "javascript://callback", "data://callback",
            "file:///callback", "vbscript://callback")
            .forEach { redirect ->
                val failure = assertFailsWith<IllegalArgumentException> {
                    config.copy(sites = listOf(config.sites[0].copy(oauthRedirects = listOf(redirect))) + config.sites.drop(1)).validate()
                }
                assertTrue(failure.message.orEmpty().contains("oauthRedirects"), failure.message)
            }
    }

    private fun assertRedirectConfiguration(application: Path, environment: Map<String, String>, expected: List<String>) {
        val classpath = System.getProperty("java.class.path")
        assertTrue(classpath.isNotEmpty())
        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp", classpath,
            SwarmOAuthRedirectsProbe::class.java.name, application.toString(),
        ).redirectErrorStream(true).apply {
            environment()["OAUTH2_ALLOWED_REDIRECTS"] = "invalid JSON must not affect the original YAML list"
            environment().putAll(environment)
        }.start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
        val result = output.lineSequence().single { it.startsWith("REDIRECTS=") }.substringAfter("=")
        assertEquals(expected, Json.decodeFromString(ListSerializer(String.serializer()), result), application.toString())
    }
}

object SwarmOAuthRedirectsProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val security = SecurityConfigurationImpl(BoscaApplication(ApplicationConfig.loadFromFile(args.single())))
        println("REDIRECTS=" + Json.encodeToString(ListSerializer(String.serializer()), security.allowedRedirects))
    }
}
