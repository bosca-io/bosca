package bosca.content.metadata.service

import bosca.bible.Reference
import bosca.cache.CacheManager
import bosca.cache.RequestCache
import bosca.cache.RequestCacheSerializer
import bosca.cache.RequestCacheSerializerImpl
import bosca.cache.asCoroutineContext
import bosca.content.metadata.model.Bible
import bosca.content.metadata.model.BibleBook
import bosca.content.metadata.model.BibleChapter
import bosca.content.metadata.model.BibleInput
import bosca.content.metadata.model.BibleLanguage
import bosca.content.metadata.model.BibleLanguageInput
import bosca.content.metadata.model.BookInput
import bosca.content.metadata.model.ChapterInput
import bosca.content.metadata.model.ReferenceInput
import bosca.content.metadata.repository.BibleBookRepository
import bosca.content.metadata.repository.BibleChapterRepository
import bosca.content.metadata.repository.BibleLanguageRepository
import bosca.content.metadata.repository.BibleRepository
import bosca.db.ConnectionManager
import bosca.db.asCoroutineContext
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.events.DisabledEventManagerFilter
import bosca.events.EventManager
import bosca.events.asCoroutineContext
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.test.ContentTestInfrastructure
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import org.junit.AfterClass
import org.junit.BeforeClass
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(InternalDI::class)
class BibleServiceImplCoverageTest {

    private lateinit var serializer: RequestCacheSerializer

    private val connectionPool
        get() = infrastructure.connectionPool

    private val cacheManager
        get() = infrastructure.cacheManager

    private val testJson = Json {
        ignoreUnknownKeys = true
        serializersModule = SerializersModule {
            contextual(OffsetDateTimeSerializer())
            contextual(UUIDSerializer())
        }
    }

    private val bibleRepository = mockk<BibleRepository>(relaxed = true)
    private val bibleBookRepository = mockk<BibleBookRepository>(relaxed = true)
    private val bibleChapterRepository = mockk<BibleChapterRepository>(relaxed = true)
    private val bibleLanguages = mockk<BibleLanguageRepository>(relaxed = true)

    private lateinit var service: BibleServiceImpl

    @BeforeTest
    fun setup() = runBlocking {
        unmockkAll()
        infrastructure.reset()
        serializer = RequestCacheSerializerImpl(testJson)

        ProviderRegistry.clear()
        provides<CacheManager>(singleton = true) { cacheManager }
        provides<RequestCacheSerializer>(singleton = true) { serializer }
        provides<Json>(singleton = true) { testJson }

        service = BibleServiceImpl(
            bibleRepository,
            bibleBookRepository,
            bibleChapterRepository,
            bibleLanguages,
        )
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
        unmockkAll()
    }

    companion object {

        private val infrastructure = ContentTestInfrastructure()

        @BeforeClass
        @JvmStatic
        fun startInfrastructure() = runBlocking {
            infrastructure.start()
        }

        @AfterClass
        @JvmStatic
        fun stopInfrastructure() = runBlocking {
            infrastructure.stop()
        }
    }

    private suspend fun <T> withRequest(block: suspend () -> T): T {
        val cm = ConnectionManager(connectionPool)
        val rc = RequestCache(cacheManager, serializer)
        val em = EventManager().apply { filter = DisabledEventManagerFilter }
        return withContext(cm.asCoroutineContext() + rc.asCoroutineContext() + em.asCoroutineContext()) {
            try {
                block()
            } finally {
                withContext(NonCancellable) {
                    cm.release()
                }
            }
        }
    }

    // ── Test data builders ────────────────────────────────────────────────────

    private fun bible(
        id: UUID,
        variant: String = "default",
        defaultVariant: Boolean = true,
    ) = Bible(
        metadataId = id,
        version = 1,
        systemId = "sys",
        variant = variant,
        defaultVariant = defaultVariant,
        name = "Name",
        nameLocal = "NameLocal",
        description = "Desc",
        abbreviation = "AB",
        abbreviationLocal = "ABL",
        styles = JsonNull,
    )

