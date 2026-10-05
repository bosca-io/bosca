package bosca.feeds.service

import bosca.content.metadata.model.Metadata
import bosca.feeds.http.FeedHttpClient
import bosca.feeds.http.FeedHttpResponse
import bosca.feeds.model.FeedAuth
import bosca.feeds.model.FeedConfiguration
import bosca.feeds.model.FeedSource
import bosca.feeds.model.RawFeedItem
import bosca.feeds.model.SourceType
import bosca.feeds.parser.FeedContentParser
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/**fetch orchestration — 200 parses+ingests+stores validators; 304 is a no-op; non-2xx throws. */
@OptIn(ExperimentalUuidApi::class)
class FeedFetchServiceImplTest {

    private val feedSourceService = mockk<FeedSourceService>(relaxed = true)
    private val httpClient = mockk<FeedHttpClient>()
    private val parser = mockk<FeedContentParser>()
    private val ingestion = mockk<FeedIngestionService>(relaxed = true)
    private lateinit var service: FeedFetchServiceImpl

    @BeforeTest
    fun setup() {
        service = FeedFetchServiceImpl(feedSourceService, httpClient, parser, ingestion)
    }

    @AfterTest
    fun teardown() = io.mockk.clearAllMocks()

    private fun stubSource(sourceId: UUID) {
        coEvery { feedSourceService.get(sourceId) } returns FeedSource(sourceId = sourceId, url = "https://x/feed")
        coEvery { feedSourceService.getConfiguration(sourceId) } returns FeedConfiguration(
            type = SourceType.RSS, endpoint = "https://x/feed", cronInterval = "0 0 * * * *", auth = FeedAuth.None,
        )
        coEvery { feedSourceService.getAuthSecret(sourceId) } returns null
    }

    @Test
    fun `a fresh 200 parses, ingests each item, and stores validators`() = runTest {
        val sourceId = UUID.random()
        val lm = OffsetDateTime.parse("2026-01-01T00:00:00Z")
        stubSource(sourceId)
        coEvery { httpClient.get(eq("https://x/feed"), any()) } returns
            FeedHttpResponse(status = 200, body = "<rss/>", etag = "e1", lastModified = lm)
        coEvery { parser.parse(SourceType.RSS, "<rss/>") } returns
            listOf(RawFeedItem(guid = "a", title = "A"), RawFeedItem(guid = "b", title = "B"))
        coEvery { ingestion.ingest(eq(sourceId), any()) } returns mockk<Metadata>()

        val result = service.fetch(sourceId)

        assertEquals(false, result.notModified)
        assertEquals(2, result.ingested)
        coVerify(exactly = 2) { ingestion.ingest(eq(sourceId), any()) }
        coVerify(exactly = 1) { feedSourceService.updateValidators(sourceId, "e1", lm) }
    }

    @Test
    fun `a stored Last-Modified is sent as an RFC 7231 If-Modified-Since header`() = runTest {
        val sourceId = UUID.random()
        val lm = OffsetDateTime.parse("2026-01-01T00:00:00Z")
        coEvery { feedSourceService.get(sourceId) } returns
            FeedSource(sourceId = sourceId, url = "https://x/feed", lastModified = lm)
        coEvery { feedSourceService.getConfiguration(sourceId) } returns FeedConfiguration(
            type = SourceType.RSS, endpoint = "https://x/feed", cronInterval = "0 0 * * * *", auth = FeedAuth.None,
        )
        coEvery { feedSourceService.getAuthSecret(sourceId) } returns null
        val headers = slot<Map<String, String>>()
        coEvery { httpClient.get(eq("https://x/feed"), capture(headers)) } returns
            FeedHttpResponse(status = 304, body = "")

        service.fetch(sourceId)

        assertEquals("Thu, 01 Jan 2026 00:00:00 GMT", headers.captured["If-Modified-Since"])
    }

    @Test
    fun `a forced fetch omits the conditional-GET validators even when stored`() = runTest {
        val sourceId = UUID.random()
        val lm = OffsetDateTime.parse("2026-01-01T00:00:00Z")
        coEvery { feedSourceService.get(sourceId) } returns
            FeedSource(sourceId = sourceId, url = "https://x/feed", etag = "e1", lastModified = lm)
        coEvery { feedSourceService.getConfiguration(sourceId) } returns FeedConfiguration(
            type = SourceType.RSS, endpoint = "https://x/feed", cronInterval = "0 0 * * * *", auth = FeedAuth.None,
        )
        coEvery { feedSourceService.getAuthSecret(sourceId) } returns null
        val headers = slot<Map<String, String>>()
        coEvery { httpClient.get(eq("https://x/feed"), capture(headers)) } returns
            FeedHttpResponse(status = 200, body = "<rss/>", etag = "e2", lastModified = lm)
        coEvery { parser.parse(SourceType.RSS, "<rss/>") } returns emptyList()

        service.fetch(sourceId, force = true)

        // Forced: the origin must return the full feed, so neither validator is sent.
        assertEquals(null, headers.captured["If-None-Match"])
        assertEquals(null, headers.captured["If-Modified-Since"])
    }

    @Test
    fun `a 304 is a no-op (no parse, ingest, or validator update)`() = runTest {
        val sourceId = UUID.random()
        stubSource(sourceId)
        coEvery { httpClient.get(any(), any()) } returns FeedHttpResponse(status = 304, body = "")

        val result = service.fetch(sourceId)

        assertEquals(true, result.notModified)
        assertEquals(0, result.ingested)
        coVerify(exactly = 0) { parser.parse(any(), any()) }
        coVerify(exactly = 0) { ingestion.ingest(any(), any()) }
        coVerify(exactly = 0) { feedSourceService.updateValidators(any(), any(), any()) }
    }

    @Test
    fun `a non-2xx response fails the fetch`() = runTest {
        val sourceId = UUID.random()
        stubSource(sourceId)
        coEvery { httpClient.get(any(), any()) } returns FeedHttpResponse(status = 500, body = "error")

        assertFailsWith<IllegalStateException> { service.fetch(sourceId) }
        coVerify(exactly = 0) { ingestion.ingest(any(), any()) }
    }
}
