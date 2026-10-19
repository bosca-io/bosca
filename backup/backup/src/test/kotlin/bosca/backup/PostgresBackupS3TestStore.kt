package bosca.backup

import bosca.storage.configuration.StorageConfiguration
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpExchange
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals

/** Independent retained endpoint exercising signed SDK multipart uploads and verified reads. */
internal class PostgresBackupS3TestStore(private val beforeRequest: () -> Unit) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val parts = ConcurrentHashMap<String, MutableMap<Int, ByteArray>>()
    val objects = ConcurrentHashMap<String, ByteArray>()
    val signatures = CopyOnWriteArrayList<String>()
    val writes = CopyOnWriteArrayList<String>()
    private val failures = CopyOnWriteArrayList<Throwable>()
    val configuration get() = StorageConfiguration(
        "s3", "", bucket = "retained", region = "us-east-1", forcePathStyle = true,
        endpoint = "http://127.0.0.1:${server.address.port}", accessKeyId = "POSTGRES_TRANSFER_KEY", accessKeySecret = "test-secret",
    )

    init {
        server.createContext("/") { exchange ->
            try {
                exchange.use {
                    beforeRequest()
                    signatures.add(exchange.requestHeaders.getFirst("Authorization").orEmpty())
                    val key = exchange.requestURI.path.removePrefix("/retained/")
                    val query = exchange.requestURI.rawQuery.orEmpty().split('&').associate {
                        URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to
                            URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8)
                    }
                    val body = when {
                        exchange.requestMethod == "POST" && "uploads" in query -> {
                            val id = UUID.randomUUID().toString()
                            parts[id] = ConcurrentHashMap()
                            "<InitiateMultipartUploadResult><Bucket>retained</Bucket><Key>$key</Key><UploadId>$id</UploadId></InitiateMultipartUploadResult>".toByteArray()
                        }
                        exchange.requestMethod == "PUT" && "uploadId" in query -> {
                            val bytes = partBody(exchange)
                            val checksum = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))
                            assertEquals(checksum, exchange.requestHeaders.getFirst("x-amz-checksum-sha256"))
                            checkNotNull(parts[query.getValue("uploadId")])[query.getValue("partNumber").toInt()] = bytes
                            exchange.responseHeaders.add("ETag", "\"part\"")
                            exchange.responseHeaders.add("x-amz-checksum-sha256", checksum)
                            ByteArray(0)
                        }
                        exchange.requestMethod == "POST" && "uploadId" in query -> {
                            exchange.requestBody.readBytes()
                            val selected = checkNotNull(parts.remove(query.getValue("uploadId"))).toSortedMap().values
                            objects[key] = selected.fold(ByteArray(0)) { all, part -> all + part }
                            writes.add(key)
                            "<CompleteMultipartUploadResult><Bucket>retained</Bucket><Key>$key</Key><ETag>\"complete\"</ETag></CompleteMultipartUploadResult>".toByteArray()
                        }
                        exchange.requestMethod == "DELETE" && "uploadId" in query -> {
                            parts.remove(query.getValue("uploadId"))
                            ByteArray(0)
                        }
                        exchange.requestMethod == "HEAD" -> {
                            val bytes = objects[key]
                            if (bytes != null) exchange.responseHeaders.add("Content-Length", bytes.size.toString())
                            exchange.sendResponseHeaders(if (bytes == null) 404 else 200, -1)
                            return@use
                        }
                        exchange.requestMethod == "GET" -> objects[key] ?: run {
                            val missing = "<Error><Code>NoSuchKey</Code></Error>".toByteArray()
                            exchange.sendResponseHeaders(404, missing.size.toLong())
                            exchange.responseBody.write(missing)
                            return@use
                        }
                        else -> error("Unexpected retained request: ${exchange.requestMethod} ${exchange.requestURI}")
                    }
                    exchange.sendResponseHeaders(200, if (body.isEmpty()) -1 else body.size.toLong())
                    if (body.isNotEmpty()) exchange.responseBody.write(body)
                }
            } catch (failure: Throwable) {
                failures.add(failure)
                exchange.close()
            }
        }
        server.start()
    }

    override fun close() {
        server.stop(0)
        failures.firstOrNull()?.let { throw AssertionError("Retained S3 fixture failed", it) }
    }

    /** S3's signed aws-chunked framing is separate from HTTP transfer encoding. */
    private fun partBody(exchange: HttpExchange): ByteArray {
        val raw = exchange.requestBody.readBytes()
        if (!exchange.requestHeaders.getFirst("Content-Encoding").orEmpty().contains("aws-chunked")) return raw
        val input = raw.inputStream()
        val decoded = ByteArrayOutputStream()
        while (true) {
            val header = StringBuilder()
            while (true) {
                val value = input.read()
                check(value >= 0) { "Incomplete signed chunk header" }
                if (value == 13) {
                    assertEquals(10, input.read())
                    break
                }
                header.append(value.toChar())
            }
            val size = header.toString().substringBefore(';').toInt(16)
            if (size == 0) break
            val chunk = input.readNBytes(size)
            assertEquals(size, chunk.size)
            decoded.write(chunk)
            assertEquals(13, input.read())
            assertEquals(10, input.read())
        }
        val bytes = decoded.toByteArray()
        assertEquals(exchange.requestHeaders.getFirst("x-amz-decoded-content-length").toLong(), bytes.size.toLong())
        return bytes
    }
}
