package bosca.cli.swarm

import bosca.server.config.ApplicationConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SwarmBackendTest {
    @Test
    fun `each backend is independently configured across every Bosca service and can be switched back`() {
        val directory = Files.createTempDirectory("swarm-backend-test-")
        try {
            val defaults = SwarmConfig(sites = (1..5).map {
                SwarmSite("site$it", "site$it.example.invalid", "Site $it", "noreply@site$it.example.invalid", it, atlas = SwarmAtlas(separateGit = true, separateArtifacts = true))
            }).withSecrets()
            val config = defaults.copy(sites = defaults.sites.mapIndexed { index, site ->
                when (index) {
                    0 -> site.copy(cacheBackend = "redis")
                    1 -> site.copy(pubsubBackend = "redis")
                    2 -> site.copy(jobQueueBackend = "redis")
                    3 -> site.copy(distributedLockBackend = "redis")
                    else -> site.copy(counterBackend = "redis")
                }
            })
            val path = directory.resolve("swarm.json")
            saveConfig(path, config)
            val loaded = loadConfig(path)
            assertEquals(config, loaded)
            assertEquals(listOf("redis", "nats", "nats", "nats", "nats"), loaded.sites.map { it.cacheBackend })
            assertEquals(listOf("nats", "redis", "nats", "nats", "nats"), loaded.sites.map { it.pubsubBackend })
            val bundle = renderSwarm(loaded, directory.resolve("generated"))
            loaded.sites.forEach { site ->
                val services = swarmJson.parseToJsonElement(Files.readString(bundle.resolve("stacks/${site.id}.yml")))
                    .jsonObject.getValue("services").jsonObject
                val applications = mapOf(
                    "server" to "server/bosca-server", "runner" to "server/bosca-runner",
                    "git" to "git/git-server", "collector" to "analytics/analytics-collector",
                    "artifacts" to "artifacts/artifacts-server", "atlas" to "server/atlas-server",
                )
                applications.forEach { (service, module) ->
                    val environment = services.getValue(service).jsonObject.getValue("environment").jsonObject
                        .mapValues { it.value.jsonPrimitive.content }
                    val process = ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin/java").toString(),
                        "-cp", System.getProperty("java.class.path"), SwarmBackendProbe::class.java.name,
                        Path.of("../$module/src/main/resources/${if (service == "atlas") "atlas-server" else "application"}.yaml").toAbsolutePath().toString(),
                    ).redirectErrorStream(true).apply { environment().putAll(environment) }.start()
                    val output = process.inputStream.bufferedReader().readText()
                    assertEquals(0, process.waitFor(), output)
                    val result = swarmJson.parseToJsonElement(output.lineSequence()
                        .single { it.startsWith("BACKEND=") }.substringAfter("=")).jsonObject
                    mapOf(
                        "cache.type" to site.cacheBackend, "jobQueue.factory" to site.jobQueueBackend,
                        "pubsub.type" to site.pubsubBackend, "distributedLock.type" to site.distributedLockBackend,
                        "counter.type" to site.counterBackend,
                    ).forEach { (key, backend) ->
                        assertEquals(backend, result.getValue(key).jsonPrimitive.content, "$service.$key")
                    }
                    assertEquals("dragonfly", result.getValue("redis.host").jsonPrimitive.content, service)
                    assertEquals("6379", result.getValue("redis.port").jsonPrimitive.content, service)
                    assertEquals(site.redisDatabase.toString(), result.getValue("redis.database").jsonPrimitive.content, service)
                    assertEquals("${site.id}_", result.getValue("redis.namespace").jsonPrimitive.content, service)
                }
            }
            saveConfig(path, loaded.copy(sites = loaded.sites.map { it.copy(
                cacheBackend = "nats", pubsubBackend = "nats", jobQueueBackend = "nats",
                distributedLockBackend = "nats", counterBackend = "nats",
            ) }))
            val restored = loadConfig(path)
            assertEquals(defaults, restored)
            renderSwarm(restored, bundle)
            assertEquals("nats", siteEnvironment(restored, restored.sites.first())["PUBSUB_TYPE"])
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    @Test
    fun `unsupported backends are rejected before deployment`() {
        val config = SwarmConfig().withSecrets()
        val setters: Map<String, (SwarmSite, String) -> SwarmSite> = mapOf(
            "cacheBackend" to { site, backend -> site.copy(cacheBackend = backend) },
            "pubsubBackend" to { site, backend -> site.copy(pubsubBackend = backend) },
            "jobQueueBackend" to { site, backend -> site.copy(jobQueueBackend = backend) },
            "distributedLockBackend" to { site, backend -> site.copy(distributedLockBackend = backend) },
            "counterBackend" to { site, backend -> site.copy(counterBackend = backend) },
        )
        setters.forEach { (setting, setter) ->
            listOf("", "Redis", "rabbitmq").forEach { backend ->
                val failure = assertFailsWith<IllegalArgumentException> {
                    config.copy(sites = config.sites.map { setter(it, backend) }).validate()
                }
                assertTrue(failure.message.orEmpty().contains(setting), failure.message)
            }
        }
    }

    @Test
    fun `Dragonfly data is backed up for jobs or counters independently of transient backends`() {
        val config = SwarmConfig(backup = SwarmBackup("sftp:backup@example.invalid:/bosca")).withSecrets()
        fun volumes(config: SwarmConfig): List<String> {
            @Suppress("UNCHECKED_CAST")
            val service = backupServices(config).getValue("backup") as Map<String, Any>
            @Suppress("UNCHECKED_CAST")
            return service.getValue("volumes") as List<String>
        }
        assertFalse("${config.dataRoot}/dragonfly:/data/dragonfly:ro" in volumes(config))
        val transient = config.copy(sites = config.sites.map {
            it.copy(cacheBackend = "redis", pubsubBackend = "redis", distributedLockBackend = "redis")
        })
        assertFalse("${config.dataRoot}/dragonfly:/data/dragonfly:ro" in volumes(transient))
        listOf<(SwarmSite) -> SwarmSite>(
            { it.copy(jobQueueBackend = "redis") }, { it.copy(counterBackend = "redis") },
        ).forEach { select ->
            val redis = config.copy(sites = config.sites.mapIndexed { index, site ->
                if (index == 0) select(site) else site
            })
            assertTrue("${config.dataRoot}/dragonfly:/data/dragonfly:ro" in volumes(redis))
            assertTrue("${config.dataRoot}/nats:/data/nats:ro" in volumes(redis))
        }
    }
}

object SwarmBackendProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val config = ApplicationConfig.loadFromFile(args.single())
        val keys = listOf("cache.type", "jobQueue.factory", "pubsub.type", "distributedLock.type", "counter.type",
            "redis.host", "redis.port", "redis.database", "redis.namespace")
        println("BACKEND=" + JsonObject(keys.associateWith { JsonPrimitive(config.property(it).getString()) }))
    }
}
