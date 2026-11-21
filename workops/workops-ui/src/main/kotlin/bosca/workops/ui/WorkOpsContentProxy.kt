package bosca.workops.ui

import bosca.server.BoscaApplication
import bosca.server.ContentType
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import bosca.server.http.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.IOException
import java.net.URI

/** Relays Content's existing upload and image routes; Content authorizes the caller and bytes. */
internal class WorkOpsContentProxy(graphqlEndpoint: String) {
    private val api = graphqlEndpoint.substringBefore("/graphql").trimEnd('/')
    private val apiPath = URI(api).rawPath.orEmpty().trimEnd('/')
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()

    fun install(application: BoscaApplication) {
        application.router.post("/api/v1/content/metadata/upload") { forward(call, upload = true) }
        application.router.get("/content/image/{id}") { forward(call, upload = false) }
    }

    private suspend fun forward(call: ServerCall, upload: Boolean) {
        val request = Request.Builder().url(api + call.request.uri)
        if (request.build().url.encodedPath != apiPath + call.request.path) {
            call.respond(HttpStatusCode.BadRequest, "Invalid Content path")
            return
        }
        for (header in listOf("Authorization", "Cookie", "Accept", "If-None-Match", "If-Modified-Since")) {
            call.request.header(header)?.let { request.header(header, it) }
        }
        request.header("Origin", call.request.appOrigin)
        call.response.header("Cache-Control", "private, no-store")
        if (!upload) {
            // Raw images share the UI origin. Keep SVG presentation while disabling active documents.
            call.response.header("Content-Security-Policy", "sandbox; default-src 'none'; style-src 'unsafe-inline'; img-src data:")
            call.response.header("X-Content-Type-Options", "nosniff")
        }
        if (upload) {
            call.request.bodyInputStream().use { input ->
                val body = object : RequestBody() {
                    override fun contentType() = call.request.header("Content-Type")?.toMediaTypeOrNull()
                    override fun contentLength() = call.request.header("Content-Length")?.toLongOrNull() ?: -1L
                    override fun writeTo(sink: BufferedSink) { sink.writeAll(input.source()) }
                }
                send(call, request.post(body))
            }
        } else send(call, request.get())
    }

    private suspend fun send(call: ServerCall, request: Request.Builder) {
        val response = try { client.newCall(request.build()).await() }
        catch (e: CancellationException) { throw e }
        catch (e: IOException) {
            call.application.log.warn("WorkOps Content API unavailable", e)
            call.respond(HttpStatusCode.BadGateway, "Content is unavailable")
            return
        }
        response.use {
            for (header in listOf("ETag", "Last-Modified", "Content-Disposition", "X-Content-Type-Options", "Content-Security-Policy")) {
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
