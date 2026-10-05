package bosca.content.metadata.service

import bosca.cache.CacheManager
import bosca.content.metadata.repository.BibleBookRepository
import bosca.content.metadata.repository.BibleChapterRepository
import bosca.content.metadata.repository.BibleLanguageRepository
import bosca.content.metadata.repository.BibleRepository
import bosca.di.provides
import io.mockk.mockk
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BibleServiceImplTest {

    private val bibleRepository = mockk<BibleRepository>(relaxed = true)
    private val bibleBookRepository = mockk<BibleBookRepository>(relaxed = true)
    private val bibleChapterRepository = mockk<BibleChapterRepository>(relaxed = true)
    private val bibleLanguageRepository = mockk<BibleLanguageRepository>(relaxed = true)
    private val cacheManager = mockk<CacheManager>(relaxed = true)

    private lateinit var service: BibleServiceImpl

    @BeforeTest
    fun setup() {
        provides<CacheManager> { cacheManager }
        service = BibleServiceImpl(
            bibleRepository,
            bibleBookRepository,
            bibleChapterRepository,
            bibleLanguageRepository
        )
    }

    @Test
    fun `BibleServiceImpl can be instantiated`() {
        assertNotNull(service)
    }

    @Test
    fun `BibleServiceImpl implements BibleService`() {
        assertTrue(service is BibleService)
    }
}
