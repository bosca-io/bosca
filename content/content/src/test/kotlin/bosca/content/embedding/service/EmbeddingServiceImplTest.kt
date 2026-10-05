package bosca.content.embedding.service

import bosca.cache.RequestCache
import bosca.cache.asCoroutineContext
import bosca.content.embedding.model.EmbeddingChunk
import bosca.content.embedding.model.EmbeddingConfiguration
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.MetadataService
import bosca.content.transformations.MetadataToSearchDocument
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * EmbeddingServiceImpl against a mock TEI server: model information, tokenizer-aware chunk embeddings,
 * the `embedding.enabled` gate, and the document-prompt template. Null means "deliberately
 * nothing to embed" (disabled, or blank text); an attempted embed that fails throws so the calling job
 * fails instead of silently skipping the embedding.
 */
class EmbeddingServiceImplTest {

    private lateinit var server: MockWebServer
    private val metadataService = mockk<MetadataService>(relaxed = true)
    private val metadataToSearchDocument = mockk<MetadataToSearchDocument>()

    @BeforeTest
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun teardown() = server.close()

    private fun service(
        enabled: Boolean = true,
        documentPrompt: String = "",
        chunkOverlapTokens: Int = 0,
    ) = EmbeddingServiceImpl(
        EmbeddingConfiguration(
            enabled = enabled,
            url = server.url("/").toString().trimEnd('/'),
            documentPrompt = documentPrompt,
            chunkOverlapTokens = chunkOverlapTokens,
            timeoutSeconds = 5,
        ),
        metadataService,
        metadataToSearchDocument,
    )

    private fun metadata(
        id: UUID = UUID.random(),
        ready: OffsetDateTime? = OffsetDateTime.now(),
        recommendable: Boolean = true,
    ) = Metadata(
        id = id,
        name = "Document",
        type = MetadataType.STANDARD,
        contentType = "text/plain",
        contentLength = 10,
        languageTag = "en",
        ready = ready,
        workflowStateId = "published",
        recommendable = recommendable,
    )

    private suspend fun <T> withRequestCache(block: suspend () -> T): T = withContext(
        RequestCache(mockk(relaxed = true), mockk(relaxed = true)).asCoroutineContext(),
    ) { block() }

    private fun enqueue(code: Int, body: String) =
        server.enqueue(MockResponse.Builder().code(code).body(body).build())

    private fun enqueueInfo(
        maxInputLength: Int = 2048,
        maxBatchTokens: Int = 16384,
        maxClientBatchSize: Int = 1,
    ) =
        enqueue(
            200,
            """{"max_input_length":$maxInputLength,"max_batch_tokens":$maxBatchTokens,"max_client_batch_size":$maxClientBatchSize}""",
        )

    private fun tokens(
        ids: List<Int>,
        special: Boolean = false,
        spans: List<Pair<Int, Int>> = ids.indices.map { it to it + 1 },
    ): String = ids.mapIndexed { index, id ->
        val offsets = if (special) {
            "\"start\":null,\"stop\":null"
        } else {
            "\"start\":${spans[index].first},\"stop\":${spans[index].second}"
        }
        """{"id":$id,"text":"token$id","special":$special,$offsets}"""
    }.joinToString(
        prefix = "[",
        postfix = "]",
    )

    private fun enqueueTokenization(
        sourceTokenIds: List<Int> = listOf(10, 11),
        spans: List<Pair<Int, Int>> = sourceTokenIds.indices.map { it to it + 1 },
        specialTokenIds: List<Int> = emptyList(),
    ) {
        val entries = listOf(
            tokens(specialTokenIds, special = true).removeSurrounding("[", "]"),
            tokens(sourceTokenIds, spans = spans).removeSurrounding("[", "]"),
        ).filter { it.isNotBlank() }
        val batch = entries.joinToString(prefix = "[", postfix = "]")
        enqueue(200, "[$batch]")
    }

    private fun enqueuePromptProbe(promptTokenOverhead: Int) {
        enqueue(200, "[${tokens(listOf(100))}]")
        enqueue(200, "[${tokens((0..promptTokenOverhead).map { 200 + it })}]")
    }

