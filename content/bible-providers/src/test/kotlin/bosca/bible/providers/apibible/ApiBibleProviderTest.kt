package bosca.bible.providers.apibible

import bosca.bible.Chapter
import bosca.bible.Reference
import bosca.bible.bibleJson
import bosca.bible.components.ComponentContainer
import bosca.bible.components.IComponent
import bosca.bible.components.Text
import bosca.bible.providers.model.BibleProviderResponse
import bosca.bible.providers.model.BibleProviderSettings
import bosca.bible.providers.model.BibleSourceKind
import bosca.configuration.service.ConfigurationService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiBibleProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private val json = Json { ignoreUnknownKeys = true }
    private val configurations = mockk<ConfigurationService>()
    private val configurationId = UUID.random()

    @BeforeTest
    fun setup() {
        coEvery { configurations.getValue(configurationId) } returns settings("test-key")
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
    }

    @AfterTest
    fun teardown() {
        server.close()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test
    fun `chapter content attribution and token come from one response`() = runTest {
        server.enqueue(MockResponse.Builder().body(chapterResponse).build())

        val response = ApiBibleProvider(configurations, json, client, server.url("/v1/"))
            .getChapter(configurationId, "edition", "GEN.1")

        assertEquals(Reference("GEN.1"), response.content.reference)
        assertEquals("First verse. ", text(assertNotNull(response.content[Reference("GEN.1.1")])))
        assertEquals("Second verse.", text(assertNotNull(response.content[Reference("GEN.1.2")])))
        assertEquals("Chapter copyright", response.attribution?.copyright)
        assertEquals(2, response.verseCount)
        assertEquals("response-token", response.fumsToken)
        assertEquals(1, server.requestCount)

        val request = server.takeRequest()
        assertEquals("test-key", request.headers["api-key"])
        assertEquals("/v1/bibles/edition/chapters/GEN.1", request.url.encodedPath)
        assertEquals("json", request.url.queryParameter("content-type"))
        assertEquals("3", request.url.queryParameter("fums-version"))

        val snapshot = response.asSerializable { it.asSerializable() }
        val serializer = BibleProviderResponse.serializer(Chapter.serializer())
        val decoded = bibleJson.decodeFromString(serializer, bibleJson.encodeToString(serializer, snapshot))
        assertEquals("response-token", decoded.fumsToken)
        assertEquals("Chapter copyright", decoded.attribution?.copyright)
        assertEquals("Second verse.", text(assertNotNull(decoded.content[Reference("GEN.1.2")])))
        assertTrue("response-token" !in response.toString())
    }

    @Test
    fun `each fetch preserves its own response token and attribution`() = runTest {
        server.enqueue(MockResponse.Builder().body(chapterResponse).build())
        server.enqueue(MockResponse.Builder().body(chapterResponse.replace("response-token", "next-token")
            .replace("Chapter copyright", "Next copyright")).build())
        val provider = provider()

        val first = provider.getChapter(configurationId, "edition", "GEN.1")
        val second = provider.getChapter(configurationId, "edition", "GEN.1")

        assertEquals("response-token", first.fumsToken)
        assertEquals("Chapter copyright", first.attribution?.copyright)
        assertEquals("next-token", second.fumsToken)
        assertEquals("Next copyright", second.attribution?.copyright)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `missing chapter attribution remains absent for source fallback`() = runTest {
        server.enqueue(MockResponse.Builder().body(chapterResponse.replace("\"copyright\": \"Chapter copyright\",", "")).build())

        assertNull(provider().getChapter(configurationId, "edition", "GEN.1").attribution)
    }

    @Test
    fun `chapter payload must match requested edition and chapter`() = runTest {
        for (payload in listOf(
            chapterResponse.replace("\"bibleId\": \"edition\"", "\"bibleId\": \"other\""),
            chapterResponse.replace("\"id\": \"GEN.1\"", "\"id\": \"GEN.2\""),
            chapterResponse.replace("\"bookId\": \"GEN\"", "\"bookId\": \"EXO\""),
            chapterResponse.replace("\"verseCount\": 2", "\"verseCount\": -1"),
            chapterResponse.replace("\"fumsToken\":\"response-token\"", "\"fumsToken\":\"\""),
            chapterResponse.replace("\"meta\": {\"fumsToken\":\"response-token\"}", "\"meta\": {}"),
        )) {
            server.enqueue(MockResponse.Builder().body(payload).build())
            assertFailsWith<IOException> { provider().getChapter(configurationId, "edition", "GEN.1") }
        }
    }

    @Test
    fun `provider errors expose status and retry delay without response body`() = runTest {
        server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "60")
            .body("private-provider-response").build())

        val failure = assertFailsWith<ApiBibleException> { provider().getChapter(configurationId, "edition", "GEN.1") }

        assertEquals(429, failure.statusCode)
        assertEquals("60", failure.retryAfter)
        assertTrue("private-provider-response" !in failure.toString())
        assertTrue("test-key" !in failure.toString())
    }

    @Test
    fun `cancelling a pending request propagates cancellation`() = runTest {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())
        val call = async(Dispatchers.Default) { provider().getChapter(configurationId, "edition", "GEN.1") }
        assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })

        call.cancel(CancellationException("Cancelled by caller"))

        assertFailsWith<CancellationException> { call.await() }
    }

    @Test
    fun `API redirects are reported without following them`() = runTest {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", server.url("/redirected").toString()).build())

        assertEquals(302, assertFailsWith<ApiBibleException> { provider().getChapter(configurationId, "edition", "GEN.1") }.statusCode)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `malformed provider JSON fails instead of returning empty content`() = runTest {
        server.enqueue(MockResponse.Builder().body("{invalid").build())

        assertFailsWith<SerializationException> { provider().getChapter(configurationId, "edition", "GEN.1") }
    }

    @Test
    fun `provider honors the injected JSON configuration`() = runTest {
        val response = """{"data":[],"additionalField":true}"""
        server.enqueue(MockResponse.Builder().body(response).build())
        val strict = ApiBibleProvider(configurations, Json, client, server.url("/v1/"))
        assertFailsWith<SerializationException> { strict.test(configurationId) }

        server.enqueue(MockResponse.Builder().body(response).build())
        provider().test(configurationId)
    }

    @Test
    fun `invalid JSON errors do not expose the response FUMS token`() = runTest {
        server.enqueue(MockResponse.Builder().body("""{"meta":{"fumsToken":"private-token"},"data":null}""").build())

        val failure = assertFailsWith<SerializationException> { provider().getChapter(configurationId, "edition", "GEN.1") }

        assertTrue("private-token" !in failure.toString())
    }

    @Test
    fun `invalid requests fail before contacting the provider`() = runTest {
        assertFailsWith<IllegalArgumentException> { provider().getChapter(configurationId, "edition", "GEN.1.1") }
        assertFailsWith<IllegalArgumentException> { provider().getCatalog(configurationId, BibleSourceKind.TEXT, null, -1, 10) }
        assertFailsWith<IllegalArgumentException> { provider().getCatalog(configurationId, BibleSourceKind.TEXT, null, 0, -1) }
        assertTrue(provider().getCatalog(configurationId, BibleSourceKind.TEXT, null, 0, 0).isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `catalog uses shared language models and filters before offset pagination`() = runTest {
        val second = edition.replace("\"id\":\"edition\"", "\"id\":\"second-edition\"")
        server.enqueue(MockResponse.Builder().body("""{"data":[$edition,$second]}""").build())

        val catalog = provider().getCatalog(configurationId, BibleSourceKind.AUDIO, "ENGLISH", 1, 1).single()

        assertEquals("second-edition", catalog.id)
        assertEquals(BibleSourceKind.AUDIO, catalog.kind)
        assertEquals("dbl-edition", catalog.systemId)
        assertEquals("text-dbl", catalog.relatedSystemId)
        assertEquals("eng", catalog.languages.single().iso)
        assertEquals("Latn", catalog.languages.single().scriptCode)
        assertEquals("Edition copyright", catalog.attribution.copyright)
        assertEquals("Latn", catalog.asSerializable().languages.single().scriptCode)
        val request = server.takeRequest()
        assertEquals("/v1/audio-bibles", request.url.encodedPath)
        assertEquals("true", request.url.queryParameter("include-full-details"))
    }

    @Test
    fun `catalog query can exclude all editions and missing details convert through projections`() = runTest {
        server.enqueue(MockResponse.Builder().body("""{"data":[$edition]}""").build())
        assertTrue(provider().getCatalog(configurationId, BibleSourceKind.TEXT, "absent", 0, 10).isEmpty())
        val sparse = edition.replace("\"copyright\":\"Edition copyright\",", "")
            .replace("\"nameLocal\":\"English\",", "")
            .replace("\"script\":\"Latin\"", "\"script\":\"Latn\"")
        server.enqueue(MockResponse.Builder().body("""{"data":[$sparse]}""").build())

        val snapshot = provider().getCatalog(configurationId, BibleSourceKind.TEXT, null, 0, 10).single().asSerializable()

        assertEquals("", snapshot.attribution.copyright)
        assertEquals("English", snapshot.languages.single().nameLocal)
        assertEquals("Latn", snapshot.languages.single().scriptCode)
    }

    @Test
    fun `Bible uses shared models and loads references without chapter bodies`() = runTest {
        server.enqueue(MockResponse.Builder().body("""{"data":$edition}""").build())
        server.enqueue(MockResponse.Builder().body(books).build())

        val response = provider().getBible(configurationId, "edition", BibleSourceKind.TEXT)
        val bible = response.content.asSerializable()

        assertEquals("dbl-edition", bible.metadata.identification.system.id)
        assertEquals("edition", bible.metadata.publication.id)
        assertEquals("eng", bible.metadata.language.iso)
        val book = assertNotNull(bible[Reference("GEN")])
        assertEquals("Genesis", book.name.long)
        assertEquals(Reference("GEN.1"), book.chapters.single().reference)
        assertNull(book.chapters.single()[Reference("GEN.1")])
        assertEquals("Edition copyright", response.attribution?.copyright)
        assertNull(response.fumsToken)
        assertEquals(2, server.requestCount)
        assertEquals("/v1/bibles/edition", server.takeRequest().url.encodedPath)
        val request = server.takeRequest()
        assertEquals("/v1/bibles/edition/books", request.url.encodedPath)
        assertEquals("true", request.url.queryParameter("include-chapters"))
    }

    @Test
    fun `nullable provider descriptions convert to empty shared descriptions`() = runTest {
        server.enqueue(MockResponse.Builder().body("""{"data":${edition.replace("\"description\":\"English translation\"", "\"description\":null,\"descriptionLocal\":null")}}""").build())
        server.enqueue(MockResponse.Builder().body(books).build())

        val bible = provider().getBible(configurationId, "edition", BibleSourceKind.TEXT).content

        assertEquals("", bible.metadata.identification.description)
        assertEquals("", bible.metadata.publication.description)
        assertEquals("", bible.metadata.publication.descriptionLocal)
    }

    @Test
    fun `audio Bible metadata comes from the audio catalog`() = runTest {
        server.enqueue(MockResponse.Builder().body("""{"data":[$edition]}""").build())
        server.enqueue(MockResponse.Builder().body(books).build())

        assertEquals("edition", provider().getBible(configurationId, "edition", BibleSourceKind.AUDIO).content.metadata.publication.id)
        val request = server.takeRequest()
        assertEquals("/v1/audio-bibles", request.url.encodedPath)
        assertEquals("edition", request.url.queryParameter("ids"))
        assertEquals("/v1/audio-bibles/edition/books", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `unavailable audio Bible and inconsistent text metadata fail`() = runTest {
        server.enqueue(MockResponse.Builder().body("""{"data":[]}""").build())
        assertFailsWith<IOException> { provider().getBible(configurationId, "edition", BibleSourceKind.AUDIO) }
        server.enqueue(MockResponse.Builder().body("""{"data":${edition.replace("\"id\":\"edition\"", "\"id\":\"other\"")}}""").build())
        assertFailsWith<IOException> { provider().getBible(configurationId, "edition", BibleSourceKind.TEXT) }
        server.enqueue(MockResponse.Builder().body("""{"data":$edition}""").build())
        server.enqueue(MockResponse.Builder().body(books.replace("\"bookId\":\"GEN\"", "\"bookId\":\"EXO\"")).build())
        assertFailsWith<IOException> { provider().getBible(configurationId, "edition", BibleSourceKind.TEXT) }
    }

    @Test
    fun `test verifies catalog access without fetching content`() = runTest {
        server.enqueue(MockResponse.Builder().body("""{"data":[]}""").build())

        provider().test(configurationId)

        assertEquals("API_BIBLE", provider().key)
        assertEquals("/v1/bibles", server.takeRequest().url.encodedPath)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `shared provider reads credentials for each configuration without retaining them`() = runTest {
        val secondId = UUID.random()
        coEvery { configurations.getValue(secondId) } returns settings("second-key")
        val provider = provider()
        repeat(2) { server.enqueue(MockResponse.Builder().body("""{"data":[]}""").build()) }
        val first = async(Dispatchers.Default) { provider.test(configurationId) }
        val second = async(Dispatchers.Default) { provider.test(secondId) }
        first.await()
        second.await()
        assertEquals(setOf("test-key", "second-key"), setOf(server.takeRequest().headers["api-key"], server.takeRequest().headers["api-key"]))

        coEvery { configurations.getValue(configurationId) } returns settings("replacement-key")
        server.enqueue(MockResponse.Builder().body("""{"data":[]}""").build())
        provider.test(configurationId)
        assertEquals("replacement-key", server.takeRequest().headers["api-key"])
    }

    @Test
    fun `a Bible operation reads its configuration once for all upstream requests`() = runTest {
        coEvery { configurations.getValue(configurationId) } returnsMany listOf(settings("first-key"), settings("next-key"))
        server.enqueue(MockResponse.Builder().body("""{"data":$edition}""").build())
        server.enqueue(MockResponse.Builder().body(books).build())
        val provider = provider()
        provider.getBible(configurationId, "edition", BibleSourceKind.TEXT)
        assertEquals("first-key", server.takeRequest().headers["api-key"])
        assertEquals("first-key", server.takeRequest().headers["api-key"])
        server.enqueue(MockResponse.Builder().body(chapterResponse).build())
        provider.getChapter(configurationId, "edition", "GEN.1")
        assertEquals("next-key", server.takeRequest().headers["api-key"])
    }

    @Test
    fun `invalid saved credentials fail before network access and disabled configurations allow only tests`() = runTest {
        val provider = provider()
        for (value in listOf(null, settings(null), settings(" "), settings("key\nprivate"), settings("key", provider = "OTHER"))) {
            coEvery { configurations.getValue(configurationId) } returns value
            assertFailsWith<IllegalArgumentException> { provider.test(configurationId) }
        }
        coEvery { configurations.getValue(configurationId) } returns settings("test-key", enabled = false)
        assertFailsWith<IllegalStateException> { provider.getChapter(configurationId, "edition", "GEN.1") }
        assertEquals(0, server.requestCount)
        server.enqueue(MockResponse.Builder().body("""{"data":[]}""").build())
        provider.test(configurationId)
        assertEquals("test-key", server.takeRequest().headers["api-key"])
    }

    @Test
    fun `configuration parsing hides credentials and configuration reads preserve cancellation`() = runTest {
        coEvery { configurations.getValue(configurationId) } returns json.parseToJsonElement(
            """{"name":"Test","provider":"API_BIBLE","enabled":"invalid","apiKey":"private-key"}""",
        )
        val provider = provider()
        val failure = assertFailsWith<SerializationException> { provider.test(configurationId) }
        assertTrue("private-key" !in failure.toString())

        val cancelled = CancellationException("Configuration read cancelled")
        coEvery { configurations.getValue(configurationId) } throws cancelled
        assertEquals(cancelled, assertFailsWith<CancellationException> { provider.test(configurationId) })
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `public constructor uses the fixed API Bible endpoint`() = runTest {
        val intercepted = client.newBuilder().addInterceptor { chain ->
            assertEquals("https://rest.api.bible/v1/bibles?include-full-details=true", chain.request().url.toString())
            assertEquals("test-key", chain.request().header("api-key"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"data":[]}""".toResponseBody("application/json".toMediaType())).build()
        }.build()

        ApiBibleProvider(configurations, json, intercepted).test(configurationId)
    }

    @Test
    fun `audio downloads bytes with one response token and leaves destination open`() = runTest {
        MockWebServer().use { media ->
            media.start()
            val destination = object : ByteArrayOutputStream() {
                var closed = false
                override fun close() { closed = true; super.close() }
            }
            server.enqueue(MockResponse.Builder().body("""
                {"data":{"id":"GEN.1","bibleId":"edition","bookId":"GEN",
                "resourceUrl":"${media.url("/audio.mp3")}","copyright":"Audio copyright",
                "timecodes":[{"verseId":"GEN.1.1","start":"00:01.5","end":"00:04"}]},
                "meta":{"fumsToken":"audio-token"}}
            """.trimIndent()).build())
            media.enqueue(MockResponse.Builder().addHeader("Content-Type", "audio/mpeg").body("audio-bytes").build())

            val response = provider().downloadAudioChapter(configurationId, "edition", "GEN.1", destination)

            assertEquals("audio-bytes", destination.toString(Charsets.UTF_8))
            assertTrue(!destination.closed)
            assertEquals(11L, response.content.contentLength)
            assertEquals("audio/mpeg", response.content.contentType)
            assertEquals("GEN.1.1", response.content.timecodes.single().usfm)
            assertEquals(1.5, response.content.timecodes.single().start)
            assertEquals(4.0, response.content.timecodes.single().end)
            assertEquals("audio-token", response.fumsToken)
            assertEquals("Audio copyright", response.attribution?.copyright)
            assertNull(response.verseCount)
            assertNull(media.takeRequest().headers["api-key"])
            val request = server.takeRequest()
            assertEquals("/v1/audio-bibles/edition/chapters/GEN.1", request.url.encodedPath)
            assertEquals("3", request.url.queryParameter("fums-version"))
        }
    }

    @Test
    fun `invalid audio timing identity or token fails before downloading bytes`() = runTest {
        for (payload in listOf(
            audioResponse().replace("\"bibleId\":\"edition\"", "\"bibleId\":\"other\""),
            audioResponse().replace("\"start\":\"1.5\"", "\"start\":\"NaN\""),
            audioResponse().replace("\"start\":\"1.5\"", "\"start\":\"-1\""),
            audioResponse().replace("\"start\":\"1.5\"", "\"start\":\"00:61\""),
            audioResponse().replace("\"end\":\"4\"", "\"end\":\"0\""),
            audioResponse().replace("\"verseId\":\"GEN.1.1\"", "\"verseId\":\"EXO.1.1\""),
            audioResponse().replace("\"fumsToken\":\"audio-token\"", "\"fumsToken\":null"),
        )) {
            server.enqueue(MockResponse.Builder().body(payload).build())
            val destination = ByteArrayOutputStream()
            assertFailsWith<IOException> { provider().downloadAudioChapter(configurationId, "edition", "GEN.1", destination) }
            assertEquals(0, destination.size())
        }
    }

    @Test
    fun `audio media redirects failures and non audio bodies are rejected`() = runTest {
        for (mediaResponse in listOf(
            MockResponse.Builder().code(302).addHeader("Location", server.url("/redirected").toString()).build(),
            MockResponse.Builder().code(403).build(),
            MockResponse.Builder().addHeader("Content-Type", "text/html").body("not audio").build(),
            MockResponse.Builder().addHeader("Content-Type", "audio/mpeg").body("").build(),
        )) {
            server.enqueue(MockResponse.Builder().body(audioResponse()).build())
            server.enqueue(mediaResponse)
            assertFailsWith<IOException> { provider().downloadAudioChapter(configurationId, "edition", "GEN.1", ByteArrayOutputStream()) }
            server.takeRequest()
            assertNull(server.takeRequest().headers["api-key"])
        }
        assertEquals(8, server.requestCount)
    }

    @Test
    fun `destination write failures propagate without closing the destination`() = runTest {
        server.enqueue(MockResponse.Builder().body(audioResponse()).build())
        server.enqueue(MockResponse.Builder().body("audio bytes").build())
        val failure = IOException("Destination unavailable")
        val destination = object : ByteArrayOutputStream() {
            var closed = false
            override fun write(buffer: ByteArray, offset: Int, length: Int) { throw failure }
            override fun close() { closed = true }
        }

        val propagated = assertFailsWith<IOException> { provider().downloadAudioChapter(configurationId, "edition", "GEN.1", destination) }
        assertEquals(failure.message, propagated.message)
        assertTrue(!destination.closed)
    }

    private fun audioResponse() = """
        {"data":{"id":"GEN.1","bibleId":"edition","bookId":"GEN","resourceUrl":"${server.url("/audio.mp3")}",
        "timecodes":[{"verseId":"GEN.1.1","start":"1.5","end":"4"}]},"meta":{"fumsToken":"audio-token"}}
    """.trimIndent()

    private fun settings(apiKey: String?, enabled: Boolean = true, provider: String = "API_BIBLE") =
        json.encodeToJsonElement(BibleProviderSettings.serializer(), BibleProviderSettings("Test", provider, enabled, apiKey))

    private fun provider() = ApiBibleProvider(configurations, json, client, server.url("/v1/"))

    private fun text(component: IComponent): String = when (component) {
        is Text -> component.text
        is ComponentContainer -> component.components.joinToString("") { text(it) }
        else -> ""
    }

    private val chapterResponse = """
        {
          "data": {
            "id": "GEN.1", "bibleId": "edition", "bookId": "GEN", "number": "1",
            "content": [
              {"type":"tag","name":"para","attrs":{"style":"p"},"items":[
                {"type":"tag","name":"verse","attrs":{"number":"1","sid":"GEN 1:1"},"items":[{"type":"text","text":"1"}]},
                {"type":"text","text":"First verse. ","attrs":{"verseId":"GEN.1.1","verseOrgIds":["GEN.1.1"]}},
                {"type":"tag","name":"verse","attrs":{"number":"2","sid":"GEN 1:2"},"items":[{"type":"text","text":"2"}]},
                {"type":"text","text":"Second verse.","attrs":{"verseId":"GEN.1.2","verseOrgIds":["GEN.1.2"]}}
              ]}
            ],
            "copyright": "Chapter copyright", "verseCount": 2
          },
          "meta": {"fumsToken":"response-token"}
        }
    """.trimIndent()

    private val edition = """
        {"id":"edition","dblId":"dbl-edition","name":"English Bible","nameLocal":"English Bible",
        "abbreviation":"EB","abbreviationLocal":"EB","description":"English translation",
        "language":{"id":"eng","name":"English","nameLocal":"English","script":"Latin","scriptDirection":"LTR"},
        "copyright":"Edition copyright","relatedDbl":"text-dbl"}
    """.trimIndent()

    private val books = """
        {"data":[{"id":"GEN","name":"Genesis","nameLong":"Genesis","abbreviation":"Gen",
        "chapters":[{"id":"GEN.1","number":"1","bookId":"GEN"}]}]}
    """.trimIndent()
}