    private fun book(
        id: UUID,
        variant: String = "default",
        usfm: String = "GEN",
        nameShort: String? = "Gen",
        nameLong: String? = "Genesis",
        abbreviation: String = "Ge",
    ) = BibleBook(
        metadataId = id,
        version = 1,
        variant = variant,
        usfm = usfm,
        nameShort = nameShort,
        nameLong = nameLong,
        abbreviation = abbreviation,
        sort = 0,
    )

    private fun chapter(
        id: UUID,
        variant: String = "default",
        bookUsfm: String = "GEN",
        usfm: String = "GEN.1",
    ) = BibleChapter(
        metadataId = id,
        version = 1,
        variant = variant,
        bookUsfm = bookUsfm,
        usfm = usfm,
        components = null,
        sort = 0,
    )

    private fun language(
        id: UUID,
        variant: String = "default",
    ) = BibleLanguage(
        metadataId = id,
        version = 1,
        variant = variant,
        iso = "en",
        name = "English",
        nameLocal = "English",
        script = "Latin",
        scriptCode = "Latn",
        scriptDirection = "ltr",
        sort = 0,
    )

    // ── getBibles ───────────────────────────────────────────────────────────

    @Test
    fun `getBibles returns all bibles from the repository`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = UUID.random()
        coEvery { bibleRepository.getAll() } returns listOf(bible(id))

        val result = withRequest { service.getBibles() }

