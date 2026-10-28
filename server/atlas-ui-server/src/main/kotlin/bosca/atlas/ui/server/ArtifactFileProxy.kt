package bosca.atlas.ui.server

import bosca.server.BoscaApplication
import bosca.server.ContentType
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import bosca.server.http.await
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.BufferedSink
import okio.source

/** Streams registry file traffic through the UI origin; registry services authorize the caller. */
internal class ArtifactFileProxy(endpoint: String) {
    private val origin = endpoint.substringBefore("/graphql").trimEnd('/').toHttpUrl()
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()

    fun install(application: BoscaApplication) {
        application.router.put("/_artifacts/raw/{namespace}/{repository}/{version}/{filename}") {
            val segments = listOf("namespace", "repository", "version", "filename").map { key -> call.pathParameters[key].orEmpty() }
            if (segments.any { it.isBlank() || it == "." || it == ".." || '/' in it || '\\' in it || it.any(Char::isISOControl) }) {
                call.respond(HttpStatusCode.BadRequest, "Invalid artifact coordinates")
                return@put
            }
            // Bosca decodes '+' as a space in route parameters, so preserve literal plus signs.
            val path = listOf("raw", segments[0], "api", segments[1], segments[2], segments[3])
                .joinToString("/") { URLEncoder.encode(it, StandardCharsets.UTF_8).replace("+", "%20") }
            val url = origin.newBuilder().addEncodedPathSegments(path).build()
            call.request.bodyInputStream().use { input ->
                val body = object : RequestBody() {
                    override fun contentType() = call.request.header("Content-Type")?.toMediaTypeOrNull()
                    override fun contentLength() = call.request.header("Content-Length")?.toLongOrNull() ?: -1L
                    override fun writeTo(sink: BufferedSink) { sink.writeAll(input.source()) }
                }
                forward(call, Request.Builder().url(url).put(body))
            }
        }
        for (path in listOf("/raw/{namespace}/{repository}/{version}/{filename}", "/maven/{repository}/{path...}", "/npm/{name}/-/{filename}", "/npm/@{scope}/{name}/-/{filename}", "/helm/{namespace}/charts/{name}/{version}", "/ml/{namespace}/{name}/{version}")) {
            application.router.get(path) {
                val request = Request.Builder().url(origin.toString().trimEnd('/') + call.request.uri).get()
                if (request.build().url.encodedPath != origin.encodedPath.trimEnd('/') + call.request.path) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid artifact path")
                } else forward(call, request)
            }
        }
    }
    private suspend fun forward(call: ServerCall, request: Request.Builder) {
        for (header in listOf("Authorization", "Cookie", "Accept", "Range", "If-None-Match", "If-Modified-Since")) {
            call.request.header(header)?.let { request.header(header, it) }
        }
        request.header("Origin", call.request.appOrigin)
        call.response.header("Cache-Control", "private, no-store")
        val response = try { client.newCall(request.build()).await() } catch (e: CancellationException) { throw e } catch (e: IOException) {
            call.application.log.warn("Atlas artifact registry unavailable", e)
            call.respond(HttpStatusCode.BadGateway, "Artifact registry unavailable")
            return
        }
        response.use {
            for (header in listOf("Content-Disposition", "ETag", "Last-Modified", "Content-Range", "Accept-Ranges", "X-Content-Type-Options", "Content-Security-Policy")) {
                response.header(header)?.let { call.response.header(header, it) }
            }
            val type = response.header("Content-Type")?.let(ContentType::parse) ?: ContentType.Application.OctetStream
            withContext(Dispatchers.IO) {
                response.body.byteStream().use { input ->
                    call.respondStreaming(type, HttpStatusCode.fromValue(response.code), timeLimit = null) { output -> output.copyFrom(input) }
                }
            }
        }
    }
}
