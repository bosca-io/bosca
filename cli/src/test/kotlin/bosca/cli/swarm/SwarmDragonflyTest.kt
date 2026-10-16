package bosca.cli.swarm

import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SwarmDragonflyTest {
    @Test
    fun `Swarm and Compose use the same standalone Dragonfly configuration`() {
        val config = SwarmConfig()
        val swarmServices = infraStack(config)["services"] as Map<*, *>
        val swarm = swarmServices["dragonfly"] as Map<*, *>
        val compose = Yaml().load<Map<String, Any>>(Files.readString(Path.of("../docker-compose.yaml")))
        val composeServices = compose.getValue("services") as Map<*, *>
        val local = composeServices["dragonfly"] as Map<*, *>
        assertFalse(swarmServices.containsKey("redis"))
        assertFalse(composeServices.containsKey("redis"))
        assertFalse((compose.getValue("volumes") as Map<*, *>).containsKey("redis"))
        assertEquals("docker.dragonflydb.io/dragonflydb/dragonfly:v2.0.2", swarm["image"])
        for (field in listOf("image", "command", "ulimits", "stop_grace_period")) {
            assertEquals(swarm[field], local[field], field)
        }
        val command = swarm["command"] as List<*>
        assertFalse(command.any { it.toString().contains("cluster_mode") })
        assertTrue("--dir=/data" in command)
        assertTrue("--dbfilename=dump" in command)
        assertTrue("--df_snapshot_format=false" in command)
        assertTrue("--snapshot_cron=* * * * *" in command)
        assertEquals(listOf("${config.dataRoot}/dragonfly:/data"), swarm["volumes"])
        assertEquals(listOf("dragonfly:/data"), local["volumes"])
        assertEquals((swarm["healthcheck"] as Map<*, *>)["test"], (local["healthcheck"] as Map<*, *>)["test"])
        composeServices.values.forEach { value ->
            val service = value as Map<*, *>
            val environment = service["environment"] as? Map<*, *> ?: emptyMap<Any, Any>()
            if (environment.containsKey("REDIS_HOST")) assertEquals("dragonfly", environment["REDIS_HOST"])
            val dependencies = service["depends_on"] as? Map<*, *> ?: emptyMap<Any, Any>()
            assertFalse(dependencies.containsKey("redis"))
        }
        config.sites.forEach { site ->
            val services = siteStack(config, site)["services"] as Map<*, *>
            val studio = services["studio"] as Map<*, *>
            assertEquals("dragonfly", (studio["environment"] as Map<*, *>)["REDIS_HOST"])
        }
    }

    @Test
    fun `loading existing Redis configs adds Dragonfly without changing secrets or sites`() {
        val directory = Files.createTempDirectory("bosca-dragonfly-config-")
        try {
            val path = directory.resolve("swarm.json")
            val config = SwarmConfig().withSecrets()
            val old = config.copy(images = config.images - "dragonfly" + ("redis" to "redis:7.4.11-alpine"))
            saveConfig(path, old)
            val loaded = loadConfig(path)
            assertEquals(old.copy(images = old.images + ("dragonfly" to config.images.getValue("dragonfly"))), loaded)
            assertEquals(loaded, loadConfig(path))
            val service = (infraStack(loaded)["services"] as Map<*, *>)["dragonfly"] as Map<*, *>
            assertEquals(config.images.getValue("dragonfly"), service["image"])
            assertEquals(old.secrets, loaded.secrets)
            assertEquals(old.sites, loaded.sites)

            val pinned = loaded.copy(images = loaded.images + ("dragonfly" to "registry.example.com/dragonfly:v2.0.2"))
            saveConfig(path, pinned)
            assertEquals(pinned, loadConfig(path))
            val pinnedService = (infraStack(loadConfig(path))["services"] as Map<*, *>)["dragonfly"] as Map<*, *>
            assertEquals(pinned.images.getValue("dragonfly"), pinnedService["image"])
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
