package bosca.content.metadata.service

import bosca.bible.BibleChapterContent
import bosca.bible.BibleContentUse
import bosca.bible.Reference
import bosca.bible.providers.service.BibleSourceService
import bosca.cache.ServiceCache
import bosca.content.metadata.jobs.LimitBibleChaptersJob
import bosca.content.metadata.jobs.enqueue
import bosca.content.metadata.model.Bible
import bosca.content.metadata.model.BibleBook
import bosca.content.metadata.model.BibleChapter
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
import bosca.service.annotation.ServiceImplementation
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@ServiceImplementation
class BibleServiceImpl(
    private val bibleRepository: BibleRepository,
    private val bibleBookRepository: BibleBookRepository,
    private val bibleChapterRepository: BibleChapterRepository,
    private val bibleLanguages: BibleLanguageRepository,
    private val sources: BibleSourceService,
    private val locks: DistributedLockFactory,
) : BibleService {

    private val semaphore = Semaphore(5)

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
        return BibleChapter(
            metadataId = chapter.metadataId,
            version = chapter.version,
            variant = chapter.variant,
            bookUsfm = chapter.bookUsfm,
            usfm = chapter.usfm,
            components = null,
            sort = chapter.sort,
            sourceId = chapter.sourceId,
        )
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
        val lock = locks.create("bible:chapter:${source.provider}:${source.providerBibleId}")
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
                    if (source.provider == "API_BIBLE") LimitBibleChaptersJob(source.provider, source.providerBibleId).enqueue()
                }
            }
            fetchedChapter.getContent()
        } ?: error("Timed out waiting for the provider chapter")
    }

    override suspend fun limitChapterContent(provider: String, providerBibleId: String): Int {
        val lock = locks.create("bible:chapter:$provider:$providerBibleId")
        return lock.withLock(ttlMillis = 60_000, waitTimeoutMillis = 30_000) {
            transaction {
                var remaining = 499
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

    override suspend fun clearChapterContent(sourceId: UUID): Int =
        invalidate(bibleChapterRepository.clearContent(sourceId))

    override suspend fun removeExpiredChapterContent(): Int =
        invalidate(bibleChapterRepository.removeExpired(OffsetDateTime.now()))

    private suspend fun invalidate(chapters: List<BibleChapter>): Int {
        for (chapter in chapters) bibleChapterCache.remove(chapter.cacheKey())
        return chapters.size
    }

    override suspend fun getFumsToken(id: UUID, version: Int, variant: String, usfm: String): String? =
        bibleChapterRepository.getFumsToken(id, version, variant, usfm, OffsetDateTime.now())

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

    override suspend fun deleteBible(id: UUID, version: Int) {
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
