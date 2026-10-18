package bosca.storage.service

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Local HTTP endpoint for exercising real SDK requests and scoped client lifetimes. */
internal class ObjectStorageS3TestEndpoint(
    private val bucket: String = "test-bucket",
    private val content: String = "data",
    private val headStatus: Int = 200,
    private val getStatus: Int = 200,
    private val objectHeadStatus: Int = 200,
    private val objectLength: Long = content.toByteArray().size.toLong(),
    private val objectEtag: String? = null,
    private val objectVersion: String? = null,
    private val objectModified: String? = null,
    private val onList: ((Map<String, String>) -> Pair<Int, String>)? = null,
    private val onHead: () -> Unit = {},
) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private var created = false
    val paths = CopyOnWriteArrayList<String>()
    val authorizationHeaders = CopyOnWriteArrayList<String>()
    val bucketChecks = AtomicInteger()
    val bucketCreations = AtomicInteger()
    val objectHeads = AtomicInteger()
    val objectGets = AtomicInteger()
    val listQueries = CopyOnWriteArrayList<Map<String, String>>()
    val endpoint: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange ->
            exchange.use {
                paths.add(exchange.requestURI.path)
                authorizationHeaders.add(exchange.requestHeaders.getFirst("Authorization").orEmpty())
                val path = exchange.requestURI.path
                when {
                    exchange.requestMethod == "GET" && path == "/$bucket" -> {
                        val query = exchange.requestURI.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
                            URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to
                                URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8)
                        }
                        listQueries.add(query)
                        val (status, body) = onList?.invoke(query) ?: (404 to "")
                        val bytes = body.toByteArray()
                        exchange.responseHeaders.add("Content-Type", "application/xml")
                        exchange.sendResponseHeaders(status, bytes.size.toLong())
                        exchange.responseBody.write(bytes)
                    }
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
                    exchange.requestMethod == "HEAD" && path == "/$bucket/asset" -> {
                        objectHeads.incrementAndGet()
                        exchange.responseHeaders.add("Content-Length", objectLength.toString())
                        objectEtag?.let { exchange.responseHeaders.add("ETag", it) }
                        objectVersion?.let { exchange.responseHeaders.add("x-amz-version-id", it) }
                        objectModified?.let { exchange.responseHeaders.add("Last-Modified", it) }
                        exchange.sendResponseHeaders(objectHeadStatus, -1)
                    }
                    exchange.requestMethod == "GET" && path == "/$bucket/asset" -> {
                        objectGets.incrementAndGet()
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