    private fun enqueueShortEmbedding(code: Int = 200, body: String, promptTokenOverhead: Int? = null) {
        enqueueInfo(maxClientBatchSize = if (promptTokenOverhead == null) 1 else 2)
        if (promptTokenOverhead != null) enqueuePromptProbe(promptTokenOverhead)
        enqueueTokenization()
        enqueueTokenization()
        enqueue(code, body)
    }

    @Test
    fun `posts the text to the embed endpoint and parses the returned vector`() = runTest {
        enqueueShortEmbedding(body = "[[0.1, 0.2, 0.3]]")

        val result = service().embed("hello world")

        assertEquals(
            listOf(EmbeddingChunk(index = 0, tokenCount = 2, embedding = listOf(0.1f, 0.2f, 0.3f))),
            result,
        )
        assertEquals("/info", server.takeRequest().url.encodedPath)
        val tokenizeRequest = server.takeRequest()
        assertEquals("/tokenize", tokenizeRequest.url.encodedPath)
        assertTrue(tokenizeRequest.body!!.utf8().contains("\"add_special_tokens\":true"))
        assertEquals("/tokenize", server.takeRequest().url.encodedPath)
        val embedRequest = server.takeRequest()
        assertEquals("/embed", embedRequest.url.encodedPath)
        val requestBody = embedRequest.body!!.utf8()
        assertTrue(requestBody.contains("\"inputs\":[\"hello world\"]"), "posts the text under `inputs`")
        assertTrue(requestBody.contains("\"truncate\":false"), "never hides a sizing error by truncating")
    }

    @Test
    fun `applies the document prompt template before embedding`() = runTest {
        enqueueShortEmbedding(body = "[[1.0]]", promptTokenOverhead = 2)

        service(documentPrompt = "query: {text}").embed("hello")

        repeat(5) { server.takeRequest() }
        assertTrue(server.takeRequest().body!!.utf8().contains("\"inputs\":[\"query: hello\"]"))
    }

