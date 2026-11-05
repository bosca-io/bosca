package bosca.cli.swarm

import bosca.ai.kit.harness.KitHarnessDeployment
import bosca.ai.kit.harness.RuntimeVersions
import bosca.cli.images.ImageInspection
import bosca.cli.swarm.runtime.SwarmRuntimeConfiguration
import bosca.cli.swarm.runtime.SwarmRuntimeRegistryAuth
import bosca.cli.swarm.runtime.validateSwarmRuntime
import bosca.server.config.ApplicationConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.io.path.readText
import kotlin.test.*

class SwarmKitDeploymentTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `one Kit setting renders matched application profiles controller and provider Secret`() = runBlocking {
        for ((family, architecture) in listOf("codex" to "amd64", "claude" to "arm64")) {
            val site = SwarmConfig().sites.first().copy(kit = SwarmKit(enabled = true, harness = family),
                secrets = SwarmSiteSecrets(kitProvider = "provider-DO-NOT-LEAK-fixture"))
            val config = SwarmConfig(sites = listOf(site)).withSecrets()
            val versions = RuntimeVersions("6.0.0", "adapter", "harness", "$family-home-v1")
            val image = ImageInspection("registry.invalid/kit-$family@sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64),
                mapOf("io.bosca.kit.versions" to swarmJson.encodeToString(RuntimeVersions.serializer(), versions)), "linux", architecture)
            val resolved = resolveSwarmKit(config) { image }
            val enabled = resolved.sites.single()
            val output = temporary.root.toPath().resolve(family)
            renderSwarm(resolved, output)
            val applications = listOf("server", "runner").map { app ->
                val configFile = output.resolve("config/sites/${site.id}/$app.yaml")
                ApplicationConfig.load(configFile.readText().byteInputStream())
            }
            val profiles = applications.map { swarmJson.decodeFromString(KitHarnessDeployment.serializer(), it.property("kit.harness").getString()) }
            assertEquals(profiles[0], profiles[1])
            assertEquals(image.reference, profiles[0].profiles.single().image)
            assertEquals(versions, profiles[0].profiles.single().versions)
            assertEquals("http://kit-egress:3128", profiles[0].profiles.single().egressProxy)
            assertEquals(listOf(if (family == "codex") "api.openai.com:443" else "api.anthropic.com:443"), profiles[0].profiles.single().egressHosts)
            val controller = swarmJson.decodeFromString(SwarmRuntimeConfiguration.serializer(), output.resolve("config/sites/${site.id}/kit-runtime.json").readText())
            val profile = controller.profiles.getValue("kit-$family")
            assertEquals(buildJsonArray { add(buildJsonObject { put("OS", "linux"); put("Architecture", architecture) }) },
                profile.service["TaskTemplate"]?.jsonObject?.get("Placement")?.jsonObject?.get("Platforms"))
            validateSwarmRuntime(profile, image.reference)
            // Runtimes join only the site's internal overlay; the server, runner and egress proxy share it with them.
            val runtimeNetwork = kitRuntimeNetwork(enabled)
            val egressNetwork = kitEgressNetwork(enabled)
            assertEquals(buildJsonArray { add(buildJsonObject { put("Target", runtimeNetwork) }) },
                profile.service["TaskTemplate"]?.jsonObject?.get("Networks"))
            val siteStack = siteStack(resolved, enabled)
            @Suppress("UNCHECKED_CAST")
            val siteServices = siteStack["services"] as Map<String, Map<String, Any>>
            for ((serviceName, definition) in siteServices) {
                @Suppress("UNCHECKED_CAST")
                val networks = definition["networks"] as Map<String, Any>
                val expected = when (serviceName) {
                    "server", "runner" -> setOf("bosca", runtimeNetwork)
                    "kit-egress" -> setOf(runtimeNetwork, egressNetwork)
                    else -> setOf("bosca")
                }
                assertEquals(expected, networks.keys, serviceName)
            }
            assertEquals(mapOf("bosca" to mapOf("external" to true), runtimeNetwork to mapOf("external" to true), egressNetwork to mapOf("external" to true)),
                siteStack["networks"])
            val proxy = siteServices.getValue("kit-egress")
            assertEquals(mapOf(runtimeNetwork to mapOf("aliases" to listOf("kit-egress")), egressNetwork to mapOf("aliases" to listOf("kit-egress"))), proxy["networks"])
            assertEquals(resolved.images.getValue("kit-controller"), proxy["image"])
            @Suppress("UNCHECKED_CAST")
            val command = proxy["command"] as List<String>
            assertEquals(listOf("harness", "egress", "--port", "3128", "--bosca-url", "http://${site.id}-server:8080"), command.take(6))
            val allowed = command.drop(6).chunked(2).map { (option, value) -> assertEquals("--allow", option); value }
            assertEquals((enabled.hosts.all - enabled.hosts.www).map { "$it:443" }, allowed)
            assertTrue("${site.id}_kit-egress" in expectedServices(resolved))
            assertEquals(setOf(if (family == "codex") "OPENAI_API_KEY" else "ANTHROPIC_API_KEY"), profile.secretEnvironment.keys)
            assertEquals(kitProviderSecret(enabled), profile.secretEnvironment.values.single())
            assertFalse(output.resolve("config/sites/${site.id}/kit-runtime.json").readText().contains(site.secrets.kitProvider))
            val stack = output.resolve("stacks/${site.id}.yml").readText()
            assertTrue(stack.contains("/var/run/docker.sock:/var/run/docker.sock:ro"))
            assertFalse(stack.contains(site.secrets.kitProvider))
            assertTrue("${site.id}_kit-controller" in expectedServices(resolved))
            assertEquals(site.secrets.kitProvider, output.resolve("config/sites/${site.id}/kit-provider").readText())
            val encrypted = SwarmSecretEncryption.encrypt(swarmJson.encodeToJsonElement(SwarmConfig.serializer(), config).jsonObject, "test-passphrase".toCharArray())
            assertFalse(encrypted.toString().contains(site.secrets.kitProvider))
            assertFalse(encrypted.toString().contains(enabled.secrets.kitController))
        }
    }

    @Test fun `runtime registry authentication uses the existing registry login and a manager-only Secret`() = runBlocking {
        val site = SwarmConfig().sites.first().copy(kit = SwarmKit(enabled = true), secrets = SwarmSiteSecrets(kitProvider = "provider-fixture"))
        val auth = SwarmRegistryAuth("registry.invalid", "operator", password = "fixture-registry-password")
        val config = SwarmConfig(sites = listOf(site), registryAuth = auth).withSecrets()
        val versions = RuntimeVersions("6.0.0", "adapter", "harness", "codex-home-v1")
        val image = ImageInspection("registry.invalid/kit@sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64),
            mapOf("io.bosca.kit.versions" to swarmJson.encodeToString(RuntimeVersions.serializer(), versions)), "linux", "amd64")
        val resolved = resolveSwarmKit(config) { image }
        val output = temporary.root.toPath().resolve("private-registry")
        renderSwarm(resolved, output)
        val folder = output.resolve("config/sites/${site.id}")
        val credential = folder.resolve("kit-registry").readText()
        assertEquals(SwarmRuntimeRegistryAuth(auth.username, auth.password, auth.server),
            swarmJson.decodeFromString(SwarmRuntimeRegistryAuth.serializer(), credential))
        val runtime = folder.resolve("kit-runtime.json").readText()
        assertEquals("kit-registry", swarmJson.decodeFromString(SwarmRuntimeConfiguration.serializer(), runtime).profiles.getValue("kit-codex").registryAuthSecret)
        assertFalse(runtime.contains(auth.password))
        val stack = output.resolve("stacks/${site.id}.yml").readText()
        assertFalse(stack.contains(auth.password))
        assertContains(stack, kitRegistrySecret(resolved.sites.single(), credential))
        val mounts = swarmJson.parseToJsonElement(stack).jsonObject.getValue("services").jsonObject
            .getValue("kit-controller").jsonObject.getValue("secrets").jsonArray
        assertTrue(mounts.any { it.jsonObject["target"]?.jsonPrimitive?.content == "kit-registry" })
        assertFalse(kitControllerConfiguration(resolved.copy(registryAuth = auth.copy(server = "other.invalid")), resolved.sites.single()).contains("registryAuthSecret"))
    }

    @Test fun `egress allows the website host only when the site serves one`() {
        val site = SwarmConfig().sites.first()
        assertFalse("${site.hosts.www}:443" in kitEgressAllow(site))
        assertTrue("${site.hosts.www}:443" in kitEgressAllow(site.copy(rootImage = "registry.invalid/site:1")))
        assertTrue(kitEgressAllow(site).containsAll(listOf("${site.hosts.root}:443", "${site.hosts.studio}:443", "${site.hosts.git}:443")))
    }

    @Test fun `deployment creates an internal runtime overlay and a routable egress overlay`() {
        val site = SwarmConfig().sites.first().copy(kit = SwarmKit(enabled = true))
        val (runtime, egress) = kitNetworkCommands(site)
        assertContains(runtime, "docker network create --driver overlay --internal ${site.id}-kit-runtime")
        assertContains(runtime, "--format '{{.Internal}}' ${site.id}-kit-runtime")
        assertContains(egress, "docker network create --driver overlay ${site.id}-kit-egress")
        assertFalse(egress.contains("--internal"))
        val bash = { script: String, network: String -> ProcessBuilder("bash", "-c", "sudo() { shift; \"\$@\"; }\n" +
            "docker() { if [ \"\$1 \$2\" = 'network inspect' ]; then [ $network != missing ] || return 1; echo $network; else echo \"created \$*\"; fi; }\n" + script)
            .redirectErrorStream(true).start().let { it.inputStream.bufferedReader().readText() to it.waitFor() } }
        assertEquals("" to 0, bash(runtime, "true"))
        assertEquals("created network create --driver overlay --internal ${site.id}-kit-runtime\n" to 0, bash(runtime, "missing"))
        val (message, status) = bash(runtime, "false")
        assertEquals(1, status)
        assertContains(message, "${site.id}-kit-runtime is not internal")
    }

    @Test fun `disabled Kit stays offline and enabled Kit requires its configured provider credential`(): Unit = runBlocking {
        val config = SwarmConfig().withSecrets()
        assertEquals(config, resolveSwarmKit(config) { error("Disabled Kit must not resolve images") })
        val site = config.sites.first().copy(kit = SwarmKit(enabled = true))
        assertFailsWith<IllegalArgumentException> { resolveSwarmKit(config.copy(sites = listOf(site))) { error("Check provider configuration before registry access") } }
    }
}
