package bosca.content.metadata.service

import bosca.bible.BibleChapterContent
import bosca.bible.BibleAudio
import bosca.bible.BibleAudioChapter
import bosca.bible.BibleContentUse
import bosca.bible.Reference
import bosca.bible.providers.service.BibleSourceService
import bosca.bible.providers.model.BibleSourceKind
import bosca.bible.providers.model.BibleSource
import bosca.cache.ServiceCache
import bosca.cache.serializers.StringKeySerializer
import bosca.content.metadata.jobs.LimitBibleChaptersJob
import bosca.content.metadata.jobs.LimitBibleAudioChaptersJob
import bosca.content.metadata.jobs.DeleteBibleAudioJob
import bosca.content.metadata.jobs.enqueue
import bosca.content.metadata.jobs.enqueueLater
import bosca.content.metadata.model.Bible
import bosca.content.metadata.model.BibleBook
import bosca.content.metadata.model.BibleChapter
import bosca.content.metadata.model.BibleChapterAudio
import bosca.content.metadata.model.BibleInput
import bosca.content.metadata.model.toJson
import bosca.content.metadata.repository.BibleBookRepository
import bosca.content.metadata.repository.BibleChapterRepository
import bosca.content.metadata.repository.BibleLanguageRepository
import bosca.content.metadata.repository.BibleRepository
import bosca.db.transaction
import bosca.db.connection
import bosca.lock.DistributedLockFactory
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.server.BoscaApplication
import bosca.service.annotation.ServiceImplementation
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.ObjectPath
import bosca.storage.service.ObjectNotFoundException
import bosca.storage.service.StringObjectPath
import bosca.storage.service.StorageDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.InputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.days
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

internal fun getBibleAudioPath(id: UUID): ObjectPath = StringObjectPath("bible/audio/$id")

private val audioDeletionDelay = 5.minutes

/** API.Bible terms limit retained text per edition and retained files per recording. */
private const val API_BIBLE = "API_BIBLE"
private const val API_BIBLE_VERSE_LIMIT = 499
private const val API_BIBLE_RECORDING_LIMIT = 50