        assertEquals(1, result.size)
        assertEquals(id, result.first().metadataId)
    }

    @Test
    fun `getVariants returns enabled and disabled variants in repository order`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = UUID.random()
        val variants = listOf(
            bible(id),
            bible(id, variant = "study", defaultVariant = false).copy(enabled = false),
        )
        coEvery { bibleRepository.getVariants(id, 1) } returns variants

        val results = withRequest {
            listOf(service.getVariants(id, 1), service.getVariants(id, 1))
        }

        assertEquals(listOf(variants, variants), results)
        coVerify(exactly = 1) { bibleRepository.getVariants(id, 1) }
    }

    @Test
    fun `variant mutation invalidates the cached variant list`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = UUID.random()
        val existing = bible(id, variant = "study", defaultVariant = false)
        val updated = existing.copy(enabled = false)
        coEvery { bibleRepository.getVariants(id, 1) } returnsMany listOf(
            listOf(existing),
            listOf(updated),
        )
        coEvery { bibleRepository.getByIdAndVariantIncludingDisabled(id, 1, "study") } returns existing
        coEvery { bibleRepository.setEnabled(id, 1, "study", false) } returns updated

        val results = withRequest {
            val before = service.getVariants(id, 1)
            service.setVariantEnabled(id, 1, "study", false)
            val after = service.getVariants(id, 1)
            before to after
        }

        assertEquals(listOf(existing), results.first)
        assertEquals(listOf(updated), results.second)
        coVerify(exactly = 2) { bibleRepository.getVariants(id, 1) }
    }

    // ── getBible (default variant path) ───────────────────────────────────────

    @Test
    fun `getBible with null variant resolves via default variant lookup`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = UUID.random()
        coEvery { bibleRepository.getById(id, 1, true) } returns bible(id)

        val result = withRequest { service.getBible(id, 1, null) }

        assertEquals(id, result?.metadataId)
        assertEquals("default", result?.variant)
    }

    @Test
    fun `getBible with blank variant falls back to non-default when default missing`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = UUID.random()
        // default lookup returns null → falls through to the non-default lookup
        coEvery { bibleRepository.getById(id, 1, true) } returns null
        coEvery { bibleRepository.getById(id, 1, false) } returns bible(id, defaultVariant = false)

        val result = withRequest { service.getBible(id, 1, "  ") }

        assertEquals(id, result?.metadataId)
    }

    @Test
    fun `getBible with null variant returns null when neither default nor non-default exists`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = UUID.random()
        coEvery { bibleRepository.getById(id, 1, true) } returns null
        coEvery { bibleRepository.getById(id, 1, false) } returns null

        val result = withRequest { service.getBible(id, 1, null) }

        assertNull(result)
    }

    // ── getBible (variant path) ───────────────────────────────────────────────

    @Test
    fun `getBible with non-blank variant resolves via variant cache`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = UUID.random()
        coEvery { bibleRepository.getByIdAndVariant(id, 1, "kjv") } returns bible(id, variant = "kjv")

        val result = withRequest { service.getBible(id, 1, "kjv") }

        assertEquals("kjv", result?.variant)
    }

    // ── setVariantEnabled ───────────────────────────────────────────────────────────────────────

    @Test
    fun `setVariantEnabled updates a non-default variant`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = UUID.random()
        val existing = bible(id, variant = "study", defaultVariant = false)
        val updated = existing.copy(enabled = false)
        coEvery { bibleRepository.getByIdAndVariantIncludingDisabled(id, 1, "study") } returns existing
        coEvery { bibleRepository.setEnabled(id, 1, "study", false) } returns updated

        assertEquals(updated, withRequest { service.setVariantEnabled(id, 1, "study", false) })
        coVerify { bibleRepository.setEnabled(id, 1, "study", false) }
    }

    @Test
    fun `setVariantEnabled rejects disabling the default variant`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = UUID.random()
        coEvery { bibleRepository.getByIdAndVariantIncludingDisabled(id, 1, "default") } returns bible(id)

        val error = assertFailsWith<IllegalArgumentException> {
            withRequest { service.setVariantEnabled(id, 1, "default", false) }
        }
        assertEquals("The default Bible variant cannot be disabled", error.message)
        coVerify(exactly = 0) { bibleRepository.setEnabled(any(), any(), any(), any()) }
    }

    @Test
    fun `setVariantEnabled returns unchanged variant without writing`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = UUID.random()
        val existing = bible(id, variant = "study", defaultVariant = false).copy(enabled = false)
        coEvery { bibleRepository.getByIdAndVariantIncludingDisabled(id, 1, "study") } returns existing

        assertEquals(existing, withRequest { service.setVariantEnabled(id, 1, "study", false) })
        coVerify(exactly = 0) { bibleRepository.setEnabled(any(), any(), any(), any()) }
    }

    @Test
    fun `setVariantEnabled reports a missing variant`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = UUID.random()
        coEvery { bibleRepository.getByIdAndVariantIncludingDisabled(id, 1, "missing") } returns null

        assertFailsWith<NoSuchElementException> {
            withRequest { service.setVariantEnabled(id, 1, "missing", true) }
        }
    }

    // ── setDefaultVariant ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `setDefaultVariant atomically replaces the enabled default`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = UUID.random()
        val existing = bible(id, variant = "study", defaultVariant = false)
        val updated = existing.copy(defaultVariant = true)
        coEvery { bibleRepository.getByIdAndVariantIncludingDisabled(id, 1, "study") } returns existing
        coEvery { bibleRepository.clearDefault(id, 1) } returns Unit
        coEvery { bibleRepository.setDefault(id, 1, "study") } returns updated

        assertEquals(updated, withRequest { service.setDefaultVariant(id, 1, "study") })
        coVerify { bibleRepository.clearDefault(id, 1) }
        coVerify { bibleRepository.setDefault(id, 1, "study") }
    }

    @Test
    fun `setDefaultVariant rejects a disabled variant`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = UUID.random()
        val disabled = bible(id, variant = "study", defaultVariant = false).copy(enabled = false)
        coEvery { bibleRepository.getByIdAndVariantIncludingDisabled(id, 1, "study") } returns disabled

        val error = assertFailsWith<IllegalArgumentException> {
            withRequest { service.setDefaultVariant(id, 1, "study") }
        }
        assertEquals("A disabled Bible variant cannot be the default", error.message)
        coVerify(exactly = 0) { bibleRepository.clearDefault(any(), any()) }
    }

    @Test
    fun `setDefaultVariant returns the existing default without writing`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = UUID.random()
        val existing = bible(id)
        coEvery { bibleRepository.getByIdAndVariantIncludingDisabled(id, 1, "default") } returns existing

        assertEquals(existing, withRequest { service.setDefaultVariant(id, 1, "default") })
        coVerify(exactly = 0) { bibleRepository.clearDefault(any(), any()) }
    }

    // ── getLanguages ──────────────────────────────────────────────────────────

    @Test
    fun `getLanguages returns languages when present`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = UUID.random()
        coEvery { bibleLanguages.getById(id, 1, "default") } returns listOf(language(id))

        val result = withRequest { service.getLanguages(bible(id)) }

        assertEquals(1, result.size)
        assertEquals("en", result.first().iso)
    }

    @Test
    fun `getLanguages returns empty list when the repository yields no rows`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = UUID.random()
        coEvery { bibleLanguages.getById(id, 1, "default") } returns emptyList()

        val result = withRequest { service.getLanguages(bible(id)) }

        assertTrue(result.isEmpty())
    }

    // ── getBooks ────────────────────────────────────────────────────────────

    @Test
    fun `getBooks returns books when present`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = UUID.random()
        coEvery { bibleBookRepository.getByIdAndVariant(id, 1, "default") } returns listOf(book(id))

        val result = withRequest { service.getBooks(bible(id)) }

        assertEquals(1, result.size)
        assertEquals("GEN", result.first().usfm)
    }

    @Test
    fun `getBooks returns empty list when the repository yields no rows`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = UUID.random()
        coEvery { bibleBookRepository.getByIdAndVariant(id, 1, "default") } returns emptyList()

        val result = withRequest { service.getBooks(bible(id)) }

        assertTrue(result.isEmpty())
    }

    // ── getChapters ────────────────────────────────────────────────────────────

    @Test
    fun `getChapters returns chapters when present`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = UUID.random()
        coEvery { bibleChapterRepository.getAllByIdAndVariantAndUsfm(id, 1, "default", "GEN") } returns listOf(chapter(id))

        val result = withRequest { service.getChapters(book(id)) }

        assertEquals(1, result.size)
        assertEquals("GEN.1", result.first().usfm)
    }

    @Test
    fun `getChapters returns empty list when the repository yields no rows`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = UUID.random()
        coEvery { bibleChapterRepository.getAllByIdAndVariantAndUsfm(id, 1, "default", "GEN") } returns emptyList()

        val result = withRequest { service.getChapters(book(id)) }

        assertTrue(result.isEmpty())
    }

    // ── getChapter(book, usfm) ───────────────────────────────────────────────

    @Test
    fun `getChapter by book and usfm returns the chapter when found`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = UUID.random()
        coEvery { bibleChapterRepository.getByIdAndVariantAndUsfm(id, 1, "default", "GEN.1") } returns chapter(id)

        val result = withRequest { service.getChapter(book(id), "GEN.1") }

        assertEquals("GEN.1", result.usfm)
    }

    @Test
    fun `getChapter by book and usfm errors when not found`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = UUID.random()
        coEvery { bibleChapterRepository.getByIdAndVariantAndUsfm(id, 1, "default", "GEN.99") } returns null

        val ex = assertFailsWith<IllegalStateException> {
            withRequest { service.getChapter(book(id), "GEN.99") }
        }
        assertEquals("chapter not found", ex.message)
    }

    // ── getChapter(bible, reference) ─────────────────────────────────────────

    @Test
    fun `getChapter by bible and reference returns the chapter when found`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = UUID.random()
        coEvery { bibleChapterRepository.getByIdAndVariantAndUsfm(id, 1, "default", "GEN.1") } returns chapter(id)

        val result = withRequest { service.getChapter(bible(id), Reference("GEN.1.1")) }

        assertEquals("GEN.1", result.usfm)
    }

    @Test
    fun `getChapter by bible and reference errors when not found`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = UUID.random()
        coEvery { bibleChapterRepository.getByIdAndVariantAndUsfm(id, 1, "default", "GEN.99") } returns null

        val ex = assertFailsWith<IllegalStateException> {
            withRequest { service.getChapter(bible(id), Reference("GEN.99.1")) }
        }
        assertTrue(ex.message?.startsWith("chapter not found") == true)
    }

    // ── getHuman ─────────────────────────────────────────────────────────────

    @Test
    fun `getHuman prefers nameShort`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = UUID.random()
        coEvery { bibleBookRepository.getByIdAndVariant(id, 1, "default") } returns
            listOf(book(id, nameShort = "Gen", nameLong = "Genesis"))

        val result = withRequest { service.getHuman(bible(id), Reference("GEN.1.1")) }

        assertEquals("Gen 1:1", result)
    }

    @Test
    fun `getHuman falls back to nameLong then abbreviation`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = UUID.random()
        coEvery { bibleBookRepository.getByIdAndVariant(id, 1, "default") } returns
            listOf(book(id, nameShort = null, nameLong = "Genesis", abbreviation = "Ge"))

        val resultLong = withRequest { service.getHuman(bible(id), Reference("GEN.1.1")) }
        assertEquals("Genesis 1:1", resultLong)

        val id2 = UUID.random()
        coEvery { bibleBookRepository.getByIdAndVariant(id2, 1, "default") } returns
            listOf(book(id2, nameShort = null, nameLong = null, abbreviation = "Ge"))

        val resultAbbr = withRequest { service.getHuman(bible(id2), Reference("GEN.1.1")) }
        assertEquals("Ge 1:1", resultAbbr)
    }

    // ── getHumanLong ────────────────────────────────────────────────────────

    @Test
    fun `getHumanLong prefers nameLong`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = UUID.random()
        coEvery { bibleBookRepository.getByIdAndVariant(id, 1, "default") } returns
            listOf(book(id, nameShort = "Gen", nameLong = "Genesis"))

        val result = withRequest { service.getHumanLong(bible(id), Reference("GEN.1.1")) }

        assertEquals("Genesis 1:1", result)
    }

    @Test
    fun `getHumanLong falls back to nameShort then abbreviation`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = UUID.random()
        coEvery { bibleBookRepository.getByIdAndVariant(id, 1, "default") } returns
            listOf(book(id, nameShort = "Gen", nameLong = null, abbreviation = "Ge"))

        val resultShort = withRequest { service.getHumanLong(bible(id), Reference("GEN.1.1")) }
        assertEquals("Gen 1:1", resultShort)

        val id2 = UUID.random()
        coEvery { bibleBookRepository.getByIdAndVariant(id2, 1, "default") } returns
            listOf(book(id2, nameShort = null, nameLong = null, abbreviation = "Ge"))

        val resultAbbr = withRequest { service.getHumanLong(bible(id2), Reference("GEN.1.1")) }
        assertEquals("Ge 1:1", resultAbbr)
    }

    // ── getReferences ────────────────────────────────────────────────────────

    @Test
    fun `getReferences delegates to the reference parser and resolves a citation`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = UUID.random()
        coEvery { bibleBookRepository.getByIdAndVariant(id, 1, "default") } returns
            listOf(book(id, usfm = "GEN", nameShort = "Gen", nameLong = "Genesis", abbreviation = "Ge"))
        coEvery { bibleChapterRepository.getAllByIdAndVariantAndUsfm(id, 1, "default", "GEN") } returns
            listOf(chapter(id, usfm = "GEN.1"))

        val result = withRequest { service.getReferences(bible(id), "Genesis 1:1") }

        assertEquals(1, result.size)
        assertEquals("GEN.1.1", result.first().usfm)
    }

    @Test
    fun `getReferences returns empty when the citation matches no book`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = UUID.random()
        coEvery { bibleBookRepository.getByIdAndVariant(id, 1, "default") } returns emptyList()

        val result = withRequest { service.getReferences(bible(id), "Nonexistent 1:1") }

        assertTrue(result.isEmpty())
    }

    // ── deleteBible ──────────────────────────────────────────────────────────

    @Test
    fun `deleteBible deletes from repository and clears the cache`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = UUID.random()

        withRequest { service.deleteBible(id, 1) }

        coVerify { bibleRepository.deleteById(id, 1) }
    }

    // ── setBible ─────────────────────────────────────────────────────────────

    @Test
    fun `setBible replaces the bible and writes languages, books, and chapters`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = UUID.random()
        val input = BibleInput(
            systemId = "sys",
            name = "Name",
            nameLocal = "NameLocal",
            abbreviation = "AB",
            abbreviationLocal = "ABL",
            languages = listOf(
                BibleLanguageInput(
                    iso = "en",
                    name = "English",
                    nameLocal = "English",
                    script = "Latin",
                    scriptCode = "Latn",
                    scriptDirection = "ltr",
                ),
            ),
            description = "Desc",
            books = listOf(
                BookInput(
                    abbreviation = "Ge",
                    chapters = listOf(
                        ChapterInput(
                            component = JsonObject(emptyMap()),
                            reference = ReferenceInput("GEN.1"),
                        ),
                    ),
                    nameLong = "Genesis",
                    nameShort = "Gen",
                    reference = ReferenceInput("GEN"),
                ),
            ),
            defaultVariant = true,
            styles = JsonNull,
            variant = "default",
        )
        coEvery { bibleRepository.add(any()) } answers { firstArg() }

        withRequest { service.setBible(id, 1, input) }

        coVerify { bibleRepository.deleteById(id, 1, "default") }
        coVerify { bibleRepository.add(any()) }
        coVerify { bibleLanguages.add(any()) }
        coVerify { bibleBookRepository.add(any()) }
        coVerify { bibleChapterRepository.add(any()) }
    }

    @Test
    fun `setBible with no languages, books, or chapters still replaces the bible`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = UUID.random()
        val added = slot<Bible>()
        val input = BibleInput(
            systemId = "sys",
            name = "Name",
            nameLocal = "NameLocal",
            abbreviation = "AB",
            abbreviationLocal = "ABL",
            languages = emptyList(),
            description = "Desc",
            books = emptyList(),
            defaultVariant = false,
            styles = JsonNull,
            variant = "kjv",
        )
        coEvery { bibleRepository.add(capture(added)) } answers { firstArg() }

        withRequest { service.setBible(id, 1, input) }

        coVerify { bibleRepository.deleteById(id, 1, "kjv") }
        coVerify { bibleRepository.add(any()) }
        assertEquals(true, added.captured.defaultVariant)
    }

    @Test
    fun `setBible preserves an administrators disabled non-default variant`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = UUID.random()
        val existingDefault = bible(id, variant = "reader")
        val existingStudy = bible(id, variant = "study", defaultVariant = false).copy(enabled = false)
        val added = slot<Bible>()
        coEvery { bibleRepository.getVariants(id, 1) } returns listOf(existingDefault, existingStudy)
        coEvery { bibleRepository.add(capture(added)) } answers { firstArg() }

        withRequest {
            service.setBible(
                id,
                1,
                BibleInput(
                    systemId = "sys",
                    name = "Study Edition",
                    nameLocal = "Study Edition",
                    abbreviation = "SE",
                    abbreviationLocal = "SE",
                    languages = emptyList(),
                    description = "Desc",
                    books = emptyList(),
                    defaultVariant = true,
                    styles = JsonNull,
                    variant = "study",
                ),
            )
        }

        assertEquals(false, added.captured.enabled)
        assertEquals(false, added.captured.defaultVariant)
    }

    @Test
    fun `setBible preserves an administrators selected default variant`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = UUID.random()
        val selectedDefault = bible(id, variant = "study")
        val added = slot<Bible>()
        coEvery { bibleRepository.getVariants(id, 1) } returns listOf(selectedDefault)
        coEvery { bibleRepository.add(capture(added)) } answers { firstArg() }

        withRequest {
            service.setBible(
                id,
                1,
                BibleInput(
                    systemId = "sys",
                    name = "Study Edition",
                    nameLocal = "Study Edition",
                    abbreviation = "SE",
                    abbreviationLocal = "SE",
                    languages = emptyList(),
                    description = "Desc",
                    books = emptyList(),
                    defaultVariant = false,
                    styles = JsonNull,
                    variant = "study",
                ),
            )
        }

        assertEquals(true, added.captured.enabled)
        assertEquals(true, added.captured.defaultVariant)
    }
}
