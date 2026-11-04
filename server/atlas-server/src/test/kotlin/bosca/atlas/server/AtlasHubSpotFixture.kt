package bosca.atlas.server

import bosca.di.annotation.InternalDI
import bosca.di.provide
import bosca.di.provides
import bosca.hubspot.client.HubSpot
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.atomic.AtomicInteger

/** Uses the real adapter and pipeline with isolated upstream HTTP responses. */
@OptIn(InternalDI::class)
fun main(args: Array<String>) = startAtlasServer(args) {
    val sequence = AtomicInteger()
    val client = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        check(request.url.host == "api.hubapi.com") { "Unexpected upstream host" }
        val path = request.url.encodedPath
        val body = when {
            path == "/communication-preferences/v4/definitions" -> """{"results":[]}"""
            path.startsWith("/crm/v3/objects/") -> """{"id":"fixture-${sequence.incrementAndGet()}"}"""
            else -> "{}"
        }
        log.info("HubSpot fixture: {} {}", request.method, path)
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }.build()
    provides<HubSpot>(singleton = true, overrideExisting = true) { HubSpot("fixture-token", client = client, configurationService = provide(), json = provide()) }
}