@ServiceImplementation
class BibleServiceImpl(
    private val bibleRepository: BibleRepository,
    private val bibleBookRepository: BibleBookRepository,
    private val bibleChapterRepository: BibleChapterRepository,
    private val bibleLanguages: BibleLanguageRepository,
    private val sources: BibleSourceService,
    private val locks: DistributedLockFactory,
    private val storage: ObjectStorageService,
    private val json: Json,
    application: BoscaApplication,
) : BibleService {

    private val semaphore = Semaphore(5)
    private val urlPrefix by lazy { application.environment.config.property("content.storage.urlPrefix").getString().trimEnd('/') }

    private val bibleCache = ServiceCache("bible", BibleCacheKeySerializer) {
        bibleRepository.getById(it.id, it.version ?: 1, true) ?:
        bibleRepository.getById(it.id, it.version ?: 1, false)
    }

    private val bibleVariantCache = ServiceCache("bible:variant", BibleCacheKeySerializer) {
        bibleRepository.getByIdAndVariant(it.id, it.version ?: 1, it.variant ?: error("variant not found"))
    }

    private val bibleVariantsCache = ServiceCache<BibleCacheKeyId, List<Bible>>("bible:variants", BibleCacheKeySerializer) {
        bibleRepository.getVariants(it.id, it.version ?: 1)
    }

    private val bibleLanguagesCache = ServiceCache("bible:languages", BibleCacheKeySerializer) {
        bibleLanguages.getById(it.id, it.version ?: 1, it.variant ?: error("variant not found"))
    }

    private val bibleBookCache = ServiceCache("bible:books", BibleCacheKeySerializer) {
        bibleBookRepository.getByIdAndVariant(it.id, it.version ?: 1, it.variant ?: error("variant not found"))
    }

    private val bibleChaptersCache = ServiceCache("bible:chapters", BibleCacheKeySerializer) {
        bibleChapterRepository.getAllByIdAndVariantAndUsfm(it.id, it.version ?: 1, it.variant ?: error("variant not found"), it.usfm ?: error("usfm not found"))
    }

    private val bibleChapterCache = ServiceCache("bible:chapter", BibleCacheKeySerializer) {
        semaphore.withPermit {
            bibleChapterRepository.getByIdAndVariantAndUsfm(it.id, it.version ?: 1, it.variant ?: error("variant not found"), it.usfm ?: error("usfm not found"))
        }
    }

    private val audioChapterCache = ServiceCache("bible:audio:chapter", StringKeySerializer) { key ->
        val sourceId = UUID.parse(key.substringBefore(':'))
        val source = sources.getById(sourceId) ?: return@ServiceCache null
        val chapter = bibleChapterRepository.getAudioChapter(source.metadataId, source.metadataVersion, source.variant, key.substringAfter(':'))
            ?: return@ServiceCache null
        chapter.audio?.singleOrNull { it.sourceId == sourceId }
    }

    suspend fun removeFromCache(id: UUID, version: Int) {
        val id = BibleCacheKeyId(id, version)
        bibleCache.remove(id)
        bibleVariantCache.remove(id, keyPrefix = true)
        bibleVariantsCache.remove(id)
        bibleLanguagesCache.remove(id, keyPrefix = true)
        bibleBookCache.remove(id, keyPrefix = true)
        bibleChaptersCache.remove(id, keyPrefix = true)
        bibleChapterCache.remove(id, keyPrefix = true)
    }

    override suspend fun getBibles(): List<Bible> {
        return bibleRepository.getAll()
    }

    override suspend fun getVariants(id: UUID, version: Int): List<Bible> =
        bibleVariantsCache.get(BibleCacheKeyId(id, version)) ?: emptyList()

    override suspend fun getBible(id: UUID, version: Int, variant: String?) = if (variant.isNullOrBlank()) {
        bibleCache.get(BibleCacheKeyId(id, version))
    } else {
        bibleVariantCache.get(BibleCacheKeyId(id, version, variant))
    }

    override suspend fun setVariantEnabled(id: UUID, version: Int, variant: String, enabled: Boolean): Bible = transaction {
        val existing = bibleRepository.getByIdAndVariantIncludingDisabled(id, version, variant)
            ?: throw NoSuchElementException("Bible variant not found: $variant")
        require(enabled || !existing.defaultVariant) { "The default Bible variant cannot be disabled" }
        if (existing.enabled == enabled) return@transaction existing
        val updated = bibleRepository.setEnabled(id, version, variant, enabled)
            ?: throw NoSuchElementException("Bible variant not found: $variant")
        removeFromCache(id, version)
        // Lookups cache misses while disabled, so both directions invalidate recordings.
        for (source in sources.getAudio(id, version, variant)) {
            audioChapterCache.remove("${source.id}:", keyPrefix = true)
        }
        updated
    }

    override suspend fun setDefaultVariant(id: UUID, version: Int, variant: String): Bible = transaction {
        val existing = bibleRepository.getByIdAndVariantIncludingDisabled(id, version, variant)
            ?: throw NoSuchElementException("Bible variant not found: $variant")
        require(existing.enabled) { "A disabled Bible variant cannot be the default" }
        if (existing.defaultVariant) return@transaction existing
        bibleRepository.clearDefault(id, version)
        val updated = bibleRepository.setDefault(id, version, variant)
            ?: throw NoSuchElementException("Enabled Bible variant not found: $variant")
        removeFromCache(id, version)
        updated
    }

    override suspend fun getLanguages(bible: Bible) = bibleLanguagesCache.get(BibleCacheKeyId(bible.metadataId, bible.version, bible.variant)) ?: emptyList()

    override suspend fun getBooks(bible: Bible) = bibleBookCache.get(BibleCacheKeyId(bible.metadataId, bible.version, bible.variant)) ?: emptyList()

    override suspend fun getChapters(book: BibleBook) = bibleChaptersCache.get(BibleCacheKeyId(book.metadataId, book.version, book.variant, book.usfm)) ?: emptyList()

    override suspend fun getChapter(book: BibleBook, usfm: String) =
        getChapter(BibleCacheKeyId(book.metadataId, book.version, book.variant, usfm))

    override suspend fun getChapter(bible: Bible, reference: Reference): BibleChapter {
        return getChapter(BibleCacheKeyId(bible.metadataId, bible.version, bible.variant, reference.chapterUsfm))
    }

    override suspend fun getChapterContent(book: BibleBook, usfm: String, use: BibleContentUse): BibleChapterContent =
        getChapterContent(BibleCacheKeyId(book.metadataId, book.version, book.variant, usfm), use)

    override suspend fun getChapterContent(bible: Bible, reference: Reference, use: BibleContentUse): BibleChapterContent =
        getChapterContent(BibleCacheKeyId(bible.metadataId, bible.version, bible.variant, reference.chapterUsfm), use)

    private suspend fun getChapter(key: BibleCacheKeyId): BibleChapter {
        val chapter = bibleChapterCache.get(key) ?: error("chapter not found")
        if (chapter.sourceId == null) return chapter
        // Provider text is read through getChapterContent, which checks its use and expiry.
        return chapter.copy(components = null, attribution = null, sourceVersion = null, expires = null)
    }

    private fun BibleChapter.cacheKey() = BibleCacheKeyId(metadataId, version, variant, usfm)

    private suspend fun getChapterContent(key: BibleCacheKeyId, use: BibleContentUse): BibleChapterContent {
        val chapter = bibleChapterCache.get(key) ?: error("chapter not found")
        val sourceId = chapter.sourceId ?: return chapter.getContent()
        val source = sources.getForUse(sourceId, use)
        if (chapter.sourceVersion == source.version && chapter.expires?.isAfter(OffsetDateTime.now()) == true) {
            return chapter.getContent()
        }
        check(!connection().inTransaction) { "Provider chapter requests must run outside a database transaction" }
        val lock = locks.create("bible:chapter:${source.id}:${chapter.usfm}")
        return lock.withLock(ttlMillis = 60_000, waitTimeoutMillis = 30_000) {
            bibleChapterRepository.getContent(source.id, chapter.usfm, OffsetDateTime.now())?.let {
                bibleChapterCache.put(it.cacheKey(), it)
                return@withLock it.getContent()
            }
            val created = OffsetDateTime.now()
            val response = sources.getChapter(source, chapter.usfm)
            check(response.content.reference.usfm == chapter.usfm) { "Provider chapter does not match the requested reference" }
            val count = checkNotNull(response.verseCount) { "Provider chapter verse count is required" }
            check(count >= 0) { "Provider chapter verse count is invalid" }
            val fetchedChapter = chapter.copy(
                components = response.content[response.content.reference]?.toJson(),
                attribution = response.attribution ?: source.attribution,
                sourceVersion = source.version, verseCount = count, fumsToken = response.fumsToken,
                created = created, expires = created.plusDays(14),
            )
            check(lock.renew(60_000)) { "Provider chapter fetch lock expired" }
            transaction {
                if (bibleChapterRepository.putContent(fetchedChapter) > 0) {
                    bibleChapterCache.put(fetchedChapter.cacheKey(), fetchedChapter)
                    if (source.provider == API_BIBLE) LimitBibleChaptersJob(source.provider, source.providerBibleId).enqueue()
                }
            }
            fetchedChapter.getContent()
        } ?: error("Timed out waiting for the provider chapter")
    }

    override suspend fun limitChapterContent(provider: String, providerBibleId: String): Int {
        val lock = locks.create("bible:chapter:limit:$provider:$providerBibleId")
        return lock.withLock(ttlMillis = 60_000, waitTimeoutMillis = 30_000) {
            transaction {
                var remaining = API_BIBLE_VERSE_LIMIT
                var removed = 0
                for (chapter in bibleChapterRepository.getByEdition(provider, providerBibleId, OffsetDateTime.now())) {
                    val count = checkNotNull(chapter.verseCount)
                    if (count <= remaining) remaining -= count
                    else {
                        removed += bibleChapterRepository.removeContent(checkNotNull(chapter.sourceId), chapter.usfm)
                        bibleChapterCache.remove(chapter.cacheKey())
                    }
                }
                check(lock.renew(60_000)) { "Bible chapter cleanup lock expired" }
                removed
            }
        } ?: error("Timed out waiting for Bible chapter cleanup")
    }

    override suspend fun setChapterSource(id: UUID, version: Int, variant: String, sourceId: UUID) {
        bibleChapterRepository.setSource(id, version, variant, sourceId)
        removeFromCache(id, version)
    }

    override suspend fun clearChapterContent(sourceId: UUID): Int = transaction {
        val removed = invalidate(bibleChapterRepository.clearContent(sourceId))
        removed + removeAudioResponses(bibleChapterRepository.getAudioBySource(sourceId)
            .flatMap { it.audio.orEmpty() }.filter { it.sourceId == sourceId })
    }

    override suspend fun removeExpiredChapterContent(): Int = transaction {
        val now = OffsetDateTime.now()
        invalidate(bibleChapterRepository.removeExpired(now)) + removeAudioResponses(
            bibleChapterRepository.getRetainedAudio().flatMap { it.audio.orEmpty() }.filter { !it.expires.isAfter(now) })
    }

    private suspend fun invalidate(chapters: List<BibleChapter>): Int {
        for (chapter in chapters) bibleChapterCache.remove(chapter.cacheKey())
        return chapters.size
    }

    override suspend fun getFumsToken(id: UUID, version: Int, variant: String, usfm: String): String? =
        bibleChapterRepository.getFumsToken(id, version, variant, usfm, OffsetDateTime.now())

    override suspend fun getAudio(bible: Bible): List<BibleAudio> =
        sources.getAudio(bible.metadataId, bible.version, bible.variant).map {
            BibleAudio(it.id, it.name, it.nameLocal, it.abbreviation, it.attribution)
        }

    override suspend fun getAudioChapter(bible: Bible, audioId: UUID, usfm: String): BibleAudioChapter {
        val source = sources.getForUse(audioId, BibleContentUse.PLAYBACK)
        val response = getAudioChapterResponse(bible, source, usfm)
        val variant = URLEncoder.encode(bible.variant, StandardCharsets.UTF_8)
        val url = "$urlPrefix/api/v1/content/metadata/bible/audio?id=${bible.metadataId}&version=${bible.version}" +
            "&variant=$variant&audioId=$audioId&usfm=${URLEncoder.encode(usfm, StandardCharsets.UTF_8)}&recordingId=${response.id}"
        return BibleAudioChapter(BibleAudio(source.id, source.name, source.nameLocal, source.abbreviation, source.attribution),
            usfm, url, response.content, response.attribution)
    }

    override suspend fun getAudioPlayback(bible: Bible, audioId: UUID, usfm: String, recordingId: UUID?): BibleAudioPlayback {
        val response = getAudioChapterResponse(bible, sources.getForUse(audioId, BibleContentUse.PLAYBACK), usfm, recordingId)
        return object : BibleAudioPlayback {
            override val content = response.content
            override suspend fun openStream(range: LongRange?): InputStream = getAudioChapterStream(response, range)
        }
    }

    private suspend fun getAudioChapterStream(response: BibleChapterAudio, range: LongRange?): InputStream {
        if (range != null) require(range.first >= 0 && range.last >= range.first && range.last < response.content.contentLength) {
            "Invalid audio byte range"
        }
        val path = getBibleAudioPath(response.id)
        return if (range == null) storage.getInputStream(path) else storage.getInputStreamRange(path, range)
    }

    private suspend fun getAudioChapterResponse(bible: Bible, source: BibleSource, usfm: String, recordingId: UUID? = null): BibleChapterAudio {
        check(bible.enabled) { "Bible variant is disabled" }
        require(Reference(usfm).chapterUsfm == usfm) { "A chapter reference is required" }
        val audioId = source.id
        require(source.kind == BibleSourceKind.AUDIO && source.metadataId == bible.metadataId
            && source.metadataVersion == bible.version && source.variant == bible.variant) { "Recording does not belong to the Bible variant" }
        val key = "$audioId:$usfm"
        fun BibleChapterAudio.isCurrent() = sourceVersion == source.version && expires.isAfter(OffsetDateTime.now())
        val current = audioChapterCache.get(key)
        if (recordingId != null) {
            return current?.takeIf { it.id == recordingId && it.isCurrent() }
                ?: throw ObjectNotFoundException(getBibleAudioPath(recordingId))
        }
        current?.takeIf { it.isCurrent() }?.let { return it }
        check(!connection().inTransaction) { "Provider audio requests must run outside a database transaction" }
        val lock = locks.create("bible:audio:$key")
        return lock.withLock(ttlMillis = 300_000, waitTimeoutMillis = 30_000) {
            val chapter = bibleChapterRepository.getAudioChapter(bible.metadataId, bible.version, bible.variant, usfm)
                ?: error("chapter not found")
            val previous = chapter.audio?.singleOrNull { it.sourceId == audioId }
            previous?.takeIf { it.isCurrent() }?.let { audioChapterCache.put(key, it); return@withLock it }
            val id = UUID.random()
            var temporaryFile: Path? = null
            val created = OffsetDateTime.now()
            var objectCreated = false
            var retained = false
            var failure: Throwable? = null
            try {
                val file = withContext(StorageDispatcher) {
                    Files.createTempFile("bible-audio-", ".tmp").also { temporaryFile = it }
                }
                val response = withContext(StorageDispatcher) {
                    Files.newOutputStream(file).use { sources.downloadAudioChapter(source, usfm, it) }
                }
                val length = withContext(StorageDispatcher) { Files.size(file) }
                check(length > 0 && length == response.content.contentLength) { "Provider audio length does not match the downloaded file" }
                val fetched = BibleChapterAudio(id, source.id, source.version, usfm, response.content,
                    response.attribution ?: source.attribution, response.fumsToken, created, created.plusDays(14))
                objectCreated = true
                withContext(StorageDispatcher) {
                    Files.newInputStream(file).use {
                        check(storage.setInputStream(getBibleAudioPath(id), it, length) == length) { "Stored audio length does not match the downloaded file" }
                    }
                }
                check(lock.renew(300_000)) { "Provider audio fetch lock expired" }
                val audio = json.encodeToJsonElement(ListSerializer(BibleChapterAudio.serializer()), listOf(fetched))
                retained = transaction {
                    check(bibleChapterRepository.updateAudioContent(bible.metadataId, bible.version, bible.variant, usfm, source.id, source.version, audio) == 1) {
                        "Recording could not be saved for playback"
                    }
                    audioChapterCache.put(key, fetched)
                    previous?.let { DeleteBibleAudioJob(it.id).enqueueLater(audioDeletionDelay) }
                    DeleteBibleAudioJob(id).enqueueLater(14.days + audioDeletionDelay)
                    if (source.provider == API_BIBLE) LimitBibleAudioChaptersJob(source.provider, source.providerBibleId).enqueue()
                    true
                }
                fetched
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                try {
                    withContext(NonCancellable + StorageDispatcher) {
                        try {
                            if (objectCreated && !retained) {
                                audioChapterCache.remove(key)
                                DeleteBibleAudioJob(id).enqueueLater(audioDeletionDelay)
                            }
                        } finally { temporaryFile?.let { Files.deleteIfExists(it) } }
                    }
                } catch (cleanupFailure: Throwable) {
                    failure?.addSuppressed(cleanupFailure) ?: throw cleanupFailure
                }
            }
        } ?: error("Timed out waiting for the provider recording")
    }

    override suspend fun getAudioFumsToken(id: UUID, version: Int, variant: String, usfm: String, audioId: UUID): String? =
        bibleChapterRepository.getAudioFumsToken(id, version, variant, usfm, audioId, OffsetDateTime.now())

    override suspend fun limitAudioChapterContent(provider: String, providerBibleId: String): Int = transaction {
        val recordings = bibleChapterRepository.getAudioByEdition(provider, providerBibleId).flatMap { it.audio.orEmpty() }
            .sortedByDescending { it.created }
        val now = OffsetDateTime.now()
        removeAudioResponses(recordings.filter { !it.expires.isAfter(now) } + recordings.filter { it.expires.isAfter(now) }.drop(API_BIBLE_RECORDING_LIMIT))
    }

    override suspend fun clearAudioChapterContent(id: UUID): Int = transaction {
        removeAudioResponses(bibleChapterRepository.getAudioByBible(id, null, null).flatMap { it.audio.orEmpty() })
    }

    private suspend fun removeAudioResponses(responses: List<BibleChapterAudio>): Int {
        var removed = 0
        for (response in responses) {
            if (bibleChapterRepository.removeAudioContent(response) > 0) {
                audioChapterCache.remove("${response.sourceId}:${response.usfm}")
                DeleteBibleAudioJob(response.id).enqueueLater(audioDeletionDelay)
                removed++
            }
        }
        return removed
    }

    override suspend fun getHuman(bible: Bible, reference: Reference): String {
        val books = getBooks(bible)
        val book = books.first { it.usfm == reference.bookUsfm }
        return reference.toHuman(book.nameShort ?: book.nameLong ?: book.abbreviation)
    }

    override suspend fun getHumanLong(bible: Bible, reference: Reference): String {
        val books = getBooks(bible)
        val book = books.first { it.usfm == reference.bookUsfm }
        return reference.toHuman(book.nameLong ?: book.nameShort ?: book.abbreviation)
    }

    override suspend fun getReferences(bible: Bible, human: String): List<Reference> {
        return ReferenceParser.parse(this, bible, human)
    }

    override suspend fun deleteBible(id: UUID, version: Int) = transaction {
        removeAudioResponses(bibleChapterRepository.getAudioByBible(id, version, null).flatMap { it.audio.orEmpty() })
        for (source in sources.deleteByBible(id, version)) {
            audioChapterCache.remove("${source.id}:", keyPrefix = true)
        }
        bibleRepository.deleteById(id, version)
        removeFromCache(id, version)
    }

    override suspend fun setBible(id: UUID, version: Int, bible: BibleInput) = transaction {
        val variants = bibleRepository.getVariants(id, version)
        val existing = variants.firstOrNull { it.variant == bible.variant }
        val persistedBible = bible.toBible(id, version).copy(
            enabled = existing?.enabled ?: true,
            defaultVariant = existing?.defaultVariant == true ||
                variants.none { it.defaultVariant },
        )
        removeAudioResponses(bibleChapterRepository.getAudioByBible(id, version, bible.variant).flatMap { it.audio.orEmpty() })
        bibleRepository.deleteById(id, version, bible.variant)
        bibleRepository.add(persistedBible)
        for ((languageIndex, language) in bible.languages.withIndex()) {
            bibleLanguages.add(language.toLanguage(id, version, bible.variant, languageIndex))
        }
        for ((bookIndex, book) in bible.books.withIndex()) {
            bibleBookRepository.add(book.toBook(id, version, bible.variant, bookIndex))
            for ((chapterIndex, chapter) in book.chapters.withIndex()) {
                bibleChapterRepository.add(chapter.toChapter(id, version, bible.variant, book.reference.usfm, chapterIndex))
            }
        }
        removeFromCache(id, version)
    }
}
