package bosca.backup

import bosca.core.annotations.Internal
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.routes.Download
import bosca.content.metadata.routes.DownloadFile
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.service.AuthenticationProviders
import bosca.serialization.UUID
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.server.netty.NettyServerEngine
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.UrlSigner
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Exercises the production download handlers over HTTP with recovered services and fixture identities. */
@OptIn(Internal::class, InternalDI::class)
internal class PostgresContentRecoveryHttpFixture(
    private val metadata: MetadataService,
    private val permissions: MetadataPermissionEvaluator,
    private val storage: ObjectStorageService,
    private val signer: UrlSigner,
) {
    suspend fun verify(
        current: Metadata,
        identities: Map<String, AuthenticatedPrincipal>,
        supplementaryId: UUID,
    ) {
        val application = BoscaApplication(ApplicationConfig.load("""
            bosca:
              server:
                worker-threads: 2
                codec-threads: 2
                request-threads: 2
                drain-timeout-ms: 0
                shutdown-timeout-ms: 5000
                compression-enabled: false
        """.trimIndent().byteInputStream()), ownsServices = false)
        provides<AuthenticationProviders>(singleton = true, overrideExisting = true) { AuthenticationProviders(arrayOf("fixture")) }
        application.routing {
            listOf(
                "/api/v1/content/metadata/download" to Download(metadata, permissions, storage, signer),
                "/content/file" to DownloadFile(metadata, permissions, storage, signer),
            ).forEach { (path, download) ->
                get(path) {
                    call.request.header("X-Recovery-Test-Principal")?.let { identities[it] }?.let {
                        call.authenticationContext.principal("fixture", it)
                    }
                    download.execute(call)
                }
            }
        }
        val engine = NettyServerEngine(application, 0)
        val failure = AtomicReference<Throwable>()
        val server = Thread({ engine.start() }, "recovered-content-http").apply {
            isDaemon = true
            uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error -> failure.set(error) }
            start()
        }
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        try {
            withTimeout(10_000) {
                while (engine.boundPort == null) {
                    failure.get()?.let { throw it }
                    delay(20)
                }
            }
            val base = "http://127.0.0.1:${assertNotNull(engine.boundPort)}"
            suspend fun request(path: String, principal: String? = null, range: String? = null, etag: String? = null): HttpResponse<String> =
                withContext(Dispatchers.IO) {
                    val builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10))
                    principal?.let { builder.header("X-Recovery-Test-Principal", it) }
                    range?.let { builder.header("Range", it) }
                    etag?.let { builder.header("If-None-Match", it) }
                    client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString())
                }
            val selected = "/api/v1/content/metadata/download?id=${current.id}"
            val response = request(selected, "reader")
            assertEquals(200, response.statusCode())
            assertEquals("image", response.body())
            assertEquals("5", response.headers().firstValue("Content-Length").orElse(null))
            assertEquals("W/\"recovered-current-etag\"", response.headers().firstValue("ETag").orElse(null))
            for (principal in listOf(null, "outsider", "scoped")) {
                assertEquals(403, request(selected, principal).statusCode())
            }
            val range = request(selected, "reader", range = "bytes=1-3")
            assertEquals(206, range.statusCode())
            assertEquals("mag", range.body())
            assertEquals("bytes 1-3/5", range.headers().firstValue("Content-Range").orElse(null))
            assertEquals("3", range.headers().firstValue("Content-Length").orElse(null))
            val conditional = request(selected, "reader", etag = "\"recovered-current-etag\"")
            assertEquals(304, conditional.statusCode())
            assertTrue(conditional.body().isEmpty())
            val file = request("/content/file?id=${current.id}&download=true", "reader")
            assertEquals(200, file.statusCode())
            assertEquals("image", file.body())
            assertEquals("attachment; filename=\"Saved image\"", file.headers().firstValue("Content-Disposition").orElse(null))

            val path = storage.getPath(current)
            val signed = storage.getSignedDownloadUrl(path, null, current, null, false).url
            assertTrue(signer.verify(URI.create(signed)))
            val signedUri = URI.create(signed)
            val signedPath = signedUri.rawPath + "?" + signedUri.rawQuery
            val signedResponse = request(signedPath)
            assertEquals(200, signedResponse.statusCode())
            assertEquals("image", signedResponse.body())
            assertEquals(403, request(signedPath.replace("id=${current.id}", "id=${current.id}&filename=true")).statusCode())

            val attachmentPath = storage.getPath(current, supplementaryId)
            val attachmentUrl = URI.create(storage.getSignedDownloadUrl(attachmentPath, null, current, supplementaryId, true).url)
            val attachment = request(attachmentUrl.rawPath + "?" + attachmentUrl.rawQuery)
            assertEquals(200, attachment.statusCode())
            assertEquals("thumbnail", attachment.body())
            assertEquals("9", attachment.headers().firstValue("Content-Length").orElse(null))
            assertEquals("image/png", attachment.headers().firstValue("Content-Type").orElse(null))
            assertEquals("attachment; filename=\"Thumbnail\"", attachment.headers().firstValue("Content-Disposition").orElse(null))

            val bytes = storage.getInputStream(path).use { it.readBytes() }
            try {
                storage.delete(path)
                val missing = request(selected, "reader")
                assertEquals(404, missing.statusCode())
                assertFalse(missing.body().contains("image"))
            } finally {
                ByteArrayInputStream(bytes).use { assertEquals(5L, storage.setInputStream(path, it)) }
            }
        } finally {
            client.close()
            engine.stopWithoutHalting()
            server.join(10_000)
            assertFalse(server.isAlive)
        }
    }
}
