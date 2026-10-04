package bosca.cli.swarm

import com.sun.net.httpserver.HttpServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.junit.Assume.assumeTrue
import org.testcontainers.DockerClientFactory
import org.testcontainers.Testcontainers
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.MountableFile
import java.io.DataInputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.InetAddress
import java.net.URI
import java.nio.file.Files
import java.security.cert.X509Certificate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
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
                val request = DataInputStream(exchange.requestBody)
                val first = ByteArray(3).also(request::readFully)
                exchange.responseHeaders.add("Content-Type", "application/octet-stream")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.write(first)
                exchange.responseBody.flush()
                exchange.responseBody.write(ByteArray(3).also(request::readFully))
            }
        }
        upstream.start()
        val directory = Files.createTempDirectory("bosca-swarm-proxy-test-")
        try {
            Testcontainers.exposeHostPorts(upstream.address.port)
            val config = SwarmConfig(sites = listOf(SwarmSite("site1", "localhost", "Test", "test@localhost", 1)))
            val caddyfile = directory.resolve("Caddyfile")
            Files.writeString(
                caddyfile, caddyfile(config)
                    .replace("{\n    servers", "{\n    skip_install_trust\n    servers")
                    .replace("site1-server:8080", "host.testcontainers.internal:${upstream.address.port}")
            )
            GenericContainer(config.images.getValue("caddy")).apply {
                withExposedPorts(443)
                withCopyFileToContainer(MountableFile.forHostPath(caddyfile), "/etc/caddy/Caddyfile")
                waitingFor(Wait.forListeningPort())
            }.use { caddy ->
                caddy.start()
                val uri = URI("https://localhost:${caddy.getMappedPort(443)}/duplex")
                try {
                    assertHttp1Duplex(uri)
                    assertHttp2Duplex(uri)
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

    // Caddy's isolated test container issues its own certificate for localhost.
    private val testTrust = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
    }

    private fun testSslContext(): SSLContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(testTrust), null)
    }

    private fun assertHttp1Duplex(uri: URI) {
        (testSslContext().socketFactory.createSocket(uri.host, uri.port) as SSLSocket).use { socket ->
            socket.soTimeout = 10_000
            socket.sslParameters = socket.sslParameters.apply {
                endpointIdentificationAlgorithm = "HTTPS"
                applicationProtocols = arrayOf("http/1.1")
                serverNames = listOf(SNIHostName(uri.host))
            }
            socket.startHandshake()
            val output = socket.outputStream
            val input = DataInputStream(socket.inputStream)
            output.write(
                ("POST /duplex HTTP/1.1\r\nHost: ${uri.authority}\r\n" +
                        "Content-Length: 6\r\nConnection: close\r\n\r\none").toByteArray()
            )
            output.flush()
            assertEquals("HTTP/1.1 200 OK", readLine(input))
            val headers = generateSequence { readLine(input).takeIf(String::isNotEmpty) }.toList()
            assertTrue(headers.any { it.equals("Transfer-Encoding: chunked", ignoreCase = true) })
            assertEquals("one", readChunk(input))
            output.write("two".toByteArray())
            output.flush()
            assertEquals("two", readChunk(input))
            assertEquals("", readChunk(input))
        }
    }

    private fun readLine(input: InputStream): String = buildString {
        while (true) {
            val byte = input.read()
            check(byte >= 0) { "Unexpected end of HTTP response" }
            if (byte == '\n'.code) break
            if (byte != '\r'.code) append(byte.toChar())
        }
    }

    private fun readChunk(input: DataInputStream): String {
        val size = readLine(input).substringBefore(';').toInt(16)
        val chunk = ByteArray(size).also(input::readFully)
        assertEquals("", readLine(input))
        return String(chunk)
    }

    private fun assertHttp2Duplex(uri: URI) {
        val upload = CompletableFuture<BufferedSink>()
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun isDuplex() = true
            override fun writeTo(sink: BufferedSink) {
                sink.writeUtf8("one")
                sink.flush()
                upload.complete(sink)
            }
        }
        val client = OkHttpClient.Builder()
            .sslSocketFactory(testSslContext().socketFactory, testTrust)
            .dns { listOf(InetAddress.getByName(uri.host)) }
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
        try {
            val request = Request.Builder().url("https://api.localhost:${uri.port}/duplex").post(body).build()
            client.newCall(request).execute().use { response ->
                assertEquals(Protocol.HTTP_2, response.protocol)
                assertEquals(200, response.code)
                assertEquals("one", response.body.source().readUtf8(3))
                upload.get(10, TimeUnit.SECONDS).use { sink -> sink.writeUtf8("two") }
                assertEquals("two", response.body.string())
            }
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
