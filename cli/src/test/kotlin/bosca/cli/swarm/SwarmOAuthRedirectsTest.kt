package bosca.cli.swarm

import bosca.security.service.SecurityConfigurationImpl
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SwarmOAuthRedirectsTest {
    @Test
    fun `redirects survive config saves and configure server and runner from the rendered stack`() {
        val directory = Files.createTempDirectory("swarm-oauth-test-")
        try {
            val defaults = SwarmConfig().withSecrets()
            val redirects = listOf("https://studio.example.org", "https://accounts.example.org/login?next=a,b", "http://localhost:3000")
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
                    val environment = services.getValue(service).jsonObject.getValue("environment").jsonObject
                        .mapValues { it.value.jsonPrimitive.content }
                    assertRedirectConfiguration(service, environment, expected)
                }
            }

            val disabled = loaded.sites[0].copy(oauthRedirects = emptyList())
            assertRedirectConfiguration("server", siteEnvironment(loaded, disabled), emptyList())
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    @Test
    fun `invalid redirect URLs fail validation before deployment`() {
        val config = SwarmConfig().withSecrets()
        listOf("", "/login", "//example.org", "ftp://example.org", "https://user:password@example.org",
            "https://", "https://example.org:0", "https://example.org:65536", " https://example.org", "https://example.org\n")
            .forEach { redirect ->
                val failure = assertFailsWith<IllegalArgumentException> {
                    config.copy(sites = listOf(config.sites[0].copy(oauthRedirects = listOf(redirect))) + config.sites.drop(1)).validate()
                }
                assertTrue(failure.message.orEmpty().contains("oauthRedirects"), failure.message)
            }
    }

    private fun assertRedirectConfiguration(service: String, environment: Map<String, String>, expected: List<String>) {
        val classpath = System.getProperty("java.class.path")
        assertTrue(classpath.isNotEmpty())
        val application = Path.of("../server/bosca-$service/src/main/resources/application.yaml").toAbsolutePath()
        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp", classpath,
            SwarmOAuthRedirectsProbe::class.java.name, application.toString(),
        ).redirectErrorStream(true).apply {
            environment().remove("OAUTH2_ALLOWED_REDIRECTS")
            environment().putAll(environment)
        }.start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
        val result = output.lineSequence().single { it.startsWith("REDIRECTS=") }.substringAfter("=")
        assertEquals(expected, Json.decodeFromString(ListSerializer(String.serializer()), result), service)
    }
}

object SwarmOAuthRedirectsProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val security = SecurityConfigurationImpl(BoscaApplication(ApplicationConfig.loadFromFile(args.single())))
        println("REDIRECTS=" + Json.encodeToString(ListSerializer(String.serializer()), security.allowedRedirects))
    }
}
