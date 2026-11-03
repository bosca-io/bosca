package bosca.atlas.server

import bosca.bible.providers.apibible.ApiBibleProvider
import bosca.bible.providers.service.BibleProvider
import bosca.di.annotation.InternalDI
import bosca.di.provide
import bosca.di.provides
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody

/** Runs the real Atlas bootstrap and API.Bible adapter with deterministic upstream HTTP responses. */
@OptIn(InternalDI::class)
fun main(args: Array<String>) = startAtlasServer(args) {
    val client = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        check(request.url.host == "rest.api.bible") { "Unexpected upstream host" }
        val path = request.url.encodedPath
        val audio = "/audio-bibles" in path
        fun edition(id: String) = """{"id":"$id","dblId":"${if (audio) "audio-dbl" else "fixture-dbl"}","relatedDbl":"fixture-dbl","name":"${if (audio) "Fixture recording" else "Fixture Bible $id"}","nameLocal":"Local $id","abbreviation":"FB","abbreviationLocal":"FB","description":"Synthetic catalog fixture","language":{"id":"eng","name":"English","nameLocal":"English","script":"Latin","scriptDirection":"LTR"},"copyright":"Fixture attribution"}"""
        val data = when {
            path.endsWith("/books") -> """[{"id":"GEN","name":"Genesis","nameLong":"Genesis","abbreviation":"Gen","chapters":[{"id":"GEN.1","number":"1","bookId":"GEN"}]}]"""
            path == "/v1/bibles" -> (1..21).joinToString(",", "[", "]") { edition("edition-$it") }
            path == "/v1/audio-bibles" -> "[${edition("recording")}]"
            path.startsWith("/v1/bibles/edition-") -> edition(path.substringAfterLast('/'))
            else -> error("Unexpected upstream request: $path")
        }
        val authorized = request.header("api-key") != "rejected-key"
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (authorized) 200 else 401)
            .message(if (authorized) "OK" else "Unauthorized")
            .body("""{"data":$data}""".toResponseBody("application/json".toMediaType())).build()
    }.build()
    provides<BibleProvider>(name = "API_BIBLE", overrideExisting = true) { ApiBibleProvider(provide(), provide(), client) }
}
