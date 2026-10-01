package bosca.content.embedding.service

import bosca.cache.requestCache
import bosca.content.embedding.model.EmbeddingChunk
import bosca.content.embedding.model.EmbeddingConfiguration
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataService
import bosca.content.transformations.MetadataToSearchDocument
import bosca.search.IndexStorageSystem
import bosca.search.pipeline.SearchDocumentPipeline
import bosca.service.annotation.ServiceImplementation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

@ServiceImplementation
class EmbeddingServiceImpl(
    private val config: EmbeddingConfiguration,
    private val metadataService: MetadataService,
    private val metadataToSearchDocument: MetadataToSearchDocument,
) : EmbeddingService {

    // Built lazily so the OkHttp client is only created in a process that actually computes embeddings (the
    // runner's index job) — never in the native server, which holds this service but never calls embed().
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(config.timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(config.timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .build()
    }

    private val jsonMediaType = "application/json".toMediaType()

    private data class SourceToken(
        val start: Int,
        val stop: Int,
    )

    private data class TokenBatch(
        val totalCount: Int,
        val sourceTokens: List<SourceToken>,
    ) {
        val specialTokenCount: Int
            get() = totalCount - sourceTokens.size
    }

    private data class SourceSegment(
        val text: String,
        val byteStart: Int,
        val byteCount: Int,
    )

    private data class TokenGroup(
        val start: Int,
        val stop: Int,
        val tokenStart: Int,
        val tokenEnd: Int,
    )

    private data class PreparedChunk(
        val text: String,
        val tokenStart: Int,
        val tokenEnd: Int,
        val tokenCount: Int,
        val modelTokenCount: Int = 0,
    )

    private data class EmbeddingModelInfo(
        val maxInputLength: Int,
        val maxBatchTokens: Int,
    ) {
        val inputTokenLimit: Int
            get() = minOf(maxInputLength, maxBatchTokens)
    }

    /**
     * Returns embedding chunks for [text], or null ONLY when embeddings are disabled or the text is
     * blank — the deliberate "nothing to embed" cases. When embeddings are enabled, any failure (embedder
     * unreachable, error status, or unparseable/empty response) throws and fails the caller (the index job /
     * pipeline node); the failure is surfaced, never silently skipped. Inputs longer than the serving model's
     * advertised context window are tokenizer-chunked and returned individually.
     */
    override suspend fun embed(text: String): List<EmbeddingChunk>? {
        if (!config.enabled || text.isBlank()) return null
        validateDocumentPrompt()
        require(config.chunkOverlapTokens >= 0) { "Embedding chunk overlap must not be negative" }
        val modelInfo = modelInfo()
        val promptTokenOverhead = promptTokenOverhead()
        val tokenizerInputByteLimit = tokenizerInputByteLimit(modelInfo.maxInputLength)
        val segments = splitByUtf8ByteLimit(text, tokenizerInputByteLimit)
        val tokenizedSegments = tokenizeIndividually(segments.map(SourceSegment::text))
        val tokenized = mergeTokenizedSegments(segments, tokenizedSegments)
        val prepared = prepareChunks(text, tokenized, promptTokenOverhead, modelInfo)
        check(prepared.isNotEmpty()) {
            "Embedding tokenizer returned no source tokens for non-blank input"
        }
        val aggregationWeights = overlapAwareWeights(prepared, tokenized.sourceTokens.size)
        val vectors = embedIndividually(prepared, modelInfo)
        return prepared.mapIndexed { index, chunk ->
            EmbeddingChunk(
                index = index,
                tokenStart = chunk.tokenStart,
                tokenEnd = chunk.tokenEnd,
                tokenCount = chunk.tokenCount,
                aggregationWeight = aggregationWeights[index],
                embedding = vectors[index],
            )
        }
    }

    private fun validateDocumentPrompt() {
        if (config.documentPrompt.isBlank()) return
        require(config.documentPrompt.windowed("{text}".length).count { it == "{text}" } == 1) {
            "Embedding document prompt must contain exactly one {text} placeholder"
        }
    }

    private fun applyDocumentPrompt(text: String): String =
        if (config.documentPrompt.isBlank()) text else config.documentPrompt.replace("{text}", text)

    private suspend fun modelInfo(): EmbeddingModelInfo {
        val request = Request.Builder().url("${config.url.trimEnd('/')}/info").get().build()
        val response = Json.parseToJsonElement(execute(request, "Embedding model info")).jsonObject
        val maxInputLength = response.getValue("max_input_length").jsonPrimitive.int
        val maxBatchTokens = response["max_batch_tokens"]?.jsonPrimitive?.int ?: maxInputLength
        check(maxInputLength > 1) {
            "Embedding model max_input_length must be greater than one: $maxInputLength"
        }
        check(maxBatchTokens > 1) {
            "Embedding model max_batch_tokens must be greater than one: $maxBatchTokens"
        }
        return EmbeddingModelInfo(maxInputLength, maxBatchTokens)
    }

    private suspend fun promptTokenOverhead(): Int? {
        if (config.documentPrompt.isBlank()) return null
        val sentinel = "x"
        val batches = tokenizeIndividually(listOf(sentinel, applyDocumentPrompt(sentinel)))
        val overhead = batches[1].totalCount - batches[0].sourceTokens.size
        check(overhead >= 0) {
            "Embedding document prompt produced an invalid negative token overhead: $overhead"
        }
        return overhead
    }

    private suspend fun tokenizeIndividually(inputs: List<String>): List<TokenBatch> =
        inputs.map { tokenize(it) }

    private suspend fun tokenize(input: String): TokenBatch {
        val body = buildJsonObject {
            put("inputs", buildJsonArray {
                add(input)
            })
            put("add_special_tokens", true)
        }.toString().toRequestBody(jsonMediaType)
        val request = Request.Builder().url("${config.url.trimEnd('/')}/tokenize").post(body).build()
        val batches = Json.parseToJsonElement(execute(request, "Embedding tokenization")).jsonArray
        if (batches.size != 1) {
            error("Embedding tokenization response contained ${batches.size} results instead of one")
        }
        return parseTokenBatch(batches.single())
    }

    private fun parseTokenBatch(element: JsonElement): TokenBatch {
        val tokens: JsonArray = element.jsonArray
        val sourceTokens = tokens.mapNotNull { tokenElement ->
            val token = tokenElement.jsonObject
            if (token.getValue("special").jsonPrimitive.boolean) {
                null
            } else {
                SourceToken(
                    start = token.getValue("start").jsonPrimitive.int,
                    stop = token.getValue("stop").jsonPrimitive.int,
                )
            }
        }
        return TokenBatch(tokens.size, sourceTokens)
    }

    private fun mergeTokenizedSegments(
        segments: List<SourceSegment>,
        tokenizedSegments: List<TokenBatch>,
    ): TokenBatch {
        check(segments.size == tokenizedSegments.size) {
            "Embedding tokenization did not return one result for every source segment"
        }
        val sourceTokens = segments.zip(tokenizedSegments).flatMap { (segment, tokenized) ->
            tokenized.sourceTokens.map { token ->
                check(token.start >= 0 && token.stop > token.start && token.stop <= segment.byteCount) {
                    "Embedding tokenizer returned invalid source offsets ${token.start}..${token.stop} " +
                        "for a ${segment.byteCount}-byte input"
                }
                SourceToken(
                    start = segment.byteStart + token.start,
                    stop = segment.byteStart + token.stop,
                )
            }
        }
        val specialTokenCount = tokenizedSegments.maxOfOrNull(TokenBatch::specialTokenCount) ?: 0
        return TokenBatch(sourceTokens.size + specialTokenCount, sourceTokens)
    }

    private suspend fun prepareChunks(
        source: String,
        tokenized: TokenBatch,
        promptTokenOverhead: Int?,
        modelInfo: EmbeddingModelInfo,
    ): List<PreparedChunk> {
        if (tokenized.sourceTokens.isEmpty()) return emptyList()
        val sourceBytes = source.toByteArray(StandardCharsets.UTF_8)
        val groups = groupIndivisibleSourceSpans(tokenized.sourceTokens, sourceBytes.size)
        val estimatedOverhead = promptTokenOverhead ?: tokenized.specialTokenCount
        var sourceTokenLimit = modelInfo.inputTokenLimit - estimatedOverhead - 1
        check(sourceTokenLimit > 0) {
            "Embedding document prompt uses the serving model's entire ${modelInfo.inputTokenLimit}-token input budget"
        }
        while (true) {
            val chunks = partitionChunks(sourceBytes, groups, sourceTokenLimit)
            val finalTokenCounts = tokenizeIndividually(
                chunks.map { applyDocumentPrompt(it.text) },
            ).map { it.totalCount }
            val largestOverflow = finalTokenCounts.maxOf { it - modelInfo.inputTokenLimit + 1 }
            if (largestOverflow <= 0) {
                return chunks.zip(finalTokenCounts) { chunk, finalTokenCount ->
                    chunk.copy(modelTokenCount = finalTokenCount)
                }
            }
            sourceTokenLimit -= largestOverflow.coerceAtLeast(1)
            check(sourceTokenLimit > 0) {
                "A single embedding tokenizer source span cannot fit within the serving model's " +
                    "${modelInfo.inputTokenLimit}-token input budget after applying the document prompt"
            }
        }
    }

    private fun partitionChunks(
        sourceBytes: ByteArray,
        groups: List<TokenGroup>,
        sourceTokenLimit: Int,
    ): List<PreparedChunk> {
        val chunks = mutableListOf<PreparedChunk>()
        val effectiveOverlapTokens = minOf(config.chunkOverlapTokens, (sourceTokenLimit - 1).coerceAtLeast(0))
        var firstGroup = 0
        while (firstGroup < groups.size) {
            var endGroup = firstGroup
            while (
                endGroup < groups.size &&
                groups[endGroup].tokenEnd - groups[firstGroup].tokenStart <= sourceTokenLimit
            ) {
                endGroup++
            }
            check(endGroup > firstGroup) {
                "A single embedding tokenizer source span exceeds the serving model's input limit"
            }
            val firstByte = if (firstGroup == 0) 0 else groups[firstGroup].start
            val endByte = if (endGroup < groups.size) groups[endGroup].start else sourceBytes.size
            val localTokenStart = groups[firstGroup].tokenStart
            val localTokenEnd = groups[endGroup - 1].tokenEnd
            chunks += PreparedChunk(
                text = decodeSourceBytes(sourceBytes, firstByte, endByte),
                tokenStart = localTokenStart,
                tokenEnd = localTokenEnd,
                tokenCount = localTokenEnd - localTokenStart,
            )
            if (endGroup == groups.size) break

            var nextFirstGroup = endGroup
            var overlapTokenCount = 0
            while (
                nextFirstGroup > firstGroup + 1 &&
                overlapTokenCount < effectiveOverlapTokens
            ) {
                nextFirstGroup--
                overlapTokenCount += groups[nextFirstGroup].tokenEnd - groups[nextFirstGroup].tokenStart
            }
            firstGroup = nextFirstGroup
        }
        return chunks
    }

    private fun groupIndivisibleSourceSpans(tokens: List<SourceToken>, sourceByteCount: Int): List<TokenGroup> {
        val groups = mutableListOf<TokenGroup>()
        for ((tokenIndex, token) in tokens.withIndex()) {
            check(token.start >= 0 && token.stop > token.start && token.stop <= sourceByteCount) {
                "Embedding tokenizer returned invalid source offsets ${token.start}..${token.stop} " +
                    "for a $sourceByteCount-byte input"
            }
            val previous = groups.lastOrNull()
            check(previous == null || token.start >= previous.start) {
                "Embedding tokenizer returned non-monotonic source offsets"
            }
            if (previous != null && token.start < previous.stop) {
                groups[groups.lastIndex] = previous.copy(
                    stop = maxOf(previous.stop, token.stop),
                    tokenEnd = tokenIndex + 1,
                )
            } else {
                groups += TokenGroup(token.start, token.stop, tokenIndex, tokenIndex + 1)
            }
        }
        return groups
    }

    private fun overlapAwareWeights(chunks: List<PreparedChunk>, totalTokenCount: Int): List<Double> {
        val coverageChanges = IntArray(totalTokenCount + 1)
        for (chunk in chunks) {
            coverageChanges[chunk.tokenStart]++
            coverageChanges[chunk.tokenEnd]--
        }
        val inverseCoveragePrefix = DoubleArray(totalTokenCount + 1)
        var coverage = 0
        for (tokenIndex in 0 until totalTokenCount) {
            coverage += coverageChanges[tokenIndex]
            check(coverage > 0) { "Embedding chunks left source token $tokenIndex uncovered" }
            inverseCoveragePrefix[tokenIndex + 1] = inverseCoveragePrefix[tokenIndex] + 1.0 / coverage
        }
        return chunks.map { chunk ->
            inverseCoveragePrefix[chunk.tokenEnd] - inverseCoveragePrefix[chunk.tokenStart]
        }
    }

    private fun decodeSourceBytes(source: ByteArray, start: Int, stop: Int): String {
        val decoded = source.copyOfRange(start, stop).toString(StandardCharsets.UTF_8)
        check(decoded.toByteArray(StandardCharsets.UTF_8).contentEquals(source.copyOfRange(start, stop))) {
            "Embedding tokenizer offsets divided an invalid UTF-8 source boundary"
        }
        return decoded
    }

    private fun tokenizerInputByteLimit(maxInputLength: Int): Int {
        val fixedPromptBytes = if (config.documentPrompt.isBlank()) {
            0
        } else {
            config.documentPrompt.replace("{text}", "").toByteArray(StandardCharsets.UTF_8).size
        }
        val limit = maxInputLength.toLong() * TOKENIZER_MAX_CHAR_MULTIPLIER - 1L - fixedPromptBytes
        check(limit > 0) {
            "Embedding document prompt exceeds the tokenizer's input admission limit"
        }
        return limit
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    private fun splitByUtf8ByteLimit(source: String, byteLimit: Int): List<SourceSegment> {
        val segments = mutableListOf<SourceSegment>()
        var segmentStart = 0
        var segmentByteStart = 0
        var segmentBytes = 0
        var index = 0
        while (index < source.length) {
            val character = source[index]
            val codePoint: Int
            val characterCount: Int
            if (Character.isHighSurrogate(character)) {
                check(index + 1 < source.length && Character.isLowSurrogate(source[index + 1])) {
                    "Embedding source contains an unpaired high surrogate at UTF-16 index $index"
                }
                codePoint = Character.toCodePoint(character, source[index + 1])
                characterCount = 2
            } else {
                check(!Character.isLowSurrogate(character)) {
                    "Embedding source contains an unpaired low surrogate at UTF-16 index $index"
                }
                codePoint = character.code
                characterCount = 1
            }
            val characterBytes = when {
                codePoint <= 0x7f -> 1
                codePoint <= 0x7ff -> 2
                codePoint <= 0xffff -> 3
                else -> 4
            }
            if (segmentBytes > 0 && segmentBytes + characterBytes > byteLimit) {
                segments += SourceSegment(
                    text = source.substring(segmentStart, index),
                    byteStart = segmentByteStart,
                    byteCount = segmentBytes,
                )
                segmentStart = index
                segmentByteStart += segmentBytes
                segmentBytes = 0
            }
            segmentBytes += characterBytes
            index += characterCount
        }
        segments += SourceSegment(
            text = source.substring(segmentStart),
            byteStart = segmentByteStart,
            byteCount = segmentBytes,
        )
        return segments
    }

    private suspend fun embedIndividually(
        chunks: List<PreparedChunk>,
        modelInfo: EmbeddingModelInfo,
    ): List<List<Float>> {
        for (chunk in chunks) {
            check(chunk.modelTokenCount in 1 until modelInfo.inputTokenLimit) {
                "Embedding chunk has invalid final token count ${chunk.modelTokenCount}"
            }
        }
        val vectors = chunks.map { chunk -> embedOne(applyDocumentPrompt(chunk.text)) }
        check(vectors.all { it.isNotEmpty() && it.all(Float::isFinite) }) {
            "Embedding response contained an empty or non-finite vector"
        }
        check(vectors.map { it.size }.distinct().size <= 1) {
            "Embedding response contained vectors with inconsistent dimensions"
        }
        return vectors
    }

    private suspend fun embedOne(input: String): List<Float> {
        val body = buildJsonObject {
            put("inputs", buildJsonArray { add(input) })
            // Every final prompted input is re-tokenized and checked. Keep truncation disabled so a
            // tokenizer/model contract change fails loudly instead of silently discarding document text.
            put("truncate", false)
        }.toString().toRequestBody(jsonMediaType)
        val request = Request.Builder().url("${config.url.trimEnd('/')}/embed").post(body).build()
        val vectors = Json.parseToJsonElement(execute(request, "Embedding")).jsonArray
        check(vectors.size == 1) {
            "Embedding response contained ${vectors.size} vectors instead of one"
        }
        return vectors.single().jsonArray.map { it.jsonPrimitive.float }
    }

    private suspend fun execute(request: Request, operation: String): String =
        withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body.string()
                check(response.isSuccessful) {
                    "$operation request to ${request.url} failed: HTTP ${response.code} $responseBody"
                }
                responseBody
            }
        }

    override suspend fun embed(metadata: Metadata): Boolean = embed(metadata, retryStale = true)

    private suspend fun embed(metadata: Metadata, retryStale: Boolean): Boolean {
        val text = metadataToSearchDocument.extractText(
            IndexStorageSystem(name = SearchDocumentPipeline.DEFAULT_INDEX),
            metadata,
        )
        if (text.isBlank()) return false
        val chunks = embed(text) ?: return false
        if (metadataService.setEmbeddings(metadata, chunks)) return true
        if (!retryStale) return false

        // The provider work deliberately runs outside the replacement transaction. If the source changed
        // while it ran, refresh the request cache and retry the current snapshot once instead of either
        // storing stale vectors or leaving an item unembedded after an unrelated metadata update.
        metadataService.removeFromCache(metadata.id)
        val current = metadataService.getById(metadata.id) ?: return false
        return embed(current, retryStale = false)
    }

    override suspend fun backfill(overwriteExisting: Boolean, batchSize: Int): Long {
        require(batchSize > 0) { "Embedding backfill batch size must be positive" }
        var offset = 0L
        var embedded = 0L
        while (true) {
            val page = metadataService.getAll(offset, batchSize)
            if (page.isEmpty()) break
            val eligible = page.filter { it.ready != null && it.isRecommendable }
            val existing = if (overwriteExisting) {
                emptySet()
            } else {
                metadataService.getEmbeddingIds(eligible.map { it.id }).toSet()
            }
            for (metadata in eligible) {
                if (metadata.id !in existing && embed(metadata)) embedded++
            }
            offset += page.size
            requestCache().clearLocal()
        }
        return embedded
    }

    private companion object {
        // TEI 1.8.1 rejects a /tokenize input at max_input_length * 250 characters. A strictly smaller
        // UTF-8 byte bound is conservative because every Unicode code point occupies at least one byte.
        const val TOKENIZER_MAX_CHAR_MULTIPLIER = 250L
    }
}
