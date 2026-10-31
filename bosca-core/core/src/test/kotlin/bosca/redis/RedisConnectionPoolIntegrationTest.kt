package bosca.redis

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals

class RedisConnectionPoolIntegrationTest {

    @Test
    fun `Dragonfly connections can finish a handshake longer than five seconds`() = runBlocking {
        val dragonfly = GenericContainer<Nothing>(
            DockerImageName.parse("docker.dragonflydb.io/dragonflydb/dragonfly:v2.0.2"),
        ).apply {
            withExposedPorts(6379)
            withCommand("--lock_on_hashtags", "--default_lua_flags=allow-undeclared-keys")
        }
        dragonfly.start()
        val downstream = AtomicReference<Socket>()
        val upstream = AtomicReference<Socket>()
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val proxy = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val pool = RedisConnectionPool("127.0.0.1", proxy.localPort, 1, "connection-test", 1)
        try {
            executor.submit {
                proxy.accept().use { client ->
                    downstream.set(client)
                    Socket(dragonfly.host, dragonfly.getMappedPort(6379)).use { server ->
                        upstream.set(server)
                        executor.submit { client.getInputStream().copyTo(server.getOutputStream()) }
                        val input = server.getInputStream()
                        val buffer = ByteArray(8192)
                        val count = input.read(buffer)
                        if (count >= 0) {
                            Thread.sleep(6_000)
                            client.getOutputStream().write(buffer, 0, count)
                            input.copyTo(client.getOutputStream())
                        }
                    }
                }
            }
            withTimeout(30_000) {
                val connection = pool.connection()
                try {
                    assertEquals("PONG", connection.sync().ping())
                } finally {
                    pool.release(connection)
                }
            }
        } finally {
            pool.close()
            proxy.close()
            downstream.get()?.close()
            upstream.get()?.close()
            executor.shutdownNow()
            dragonfly.stop()
        }
    }
}
