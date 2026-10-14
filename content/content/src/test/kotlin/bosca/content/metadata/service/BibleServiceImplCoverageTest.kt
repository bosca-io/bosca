package bosca.content.metadata.service

import bosca.bible.BibleContentUse
import bosca.bible.BibleAttribution
import bosca.bible.BibleChapterContent
import bosca.bible.Chapter
import bosca.bible.Reference
import bosca.bible.components.ComponentContainer
import bosca.bible.components.ContainerType
import bosca.bible.components.IComponent
import bosca.bible.components.Text
import bosca.bible.components.VerseEnd
import bosca.bible.components.VerseStart
import bosca.bible.providers.service.BibleSourceService
import bosca.bible.providers.model.BibleSource
import bosca.bible.providers.model.BibleSourceKind
import bosca.serialization.OffsetDateTime
import bosca.lock.DistributedLock
import bosca.lock.DistributedLockFactory
import bosca.bible.providers.model.BibleProviderResponse
import kotlinx.coroutines.CancellationException
import java.io.IOException
import bosca.cache.CacheManager
import bosca.cache.RequestCache
import bosca.cache.RequestCacheSerializer
import bosca.cache.RequestCacheSerializerImpl
import bosca.cache.asCoroutineContext
import bosca.content.metadata.model.Bible
import bosca.content.metadata.model.BibleBook
import bosca.content.metadata.model.BibleChapter
import bosca.content.metadata.model.BibleFumsToken
import bosca.content.metadata.model.BibleInput
import bosca.content.metadata.model.BibleLanguage
import bosca.content.metadata.model.BibleLanguageInput
import bosca.content.metadata.model.BookInput
import bosca.content.metadata.model.ChapterInput
import bosca.content.metadata.model.ReferenceInput
import bosca.content.metadata.model.toJson
import bosca.content.metadata.repository.BibleBookRepository
import bosca.content.metadata.repository.BibleChapterRepository
import bosca.content.metadata.repository.BibleFumsTokenRepository
import bosca.content.metadata.repository.BibleLanguageRepository
import bosca.content.metadata.repository.BibleRepository
import bosca.content.configuration.JobQueueNames
import bosca.content.metadata.jobs.LimitBibleChaptersExecutor
import bosca.content.metadata.jobs.LimitBibleChaptersJob
import bosca.content.metadata.jobs.prepare
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
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import bosca.test.ContentTestInfrastructure
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
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
import kotlin.test.assertNotNull
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
    private val fumsTokens = mockk<BibleFumsTokenRepository>(relaxed = true)
    private val bibleLanguages = mockk<BibleLanguageRepository>(relaxed = true)

    private val sources = mockk<BibleSourceService>(relaxed = true)
    private val locks = mockk<DistributedLockFactory>()
    private val jobQueue = mockk<JobQueue>(relaxed = true)
    private val audioStorage = mockk<bosca.storage.service.ObjectStorageService>()
    private val application = mockk<BoscaApplication>()
    private lateinit var service: BibleServiceImpl

    @BeforeTest
    fun setup(): Unit = runBlocking {
        unmockkAll()
        infrastructure.reset()
        serializer = RequestCacheSerializerImpl(testJson)
        every { application.environment } returns BoscaApplication.Environment(ApplicationConfig.load("""
            content:
              storage:
                urlPrefix: https://media.bosca.test/platform/
        """.trimIndent().byteInputStream()))

        ProviderRegistry.clear()
        provides<CacheManager>(singleton = true) { cacheManager }
        provides<RequestCacheSerializer>(singleton = true) { serializer }
        provides<Json>(singleton = true) { testJson }
        provides<JobQueue>(name = JobQueueNames.contentJobQueue) { jobQueue }

        service = BibleServiceImpl(
            bibleRepository,
            bibleBookRepository,
            bibleChapterRepository,
            fumsTokens,
            bibleLanguages,
            sources,
            locks,
            audioStorage,
            testJson,
            application,
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

    private fun recordingId(url: String): UUID = UUID.parse(java.net.URI(url).rawQuery.split('&')
        .single { it.startsWith("recordingId=") }.substringAfter('='))

    private fun audioFixture(responseAttribution: bosca.bible.BibleAttribution? = bosca.bible.BibleAttribution("Recording copyright"), variant: String = "default"):
        Pair<Bible, bosca.bible.providers.model.BibleSource> {
        val bible = bible(UUID.random(), variant = variant)
        val source = bosca.bible.providers.model.BibleSource(UUID.random(), UUID.random(), "API_BIBLE", "recording",
            bible.metadataId, bible.version, bible.variant, bosca.bible.providers.model.BibleSourceKind.AUDIO,
            "Recording", "Local recording", "REC", bosca.bible.BibleAttribution("Source copyright"), setOf(bosca.bible.BibleContentUse.PLAYBACK))
        val bytes = "recording bytes".toByteArray()
        coEvery { sources.getForUse(source.id, bosca.bible.BibleContentUse.PLAYBACK) } returns source
        coEvery { sources.getById(source.id) } returns source
        coEvery { sources.getAudio(bible.metadataId, bible.version, bible.variant) } returns listOf(source)
        coEvery { bibleChapterRepository.getAudioChapter(bible.metadataId, bible.version, bible.variant, "GEN.1") } returns chapter(bible.metadataId, variant = variant)
        coEvery { sources.downloadAudioChapter(source, "GEN.1", any()) } coAnswers {
            thirdArg<java.io.OutputStream>().write(bytes)
            bosca.bible.providers.model.BibleProviderResponse(bosca.bible.BibleAudioContent("audio/mpeg", bytes.size.toLong()), responseAttribution, fumsToken = "audio-token")
        }
        val lock = mockk<bosca.lock.DistributedLock>()
        coEvery { locks.create("bible:audio:${source.id}:GEN.1") } returns lock
        coEvery { lock.withLock<Any?>(any(), any(), any(), any()) } coAnswers { arg<suspend () -> Any?>(3).invoke() }
        coEvery { lock.renew(any()) } returns true
        coEvery { bibleChapterRepository.updateAudioContent(any(), any(), any(), any(), any(), any(), any()) } returns 1
        coEvery { audioStorage.setInputStream(any(), any(), any()) } coAnswers { secondArg<java.io.InputStream>().readBytes().size.toLong() }
        coEvery { audioStorage.getInputStream(any()) } returns java.io.ByteArrayInputStream(bytes)
        coEvery { audioStorage.getInputStreamRange(any(), 2L..5L) } returns java.io.ByteArrayInputStream(bytes.copyOfRange(2, 6))
        return bible to source
    }

    @Test
    fun `audio cancellation during temporary file creation removes the file`() = runTest {
        val (bible, source) = audioFixture()
        val file = java.nio.file.Files.createTempFile("bible-audio-test-", ".tmp")
        try {
            withRequest {
                val caller = kotlinx.coroutines.Job(kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job])
                io.mockk.mockkStatic(java.nio.file.Files::class)
                try {
                    io.mockk.every { java.nio.file.Files.createTempFile("bible-audio-", ".tmp") } answers {
                        caller.cancel(CancellationException("disconnected during file creation"))
                        file
                    }
                    io.mockk.every { java.nio.file.Files.deleteIfExists(any()) } answers {
                        firstArg<java.nio.file.Path>().toFile().delete()
                    }
                    assertFailsWith<CancellationException> {
                        withContext(caller) { service.getAudioPlayback(bible, source.id, "GEN.1") }
                    }
                    io.mockk.verify(exactly = 1) { java.nio.file.Files.deleteIfExists(file) }
                } finally { io.mockk.unmockkStatic(java.nio.file.Files::class) }
                kotlin.test.assertFalse(file.toFile().exists(), "The allocated temporary file must be removed")
                io.mockk.mockkStatic(java.nio.file.Files::class)
                try {
                    io.mockk.every { java.nio.file.Files.createTempFile("bible-audio-", ".tmp") } throws IOException("cannot create temporary file")
                    val error = assertFailsWith<IOException> { service.getAudioPlayback(bible, source.id, "GEN.1") }
                    assertEquals("cannot create temporary file", error.message)
                    io.mockk.verify(exactly = 0) { java.nio.file.Files.deleteIfExists(any()) }
                } finally { io.mockk.unmockkStatic(java.nio.file.Files::class) }
                coVerify(exactly = 0) { audioStorage.setInputStream(any(), any(), any()) }
            }
        } finally { java.nio.file.Files.deleteIfExists(file) }
    }

    @Test
    fun `audio stream is closed when cancellation occurs during handoff`() = runTest {
        val (bible, source) = audioFixture()
        withRequest {
            val response = service.getAudioPlayback(bible, source.id, "GEN.1")
            val caller = kotlinx.coroutines.Job(kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job])
            var closed = false
            val input = object : java.io.ByteArrayInputStream("recording bytes".toByteArray()) {
                override fun close() { closed = true; super.close() }
            }
            coEvery { audioStorage.getInputStream(any()) } coAnswers {
                var opened: java.io.InputStream? = null
                val result = try {
                    withContext(bosca.storage.service.StorageDispatcher) { input.also { opened = it } }
                } catch (error: CancellationException) {
                    opened?.close()
                    throw error
                }
                caller.cancel(CancellationException("disconnected during handoff"))
                result
            }
            assertFailsWith<CancellationException> {
                withContext(caller) { response.openStream(null).use { it.readBytes() } }
            }
            assertTrue(closed, "The opened audio stream must be closed")
        }
    }

    @Test
    fun `audio playback fails when its recording cannot be saved`() = runTest {
        val (bible, source) = audioFixture()
        coEvery { bibleChapterRepository.updateAudioContent(any(), any(), any(), any(), any(), any(), any()) } returns 0
        withRequest {
            assertFailsWith<IllegalStateException> { service.getAudioPlayback(bible, source.id, "GEN.1") }
        }
        coVerify(exactly = 1) { sources.downloadAudioChapter(any(), any(), any()) }
        coVerify(exactly = 1) { bibleChapterRepository.updateAudioContent(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { jobQueue.enqueueLater(any(), kotlin.time.Duration.parse("5m")) }
        coVerify(exactly = 0) { audioStorage.getInputStream(any()) }
    }

    @Test
    fun `audio descriptions are lazy and authorized playback reuses the response and supports ranges`() = runTest {
        val (bible, source) = audioFixture()
        withRequest {
            assertEquals(source.id, service.getAudio(bible).single().id)
            coVerify(exactly = 0) { sources.downloadAudioChapter(any(), any(), any()) }
            val description = service.getAudioChapter(bible, source.id, "GEN.1")
            assertEquals("Recording copyright", description.attribution.copyright)
            assertEquals("https://media.bosca.test/platform/api/v1/content/metadata/bible/audio" +
                "?id=${bible.metadataId}&version=${bible.version}&variant=default&audioId=${source.id}&usfm=GEN.1" +
                "&recordingId=${recordingId(description.url)}", description.url)
            val response = service.getAudioPlayback(bible, source.id, "GEN.1")
            assertEquals("recording bytes", response.openStream(null).use { it.readBytes().decodeToString() })
            assertEquals("cord", response.openStream(2L..5L).use { it.readBytes().decodeToString() })
            assertFailsWith<IllegalArgumentException> { response.openStream(-1L..2L) }
            assertFailsWith<IllegalArgumentException> { response.openStream(0L..100L) }
        }
        coVerify(exactly = 1) { sources.downloadAudioChapter(any(), any(), any()) }
    }

    @Test
    fun `audio playback does not load chapter text`() = runTest {
        val (bible, source) = audioFixture()
        withRequest { service.getAudioChapter(bible, source.id, "GEN.1") }
        coVerify(exactly = 0) { bibleChapterRepository.getByIdAndVariantAndUsfm(any(), any(), any(), any()) }
        coVerify(exactly = 0) { sources.getChapter(any(), any()) }
        coVerify(exactly = 2) { bibleChapterRepository.getAudioChapter(bible.metadataId, bible.version, bible.variant, "GEN.1") }
    }

    @Test
    fun `missing audio chapters are rejected before downloading`() = runTest {
        val (bible, source) = audioFixture()
        coEvery { bibleChapterRepository.getAudioChapter(bible.metadataId, bible.version, bible.variant, "GEN.1") } returns null
        withRequest {
            assertFailsWith<IllegalStateException> { service.getAudioPlayback(bible, source.id, "GEN.1") }
        }
        coVerify(exactly = 0) { sources.downloadAudioChapter(any(), any(), any()) }
    }

    @Test
    fun `pinned playback rejects unknown recordings and chapter mismatches without downloading a replacement`() = runTest {
        val (bible, source) = audioFixture()
        coEvery { bibleChapterRepository.getAudioChapter(bible.metadataId, bible.version, bible.variant, "GEN.2") } returns
            chapter(bible.metadataId).copy(usfm = "GEN.2")
        withRequest {
            val description = service.getAudioChapter(bible, source.id, "GEN.1")
            val id = recordingId(description.url)
            val playback = service.getAudioPlayback(bible, source.id, "GEN.1", id)
            assertEquals(description.content, playback.content)
            assertEquals("recording bytes", playback.openStream().use { it.readBytes().decodeToString() })
            assertFailsWith<bosca.storage.service.ObjectNotFoundException> { service.getAudioPlayback(bible, source.id, "GEN.1", UUID.random()) }
            assertFailsWith<bosca.storage.service.ObjectNotFoundException> { service.getAudioPlayback(bible, source.id, "GEN.2", id) }
        }
        coVerify(exactly = 1) { sources.downloadAudioChapter(any(), any(), any()) }
    }

    @Test
    fun `pinned playback rejects expired recordings before opening storage`() = runTest {
        val (bible, source) = audioFixture()
        val now = OffsetDateTime.now()
        val expired = bosca.content.metadata.model.BibleChapterAudio(UUID.random(), source.id, source.version, "GEN.1",
            bosca.bible.BibleAudioContent("audio/mpeg", 10), source.attribution, now.minusDays(14), now.minusSeconds(1))
        coEvery { bibleChapterRepository.getAudioChapter(bible.metadataId, bible.version, bible.variant, "GEN.1") } returns
            chapter(bible.metadataId).copy(audio = listOf(expired))
        withRequest {
            assertFailsWith<bosca.storage.service.ObjectNotFoundException> { service.getAudioPlayback(bible, source.id, "GEN.1", expired.id) }
        }
        coVerify(exactly = 0) { sources.downloadAudioChapter(any(), any(), any()); audioStorage.getInputStream(any()) }
    }

    @Test
    fun `pinned playback rejects recordings from an older source version`() = runTest {
        val (bible, source) = audioFixture()
        val now = OffsetDateTime.now()
        val old = bosca.content.metadata.model.BibleChapterAudio(UUID.random(), source.id, source.version - 1, "GEN.1",
            bosca.bible.BibleAudioContent("audio/mpeg", 10), source.attribution, now, now.plusDays(14))
        coEvery { bibleChapterRepository.getAudioChapter(bible.metadataId, bible.version, bible.variant, "GEN.1") } returns
            chapter(bible.metadataId).copy(audio = listOf(old))
        withRequest {
            assertFailsWith<bosca.storage.service.ObjectNotFoundException> { service.getAudioPlayback(bible, source.id, "GEN.1", old.id) }
        }
        coVerify(exactly = 0) { sources.downloadAudioChapter(any(), any(), any()); audioStorage.getInputStream(any()) }
    }

    @Test
    fun `audio playback URLs encode the variant in the query`() = runTest {
        val (bible, source) = audioFixture(variant = "reader & 中文/+")
        withRequest {
            val description = service.getAudioChapter(bible, source.id, "GEN.1")
            assertEquals("https://media.bosca.test/platform/api/v1/content/metadata/bible/audio" +
                "?id=${bible.metadataId}&version=${bible.version}&variant=reader+%26+%E4%B8%AD%E6%96%87%2F%2B" +
                "&audioId=${source.id}&usfm=GEN.1&recordingId=${recordingId(description.url)}", description.url)
        }
    }

    @Test
    fun `audio rejects foreign variants references and transactions and propagates access failures`() = runTest {
        val (bible, source) = audioFixture()
        withRequest {
            assertFailsWith<IllegalArgumentException> { service.getAudioChapter(bible.copy(variant = "other"), source.id, "GEN.1") }
            assertFailsWith<IllegalArgumentException> { service.getAudioChapter(bible.copy(version = 2), source.id, "GEN.1") }
            assertFailsWith<IllegalArgumentException> { service.getAudioChapter(bible.copy(metadataId = UUID.random()), source.id, "GEN.1") }
            assertFailsWith<IllegalArgumentException> { service.getAudioChapter(bible, source.id, "GEN.1.1") }
            bosca.db.transaction {
                assertFailsWith<IllegalStateException> { service.getAudioChapter(bible, source.id, "GEN.1") }
            }
            coEvery { sources.getForUse(source.id, bosca.bible.BibleContentUse.PLAYBACK) } throws IllegalStateException("disabled")
            assertFailsWith<IllegalStateException> { service.getAudioChapter(bible, source.id, "GEN.1") }
        }
        coVerify(exactly = 0) { sources.downloadAudioChapter(any(), any(), any()) }
    }

    @Test
    fun `audio replaces expired data and uses source attribution when the response omits it`() = runTest {
        val (bible, source) = audioFixture(null)
        val now = bosca.serialization.OffsetDateTime.now()
        val old = bosca.content.metadata.model.BibleChapterAudio(UUID.random(), source.id, source.version, "GEN.1",
            bosca.bible.BibleAudioContent("audio/mpeg", 15), source.attribution, now.minusDays(15), now.minusDays(1))
        coEvery { bibleChapterRepository.getAudioChapter(bible.metadataId, bible.version, bible.variant, "GEN.1") } returns chapter(bible.metadataId).copy(audio = listOf(old))
        withRequest { assertEquals(source.attribution, service.getAudioChapter(bible, source.id, "GEN.1").attribution) }
        coVerify(exactly = 1) { sources.downloadAudioChapter(any(), any(), any()) }
        coVerify(exactly = 1) { jobQueue.enqueue(any()) }
        coVerify(exactly = 1) { jobQueue.enqueueLater(any(), kotlin.time.Duration.parse("5m")) }
    }

    @Test
    fun `audio descriptions fail a skipped write before publishing a recording URL`() = runTest {
        val (bible, source) = audioFixture()
        coEvery { bibleChapterRepository.updateAudioContent(any(), any(), any(), any(), any(), any(), any()) } returns 0
        withRequest {
            assertFailsWith<IllegalStateException> { service.getAudioChapter(bible, source.id, "GEN.1") }
        }
        coVerify(exactly = 1) { sources.downloadAudioChapter(any(), any(), any()) }
        coVerify(exactly = 1) { jobQueue.enqueueLater(any(), kotlin.time.Duration.parse("5m")) }
    }

    @Test
    fun `audio download cancellation and incomplete uploads do not persist responses`() = runTest {
        val (bible, source) = audioFixture()
        coEvery { sources.downloadAudioChapter(source, "GEN.1", any()) } throws kotlinx.coroutines.CancellationException("cancelled")
        withRequest { assertFailsWith<kotlinx.coroutines.CancellationException> { service.getAudioChapter(bible, source.id, "GEN.1") } }
        coVerify(exactly = 0) { bibleChapterRepository.updateAudioContent(any(), any(), any(), any(), any(), any(), any()) }
        coEvery { sources.downloadAudioChapter(source, "GEN.1", any()) } coAnswers {
            thirdArg<java.io.OutputStream>().write(byteArrayOf(1))
            bosca.bible.providers.model.BibleProviderResponse(bosca.bible.BibleAudioContent("audio/mpeg", 10), source.attribution)
        }
        withRequest { assertFailsWith<IllegalStateException> { service.getAudioChapter(bible, source.id, "GEN.1") } }
        coVerify(exactly = 0) { audioStorage.setInputStream(any(), any(), any()) }
    }

    @Test
    fun `upload cancellation remains primary when object cleanup cannot be queued`() = runTest {
        val (bible, source) = audioFixture()
        val cancellation = kotlinx.coroutines.CancellationException("upload cancelled")
        coEvery { audioStorage.setInputStream(any(), any(), any()) } throws cancellation
        coEvery { jobQueue.enqueueLater(any(), any()) } throws java.io.IOException("cleanup queue unavailable")
        withRequest {
            val error = assertFailsWith<kotlinx.coroutines.CancellationException> { service.getAudioChapter(bible, source.id, "GEN.1") }
            assertEquals("upload cancelled", error.message)
            val cleanupErrors = generateSequence(error as Throwable) { it.cause }.flatMap { it.suppressed.asSequence() }.toList()
            assertTrue(cleanupErrors.any { it is java.io.IOException && it.message == "cleanup queue unavailable" })
        }
        coVerify(exactly = 0) { bibleChapterRepository.updateAudioContent(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `metadata deletion clears audio in every version through the chapter owner`() = runTest {
        val (bible, source) = audioFixture()
        val now = bosca.serialization.OffsetDateTime.now()
        val response = bosca.content.metadata.model.BibleChapterAudio(UUID.random(), source.id, source.version, "GEN.1",
            bosca.bible.BibleAudioContent("audio/mpeg", 15), source.attribution, now, now.plusDays(14))
        coEvery { bibleChapterRepository.getAudioByBible(bible.metadataId, null, null) } returns listOf(chapter(bible.metadataId).copy(audio = listOf(response)))
        coEvery { bibleChapterRepository.removeAudioContent(response) } returns 1
        withRequest { assertEquals(1, service.clearAudioChapterContent(bible.metadataId)) }
        coVerify(exactly = 1) { jobQueue.enqueueLater(any(), kotlin.time.Duration.parse("5m")) }
    }

    @Test
    fun `retention and expiry remove exact recording responses and retain replacements`() = runTest {
        val (bible, source) = audioFixture()
        val now = bosca.serialization.OffsetDateTime.now()
        val responses = (0..51).map { index -> bosca.content.metadata.model.BibleChapterAudio(UUID.random(), source.id, source.version, "GEN.${index + 1}",
            bosca.bible.BibleAudioContent("audio/mpeg", 15), source.attribution, now.minusMinutes(index.toLong()), if (index == 0) now.minusMinutes(1) else now.plusDays(1)) }
        coEvery { bibleChapterRepository.getAudioByEdition("API_BIBLE", "recording") } returns listOf(chapter(bible.metadataId).copy(audio = responses))
        coEvery { bibleChapterRepository.removeAudioContent(any()) } returns 1
        coEvery { bibleChapterRepository.removeAudioContent(responses[0]) } returns 0
        coEvery { bibleChapterRepository.evictAudioContent(responses.last().id, responses.last().usfm, any()) } returns 1
        withRequest { assertEquals(1, service.limitAudioChapterContent("API_BIBLE", "recording")) }
        coVerify(exactly = 1) { bibleChapterRepository.evictAudioContent(responses.last().id, responses.last().usfm, any()) }
        coVerify(exactly = 0) { bibleChapterRepository.removeAudioContent(responses.last()) }
        coEvery { bibleChapterRepository.getRetainedAudio() } returns listOf(chapter(bible.metadataId).copy(audio = responses))
        coEvery { bibleChapterRepository.removeExpired(any()) } returns emptyList()
        withRequest { assertEquals(0, service.removeExpiredChapterContent()) }
        coEvery { bibleChapterRepository.getAudioBySource(source.id) } returns listOf(chapter(bible.metadataId).copy(audio = listOf(responses.last())))
        coEvery { bibleChapterRepository.clearContent(source.id) } returns emptyList()
        coEvery { bibleChapterRepository.evictAudioContent(responses.last().id, responses.last().usfm, any()) } returns 1
        withRequest { assertEquals(1, service.clearChapterContent(source.id)) }
        coVerify(exactly = 2) { bibleChapterRepository.evictAudioContent(responses.last().id, responses.last().usfm, any()) }
        coVerify(exactly = 0) { bibleChapterRepository.removeAudioContent(responses.last()) }
        coEvery { fumsTokens.getAudio(bible.metadataId, bible.version, bible.variant, "GEN.1", source.id, any()) } returns "token"
        withRequest { assertEquals("token", service.getAudioFumsToken(bible.metadataId, bible.version, bible.variant, "GEN.1", source.id)) }
    }

    @Test
    fun `recording tokens are stored separately from the retained response`() = runTest {
        val (bible, source) = audioFixture()
        val token = slot<BibleFumsToken>()
        coEvery { fumsTokens.put(capture(token)) } returns Unit
        withRequest { service.getAudioChapter(bible, source.id, "GEN.1") }
        assertEquals("audio-token", token.captured.token)
        assertEquals(source.id, token.captured.sourceId)
        assertTrue(token.captured.audio)
        assertEquals("GEN.1", token.captured.usfm)
    }

    @Test
    fun `replacing a recording keeps the previous one listed as evicted during its grace period`() = runTest {
        val (bible, source) = audioFixture()
        val now = OffsetDateTime.now()
        val previous = bosca.content.metadata.model.BibleChapterAudio(UUID.random(), source.id, source.version - 1, "GEN.1",
            bosca.bible.BibleAudioContent("audio/mpeg", 10), source.attribution, now.minusDays(1), now.plusDays(13))
        val retired = previous.copy(id = UUID.random(), evicted = now.minusMinutes(1))
        val stale = previous.copy(id = UUID.random(), evicted = now.minusMinutes(10))
        coEvery { bibleChapterRepository.getAudioChapter(bible.metadataId, bible.version, bible.variant, "GEN.1") } returns
            chapter(bible.metadataId).copy(audio = listOf(previous, retired, stale))
        val saved = slot<kotlinx.serialization.json.JsonElement>()
        coEvery { bibleChapterRepository.updateAudioContent(any(), any(), any(), any(), any(), any(), capture(saved)) } returns 1
        withRequest { service.getAudioChapter(bible, source.id, "GEN.1") }
        val audio = testJson.decodeFromJsonElement(
            kotlinx.serialization.builtins.ListSerializer(bosca.content.metadata.model.BibleChapterAudio.serializer()), saved.captured)
        assertEquals(listOf(previous.id, retired.id), audio.dropLast(1).map { it.id })
        assertNotNull(audio[0].evicted)
        assertEquals(retired.evicted, audio[1].evicted)
        assertNull(audio.last().evicted)
        coVerify(exactly = 1) { jobQueue.enqueueLater(any(), kotlin.time.Duration.parse("5m")) }
    }

    @Test
    fun `pinned playback serves replaced or evicted recordings only during the deletion grace period`() = runTest {
        val (bible, source) = audioFixture()
        val now = OffsetDateTime.now()
        val current = bosca.content.metadata.model.BibleChapterAudio(UUID.random(), source.id, source.version, "GEN.1",
            bosca.bible.BibleAudioContent("audio/mpeg", 15), source.attribution, now, now.plusDays(14))
        val replaced = current.copy(id = UUID.random(), sourceVersion = source.version - 1, evicted = now.minusMinutes(1))
        val deleted = current.copy(id = UUID.random(), evicted = now.minusMinutes(6))
        val expired = current.copy(id = UUID.random(), expires = now.minusSeconds(1), evicted = now.minusMinutes(1))
        coEvery { bibleChapterRepository.getAudioChapter(bible.metadataId, bible.version, bible.variant, "GEN.1") } returns
            chapter(bible.metadataId).copy(audio = listOf(current, replaced, deleted, expired))
        withRequest {
            val playback = service.getAudioPlayback(bible, source.id, "GEN.1", replaced.id)
            assertEquals(replaced.content, playback.content)
            assertEquals("recording bytes", playback.openStream().use { it.readBytes().decodeToString() })
            assertFailsWith<bosca.storage.service.ObjectNotFoundException> { service.getAudioPlayback(bible, source.id, "GEN.1", deleted.id) }
            assertFailsWith<bosca.storage.service.ObjectNotFoundException> { service.getAudioPlayback(bible, source.id, "GEN.1", expired.id) }
            assertEquals(current.content, service.getAudioPlayback(bible, source.id, "GEN.1", current.id).content)
        }
        coVerify(exactly = 0) { sources.downloadAudioChapter(any(), any(), any()) }
        coVerify(exactly = 1) { audioStorage.getInputStream(match { it.toString() == getBibleAudioPath(replaced.id).toString() }) }
    }

    @Test
    fun `expiry maintenance drops evicted recordings after their grace without queueing deletion again`() = runTest {
        val (bible, source) = audioFixture()
        val now = OffsetDateTime.now()
        val base = bosca.content.metadata.model.BibleChapterAudio(UUID.random(), source.id, source.version, "GEN.1",
            bosca.bible.BibleAudioContent("audio/mpeg", 15), source.attribution, now.minusDays(1), now.plusDays(13))
        val playing = base.copy(id = UUID.random(), evicted = now.minusMinutes(1))
        val finished = base.copy(id = UUID.random(), evicted = now.minusMinutes(6))
        coEvery { bibleChapterRepository.getRetainedAudio() } returns listOf(chapter(bible.metadataId).copy(audio = listOf(base, playing, finished)))
        coEvery { bibleChapterRepository.removeExpired(any()) } returns emptyList()
        coEvery { bibleChapterRepository.removeAudioContent(finished) } returns 1
        withRequest { assertEquals(1, service.removeExpiredChapterContent()) }
        coVerify(exactly = 0) { bibleChapterRepository.removeAudioContent(base); bibleChapterRepository.removeAudioContent(playing) }
        coVerify(exactly = 0) { jobQueue.enqueueLater(any(), any()) }
        coVerify(exactly = 1) { fumsTokens.removeExpired(any()) }
    }

    @Test
    fun `deleting a default variant removes its recordings and sources and promotes an enabled variant`() = runTest {
        val (bible, source) = audioFixture()
        val now = OffsetDateTime.now()
        val response = bosca.content.metadata.model.BibleChapterAudio(UUID.random(), source.id, source.version, "GEN.1",
            bosca.bible.BibleAudioContent("audio/mpeg", 15), source.attribution, now, now.plusDays(14))
        val disabled = bible.copy(variant = "disabled", defaultVariant = false, enabled = false)
        val other = bible.copy(variant = "other", defaultVariant = false)
        coEvery { bibleRepository.getByIdAndVariantIncludingDisabled(bible.metadataId, bible.version, bible.variant) } returns bible
        coEvery { bibleChapterRepository.getAudioByBible(bible.metadataId, bible.version, bible.variant) } returns
            listOf(chapter(bible.metadataId).copy(audio = listOf(response)))
        coEvery { bibleChapterRepository.removeAudioContent(response) } returns 1
        coEvery { sources.deleteByVariant(bible.metadataId, bible.version, bible.variant) } returns listOf(source)
        coEvery { bibleRepository.getVariants(bible.metadataId, bible.version) } returns listOf(disabled, other)
        coEvery { bibleRepository.setDefault(bible.metadataId, bible.version, "other") } returns other.copy(defaultVariant = true)
        val remaining = withRequest { service.deleteVariant(bible.metadataId, bible.version, bible.variant) }
        assertEquals(listOf(disabled, other.copy(defaultVariant = true)), remaining)
        coVerifyOrder {
            bibleChapterRepository.removeAudioContent(response)
            sources.deleteByVariant(bible.metadataId, bible.version, bible.variant)
            bibleRepository.deleteById(bible.metadataId, bible.version, bible.variant)
            bibleRepository.setDefault(bible.metadataId, bible.version, "other")
        }
    }

    @Test
    fun `deleting a non-default or last variant leaves the default unchanged`() = runTest {
        val id = UUID.random()
        val variant = bible(id, variant = "study", defaultVariant = false)
        coEvery { bibleRepository.getByIdAndVariantIncludingDisabled(id, 1, "study") } returns variant
        coEvery { bibleRepository.getVariants(id, 1) } returns listOf(bible(id))
        withRequest { assertEquals(listOf(bible(id)), service.deleteVariant(id, 1, "study")) }
        val last = bible(id)
        coEvery { bibleRepository.getByIdAndVariantIncludingDisabled(id, 1, "default") } returns last
        coEvery { bibleRepository.getVariants(id, 1) } returns emptyList()
        withRequest { assertEquals(emptyList(), service.deleteVariant(id, 1, "default")) }
        coVerify(exactly = 0) { bibleRepository.setDefault(any(), any(), any()) }
        coEvery { bibleRepository.getByIdAndVariantIncludingDisabled(id, 1, "missing") } returns null
        withRequest { assertFailsWith<NoSuchElementException> { service.deleteVariant(id, 1, "missing") } }
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
        coVerify(exactly = 1) { sources.getAudio(id, 1, "study") }
    }

    @Test
    fun `setVariantEnabled invalidates recordings when a variant is enabled again`() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        val id = UUID.random()
        val existing = bible(id, variant = "study", defaultVariant = false).copy(enabled = false)
        coEvery { bibleRepository.getByIdAndVariantIncludingDisabled(id, 1, "study") } returns existing
        coEvery { bibleRepository.setEnabled(id, 1, "study", true) } returns existing.copy(enabled = true)

        withRequest { service.setVariantEnabled(id, 1, "study", true) }

        coVerify(exactly = 1) { sources.getAudio(id, 1, "study") }
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
    fun `getChapterContent preserves locally imported content and exact variant`() = runTest {
        val id = UUID.random()
        val components = ComponentContainer(
            ContainerType.DIV,
            listOf(
                VerseStart(Reference("GEN.1.1")), Text("First verse.", null), VerseEnd(),
                VerseStart(Reference("GEN.1.2")), Text("Second verse.", null), VerseEnd(),
            ),
            null,
        )
        val stored = chapter(id, variant = "study").copy(version = 2, components = components.toJson())
        coEvery { bibleChapterRepository.getByIdAndVariantAndUsfm(id, 2, "study", "GEN.1") } returns stored

        val content = withRequest {
            service.getChapterContent(
                bible(id, variant = "study").copy(version = 2),
                Reference("GEN.1.2"),
                BibleContentUse.AI_CONTEXT,
            )
        }

        assertEquals(Reference("GEN.1"), content.chapter.reference)
        assertEquals("Second verse.", text(assertNotNull(content.chapter[Reference("GEN.1.2")])))
        assertEquals("First verse.", text(assertNotNull(content.chapter[Reference("GEN.1.1")])))
        assertNull(content.attribution)
        coVerify(exactly = 1) { bibleChapterRepository.getByIdAndVariantAndUsfm(id, 2, "study", "GEN.1") }
        coVerify(exactly = 0) { sources.getForUse(any(), any()) }
    }

    private fun external(provider: String = "API_BIBLE"): Pair<Bible, BibleChapter> {
        val id = UUID.random()
        val descriptor = chapter(id).copy(sourceId = UUID.random())
        val source = BibleSource(assertNotNull(descriptor.sourceId), UUID.random(), provider, "edition", id, 1, "default",
            BibleSourceKind.TEXT, "Bible", "Bible", "B", BibleAttribution("Edition copyright"), setOf(BibleContentUse.DISPLAY))
        val lock = mockk<DistributedLock>()
        coEvery { sources.getForUse(source.id, BibleContentUse.DISPLAY) } returns source
        coEvery { bibleChapterRepository.getByIdAndVariantAndUsfm(id, 1, "default", "GEN.1") } returns descriptor
        coEvery { bibleChapterRepository.getContent(source.id, "GEN.1", any()) } returns null
        coEvery { sources.getChapter(source, "GEN.1") } returns BibleProviderResponse(
            Chapter(Reference("GEN.1"), Text("Provider text", null)), BibleAttribution("Response copyright"), 2, "private-token",
        )
        // Fetches lock one chapter of one source; retention locks the upstream edition.
        coEvery { locks.create(match { it == "bible:chapter:${source.id}:GEN.1" || it == "bible:chapter:limit:$provider:edition" }) } returns lock
        coEvery { lock.withLock<Any?>(any(), any(), any(), any()) } coAnswers {
            arg<suspend () -> Any?>(3).invoke()
        }
        coEvery { lock.renew(any()) } returns true
        coEvery { bibleChapterRepository.putContent(any()) } returns 1
        coEvery { bibleChapterRepository.getByEdition(any(), any(), any()) } returns emptyList()
        return bible(id) to descriptor
    }

    @Test
    fun `provider response is stored on the existing chapter with its private token and fixed expiry`() = runTest {
        val (bible, descriptor) = external()
        val saved = slot<BibleChapter>()
        val token = slot<BibleFumsToken>()
        coEvery { bibleChapterRepository.putContent(capture(saved)) } returns 1
        coEvery { fumsTokens.put(capture(token)) } returns Unit
        val result = withRequest { service.getChapterContent(bible, Reference("GEN.1"), BibleContentUse.DISPLAY) }
        assertEquals("Provider text", text(assertNotNull(result.chapter[Reference("GEN.1")])))
        assertEquals("Response copyright", result.attribution?.copyright)
        assertEquals(descriptor.sourceId, saved.captured.sourceId)
        assertEquals(0L, saved.captured.sourceVersion)
        assertEquals(assertNotNull(saved.captured.created).plusDays(14), saved.captured.expires)
        assertEquals("private-token", token.captured.token)
        assertEquals(descriptor.sourceId, token.captured.sourceId)
        assertEquals(false, token.captured.audio)
        assertEquals(saved.captured.created, token.captured.created)
        assertEquals(saved.captured.expires, token.captured.expires)
        coEvery { bibleChapterRepository.getContent(assertNotNull(descriptor.sourceId), "GEN.1", any()) } returns saved.captured
        withRequest { service.getChapterContent(bible, Reference("GEN.1"), BibleContentUse.DISPLAY) }
        coVerify(exactly = 1) { sources.getChapter(any(), any()) }
        coVerify(exactly = 1) { bibleChapterRepository.getContent(any(), any(), any()) }
        coVerify(exactly = 1) { locks.create(any()) }
        coVerify(exactly = 1) { jobQueue.enqueue(any()) }
    }

    @Test
    fun `chapter fetches enqueue retention without querying or evicting the edition`() = runTest {
        val (bible, _) = external()
        withRequest { service.getChapterContent(bible, Reference("GEN.1"), BibleContentUse.DISPLAY) }
        coVerify(exactly = 1) { jobQueue.enqueue(any()) }
        coVerify(exactly = 0) { bibleChapterRepository.getByEdition(any(), any(), any()) }
        coVerify(exactly = 0) { bibleChapterRepository.removeContent(any(), any()) }
    }

    @Test
    fun `cached provider chapters with expired missing or stale response metadata are fetched again`() = runTest {
        for ((version, expires) in listOf(0L to OffsetDateTime.now().minusSeconds(1),
            1L to OffsetDateTime.now().plusDays(1), 0L to null)) {
            val (bible, descriptor) = external()
            coEvery { bibleChapterRepository.getByIdAndVariantAndUsfm(bible.metadataId, 1, "default", "GEN.1") } returns
                descriptor.copy(components = Text("Stale text", null).toJson(), sourceVersion = version, expires = expires)
            val result = withRequest { service.getChapterContent(bible, Reference("GEN.1"), BibleContentUse.DISPLAY) }
            assertEquals("Provider text", text(assertNotNull(result.chapter[Reference("GEN.1")])))
        }
        coVerify(exactly = 3) { sources.getChapter(any(), any()) }
    }

    @Test
    fun `warm provider chapters can be read within a transaction without fetching or locking`() = runTest {
        val (bible, _) = external()
        withRequest { service.getChapterContent(bible, Reference("GEN.1"), BibleContentUse.DISPLAY) }
        withRequest {
            bosca.db.transaction {
                val content = service.getChapterContent(bible, Reference("GEN.1"), BibleContentUse.DISPLAY)
                assertEquals("Provider text", text(assertNotNull(content.chapter[Reference("GEN.1")])))
            }
        }
        coVerify(exactly = 1) { bibleChapterRepository.getByIdAndVariantAndUsfm(any(), any(), any(), any()) }
        coVerify(exactly = 1) { bibleChapterRepository.getContent(any(), any(), any()) }
        coVerify(exactly = 1) { sources.getChapter(any(), any()) }
        coVerify(exactly = 1) { locks.create(any()) }
    }

    @Test
    fun `provider cache filled while waiting for the lock is reused without fetching`() = runTest {
        val (bible, descriptor) = external()
        coEvery { bibleChapterRepository.getContent(assertNotNull(descriptor.sourceId), "GEN.1", any()) } returns descriptor.copy(components = Text("Cached", null).toJson(), attribution = BibleAttribution("Cached copyright"))
        val result = withRequest { service.getChapterContent(bible, Reference("GEN.1"), BibleContentUse.DISPLAY) }
        assertEquals("Cached copyright", result.attribution?.copyright)
        coVerify(exactly = 0) { sources.getChapter(any(), any()) }
    }

    @Test
    fun `provider response without attribution falls back to the edition`() = runTest {
        val (bible, _) = external()
        coEvery { sources.getChapter(any(), any()) } returns BibleProviderResponse(Chapter(Reference("GEN.1"), null), verseCount = 0)
        val result = withRequest { service.getChapterContent(bible, Reference("GEN.1"), BibleContentUse.DISPLAY) }
        assertEquals("Edition copyright", result.attribution?.copyright)
        assertNull(result.chapter[Reference("GEN.1")])
    }

    @Test
    fun `invalid provider responses fail before storing chapter data`() = runTest {
        val (bible, _) = external()
        val valid = BibleProviderResponse(Chapter(Reference("GEN.1"), Text("Text", null)), verseCount = 2)
        for (response in listOf(valid.copy(content = Chapter(Reference("GEN.2"), null)),
            valid.copy(verseCount = null), valid.copy(verseCount = -1))) {
            coEvery { sources.getChapter(any(), any()) } returns response
            assertFailsWith<IllegalStateException> {
                withRequest { service.getChapterContent(bible, Reference("GEN.1"), BibleContentUse.DISPLAY) }
            }
        }
        coVerify(exactly = 0) { bibleChapterRepository.putContent(any()) }
    }

    @Test
    fun `provider failure and cancellation propagate without persisting content`() = runTest {
        val (bible, _) = external()
        coEvery { sources.getChapter(any(), any()) } throws IOException("Unavailable")
        assertFailsWith<IOException> { withRequest { service.getChapterContent(bible, Reference("GEN.1"), BibleContentUse.DISPLAY) } }
        coEvery { sources.getChapter(any(), any()) } throws CancellationException("Cancelled")
        assertFailsWith<CancellationException> { withRequest { service.getChapterContent(bible, Reference("GEN.1"), BibleContentUse.DISPLAY) } }
        coVerify(exactly = 0) { bibleChapterRepository.putContent(any()) }
    }

    @Test
    fun `provider fetch timeouts and lost locks cannot persist a response`() = runTest {
        val (bible, _) = external()
        val lock = locks.create("bible:chapter:limit:API_BIBLE:edition")
        coEvery { lock.renew(any()) } returns false
        assertFailsWith<IllegalStateException> { withRequest { service.getChapterContent(bible, Reference("GEN.1"), BibleContentUse.DISPLAY) } }
        coEvery { lock.withLock<BibleChapterContent>(any(), any(), any(), any()) } returns null
        assertFailsWith<IllegalStateException> { withRequest { service.getChapterContent(bible, Reference("GEN.1"), BibleContentUse.DISPLAY) } }
        coVerify(exactly = 0) { bibleChapterRepository.putContent(any()) }
    }

    @Test
    fun `skipped cache writes return fresh content without caching`() = runTest {
        val (bible, _) = external()
        coEvery { bibleChapterRepository.putContent(any()) } returns 0
        val first = withRequest { service.getChapterContent(bible, Reference("GEN.1"), BibleContentUse.DISPLAY) }
        assertEquals("Provider text", text(assertNotNull(first.chapter[Reference("GEN.1")])))
        coEvery { sources.getChapter(any(), any()) } returns BibleProviderResponse(
            Chapter(Reference("GEN.1"), Text("Fresh text", null)), verseCount = 2,
        )
        val second = withRequest { service.getChapterContent(bible, Reference("GEN.1"), BibleContentUse.DISPLAY) }
        assertEquals("Fresh text", text(assertNotNull(second.chapter[Reference("GEN.1")])))
        coVerify(exactly = 2) { sources.getChapter(any(), any()) }
        coVerify(exactly = 0) { bibleChapterRepository.getByEdition(any(), any(), any()) }
        coVerify(exactly = 0) { jobQueue.enqueue(any()) }
    }

    @Test
    fun `provider fetches cannot run inside a database transaction`() = runTest {
        val (bible, _) = external()
        assertFailsWith<IllegalStateException> {
            withRequest { bosca.db.transaction { service.getChapterContent(bible, Reference("GEN.1"), BibleContentUse.DISPLAY) } }
        }
        coVerify(exactly = 0) { sources.getChapter(any(), any()) }
    }

    @Test
    fun `retention evicts whole older responses when an edition exceeds 499 verses`() = runTest {
        val (bible, descriptor) = external()
        val olderSource = UUID.random()
        coEvery { bibleChapterRepository.getByEdition("API_BIBLE", "edition", any()) } returns listOf(
            descriptor.copy(verseCount = 2),
            descriptor.copy(sourceId = olderSource, verseCount = 496),
            descriptor.copy(sourceId = olderSource, usfm = "GEN.2", verseCount = 2),
        )
        coEvery { bibleChapterRepository.removeContent(olderSource, "GEN.2") } returns 1
        withRequest { service.getChapterContent(bible, Reference("GEN.1"), BibleContentUse.DISPLAY) }
        coVerify(exactly = 0) { bibleChapterRepository.getByEdition(any(), any(), any()) }
        withRequest { assertEquals(1, service.limitChapterContent("API_BIBLE", "edition")) }
        coVerify(exactly = 1) { bibleChapterRepository.removeContent(olderSource, "GEN.2") }
        coVerify(exactly = 0) { bibleChapterRepository.removeContent(olderSource, "GEN.1") }
    }

    @Test
    fun `retention executor checks the current edition even when no responses remain`() = runTest {
        external()
        withRequest {
            val job = LimitBibleChaptersJob("API_BIBLE", "edition").prepare()
            withContext(jobQueue.asCoroutineContext(job)) { LimitBibleChaptersExecutor(service).execute() }
        }
        coVerify(exactly = 1) { bibleChapterRepository.getByEdition("API_BIBLE", "edition", any()) }
        coVerify(exactly = 0) { bibleChapterRepository.removeContent(any(), any()) }
        coVerify(exactly = 0) { sources.getForUse(any(), any()) }
        coVerify(exactly = 0) { jobQueue.enqueue(any()) }
    }

    @Test
    fun `retention fails when its edition lock is unavailable or expires`() = runTest {
        external()
        val lock = locks.create("bible:chapter:limit:API_BIBLE:edition")
        coEvery { lock.withLock<Int>(any(), any(), any(), any()) } returns null
        assertFailsWith<IllegalStateException> { withRequest { service.limitChapterContent("API_BIBLE", "edition") } }
        coVerify(exactly = 0) { bibleChapterRepository.getByEdition(any(), any(), any()) }
        coEvery { lock.withLock<Any?>(any(), any(), any(), any()) } coAnswers {
            arg<suspend () -> Any?>(3).invoke()
        }
        coEvery { lock.renew(any()) } returns false
        assertFailsWith<IllegalStateException> { withRequest { service.limitChapterContent("API_BIBLE", "edition") } }
        coVerify(exactly = 1) { bibleChapterRepository.getByEdition(any(), any(), any()) }
    }

    @Test
    fun `retention preserves cancellation`() = runTest {
        external()
        coEvery { bibleChapterRepository.getByEdition(any(), any(), any()) } throws CancellationException("Cancelled cleanup")
        assertFailsWith<CancellationException> { withRequest { service.limitChapterContent("API_BIBLE", "edition") } }
    }

    @Test
    fun `other providers do not inherit API Bible verse limits`() = runTest {
        val (bible, _) = external("OTHER")
        coEvery { sources.getChapter(any(), any()) } returns BibleProviderResponse(Chapter(Reference("GEN.1"), null), verseCount = 500)
        withRequest { service.getChapterContent(bible, Reference("GEN.1"), BibleContentUse.DISPLAY) }
        coVerify(exactly = 1) { bibleChapterRepository.putContent(any()) }
        coVerify(exactly = 0) { bibleChapterRepository.getByEdition(any(), any(), any()) }
        coVerify(exactly = 0) { jobQueue.enqueue(any()) }
    }

    @Test
    fun `chapter maintenance clears retained responses and attaches sources through its repository`() = runTest {
        val id = UUID.random()
        val sourceId = UUID.random()
        coEvery { bibleChapterRepository.clearContent(sourceId) } returns listOf(chapter(id), chapter(id).copy(usfm = "GEN.2"))
        coEvery { bibleChapterRepository.removeExpired(any()) } returns listOf(chapter(id))
        coEvery { fumsTokens.getText(id, 1, "study", "GEN.1", any()) } returns "token"
        withRequest {
            service.setChapterSource(id, 1, "study", sourceId)
            assertEquals(2, service.clearChapterContent(sourceId))
            assertEquals(1, service.removeExpiredChapterContent())
            assertEquals("token", service.getFumsToken(id, 1, "study", "GEN.1"))
        }
        coVerify(exactly = 1) { fumsTokens.removeExpired(any()) }
        coVerify(exactly = 1) { bibleChapterRepository.setSource(id, 1, "study", sourceId) }
    }

    private fun text(component: IComponent): String = when (component) {
        is Text -> component.text
        is ComponentContainer -> component.components.joinToString("") { text(it) }
        else -> ""
    }

    @Test
    fun `getChapterContent by book preserves chapters without local components`() = runTest {
        val id = UUID.random()
        coEvery { bibleChapterRepository.getByIdAndVariantAndUsfm(id, 1, "default", "GEN.1") } returns chapter(id)

        val content = withRequest { service.getChapterContent(book(id), "GEN.1", BibleContentUse.DISPLAY) }

        assertEquals(Reference("GEN.1"), content.chapter.reference)
        assertNull(content.chapter[Reference("GEN.1")])
        assertNull(content.attribution)
    }

    @Test
    fun `getChapterContent reads both overloads without reloading the Bible and preserves attribution`() = runTest {
        val id = UUID.random()
        val bible = bible(id, variant = "study").copy(version = 2)
        val sourceId = UUID.random()
        val chapter = chapter(id, variant = "study").copy(version = 2, sourceId = sourceId)
        val content = BibleChapterContent(Chapter(Reference("GEN.1"), Text("External text", null)), BibleAttribution("Copyright"))
        coEvery { bibleRepository.getByIdAndVariant(id, 2, "study") } throws AssertionError("Chapter reads must not reload the Bible")
        coEvery { bibleChapterRepository.getByIdAndVariantAndUsfm(id, 2, "study", "GEN.1") } returns chapter.copy(
            components = content.chapter[content.chapter.reference]?.toJson(), attribution = content.attribution,
            sourceVersion = 0, expires = OffsetDateTime.now().plusDays(1),
        )
        val source = BibleSource(sourceId, UUID.random(), "API_BIBLE", "edition", id, 2, "study", BibleSourceKind.TEXT,
            "Bible", "Bible", "B", BibleAttribution("Copyright"), setOf(BibleContentUse.AI_CONTEXT))
        coEvery { sources.getForUse(sourceId, BibleContentUse.AI_CONTEXT) } returns source
        coEvery { bibleChapterRepository.getContent(sourceId, "GEN.1", any()) } returns chapter.copy(
            components = content.chapter[content.chapter.reference]?.toJson(), attribution = content.attribution,
            sourceVersion = 0, expires = OffsetDateTime.now().plusDays(1),
        )

        val result = withRequest { service.getChapterContent(bible, Reference("GEN.1.2"), BibleContentUse.AI_CONTEXT) }
        val bookResult = withRequest { service.getChapterContent(book(id, variant = "study").copy(version = 2), "GEN.1", BibleContentUse.AI_CONTEXT) }

        assertEquals("External text", text(assertNotNull(result.chapter[Reference("GEN.1")])))
        assertEquals("Copyright", result.attribution?.copyright)
        assertEquals(bosca.bible.bibleJson.encodeToJsonElement(BibleChapterContent.serializer(), result.asSerializable()),
            bosca.bible.bibleJson.encodeToJsonElement(BibleChapterContent.serializer(), bookResult.asSerializable()))
        coVerify(exactly = 2) { sources.getForUse(sourceId, BibleContentUse.AI_CONTEXT) }
    }

    @Test
    fun `getChapterContent rejects an unavailable chapter before requesting provider content`() = runTest {
        val id = UUID.random()
        coEvery { bibleChapterRepository.getByIdAndVariantAndUsfm(id, 1, "study", "GEN.1") } returns null

        assertFailsWith<IllegalStateException> {
            withRequest { service.getChapterContent(bible(id, variant = "study"), Reference("GEN.1"), BibleContentUse.DISPLAY) }
        }

        coVerify(exactly = 0) { sources.getForUse(any(), any()) }
    }

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

        val source = BibleSource(UUID.random(), UUID.random(), "API_BIBLE", "edition", id, 1, "default",
            BibleSourceKind.TEXT, "Bible", "Bible", "B", BibleAttribution("Copyright"), setOf(BibleContentUse.DISPLAY))
        coEvery { sources.deleteByBible(id, 1) } returns listOf(source)

        withRequest { service.deleteBible(id, 1) }

        coVerifyOrder {
            sources.deleteByBible(id, 1)
            bibleRepository.deleteById(id, 1)
        }
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
