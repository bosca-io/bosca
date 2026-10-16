package bosca.cli.swarm

import org.junit.Assume.assumeTrue
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SwarmDragonflyIntegrationTest {
    @Test
    fun `fresh Dragonfly supports isolated caches and locks and persists on shutdown`() {
        assumeTrue("Docker is required for Dragonfly integration", DockerClientFactory.instance().isDockerAvailable)
        val saved = dragonfly().use { dragonfly ->
            dragonfly.start()
            assertTrue(command(dragonfly, 0, "INFO", "server").contains("dragonfly_version:df-v2.0.2"))
            for (database in listOf(0, 1, 2, 15)) assertEquals("0", command(dragonfly, database, "DBSIZE"))
            assertEquals("OK", command(dragonfly, 1, "SET", "shared-key", "site1"))
            assertEquals("OK", command(dragonfly, 2, "SET", "shared-key", "site2"))
            assertEquals("7", command(dragonfly, 15, "INCRBY", "counter", "7"))
            assertEquals("1-0", command(dragonfly, 1, "XADD", "queue", "1-0", "payload", "job"))
            assertEquals("site1", command(dragonfly, 1, "GET", "shared-key"))
            assertEquals("site2", command(dragonfly, 2, "GET", "shared-key"))
            assertEquals("7", command(dragonfly, 15, "GET", "counter"))
            assertEquals("1", command(dragonfly, 1, "XLEN", "queue"))
            assertEquals("site1", command(dragonfly, 1, "EVAL", "return redis.call('GET', ARGV[1])", "0", "shared-key"))
            assertEquals("OK", command(dragonfly, 1, "SET", "cache", "value", "PX", "60000"))
            assertTrue(command(dragonfly, 1, "PTTL", "cache").toLong() in 1..60000)
            assertEquals("OK", command(dragonfly, 1, "SET", "lock", "owner", "NX", "PX", "60000"))
            assertEquals("", command(dragonfly, 1, "SET", "lock", "other", "NX", "PX", "60000"))
            val release = "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end"
            assertEquals("0", command(dragonfly, 1, "EVAL", release, "1", "lock", "other"))
            assertEquals("1", command(dragonfly, 1, "EVAL", release, "1", "lock", "owner"))
            assertEquals("OK", command(dragonfly, 1, "SET", "lock", "next", "NX", "PX", "60000"))
            assertEquals("8", command(dragonfly, 15, "INCR", "counter"))
            dragonfly.dockerClient.stopContainerCmd(dragonfly.containerId).withTimeout(60).exec()
            dragonfly.copyFileFromContainer("/data/dump.rdb") { it.readBytes() }
        }
        dragonfly(saved).use { restarted ->
            restarted.start()
            assertEquals("site1", command(restarted, 1, "GET", "shared-key"))
            assertEquals("site2", command(restarted, 2, "GET", "shared-key"))
            assertEquals("8", command(restarted, 15, "GET", "counter"))
            assertEquals("1", command(restarted, 1, "XLEN", "queue"))
        }
    }

    private fun dragonfly(snapshot: ByteArray? = null): GenericContainer<*> {
        val config = SwarmConfig()
        val service = (infraStack(config)["services"] as Map<*, *>)["dragonfly"] as Map<*, *>
        val arguments = (service["command"] as List<*>).map { it.toString() }
        return GenericContainer(config.images.getValue("dragonfly")).apply {
            // Bound the test independently of the Docker host's CPU and memory count.
            withCommand(*(arguments + "--proactor_threads=2").toTypedArray())
            withCreateContainerCmdModifier { it.hostConfig?.withMemory(1024L * 1024 * 1024) }
            withNetworkMode("none")
            snapshot?.let { withCopyToContainer(Transferable.of(it), "/data/dump.rdb") }
            waitingFor(Wait.forSuccessfulCommand("redis-cli ping | grep -q PONG"))
        }
    }

    private fun command(container: GenericContainer<*>, database: Int, vararg arguments: String): String {
        val result = container.execInContainer("redis-cli", "--raw", "-n", database.toString(), *arguments)
        assertEquals(0, result.exitCode, result.stderr)
        return result.stdout.trim()
    }
}
