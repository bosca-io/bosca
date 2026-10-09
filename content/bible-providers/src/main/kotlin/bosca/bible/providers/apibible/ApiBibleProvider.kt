package bosca.bible.providers.apibible

import bosca.bible.Bible
import bosca.bible.BibleAttribution
import bosca.bible.BibleAudioContent
import bosca.bible.BibleAudioTimecode
import bosca.bible.BibleIdentification
import bosca.bible.BibleMetadata
import bosca.bible.BiblePublication
import bosca.bible.BibleSystem
import bosca.bible.Book
import bosca.bible.Chapter
import bosca.bible.Name
import bosca.bible.Reference
import bosca.bible.providers.model.BibleProviderResponse
import bosca.bible.providers.model.BibleSourceKind
import bosca.bible.providers.model.IBibleCatalog
import bosca.bible.providers.service.BibleProvider
import bosca.server.http.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/** API.Bible access using server credentials and the shared Bible models. */
class ApiBibleProvider internal constructor(
    private val apiKey: String,
    client: OkHttpClient,
    private val endpoint: HttpUrl,
) : BibleProvider {

    constructor(apiKey: String, client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS).build()) : this(apiKey, client, "https://rest.api.bible/v1/".toHttpUrl())

    init {
        require(apiKey.isNotBlank() && apiKey.all { it == '\t' || it in ' '..'~' }) {
            "A valid API.Bible API key is required"
        }
    }

    override val key = "API_BIBLE"
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        exceptionsWithDebugInfo = false
    }

    override suspend fun test() {
        catalog(BibleSourceKind.TEXT)
    }

    override suspend fun getCatalog(kind: BibleSourceKind, query: String?, offset: Int, limit: Int): List<IBibleCatalog> {
        require(offset >= 0 && limit >= 0) { "Catalog offset and limit must be nonnegative" }
        if (limit == 0) return emptyList()
        val search = query?.trim().orEmpty()
        return catalog(kind).filter {
            search.isEmpty() || listOf(it.name, it.nameLocal, it.abbreviation, it.abbreviationLocal,
                it.description, it.language.name, it.language.iso).any { field -> field.contains(search, ignoreCase = true) }
        }.drop(offset).take(limit)
    }

    override suspend fun getBible(providerBibleId: String, kind: BibleSourceKind): BibleProviderResponse<Bible> {
        val remote = if (kind == BibleSourceKind.AUDIO) {
            catalog(kind, providerBibleId).singleOrNull { it.id == providerBibleId }
                ?: throw IOException("API.Bible audio Bible was not found")
        } else request(listOf("bibles", providerBibleId), emptyMap(),
            ApiBibleResponse.serializer(ApiBibleCatalog.serializer())).data
        checkPayload(remote.id == providerBibleId)
        val remoteBooks = request(listOf(collection(kind), providerBibleId, "books"),
            mapOf("include-chapters" to "true"), ApiBibleResponse.serializer(ListSerializer(ApiBibleBook.serializer()))).data
        val books = remoteBooks.map { book ->
            Book(Reference(book.usfm), Name(book.usfm, book.nameLong, book.name, book.abbreviation ?: book.name),
                book.chapters.map { chapter ->
                    checkPayload(chapter.bookUsfm == book.usfm && Reference(chapter.usfm).bookUsfm == book.usfm)
                    checkChapterReference(chapter.usfm)
                    Chapter(Reference(chapter.usfm), null)
                })
        }
        val metadata = BibleMetadata(
            BibleIdentification(BibleSystem(remote.systemId), remote.name, remote.nameLocal, remote.description,
                remote.abbreviation, remote.abbreviationLocal),
            BiblePublication(remote.id, remote.name, remote.nameLocal, remote.description, remote.descriptionLocal.orEmpty(),
                remote.abbreviation, remote.abbreviationLocal),
            remote.language.asSerializable(),
        )
        return BibleProviderResponse(Bible(metadata, books, emptyList()), remote.attribution)
    }

    override suspend fun getChapter(providerBibleId: String, usfm: String): BibleProviderResponse<Chapter> {
        checkChapterReference(usfm)
        val response = request(listOf("bibles", providerBibleId, "chapters", usfm), mapOf(
            "content-type" to "json", "fums-version" to "3", "include-titles" to "true",
            "include-verse-numbers" to "true", "include-chapter-numbers" to "false", "include-notes" to "false",
        ), ApiBibleResponse.serializer(ApiBibleChapter.serializer()))
        val chapter = response.data
        checkPayload(chapter.bibleId == providerBibleId && chapter.usfm == usfm &&
            chapter.bookUsfm == Reference(usfm).bookUsfm && chapter.verseCount >= 0)
        return BibleProviderResponse(
            Chapter(Reference(usfm), ApiBibleContent(usfm).convert(chapter.content)),
            attribution(chapter.copyright), chapter.verseCount, token(response.meta),
        )
    }

    override suspend fun downloadAudioChapter(providerBibleId: String, usfm: String, destination: OutputStream): BibleProviderResponse<BibleAudioContent> {
        checkChapterReference(usfm)
        val response = request(listOf("audio-bibles", providerBibleId, "chapters", usfm),
            mapOf("fums-version" to "3"), ApiBibleResponse.serializer(ApiBibleAudioChapter.serializer()))
        val chapter = response.data
        checkPayload(chapter.bibleId == providerBibleId && chapter.usfm == usfm && chapter.bookUsfm == Reference(usfm).bookUsfm)
        val token = token(response.meta)
        val timecodes = chapter.timecodes.map {
            val start = seconds(it.start)
            val end = seconds(it.end)
            checkPayload(it.usfm.startsWith("$usfm.") && end >= start)
            BibleAudioTimecode(it.usfm, start, end)
        }
        checkPayload(timecodes.zipWithNext().all { (first, second) -> second.start >= first.start })
        val url = chapter.resourceUrl.toHttpUrlOrNull() ?: throw IOException("API.Bible returned an invalid audio URL")
        checkPayload(url.isHttps || !endpoint.isHttps && url.host == endpoint.host)
        return client.newCall(Request.Builder().url(url).build()).await().use { media ->
            if (!media.isSuccessful) throw ApiBibleException(media.code, media.header("Retry-After"))
            val type = media.body.contentType()?.let { "${it.type}/${it.subtype}" } ?: "application/octet-stream"
            checkPayload(type.startsWith("audio/") || type == "application/octet-stream")
            val length = withContext(Dispatchers.IO) {
                media.body.byteStream().use { stream ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        ensureActive()
                        val count = stream.read(buffer)
                        if (count < 0) break
                        destination.write(buffer, 0, count)
                        total += count
                    }
                    total
                }
            }
            checkPayload(length > 0 && (media.body.contentLength() < 0 || media.body.contentLength() == length))
            BibleProviderResponse(BibleAudioContent(type, length, timecodes), attribution(chapter.copyright), fumsToken = token)
        }
    }

    private suspend fun catalog(kind: BibleSourceKind, id: String? = null): List<ApiBibleCatalog> =
        request(listOf(collection(kind)), buildMap {
            put("include-full-details", "true")
            id?.let { put("ids", it) }
        }, ApiBibleResponse.serializer(ListSerializer(ApiBibleCatalog.serializer()))).data.map { it.copy(kind = kind) }

    private fun collection(kind: BibleSourceKind) = if (kind == BibleSourceKind.AUDIO) "audio-bibles" else "bibles"

    private suspend fun <T> request(path: List<String>, parameters: Map<String, String>, serializer: KSerializer<T>): T {
        val url = endpoint.newBuilder().apply {
            path.forEach { addPathSegment(it) }
            parameters.forEach { (name, value) -> addQueryParameter(name, value) }
        }.build()
        return client.newCall(Request.Builder().url(url).header("api-key", apiKey).header("Accept", "application/json").build())
            .await().use { response ->
                if (!response.isSuccessful) throw ApiBibleException(response.code, response.header("Retry-After"))
                withContext(Dispatchers.IO) { json.decodeFromString(serializer, response.body.string()) }
            }
    }

    private fun token(meta: ApiBibleMeta?): String = meta?.fumsToken?.takeIf { it.isNotBlank() }
        ?: throw IOException("API.Bible response has no FUMS token")

    private fun attribution(copyright: String?) = copyright?.takeIf { it.isNotBlank() }?.let { BibleAttribution(it) }

    private fun checkChapterReference(usfm: String) {
        require(usfm.matches(Regex("[A-Z0-9]+\\.([0-9]+|intro)"))) { "A chapter USFM reference is required" }
    }

    private fun seconds(value: String): Double {
        val parts = value.split(':')
        checkPayload(parts.size in 1..3)
        val numbers = parts.map { it.toDoubleOrNull()?.takeIf { number -> number.isFinite() && number >= 0 }
            ?: throw IOException("API.Bible returned an invalid audio timecode") }
        checkPayload(parts.size == 1 || numbers.drop(1).all { it < 60 })
        return numbers.fold(0.0) { total, part -> total * 60 + part }.also { checkPayload(it.isFinite()) }
    }

    private fun checkPayload(valid: Boolean) {
        if (!valid) throw IOException("API.Bible returned an invalid response")
    }
}

/** HTTP failures retain status and Retry-After without exposing credentials or response bodies. */
class ApiBibleException(val statusCode: Int, val retryAfter: String?) : IOException("API.Bible request failed (HTTP $statusCode)")
