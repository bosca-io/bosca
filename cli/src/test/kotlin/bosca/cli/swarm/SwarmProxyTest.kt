package bosca.cli.swarm

import com.sun.net.httpserver.HttpServer
import org.junit.Assume.assumeTrue
import org.testcontainers.DockerClientFactory
import org.testcontainers.Testcontainers
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.MountableFile
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.cert.X509Certificate
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Flow
import java.util.concurrent.SubmissionPublisher
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SwarmProxyTest {
    @Test
    fun `Caddy streams responses before uploads finish over HTTP1 and HTTP2`() {
        assumeTrue("Docker is required for the Caddy integration test", DockerClientFactory.instance().isDockerAvailable)
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val upstream = HttpServer.create(InetSocketAddress("0.0.0.0", 0), 0)
        upstream.executor = executor
        upstream.createContext("/duplex") { exchange ->
            exchange.use {
                println("upstream entered")
                val first = exchange.requestBody.readNBytes(3)
                println("upstream read ${String(first)}")
                exchange.responseHeaders.add("Content-Type", "application/octet-stream")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.write(first)
                exchange.responseBody.flush()
                println("upstream flushed")
                exchange.responseBody.write(exchange.requestBody.readNBytes(3))
            }
        }
        upstream.start()
        val directory = Files.createTempDirectory("bosca-swarm-proxy-test-")
        try {
            Testcontainers.exposeHostPorts(upstream.address.port)
            val config = SwarmConfig(sites = listOf(SwarmSite("site1", "localhost", "Test", "test@localhost", 1)))
            val caddyfile = directory.resolve("Caddyfile")
            Files.writeString(caddyfile, caddyfile(config)
                .replace("{\n    servers", "{\n    skip_install_trust\n    servers")
                .replace("site1-server:8080", "host.testcontainers.internal:${upstream.address.port}"))
            GenericContainer(config.images.getValue("caddy")).apply {
                withExposedPorts(443)
                withCopyFileToContainer(MountableFile.forHostPath(caddyfile), "/etc/caddy/Caddyfile")
                waitingFor(Wait.forListeningPort())
            }.use { caddy ->
                caddy.start()
                val uri = URI("https://localhost:${caddy.getMappedPort(443)}/duplex")
                try {
                    assertDuplex(uri, HttpClient.Version.HTTP_1_1)
                    assertDuplex(uri, HttpClient.Version.HTTP_2)
                } catch (failure: Throwable) {
                    println(caddy.logs)
                    throw failure
                }
            }
        } finally {
            upstream.stop(0)
            executor.close()
            Files.deleteIfExists(directory.resolve("Caddyfile"))
            Files.deleteIfExists(directory)
        }
    }

    private fun assertDuplex(uri: URI, version: HttpClient.Version) {
        // Caddy's isolated test container issues its own certificate for localhost.
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), null) }
        val subscribed = CountDownLatch(1)
        val upload = object : SubmissionPublisher<ByteBuffer>() {
            override fun subscribe(subscriber: Flow.Subscriber<in ByteBuffer>) {
                super.subscribe(subscriber)
                subscribed.countDown()
            }
        }
        HttpClient.newBuilder().sslContext(ssl).version(version).build().use { client ->
            upload.use {
                    val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                        .POST(HttpRequest.BodyPublishers.fromPublisher(upload)).build()
                    val pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                    try {
                        assertTrue(subscribed.await(10, TimeUnit.SECONDS), "HTTP client subscribed to the upload")
                        upload.submit(ByteBuffer.wrap("one".toByteArray()))
                        val response = pending.get(10, TimeUnit.SECONDS)
                        assertEquals(version, response.version())
                        assertEquals(200, response.statusCode())
                        response.body().use { body ->
                            assertEquals("one", String(body.readNBytes(3)))
                            upload.submit(ByteBuffer.wrap("two".toByteArray()))
                            upload.close()
                            assertEquals("two", String(body.readAllBytes()))
                        }
                    } finally {
                        pending.cancel(true)
                    }
            }
        }
    }
}