    @Test
    fun `returns null and makes no request when embeddings are disabled`() = runTest {
        assertNull(service(enabled = false).embed("hello"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `returns null and makes no request for blank text`() = runTest {
        assertNull(service().embed("   "))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `throws on a non-2xx response`() = runTest {
        enqueueShortEmbedding(code = 500, body = "boom")
        assertFailsWith<Exception> { service().embed("hello") }
    }

    @Test
    fun `throws on a malformed response body`() = runTest {
        enqueueShortEmbedding(body = "not json")
        assertFailsWith<Exception> { service().embed("hello") }
    }

    @Test
    fun `throws when the response batch is empty`() = runTest {
        enqueueShortEmbedding(body = "[]")
        assertFailsWith<Exception> { service().embed("hello") }
    }

    @Test
    fun `throws when the response vector is empty`() = runTest {
        enqueueShortEmbedding(body = "[[]]")
        assertFailsWith<Exception> { service().embed("hello") }
    }

    @Test
    fun `throws on an empty response body`() = runTest {
        enqueueShortEmbedding(body = "")
        assertFailsWith<Exception> { service().embed("hello") }
    }

    @Test
    fun `stores every chunk at the advertised token limit`() = runTest {
        enqueueInfo(maxInputLength = 6, maxClientBatchSize = 8)
        enqueuePromptProbe(promptTokenOverhead = 2)
        enqueueTokenization(
            sourceTokenIds = (1..8).toList(),
        )
        enqueueTokenization(sourceTokenIds = (1..5).toList())
        enqueueTokenization(sourceTokenIds = (1..5).toList())
        enqueueTokenization(sourceTokenIds = (1..4).toList())
        enqueue(200, "[[1.0, 0.0]]")
        enqueue(200, "[[0.0, 1.0]]")
        enqueue(200, "[[1.0, 1.0]]")

        val chunks = service(documentPrompt = "query: {text}").embed("abcdefgh")!!

        assertEquals(
            listOf(
                EmbeddingChunk(index = 0, tokenCount = 3, tokenStart = 0, tokenEnd = 3, embedding = listOf(1.0f, 0.0f)),
                EmbeddingChunk(index = 1, tokenCount = 3, tokenStart = 3, tokenEnd = 6, embedding = listOf(0.0f, 1.0f)),
                EmbeddingChunk(index = 2, tokenCount = 2, tokenStart = 6, tokenEnd = 8, embedding = listOf(1.0f, 1.0f)),
            ),
            chunks,
        )
        val requests = List(10) { server.takeRequest() }
        assertEquals(
            listOf(
                "/info",
                "/tokenize",
                "/tokenize",
                "/tokenize",
                "/tokenize",
                "/tokenize",
                "/tokenize",
                "/embed",
                "/embed",
                "/embed",
            ),
            requests.map { it.url.encodedPath },
        )
        assertTrue(requests[7].body!!.utf8().contains("query: abc"))
        assertTrue(requests[8].body!!.utf8().contains("query: def"))
        assertTrue(requests[9].body!!.utf8().contains("query: gh"))
    }

    @Test
    fun `overlap repeats source text without increasing aggregate document weight`() = runTest {
        enqueueInfo(maxInputLength = 5, maxClientBatchSize = 8)
        enqueueTokenization(sourceTokenIds = (1..8).toList())
        repeat(3) { enqueueTokenization(sourceTokenIds = (1..4).toList()) }
        enqueue(200, "[[1.0]]")
        enqueue(200, "[[2.0]]")
        enqueue(200, "[[3.0]]")

        val chunks = service(chunkOverlapTokens = 2).embed("abcdefgh")!!

        assertEquals(listOf(0 to 4, 2 to 6, 4 to 8), chunks.map { it.tokenStart to it.tokenEnd })
        assertEquals(listOf(3.0, 2.0, 3.0), chunks.map { it.aggregationWeight })
        assertEquals(8.0, chunks.sumOf { it.aggregationWeight })
        val embedBodies = List(8) { server.takeRequest() }
            .filter { it.url.encodedPath == "/embed" }
            .map { it.body!!.utf8() }
        assertTrue(embedBodies[0].contains("abcd"))
        assertTrue(embedBodies[1].contains("cdef"))
        assertTrue(embedBodies[2].contains("efgh"))
    }

    @Test
    fun `sends each embedding separately despite advertised batch capacity`() = runTest {
        enqueueInfo(maxInputLength = 5, maxBatchTokens = 6, maxClientBatchSize = 32)
        enqueueTokenization(sourceTokenIds = (1..8).toList())
        enqueueTokenization(sourceTokenIds = (1..4).toList())
        enqueueTokenization(sourceTokenIds = (1..4).toList())
        enqueue(200, "[[1.0]]")
        enqueue(200, "[[2.0]]")

        val chunks = service().embed("abcdefgh")!!

        assertEquals(2, chunks.size)
        val requests = List(6) { server.takeRequest() }
        assertEquals(listOf("/embed", "/embed"), requests.takeLast(2).map { it.url.encodedPath })
        assertTrue(requests[4].body!!.utf8().contains("\"inputs\":[\"abcd\"]"))
        assertTrue(requests[5].body!!.utf8().contains("\"inputs\":[\"efgh\"]"))
    }

    @Test
    fun `sends one input per request without changing chunk order`() = runTest {
        val chunkCount = 8
        enqueueInfo(maxInputLength = 5, maxBatchTokens = 64, maxClientBatchSize = 32)
        enqueueTokenization(sourceTokenIds = (1..(chunkCount * 4)).toList())
        repeat(chunkCount) { enqueueTokenization(sourceTokenIds = (1..4).toList()) }
        repeat(chunkCount) { enqueue(200, "[[${it.toFloat()}]]") }

        val chunks = service().embed("a".repeat(chunkCount * 4))!!

        assertEquals(chunkCount, chunks.size)
        assertEquals((0 until chunkCount).toList(), chunks.map { it.index })
        assertEquals((0 until chunkCount).map { listOf(it.toFloat()) }, chunks.map { it.embedding })
        val requests = List(18) { server.takeRequest() }
        assertEquals(1, requests.count { it.url.encodedPath == "/info" })
        assertEquals(9, requests.count { it.url.encodedPath == "/tokenize" })
        assertEquals(8, requests.count { it.url.encodedPath == "/embed" })
        requests.filter { it.url.encodedPath in setOf("/tokenize", "/embed") }.forEach { request ->
            val inputs = Json.parseToJsonElement(request.body!!.utf8()).jsonObject.getValue("inputs").jsonArray
            assertEquals(1, inputs.size)
        }
    }

    @Test
    fun `refreshes model information for each embedding operation`() = runTest {
        enqueueShortEmbedding(body = "[[1.0]]")
        enqueueShortEmbedding(body = "[[1.0]]")
        val service = service()

        service.embed("first")
        service.embed("second")

        assertEquals(
            listOf(
                "/info",
                "/tokenize",
                "/tokenize",
                "/embed",
                "/info",
                "/tokenize",
                "/tokenize",
                "/embed",
            ),
            List(8) { server.takeRequest().url.encodedPath },
        )
    }

    @Test
    fun `rejects a prompt unless it contains exactly one placeholder`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            service(documentPrompt = "{text} and {text}").embed("hello")
        }
        assertFailsWith<IllegalArgumentException> {
            service(documentPrompt = "document:").embed("hello")
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `rejects missing and unusable model limits`() = runTest {
        enqueue(200, "{}")
        assertFailsWith<Exception> { service().embed("hello") }

        enqueueInfo(maxInputLength = 1)
        assertFailsWith<Exception> { service().embed("hello") }

        enqueueInfo(maxBatchTokens = 1)
        assertFailsWith<Exception> { service().embed("hello") }
    }

    @Test
    fun `rejects tokenizer responses with missing batches or source tokens`() = runTest {
        enqueueInfo(maxClientBatchSize = 2)
        enqueue(200, "[]")
        assertFailsWith<Exception> { service().embed("hello") }

        enqueueInfo()
        enqueueTokenization(sourceTokenIds = emptyList(), specialTokenIds = listOf(1))
        assertFailsWith<Exception> { service().embed("hello") }
    }

    @Test
    fun `rejects a prompt whose measured overhead is invalid or consumes the model window`() = runTest {
        enqueueInfo(maxClientBatchSize = 2)
        enqueueTokenization(sourceTokenIds = listOf(100))
        enqueue(200, "[[]]")
        assertFailsWith<Exception> { service(documentPrompt = "query: {text}").embed("hello") }

        enqueueInfo(maxInputLength = 3, maxClientBatchSize = 2)
        enqueuePromptProbe(promptTokenOverhead = 2)
        enqueueTokenization(sourceTokenIds = listOf(1))
        assertFailsWith<Exception> { service(documentPrompt = "query: {text}").embed("a") }
    }

    @Test
    fun `repartitions when final tokenization exceeds the estimate`() = runTest {
        enqueueInfo(maxInputLength = 4)
        enqueueTokenization(sourceTokenIds = listOf(1, 2))
        enqueueTokenization(sourceTokenIds = listOf(1, 2, 3, 4))
        enqueueTokenization(sourceTokenIds = listOf(1, 2, 3, 4))
        enqueueTokenization(sourceTokenIds = listOf(1, 2, 3))
        enqueueTokenization(sourceTokenIds = listOf(1, 2, 3))
        enqueue(200, "[[1.0]]")
        enqueue(200, "[[2.0]]")

        val chunks = service().embed("ab")!!

        assertEquals(listOf(1, 1), chunks.map { it.tokenCount })
        val requests = List(8) { server.takeRequest() }
        assertTrue(requests[6].body!!.utf8().contains("\"inputs\":[\"a\"]"))
        assertTrue(requests[7].body!!.utf8().contains("\"inputs\":[\"b\"]"))
    }

    @Test
    fun `rejects a tokenizer span that cannot fit after final tokenization`() = runTest {
        enqueueInfo(maxInputLength = 3)
        enqueueTokenization(sourceTokenIds = listOf(1))
        enqueueTokenization(sourceTokenIds = listOf(1, 2, 3))
        enqueueTokenization(sourceTokenIds = listOf(1, 2, 3))

        assertFailsWith<Exception> { service().embed("a") }

        enqueueInfo(maxInputLength = 3)
        enqueueTokenization(
            sourceTokenIds = listOf(1, 2, 3),
            spans = listOf(0 to 1, 0 to 1, 0 to 1),
        )
        assertFailsWith<Exception> { service().embed("a") }
    }

    @Test
    fun `rejects invalid tokenizer source offsets`() = runTest {
        val invalidSpans = listOf(
            listOf(-1 to 1),
            listOf(0 to 0),
            listOf(0 to 2),
            listOf(1 to 2, 0 to 1),
        )
        for (spans in invalidSpans) {
            enqueueInfo()
            enqueueTokenization(sourceTokenIds = spans.indices.map { it + 1 }, spans = spans)
            val source = if (spans.size == 1) "a" else "ab"
            assertFailsWith<Exception> { service().embed(source) }
        }
    }

    @Test
    fun `rejects tokenizer offsets that divide a UTF-8 character`() = runTest {
        enqueueInfo(maxInputLength = 2)
        enqueueTokenization(
            sourceTokenIds = listOf(1, 2),
            spans = listOf(0 to 1, 1 to 2),
        )

        assertFailsWith<Exception> { service().embed("é") }
    }

    @Test
    fun `rejects unpaired UTF-16 surrogates before tokenization`() = runTest {
        enqueueInfo()
        assertFailsWith<Exception> { service().embed("\uD800") }

        enqueueInfo()
        assertFailsWith<Exception> { service().embed("\uDC00") }
    }

    @Test
    fun `preserves two-byte and three-byte characters`() = runTest {
        enqueueInfo(maxInputLength = 6)
        enqueueTokenization(sourceTokenIds = listOf(1, 2), spans = listOf(0 to 2, 2 to 5))
        enqueueTokenization(sourceTokenIds = listOf(1, 2))
        enqueue(200, "[[1.0]]")

        assertEquals(listOf(2), service().embed("é€")!!.map { it.tokenCount })
        assertTrue(List(4) { server.takeRequest() }.last().body!!.utf8().contains("é€"))
    }

    @Test
    fun `keeps byte fallback tokens sharing one source span in the same chunk`() = runTest {
        enqueueInfo(maxInputLength = 6)
        enqueueTokenization(
            sourceTokenIds = listOf(10, 11, 12, 13, 14, 15),
            spans = listOf(0 to 1, 1 to 2, 2 to 6, 2 to 6, 2 to 6, 2 to 6),
        )
        enqueueTokenization(sourceTokenIds = listOf(10, 11))
        enqueueTokenization(
            sourceTokenIds = listOf(12, 13, 14, 15),
            spans = listOf(0 to 4, 0 to 4, 0 to 4, 0 to 4),
        )
        enqueue(200, "[[1.0, 0.0]]")
        enqueue(200, "[[0.0, 1.0]]")

        val chunks = service().embed("ab𰻞")!!

        assertEquals(listOf(2, 4), chunks.map { it.tokenCount })
        val requests = List(6) { server.takeRequest() }
        assertTrue(requests[4].body!!.utf8().contains("\"inputs\":[\"ab\"]"))
        assertTrue(requests[5].body!!.utf8().contains("\"inputs\":[\"𰻞\"]"))
        assertTrue(requests.none { it.body?.utf8()?.contains("�") == true })
    }

    @Test
    fun `segments source before reaching the tokenizer request byte cap`() = runTest {
        enqueueInfo(maxInputLength = 6)
        enqueueTokenization(sourceTokenIds = listOf(10))
        enqueueTokenization(sourceTokenIds = listOf(10))
        enqueueTokenization(sourceTokenIds = listOf(10, 11), spans = listOf(0 to 1499, 1499 to 1500))
        enqueue(200, "[[1.0]]")

        val chunks = service().embed("a".repeat(1500))!!

        assertEquals(1, chunks.size)
        val requests = List(5) { server.takeRequest() }
        val tokenizerBodies = requests.filter { it.url.encodedPath == "/tokenize" }.map { it.body!!.utf8() }
        assertTrue(tokenizerBodies.all { it.length < 1600 }, "tokenizer request bodies remain bounded")
        assertTrue(requests.last().body!!.utf8().contains("a".repeat(1500)))
    }

    @Test
    fun `preserves configured overlap across tokenizer admission segments`() = runTest {
        enqueueInfo(maxInputLength = 6, maxClientBatchSize = 32)
        enqueueTokenization(
            sourceTokenIds = (1..6).toList(),
            spans = listOf(0 to 250, 250 to 500, 500 to 750, 750 to 1000, 1000 to 1250, 1250 to 1499),
        )
        enqueueTokenization(sourceTokenIds = listOf(7), spans = listOf(0 to 1))
        enqueueTokenization(sourceTokenIds = (1..5).toList())
        enqueueTokenization(sourceTokenIds = (1..3).toList())
        enqueue(200, "[[1.0]]")
        enqueue(200, "[[2.0]]")

        val chunks = service(chunkOverlapTokens = 1).embed("a".repeat(1499) + "b")!!

        assertEquals(listOf(0 to 5, 4 to 7), chunks.map { it.tokenStart to it.tokenEnd })
        assertEquals(7.0, chunks.sumOf { it.aggregationWeight })
        val requests = List(7) { server.takeRequest() }
        val secondEmbedInput = Json.parseToJsonElement(requests.last().body!!.utf8())
            .jsonObject.getValue("inputs").jsonArray.single().jsonPrimitive.content
        assertEquals(500, secondEmbedInput.toByteArray().size)
        assertTrue(secondEmbedInput.endsWith("b"))
        requests.filter { it.url.encodedPath == "/tokenize" }.forEach { request ->
            assertEquals(
                1,
                Json.parseToJsonElement(request.body!!.utf8()).jsonObject.getValue("inputs").jsonArray.size,
            )
        }
    }

    @Test
    fun `reserves fixed prompt bytes before tokenizer admission`() = runTest {
        enqueueInfo(maxInputLength = 6, maxClientBatchSize = 2)
        enqueuePromptProbe(promptTokenOverhead = 1)
        enqueueTokenization(sourceTokenIds = listOf(10), spans = listOf(0 to 1497))
        enqueueTokenization(sourceTokenIds = listOf(11), spans = listOf(0 to 1))
        enqueueTokenization(sourceTokenIds = listOf(10, 11), specialTokenIds = listOf(100))
        enqueue(200, "[[1.0]]")

        val chunks = service(documentPrompt = "p:{text}").embed("a".repeat(1498))!!

        assertEquals(listOf(0 to 2), chunks.map { it.tokenStart to it.tokenEnd })
        val requests = List(7) { server.takeRequest() }
        val initialInputs = requests.subList(3, 5).flatMap { request ->
            Json.parseToJsonElement(request.body!!.utf8())
                .jsonObject.getValue("inputs").jsonArray.map { it.jsonPrimitive.content }
        }
        assertEquals(listOf("a".repeat(1497), "a"), initialInputs)
        assertTrue(requests.last().body!!.utf8().contains("p:${"a".repeat(1497)}"))
    }

    @Test
    fun `embeds metadata using search text and stores the vector`() = runTest {
        val metadata = metadata()
        coEvery { metadataToSearchDocument.extractText(any(), metadata) } returns "body"
        coEvery { metadataService.setEmbeddings(metadata, any()) } returns true
        enqueueShortEmbedding(body = "[[0.25, 0.75]]")

        assertTrue(service().embed(metadata))

        coVerify(exactly = 1) {
            metadataService.setEmbeddings(
                metadata,
                listOf(EmbeddingChunk(index = 0, tokenCount = 2, embedding = listOf(0.25f, 0.75f))),
            )
        }
    }

    @Test
    fun `reports a metadata embedding as stale when replacement is skipped`() = runTest {
        val metadata = metadata()
        coEvery { metadataToSearchDocument.extractText(any(), metadata) } returns "body"
        coEvery { metadataService.setEmbeddings(metadata, any()) } returns false
        coEvery { metadataService.getById(metadata.id) } returns null
        enqueueShortEmbedding(body = "[[0.25, 0.75]]")

        assertEquals(false, service().embed(metadata))
    }

    @Test
    fun `retries the current metadata snapshot once after a stale replacement`() = runTest {
        val original = metadata()
        val current = original.copy(modified = OffsetDateTime.now())
        coEvery { metadataToSearchDocument.extractText(any(), original) } returns "old body"
        coEvery { metadataToSearchDocument.extractText(any(), current) } returns "current body"
        coEvery { metadataService.setEmbeddings(original, any()) } returns false
        coEvery { metadataService.getById(original.id) } returns current
        coEvery { metadataService.setEmbeddings(current, any()) } returns true
        enqueueShortEmbedding(body = "[[0.25]]")
        enqueueShortEmbedding(body = "[[0.75]]")

        assertTrue(service().embed(original))

        coVerify(exactly = 1) { metadataService.removeFromCache(original.id, null) }
        coVerify(exactly = 1) {
            metadataService.setEmbeddings(
                current,
                listOf(EmbeddingChunk(index = 0, tokenCount = 2, embedding = listOf(0.75f))),
            )
        }
    }

    @Test
    fun `metadata with no extractable text is not stored`() = runTest {
        val metadata = metadata()
        coEvery { metadataToSearchDocument.extractText(any(), metadata) } returns "   "

        assertEquals(false, service().embed(metadata))

        assertEquals(0, server.requestCount)
        coVerify(exactly = 0) { metadataService.setEmbeddings(any(), any()) }
    }

    @Test
    fun `disabled metadata embedding extracts text but stores nothing`() = runTest {
        val metadata = metadata()
        coEvery { metadataToSearchDocument.extractText(any(), metadata) } returns "body"

        assertEquals(false, service(enabled = false).embed(metadata))

        coVerify(exactly = 0) { metadataService.setEmbeddings(any(), any()) }
    }

    @Test
    fun `backfill fills only missing ready recommendable metadata`() = runTest {
        val existing = metadata()
        val missing = metadata()
        val draft = metadata(ready = null)
        val excluded = metadata(recommendable = false)
        coEvery { metadataService.getAll(0, 4) } returns listOf(existing, missing, draft, excluded)
        coEvery { metadataService.getAll(4, 4) } returns emptyList()
        coEvery { metadataService.getEmbeddingIds(listOf(existing.id, missing.id)) } returns listOf(existing.id)
        coEvery { metadataToSearchDocument.extractText(any(), missing) } returns "missing body"
        coEvery { metadataService.setEmbeddings(missing, any()) } returns true
        enqueueShortEmbedding(body = "[[1.0]]")

        val count = withRequestCache { service().backfill(overwriteExisting = false, batchSize = 4) }

        assertEquals(1L, count)
        coVerify(exactly = 1) {
            metadataService.setEmbeddings(
                missing,
                listOf(EmbeddingChunk(index = 0, tokenCount = 2, embedding = listOf(1.0f))),
            )
        }
        coVerify(exactly = 0) { metadataToSearchDocument.extractText(any(), existing) }
        coVerify(exactly = 0) { metadataToSearchDocument.extractText(any(), draft) }
        coVerify(exactly = 0) { metadataToSearchDocument.extractText(any(), excluded) }
    }

    @Test
    fun `overwrite backfill refreshes existing embeddings without existence lookup`() = runTest {
        val metadata = metadata()
        coEvery { metadataService.getAll(0, 1) } returns listOf(metadata)
        coEvery { metadataService.getAll(1, 1) } returns emptyList()
        coEvery { metadataToSearchDocument.extractText(any(), metadata) } returns "refresh body"
        coEvery { metadataService.setEmbeddings(metadata, any()) } returns true
        enqueueShortEmbedding(body = "[[0.5]]")

        val count = withRequestCache { service().backfill(overwriteExisting = true, batchSize = 1) }

        assertEquals(1L, count)
        coVerify(exactly = 0) { metadataService.getEmbeddingIds(any()) }
        coVerify(exactly = 1) {
            metadataService.setEmbeddings(
                metadata,
                listOf(EmbeddingChunk(index = 0, tokenCount = 2, embedding = listOf(0.5f))),
            )
        }
    }

    @Test
    fun `backfill continues when eligible metadata has no extractable text`() = runTest {
        val metadata = metadata()
        coEvery { metadataService.getAll(0, 1) } returns listOf(metadata)
        coEvery { metadataService.getAll(1, 1) } returns emptyList()
        coEvery { metadataService.getEmbeddingIds(listOf(metadata.id)) } returns emptyList()
        coEvery { metadataToSearchDocument.extractText(any(), metadata) } returns "   "

        val count = withRequestCache { service().backfill(overwriteExisting = false, batchSize = 1) }

        assertEquals(0L, count)
        coVerify(exactly = 0) { metadataService.setEmbeddings(any(), any()) }
    }

    @Test
    fun `backfill rejects a non-positive batch size`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            service().backfill(overwriteExisting = false, batchSize = 0)
        }
    }
}
