package bosca.git.ui.server

import bosca.server.BoscaApplication
import bosca.server.ContentType
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import bosca.server.http.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URI

/** Streams raw files and CI artifacts on the UI origin; the API validates forwarded credentials. */
internal class GitFileProxy(graphqlEndpoint: String) {
    private val api = graphqlEndpoint.substringBefore("/graphql").trimEnd('/')
    private val apiPath = URI(api).rawPath.orEmpty().trimEnd('/')
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun install(application: BoscaApplication) {
        application.router.get("/{owner}/{repo}/raw/{ref}/{path...}") { forward(call) }
        application.router.get("/ci/artifacts/{repositoryId}/{runId}/{name...}") { forward(call) }
    }

    private suspend fun forward(call: ServerCall) {
        call.response.header("Cache-Control", "private, no-store")
        val request = Request.Builder().url(api + call.request.uri).get().apply {
            for (name in REQUEST_HEADERS) {
                call.request.header(name)?.let { header(name, it) }
            }
            header("Origin", call.request.appOrigin)
        }.build()
        // URL normalization must not turn a file route into another API endpoint.
        if (request.url.encodedPath != apiPath + call.request.path) {
            call.respond(HttpStatusCode.BadRequest, "Invalid file path")
            return
        }
        val response = try {
            client.newCall(request).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            call.application.log.warn("git-ui: file proxy upstream unreachable", e)
            call.respond(HttpStatusCode.BadGateway, "Git API unavailable")
            return
        }
        response.use {
            for (name in RESPONSE_HEADERS) {
                response.header(name)?.let { call.response.header(name, it) }
            }
            val contentType = response.header("Content-Type")?.let(ContentType::parse)
                ?: ContentType.Application.OctetStream
            withContext(Dispatchers.IO) {
                response.body.byteStream().use { input ->
                    call.respondStreaming(contentType, HttpStatusCode.fromValue(response.code), timeLimit = null) { stream ->
                        stream.copyFrom(input)
                    }
                }
            }
        }
    }
}

private val REQUEST_HEADERS = listOf(
    "Authorization",
    "Cookie",
    "Accept",
    "Range",
    "If-None-Match",
    "If-Modified-Since",
)

private val RESPONSE_HEADERS = listOf(
    "Content-Disposition",
    "Content-Security-Policy",
    "X-Content-Type-Options",
    "ETag",
    "Content-Range",
    "Accept-Ranges",
)
