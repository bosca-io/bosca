package bosca.analytics.transform.fums

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import bosca.server.http.await
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Sends a server-side FUMS report without provider credentials. */
class FumsReporter internal constructor(client: OkHttpClient, private val endpoint: HttpUrl) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    constructor() : this(OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build(), "https://fums.api.bible/f3".toHttpUrl())

    suspend fun report(token: String, deviceId: String, sessionId: String, userId: String?) {
        val url = endpoint.newBuilder().addQueryParameter("t", token)
            .addQueryParameter("dId", deviceId).addQueryParameter("sId", sessionId)
            .apply { userId?.let { addQueryParameter("uId", it) } }.build()
        val response = try {
            client.newCall(Request.Builder().url(url).build()).await()
        } catch (_: IOException) {
            // Transport exceptions can include the private token in the request URL.
            throw IOException("FUMS transport failed")
        }
        response.use {
            if (!it.isSuccessful) throw IOException("FUMS reporting failed (HTTP ${it.code})")
        }
    }
}
