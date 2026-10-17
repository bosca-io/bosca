package bosca.storage.service

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Local HTTP endpoint for exercising real SDK requests and scoped client lifetimes. */
internal class ObjectStorageS3TestEndpoint(
    private val bucket: String = "test-bucket",
    private val content: String = "data",
    private val headStatus: Int = 200,
    private val getStatus: Int = 200,
    private val onHead: () -> Unit = {},
) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private var created = false
    val paths = CopyOnWriteArrayList<String>()
    val authorizationHeaders = CopyOnWriteArrayList<String>()
    val bucketChecks = AtomicInteger()
    val bucketCreations = AtomicInteger()
    val endpoint: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange ->
            exchange.use {
                paths.add(exchange.requestURI.path)
                authorizationHeaders.add(exchange.requestHeaders.getFirst("Authorization").orEmpty())
                val path = exchange.requestURI.path
                when {
                    exchange.requestMethod == "HEAD" && path == "/$bucket" -> {
                        bucketChecks.incrementAndGet()
                        onHead()
                        exchange.sendResponseHeaders(if (created) 200 else headStatus, -1)
                    }
                    exchange.requestMethod == "PUT" && path == "/$bucket" -> {
                        exchange.requestBody.readBytes()
                        bucketCreations.incrementAndGet()
                        created = true
                        exchange.sendResponseHeaders(200, -1)
                    }
                    exchange.requestMethod == "GET" && path == "/$bucket/asset" -> {
                        val bytes = if (getStatus == 200) content.toByteArray() else
                            "<Error><Code>AccessDenied</Code><Message>Denied</Message></Error>".toByteArray()
                        exchange.sendResponseHeaders(getStatus, bytes.size.toLong())
                        exchange.responseBody.write(bytes)
                    }
                    else -> exchange.sendResponseHeaders(404, -1)
                }
            }
        }
        server.start()
    }

    override fun close() {
        server.stop(0)
    }
}
