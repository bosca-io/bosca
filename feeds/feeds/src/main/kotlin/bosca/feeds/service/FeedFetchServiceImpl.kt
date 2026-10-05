package bosca.feeds.service

import bosca.feeds.auth.FeedAuthHeaders
import bosca.feeds.http.FeedHttpClient
import bosca.feeds.parser.FeedContentParser
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Fetches a source end to end: config + secret → authenticated conditional GET → parse → ingest each
 * item → store validators. A 304 short-circuits to a no-op; a non-2xx throws (the job records the
 * failure).
 */
@ServiceImplementation
class FeedFetchServiceImpl(
    private val feedSourceService: FeedSourceService,
    private val httpClient: FeedHttpClient,
    private val parser: FeedContentParser,
    private val ingestion: FeedIngestionService,
) : FeedFetchService {

    override suspend fun fetch(sourceId: UUID, force: Boolean): FeedFetchResult {
        val feedSource = feedSourceService.get(sourceId) ?: error("feed source $sourceId not found")
        val configuration = feedSourceService.getConfiguration(sourceId)
            ?: error("feed source $sourceId has no configuration")
        val secret = feedSourceService.getAuthSecret(sourceId)

        val headers = buildMap {
            putAll(FeedAuthHeaders.forAuth(configuration.auth, secret))
            // A forced fetch omits the conditional-GET validators so the origin returns the full feed
            // even when unchanged (manual "fetch now"); scheduled fetches stay conditional (a 304 no-op).
            if (!force) {
                feedSource.etag?.let { put("If-None-Match", it) }
                feedSource.lastModified?.let { put("If-Modified-Since", httpDate(it)) }
            }
            put("Accept", "application/rss+xml, application/atom+xml, application/json;q=0.9, */*;q=0.8")
        }

        val response = httpClient.get(configuration.endpoint, headers)
        if (response.notModified) return FeedFetchResult(notModified = true, ingested = 0)
        if (!response.ok) error("fetch of feed source $sourceId failed: HTTP ${response.status}")

        val items = parser.parse(configuration.type, response.body)
        items.forEach { ingestion.ingest(sourceId, it) }
        feedSourceService.updateValidators(sourceId, response.etag, response.lastModified)
        return FeedFetchResult(notModified = false, ingested = items.size)
    }

    /** Formats a stored Last-Modified instant as an RFC 7231 IMF-fixdate for the `If-Modified-Since` header. */
    private fun httpDate(value: OffsetDateTime): String =
        HTTP_DATE.format(value.atZoneSameInstant(ZoneOffset.UTC))

    private companion object {
        /** RFC 7231 IMF-fixdate, e.g. `Sun, 06 Nov 1994 08:49:37 GMT` — the only form valid for `If-Modified-Since`. */
        private val HTTP_DATE: DateTimeFormatter =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH)
    }
}
