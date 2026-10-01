package bosca.bml.message.server

import bosca.bml.message.BmlMessageArtifacts
import bosca.server.BoscaApplication
import bosca.server.ContentType
import bosca.server.HttpHeaders
import bosca.server.HttpStatusCode
import org.slf4j.LoggerFactory
import java.util.jar.JarFile

/**
 * The PUBLIC asset surface — the server's ONLY public routes:
 * `GET /assets/{project}/{version}/{path...}` served from that VERSION's jar's bundled
 * `bml/public` tree. Version-pinned and immutable: a published version's assets never change,
 * and any published version stays servable forever (delivered emails reference their assets
 * indefinitely), fetched back from the registry on demand if the local cache was lost.
 */
object AssetRoutes {

    private val log = LoggerFactory.getLogger(AssetRoutes::class.java)

    /** A published version's bytes never change — cache hard, forever. */
    private const val IMMUTABLE = "public, max-age=31536000, immutable"

    fun install(application: BoscaApplication, cache: MessageJarCache) {
        application.router.get("/assets/{project}/{version}/{path...}") {
            val project = call.pathParameters["project"].orEmpty()
            val version = call.pathParameters["version"].orEmpty()
            val path = call.pathParameters["path"].orEmpty()
            if (project.isBlank() || version.isBlank() || path.isBlank() ||
                path.split('/').any { it == ".." || it.isEmpty() }
            ) {
                call.respond(HttpStatusCode.NotFound, "404 Not Found")
                return@get
            }
            val jar = try {
                cache.jarFor(project, version)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Unknown project/version — a public surface answers 404, never internals.
                log.warn("bml-message: asset lookup failed for {}@{}: {}", project, version, e.message)
                call.respond(HttpStatusCode.NotFound, "404 Not Found")
                return@get
            }
            val bytes = JarFile(jar).use { jarFile ->
                jarFile.getJarEntry("${BmlMessageArtifacts.ASSETS_RESOURCE_ROOT}/$path")
                    ?.takeIf { !it.isDirectory }
                    ?.let { entry -> jarFile.getInputStream(entry).use { it.readBytes() } }
            }
            if (bytes == null) {
                call.respond(HttpStatusCode.NotFound, "404 Not Found")
                return@get
            }
            call.response.header(HttpHeaders.CacheControl, IMMUTABLE)
            call.respondBytes(bytes, contentTypeFor(path))
        }
    }

    private fun contentTypeFor(path: String): ContentType = when (path.substringAfterLast('.', "").lowercase()) {
        "png" -> ContentType("image", "png")
        "jpg", "jpeg" -> ContentType("image", "jpeg")
        "gif" -> ContentType("image", "gif")
        "webp" -> ContentType("image", "webp")
        "svg" -> ContentType("image", "svg+xml")
        "ico" -> ContentType("image", "x-icon")
        "css" -> ContentType("text", "css")
        "js" -> ContentType("text", "javascript")
        "html" -> ContentType.Text.Html
        "txt" -> ContentType("text", "plain")
        "otf" -> ContentType("font", "otf")
        "ttf" -> ContentType("font", "ttf")
        "woff" -> ContentType("font", "woff")
        "woff2" -> ContentType("font", "woff2")
        else -> ContentType.Application.OctetStream
    }
}
