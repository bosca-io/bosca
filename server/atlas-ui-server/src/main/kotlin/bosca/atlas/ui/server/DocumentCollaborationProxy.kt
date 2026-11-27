package bosca.atlas.ui.server

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

/** Keeps document collaboration bytes on the UI origin while Content authorizes every request. */
internal class DocumentCollaborationProxy(graphqlEndpoint: String) {
    private val api = graphqlEndpoint.substringBefore("/graphql").trimEnd('/')
    private val apiPath = URI(api).rawPath.orEmpty().trimEnd('/')
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()

    fun install(application: BoscaApplication) {
        val path = "/api/v1/content/metadata/{id}/document/collaboration"
        application.router.get(path) { forward(call, "GET") }
        application.router.head(path) { forward(call, "HEAD") }
        application.router.put(path) { forward(call, "PUT") }
        application.router.post(path) { forward(call, "POST") }
    }

    private suspend fun forward(call: ServerCall, method: String) {
        val request = Request.Builder().url(api + call.request.uri)
        if (request.build().url.encodedPath != apiPath + call.request.path) {
            call.respond(HttpStatusCode.BadRequest, "Invalid document collaboration path")
            return
        }
        for (header in listOf("Authorization", "Cookie", "Accept")) {
            call.request.header(header)?.let { request.header(header, it) }
        }
        request.header("Origin", call.request.appOrigin)
        call.response.header("Cache-Control", "private, no-store")
        if (method == "PUT" || method == "POST") {
            call.request.bodyInputStream().use { input ->
                val body = object : RequestBody() {
                    override fun contentType() = call.request.header("Content-Type")?.toMediaTypeOrNull()
                    override fun contentLength() = call.request.header("Content-Length")?.toLongOrNull() ?: -1L
                    override fun writeTo(sink: BufferedSink) { sink.writeAll(input.source()) }
                }
                send(call, request.method(method, body))
            }
        } else send(call, request.method(method, null))
    }

    private suspend fun send(call: ServerCall, request: Request.Builder) {
        val response = try { client.newCall(request.build()).await() }
        catch (e: CancellationException) { throw e }
        catch (e: IOException) {
            call.application.log.warn("Atlas document collaboration API unavailable", e)
            call.respond(HttpStatusCode.BadGateway, "Document collaboration is unavailable")
            return
        }
        response.use {
            val contentType = response.header("Content-Type")?.let(ContentType::parse) ?: ContentType.Application.OctetStream
            withContext(Dispatchers.IO) {
                response.body.byteStream().use { input ->
                    call.respondStreaming(contentType, HttpStatusCode.fromValue(response.code), timeLimit = null) { output -> output.copyFrom(input) }
                }
            }
        }
    }
}
