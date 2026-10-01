package bosca.feeds.http

import bosca.serialization.OffsetDateTime
import bosca.service.Service

/** The result of fetching a feed URL. [notModified] is HTTP 304; [ok] is 2xx. */
data class FeedHttpResponse(
    val status: Int,
    val body: String,
    val etag: String? = null,
    val lastModified: OffsetDateTime? = null,
) {
    val notModified: Boolean get() = status == 304
    val ok: Boolean get() = status in 200..299
}

/** Minimal outbound HTTP for feed fetching: a GET with request headers, returning body + validators. */
interface FeedHttpClient : Service {

    suspend fun get(url: String, headers: Map<String, String>): FeedHttpResponse
}
