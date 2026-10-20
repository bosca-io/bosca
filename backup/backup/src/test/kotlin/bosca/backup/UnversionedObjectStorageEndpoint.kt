package bosca.backup

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Unversioned S3 HTTP storage, including real SDK multipart writes and current-object reads. */
internal class UnversionedObjectStorageEndpoint : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val sequence = AtomicInteger()
    private val objects = ConcurrentHashMap<String, ByteArray>()
    private val tags = ConcurrentHashMap<String, String>()
    private val modified = ConcurrentHashMap<String, String>()
    private val uploads = ConcurrentHashMap<String, String>()
    private val parts = ConcurrentHashMap<String, MutableMap<Int, ByteArray>>()
    val bodies = CopyOnWriteArrayList<String>()
    val writes = CopyOnWriteArrayList<String>()
    val signatures = CopyOnWriteArrayList<String>()
    var failRead: String? = null
    var afterRead: ((String) -> Unit)? = null
    val endpoint: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange ->
            exchange.use { handle(it) }
        }
        server.start()
    }

    fun put(path: String, bytes: ByteArray, preserveMetadata: Boolean = false) {
        objects[path] = bytes.copyOf()
        if (!preserveMetadata) {
            val observation = sequence.incrementAndGet()
            tags[path] = "\"opaque-$observation\""
            modified[path] = DateTimeFormatter.RFC_1123_DATE_TIME.format(
                Instant.parse("2026-10-20T00:00:00Z").plusSeconds(observation.toLong()).atOffset(ZoneOffset.UTC),
            )
        }
    }

    fun bytes(path: String): ByteArray = checkNotNull(objects[path]).copyOf()

    fun resetCounters() {
        bodies.clear()
        writes.clear()
        signatures.clear()
    }

    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path.removePrefix("/")
        val query = exchange.requestURI.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
            URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to
                URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8)
        }
        signatures.add(exchange.requestHeaders.getFirst("Authorization").orEmpty())
        if ("versionId" in query) {
            respond(exchange, 400, "<Error><Code>InvalidArgument</Code></Error>")
            return
        }
        when {
            query["list-type"] == "2" -> {
                val prefix = "$path/${query["prefix"].orEmpty()}"
                val found = objects.keys.filter { it.startsWith(prefix) }.sorted()
                respond(exchange, 200, "<ListBucketResult><IsTruncated>false</IsTruncated>" + found.joinToString("") { key ->
                    "<Contents><Key>${key.substringAfter('/')}</Key><Size>${objects.getValue(key).size}</Size></Contents>"
                } + "</ListBucketResult>")
            }
            exchange.requestMethod == "POST" && "uploads" in query -> {
                exchange.requestBody.readBytes()
                val id = "upload-${sequence.incrementAndGet()}"
                uploads[id] = path
                parts[id] = ConcurrentHashMap()
                respond(exchange, 200, "<InitiateMultipartUploadResult><Bucket>${path.substringBefore('/')}</Bucket>" +
                    "<Key>${path.substringAfter('/')}</Key><UploadId>$id</UploadId></InitiateMultipartUploadResult>")
            }
            exchange.requestMethod == "PUT" && "uploadId" in query -> {
                val id = query.getValue("uploadId")
                check(uploads[id] == path)
                writes.add(path)
                parts.getValue(id)[query.getValue("partNumber").toInt()] = uploadBytes(exchange)
                exchange.responseHeaders.add("ETag", "\"part\"")
                exchange.sendResponseHeaders(200, -1)
            }
            exchange.requestMethod == "POST" && "uploadId" in query -> {
                val id = query.getValue("uploadId")
                check(uploads.remove(id) == path)
                exchange.requestBody.readBytes()
                val chunks = checkNotNull(parts.remove(id)).toSortedMap().values
                val output = ByteArrayOutputStream()
                chunks.forEach { output.write(it) }
                put(path, output.toByteArray())
                respond(exchange, 200, "<CompleteMultipartUploadResult><Bucket>${path.substringBefore('/')}</Bucket>" +
                    "<Key>${path.substringAfter('/')}</Key><ETag>${tags.getValue(path).replace("\"", "&quot;")}</ETag></CompleteMultipartUploadResult>")
            }
            exchange.requestMethod == "DELETE" -> {
                query["uploadId"]?.let { uploads.remove(it); parts.remove(it) } ?: objects.remove(path)
                exchange.sendResponseHeaders(204, -1)
            }
            exchange.requestMethod == "HEAD" -> {
                val value = objects[path]
                if (value == null) exchange.sendResponseHeaders(404, -1)
                else {
                    exchange.responseHeaders.add("Content-Length", value.size.toString())
                    exchange.responseHeaders.add("ETag", tags.getValue(path))
                    exchange.responseHeaders.add("Last-Modified", modified.getValue(path))
                    exchange.sendResponseHeaders(200, -1)
                }
            }
            exchange.requestMethod == "GET" -> {
                if (!path.substringAfter('/').startsWith("manifests/")) bodies.add(path)
                val value = objects[path]
                when {
                    path == failRead -> respond(exchange, 403, "<Error><Code>AccessDenied</Code></Error>")
                    value == null -> respond(exchange, 404, "<Error><Code>NoSuchKey</Code></Error>")
                    else -> {
                        afterRead?.invoke(path)
                        exchange.sendResponseHeaders(200, value.size.toLong())
                        exchange.responseBody.write(value)
                    }
                }
            }
            else -> respond(exchange, 405, "<Error><Code>MethodNotAllowed</Code></Error>")
        }
    }

    private fun respond(exchange: HttpExchange, status: Int, content: String) {
        val bytes = content.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/xml")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    private fun uploadBytes(exchange: HttpExchange): ByteArray {
        val input = exchange.requestBody.buffered()
        if (!exchange.requestHeaders.getFirst("Content-Encoding").orEmpty().contains("aws-chunked")) {
            return input.readBytes()
        }
        val decoded = ByteArrayOutputStream()
        while (true) {
            val line = buildString {
                while (true) {
                    val byte = input.read()
                    check(byte != -1) { "Incomplete AWS chunk header" }
                    if (byte == '\n'.code) break
                    if (byte != '\r'.code) append(byte.toChar())
                }
            }
            val size = line.substringBefore(';').toInt(16)
            if (size == 0) {
                input.readBytes()
                return decoded.toByteArray()
            }
            val bytes = input.readNBytes(size)
            check(bytes.size == size) { "Incomplete AWS chunk body" }
            decoded.write(bytes)
            check(input.read() == '\r'.code && input.read() == '\n'.code)
        }
    }

    override fun close() = server.stop(0)
}
