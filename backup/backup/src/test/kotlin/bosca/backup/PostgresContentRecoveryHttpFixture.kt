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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exercises the production download handlers over HTTP with recovered services and fixture identities. */
@OptIn(Internal::class, InternalDI::class)
internal class PostgresContentRecoveryHttpFixture(
    private val metadata: MetadataService,
    private val permissions: MetadataPermissionEvaluator,
    private val storage: ObjectStorageService,
    private val signer: UrlSigner,
) {
    suspend fun verify(history: Metadata, identities: Map<String, AuthenticatedPrincipal>) {
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
            val current = "/api/v1/content/metadata/download?id=${history.id}"
            val selected = "$current&version=${history.version}"
            val earlier = request(selected, "reader")
            assertEquals(200, earlier.statusCode())
            assertEquals("earlier", earlier.body())
            assertEquals("7", earlier.headers().firstValue("Content-Length").orElse(null))
            assertNull(earlier.headers().firstValue("ETag").orElse(null))
            assertEquals("image", request(current, "reader").body())
            assertEquals("image", request("$current&version=7", "reader").body())
            for (principal in listOf(null, "outsider", "scoped")) {
                assertEquals(403, request(selected, principal).statusCode())
            }
            assertEquals(404, request("$current&version=5", "reader").statusCode())
            for (version in listOf("", "invalid", "0", "-1", "2147483648")) {
                assertEquals(400, request("$current&version=$version", "reader").statusCode())
            }
            val range = request(selected, "reader", range = "bytes=1-3")
            assertEquals(206, range.statusCode())
            assertEquals("arl", range.body())
            assertEquals("bytes 1-3/7", range.headers().firstValue("Content-Range").orElse(null))
            assertEquals("3", range.headers().firstValue("Content-Length").orElse(null))
            val conditional = request(selected, "reader", etag = "\"recovered-current-etag\"")
            assertEquals(200, conditional.statusCode())
            assertEquals("earlier", conditional.body())
            val file = request("/content/file?id=${history.id}&version=6&download=true", "reader")
            assertEquals(200, file.statusCode())
            assertEquals("earlier", file.body())
            assertEquals("attachment; filename=\"Earlier image\"", file.headers().firstValue("Content-Disposition").orElse(null))

            val path = storage.getPath(history)
            val signed = storage.getSignedDownloadUrl(path, null, history, null, false).url
            assertTrue(signer.verify(URI.create(signed)))
            val signedUri = URI.create(signed)
            val signedPath = signedUri.rawPath + "?" + signedUri.rawQuery
            assertTrue(signedPath.contains("version=6"), signedPath)
            val signedResponse = request(signedPath)
            assertEquals(200, signedResponse.statusCode())
            assertEquals("earlier", signedResponse.body())
            assertEquals(403, request(signedPath.replace("version=6", "version=7")).statusCode())
            assertEquals(403, request(signedPath.replace("&version=6", "")).statusCode())
            val currentSigned = URI.create(signer.sign("https://recovered.invalid$current", 1800))
            assertEquals("image", request(currentSigned.rawPath + "?" + currentSigned.rawQuery).body())

            val bytes = storage.getInputStream(path).use { it.readBytes() }
            try {
                storage.delete(path)
                val missing = request(selected, "reader")
                assertEquals(404, missing.statusCode())
                assertFalse(missing.body().contains("image"))
            } finally {
                ByteArrayInputStream(bytes).use { assertEquals(7L, storage.setInputStream(path, it)) }
            }
        } finally {
            client.close()
            engine.stopWithoutHalting()
            server.join(10_000)
            assertFalse(server.isAlive)
        }
    }
}
