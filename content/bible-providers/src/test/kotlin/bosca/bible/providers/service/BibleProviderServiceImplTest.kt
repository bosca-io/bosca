@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.bible.providers.service

import bosca.bible.Bible as SharedBible
import bosca.bible.BibleAttribution
import bosca.bible.BibleChapterContent
import bosca.bible.BibleContentUse
import bosca.bible.BibleIdentification
import bosca.bible.BibleLanguage
import bosca.bible.BibleMetadata
import bosca.bible.BiblePublication
import bosca.bible.BibleSystem
import bosca.bible.Book
import bosca.bible.Chapter
import bosca.bible.Name
import bosca.bible.Reference
import bosca.bible.bibleJson
import bosca.bible.components.Text
import bosca.bible.providers.configuration.BibleProvidersMigration
import bosca.bible.providers.model.AddProviderBibleInput
import bosca.bible.providers.model.AddProviderAudioInput
import bosca.bible.providers.model.BibleCatalog
import bosca.bible.providers.model.BibleChapterCache
import bosca.bible.providers.model.BibleProviderConfigurationInput
import bosca.bible.providers.model.BibleProviderResponse
import bosca.bible.providers.model.BibleProviderSettings
import bosca.bible.providers.model.BibleSourceKind
import bosca.bible.providers.model.BibleSource
import bosca.bible.providers.repository.BibleChapterCacheRepository
import bosca.bible.providers.repository.BibleSourceRepository
import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.cache.RequestCacheSerializerImpl
import bosca.cache.redis.RedisCacheManager
import bosca.cache.redis.RedisCacheScripts
import bosca.cache.withRequestCache
import bosca.configuration.service.ConfigurationService
import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionInput
import bosca.content.collection.repository.*
import bosca.content.collection.service.CollectionService
import bosca.content.collection.service.CollectionServiceImpl
import bosca.content.metadata.model.toInput
import bosca.content.metadata.repository.*
import bosca.content.metadata.service.BibleService
import bosca.content.metadata.service.BibleServiceImpl
import bosca.content.metadata.service.MetadataService
import bosca.content.metadata.service.MetadataServiceImpl
import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.connection
import bosca.db.migrations.CoreMigration
import bosca.db.migrations.FlywayMigration
import bosca.di.BibleProvidersProviderRegistrar
import bosca.di.ConfigurationProviderRegistrar
import bosca.di.ProviderRegistry
import bosca.di.provideProvider
import bosca.di.provides
import bosca.events.DisabledEventManagerFilter
import bosca.events.EventManager
import bosca.events.asCoroutineContext
import bosca.lock.DistributedLockFactory
import bosca.lock.redis.RedisDistributedLockFactory
import bosca.security.encryption.EncryptionService
import bosca.security.encryption.EncryptionServiceImpl
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.sharedqueue.jobs.JobConfigurationEnqueuer
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.configuration.JobQueueNames
import bosca.slug.repository.SlugRepositoryImpl
import bosca.slug.service.SlugService
import bosca.slug.service.SlugServiceImpl
import bosca.test.resources.SharedPostgreSQLContainer
import bosca.test.resources.SharedValkeyContainer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import org.junit.AfterClass
import org.junit.BeforeClass
import java.io.IOException
import java.sql.SQLException
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exercises registration through generated DI and real configuration, content, slug, and source persistence. */
class BibleProviderServiceImplTest {
    companion object {
        private val postgres = SharedPostgreSQLContainer().withDatabaseName("bible_registration")
        private val valkey = SharedValkeyContainer()
        private val cleanupScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        private lateinit var pool: ConnectionPool
        private lateinit var cacheManager: CacheManager
        private lateinit var locks: DistributedLockFactory

        @BeforeClass
        @JvmStatic
        fun start() = runBlocking {
            postgres.start()
            valkey.start()
            pool = ConnectionPool(ConnectionFactoryImpl(ConnectionConfig(
                postgres.jdbcUrl, postgres.username, postgres.password,
            ), key = "bible-registration-test"))
            val connections = valkey.newConnectionPool()
            locks = RedisDistributedLockFactory(connections)
            cacheManager = RedisCacheManager(connections, RedisCacheScripts(connections), cleanupScope)
            FlywayMigration(pool).migrate(listOf(CoreMigration(), BibleProvidersMigration()))
        }

        @AfterClass
        @JvmStatic
        fun stop() = runBlocking {
            cleanupScope.cancel()
            ProviderRegistry.clear()
            pool.close()
            valkey.stop()
            postgres.stop()
        }
    }

    private val json = Json { serializersModule = SerializersModule {
        contextual(UUIDSerializer())
        contextual(OffsetDateTimeSerializer())
    } }
    private val provider = mockk<BibleProvider>()
    private lateinit var sources: BibleSourceService
    private lateinit var service: BibleProviderService
    private lateinit var bibles: BibleService
    private lateinit var metadata: MetadataService
    private lateinit var collections: CollectionService
    private lateinit var slugs: SlugService
    private lateinit var parent: Collection
    private lateinit var configurationId: UUID
    private val language = BibleLanguage("eng", "English", "English", "Latin", "Latn", "LTR")
    private val remote = SharedBible(BibleMetadata(
        BibleIdentification(BibleSystem("publication"), "Provider Bible", "Local Bible", "Description", "PB", "LPB"),
        BiblePublication("edition", "Provider Bible", "Local Bible", "Description", "Local description", "PB", "LPB"),
        language,
    ), listOf(Book(Reference("JHN"), Name("JHN", "John", "Jn", "JHN"), listOf(
        Chapter(Reference("JHN.1"), Text("A body must not be persisted during registration", null)),
        Chapter(Reference("JHN.2"), null),
    ))), emptyList())
    private val attribution = BibleAttribution("Provider copyright", "Citation", "https://example.com/license")

    @BeforeTest
    fun setup(): Unit = runBlocking {
        ProviderRegistry.clear()
        val application = BoscaApplication(ApplicationConfig.load("""
            security:
              encryption:
                key: bible-registration-test-key
        """.trimIndent().byteInputStream()))
        provides<Json>(overrideExisting = true) { json }
        provides<CacheManager> { cacheManager }
        provides<DistributedLockFactory> { locks }
        provides<RequestCacheSerializer> { RequestCacheSerializerImpl(json) }
        provides<EncryptionService> { EncryptionServiceImpl(application) }
        ConfigurationProviderRegistrar().register()
        BibleProvidersProviderRegistrar().register()
        provides<BibleProvider>(name = "API_BIBLE", overrideExisting = true) { provider }
        sources = ProviderRegistry.get<BibleSourceService>()
        slugs = SlugServiceImpl(SlugRepositoryImpl())
        bibles = BibleServiceImpl(BibleRepositoryImpl(), BibleBookRepositoryImpl(), BibleChapterRepositoryImpl(), BibleLanguageRepositoryImpl(), sources)
        collections = CollectionServiceImpl(
            CollectionRepositoryImpl(), CollectionItemRepositoryImpl(), CollectionFindRepository(json),
            CollectionCategoryRepositoryImpl(), CollectionTraitRepositoryImpl(), CollectionMetadataRelationshipRepositoryImpl(),
            CollectionSupplementaryRepositoryImpl(), CollectionWorkflowPlanRepositoryImpl(), CollectionPermissionRepositoryImpl(),
            mockk(relaxed = true), mockk(relaxed = true), json, slugs, CollectionCollaborationRepositoryImpl(),
            CollectionLanguageVariantRepositoryImpl(), CollectionTemplateRepositoryImpl(), CollectionTemplateAttributeRepositoryImpl(),
            MetadataRepositoryImpl(), mockk(relaxed = true), mockk(relaxed = true),
        )
        metadata = MetadataServiceImpl(
            MetadataRepositoryImpl(), collections, mockk(relaxed = true), mockk(relaxed = true),
            MetadataPermissionRepositoryImpl(), MetadataTraitRepositoryImpl(), MetadataCategoryRepositoryImpl(),
            MetadataWorkflowPlanRepositoryImpl(), MetadataProfileRepositoryImpl(), MetadataSupplementaryRepositoryImpl(),
            MetadataRelationshipRepositoryImpl(), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), bibles,
            mockk(relaxed = true), slugs, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true),
        )
        provides<SlugService> { slugs }
        provides<BibleService> { bibles }
        provides<MetadataService> { metadata }
        provides<CollectionService> { collections }
        service = ProviderRegistry.get<BibleProviderService>()
        every { provider.key } returns "API_BIBLE"
        request {
            connection().useStatement("delete from configurations") { it.executeUpdate() }
            connection().useStatement("delete from metadata") { it.executeUpdate() }
            connection().useStatement("delete from collections where name = 'Bibles'") { it.executeUpdate() }
            cacheManager.clearAll()
            parent = collections.add(CollectionInput(name = "Bibles", slug = "bibles"))
            configurationId = service.saveConfiguration(BibleProviderConfigurationInput(
                "Provider", "API_BIBLE", true, apiKey = "server-secret",
            )).id
        }
        coEvery { provider.getBible(configurationId, "edition", BibleSourceKind.TEXT) } coAnswers {
            assertFalse(connection().inTransaction)
            BibleProviderResponse(remote, attribution)
        }
    }

    @Test
    fun `selected text Bible is unpublished content with exact variant language and references`() {
        val added = request { service.addBible(input()) }
        request {
            val stored = assertNotNull(metadata.getById(added.metadataId, added.version))
            assertEquals("Provider Bible", stored.name)
            assertEquals("eng", stored.languageTag)
            assertEquals("bosca/v-bible", stored.contentType)
            assertEquals("pending", stored.workflowStateId)
            assertFalse(stored.isPublished)
            assertNull(stored.ready)
            assertFalse(stored.public)
            assertEquals("Bible", stored.attributes?.jsonObject?.get("type")?.jsonPrimitive?.content)
            assertEquals(added.metadataId, collections.getItemsNoCache(parent.id, 0, 10).single().childMetadataId)
            assertEquals("study", added.variant)
            assertEquals("publication", added.systemId)
            assertTrue(added.defaultVariant)
            assertTrue(added.enabled)
            assertEquals(added, bibles.getBible(added.metadataId, added.version, "study"))
            assertNull(bibles.getBible(added.metadataId, added.version, "edition"))
            assertEquals("eng", bibles.getLanguages(added).single().iso)
            val book = bibles.getBooks(added).single()
            assertEquals("John", book.nameLong)
            assertEquals(listOf("JHN.1", "JHN.2"), bibles.getChapters(book).map { it.usfm })
            assertEquals(JsonNull, bibles.getChapter(book, "JHN.1").components)
            val source = assertNotNull(sources.getText(added.metadataId, added.version, added.variant))
            assertEquals(configurationId, source.configurationId)
            assertEquals("edition", source.providerBibleId)
            assertEquals(attribution, source.attribution)
            assertEquals(input().allowedUses, source.allowedUses)
            assertEquals(listOf(source), service.getSources(configurationId))
            assertEquals(0, count("bible_providers.chapters"))
            assertFalse(stored.attributes.toString().contains("server-secret"))
        }
        coVerify(exactly = 1) { provider.getBible(configurationId, "edition", BibleSourceKind.TEXT) }
        coVerify(exactly = 0) { provider.getChapter(any(), any(), any()) }
        coVerify(exactly = 0) { provider.downloadAudioChapter(any(), any(), any(), any()) }
    }

    @Test
    fun `text registration accepts explicitly empty uses and rejects playback before requesting content`() {
        request { assertFailsWith<IllegalArgumentException> {
            service.addBible(input().copy(allowedUses = setOf(BibleContentUse.PLAYBACK)))
        } }
        coVerify(exactly = 0) { provider.getBible(any(), any(), any()) }
        val added = request { service.addBible(input().copy(allowedUses = emptySet())) }
        request { assertTrue(assertNotNull(sources.getText(added.metadataId, added.version, added.variant)).allowedUses.isEmpty()) }
    }

    @Test
    fun `blank edition or variant is rejected without provider calls or content creation`() = request {
        for (invalid in listOf(input().copy(providerBibleId = " "), input().copy(variant = " "))) {
            assertFailsWith<IllegalArgumentException> { service.addBible(invalid) }
        }
        assertEquals(0, count("metadata"))
        coVerify(exactly = 0) { provider.getBible(any(), any(), any()) }
    }

    @Test
    fun `disabled or missing configuration cannot register a Bible`() = request {
        service.saveConfiguration(BibleProviderConfigurationInput("Provider", "API_BIBLE", false, configurationId))
        assertFailsWith<IllegalStateException> { service.addBible(input()) }
        assertFailsWith<IllegalArgumentException> { service.addBible(input().copy(configurationId = UUID.random())) }
        assertEquals(0, count("metadata"))
        coVerify(exactly = 0) { provider.getBible(any(), any(), any()) }
    }

    @Test
    fun `missing Bibles collection fails without creating another collection or metadata`() = request {
        slugs.delete("bibles")
        assertFailsWith<IllegalStateException> { service.addBible(input()) }
        assertEquals(1, count("collections"))
        assertEquals(0, count("metadata"))
    }

    @Test
    fun `provider failure or cancellation leaves no content or source records`() = request {
        coEvery { provider.getBible(any(), any(), any()) } throws IOException("Provider unavailable")
        assertFailsWith<IOException> { service.addBible(input()) }
        coEvery { provider.getBible(any(), any(), any()) } throws CancellationException("Cancelled registration")
        assertFailsWith<CancellationException> { service.addBible(input()) }
        assertEquals(0, count("metadata"))
        assertEquals(0, count("bible_providers.sources"))
        assertEquals(1, count("collections"))
    }

    @Test
    fun `source persistence failure rolls back metadata Bible references slug and collection item`() = request {
        val failing = mockk<BibleSourceService>()
        coEvery { failing.add(any()) } throws SQLException("Source insert failed")
        val service = BibleProviderServiceImpl(ProviderRegistry.get<bosca.configuration.service.ConfigurationService>(), failing, metadata, bibles, collections, slugs, json)
        assertFailsWith<SQLException> { service.addBible(input()) }
        assertEquals(0, count("metadata"))
        assertEquals(0, count("bibles"))
        assertEquals(0, count("bible_books"))
        assertEquals(0, count("bible_chapters"))
        assertEquals(0, count("bible_providers.sources"))
        assertEquals(1, count("slugs"))
        assertTrue(collections.getItemsNoCache(parent.id, 0, 10).isEmpty())
    }

    @Test
    fun `catalog browsing preserves provider models and uses offset pagination without adding content`() = request {
        val entry = BibleCatalog("edition", "publication", BibleSourceKind.TEXT, "Bible", "Local", "B", "LB",
            "Description", listOf(language), attribution)
        coEvery { provider.getCatalog(configurationId, BibleSourceKind.TEXT, "query", 4, 2) } returns listOf(entry)
        val result = service.getCatalog(configurationId, BibleSourceKind.TEXT, "query", 4, 2)
        assertEquals(entry.id, result.single().id)
        assertEquals("eng", result.single().languages.single().iso)
        assertEquals(attribution, result.single().attribution)
        assertEquals(0, count("metadata"))
        assertTrue(service.getSources(configurationId).isEmpty())
        coVerify(exactly = 1) { provider.getCatalog(configurationId, BibleSourceKind.TEXT, "query", 4, 2) }
    }

    @Test
    fun `listing sources includes disabled Bible variants and provider configurations`() {
        val added = request { service.addBible(input()) }
        request {
            bibles.setBible(added.metadataId, added.version, remote.toInput(false, "other"))
            bibles.setDefaultVariant(added.metadataId, added.version, "other")
            bibles.setVariantEnabled(added.metadataId, added.version, added.variant, false)
            service.saveConfiguration(BibleProviderConfigurationInput("Provider", "API_BIBLE", false, configurationId))
            assertEquals(added.metadataId, service.getSources(configurationId).single().metadataId)
            assertFailsWith<IllegalArgumentException> { service.getSources(UUID.random()) }
        }
    }

    @Test
    fun `missing source attribution prevents incomplete registration`() = request {
        coEvery { provider.getBible(any(), any(), any()) } returns BibleProviderResponse(remote)
        assertFailsWith<IllegalStateException> { service.addBible(input()) }
        assertEquals(0, count("metadata"))
        assertTrue(sources.getByConfiguration(configurationId).isEmpty())
    }

    @Test
    fun `source service uses optimistic versions and removes cached responses atomically`(): Unit = request {
        val added = service.addBible(input())
        val source = assertNotNull(sources.getText(added.metadataId, added.version, added.variant))
        cache(source)
        assertFailsWith<IllegalStateException> { sources.update(source.copy(version = 99)) }
        assertFailsWith<IllegalStateException> { service.removeSource(source.id, 99) }
        assertEquals("response-token", service.getFumsToken(added.metadataId, added.version, added.variant, "JHN.1"))
        val updated = sources.update(source.copy(name = "New description", allowedUses = emptySet()))
        assertEquals(1L, updated.version)
        assertEquals(source.allowedUses, updated.allowedUses)
        assertEquals(updated, sources.getById(source.id))
        assertEquals(0, count("bible_providers.chapters"))
        cache(updated)
        service.removeSource(updated.id, updated.version)
        assertNull(sources.getById(source.id))
        assertNull(sources.getText(added.metadataId, added.version, added.variant))
        assertEquals(0, count("bible_providers.chapters"))
        assertNotNull(bibles.getBible(added.metadataId, added.version, added.variant))
    }

    @Test
    fun `FUMS lookup returns only the unexpired response for the exact text variant`() = request {
        val added = service.addBible(input())
        val source = assertNotNull(sources.getText(added.metadataId, added.version, added.variant))
        cache(source)
        assertEquals("response-token", service.getFumsToken(added.metadataId, added.version, added.variant, "JHN.1"))
        assertNull(service.getFumsToken(added.metadataId, added.version + 1, added.variant, "JHN.1"))
        assertNull(service.getFumsToken(added.metadataId, added.version, "other", "JHN.1"))
        assertNull(service.getFumsToken(added.metadataId, added.version, added.variant, "JHN.2"))
        assertNull(service.getFumsToken(added.metadataId, added.version, added.variant, "JHN.1", UUID.random()))
        cache(source, expired = true)
        assertNull(service.getFumsToken(added.metadataId, added.version, added.variant, "JHN.1"))
        coVerify(exactly = 0) { provider.getChapter(any(), any(), any()) }
        coVerify(exactly = 0) { provider.downloadAudioChapter(any(), any(), any(), any()) }
    }

    @Test
    fun `refresh replaces reference metadata while preserving identity rights and variant availability`() = request {
        val added = service.addBible(input())
        bibles.setBible(added.metadataId, added.version, remote.toInput(false, "other"))
        bibles.setDefaultVariant(added.metadataId, added.version, "other")
        bibles.setVariantEnabled(added.metadataId, added.version, added.variant, false)
        val source = assertNotNull(sources.getText(added.metadataId, added.version, added.variant))
        cache(source)
        val refreshed = SharedBible(BibleMetadata(
            BibleIdentification(BibleSystem("publication"), "Refreshed Bible", "Local Bible", "Description", "PB", "LPB"),
            remote.metadata.publication, BibleLanguage("spa", "Spanish", "Spanish", "Latin", "Latn", "LTR"),
        ), remote.books.map { it.copy(chapters = listOf(Chapter(Reference("JHN.3"), Text("Discard body", null)))) }, remote.styles)
        coEvery { provider.getBible(configurationId, "edition", BibleSourceKind.TEXT) } coAnswers {
            assertFalse(connection().inTransaction)
            BibleProviderResponse(refreshed, attribution.copy(copyright = "Updated copyright"))
        }
        service.refresh(source.id)
        val variant = bibles.getVariants(added.metadataId, added.version).single { it.variant == added.variant }
        assertEquals("Refreshed Bible", variant.name)
        assertFalse(variant.enabled)
        assertFalse(variant.defaultVariant)
        assertEquals("spa", bibles.getLanguages(variant).single().iso)
        val book = bibles.getBooks(variant).single()
        assertEquals(listOf("JHN.3"), bibles.getChapters(book).map { it.usfm })
        assertFailsWith<IllegalStateException> { bibles.getChapter(book, "JHN.3") }
        val updated = assertNotNull(sources.getById(source.id))
        assertEquals(1L, updated.version)
        assertEquals(source.allowedUses, updated.allowedUses)
        assertEquals(source.providerBibleId, updated.providerBibleId)
        assertEquals("Updated copyright", updated.attribution.copyright)
        assertEquals(0, count("bible_providers.chapters"))
        bibles.setVariantEnabled(added.metadataId, added.version, added.variant, true)
        assertEquals(JsonNull, bibles.getChapter(book, "JHN.3").components)
    }

    @Test
    fun `a source changed during refresh cannot replace the Bible or purge its cache`() = request {
        val added = service.addBible(input())
        val source = assertNotNull(sources.getText(added.metadataId, added.version, added.variant))
        coEvery { provider.getBible(configurationId, "edition", BibleSourceKind.TEXT) } coAnswers {
            val changed = sources.update(source.copy(name = "Concurrent change"))
            cache(changed)
            BibleProviderResponse(remote, attribution)
        }
        assertFailsWith<IllegalStateException> { service.refresh(source.id) }
        assertEquals(added, bibles.getBible(added.metadataId, added.version, added.variant))
        assertEquals("Concurrent change", assertNotNull(sources.getById(source.id)).name)
        assertEquals(1, count("bible_providers.chapters"))
    }

    @Test
    fun `compatible audio attaches to exact variants without downloading content`() = request {
        val added = service.addBible(input())
        bibles.setBible(added.metadataId, added.version, remote.toInput(false, "other"))
        audioCatalog()
        assertEquals(setOf("study", "other"), service.getAudioTargets(configurationId, "recording").map { it.variant }.toSet())
        val recording = service.addAudio(added, AddProviderAudioInput(configurationId, "recording", setOf(BibleContentUse.PLAYBACK)))
        val audio = sources.getAudio(added.metadataId, added.version, added.variant).single()
        assertEquals(recording.id, audio.id)
        assertEquals(BibleSourceKind.AUDIO, audio.kind)
        assertEquals(attribution, recording.attribution)
        assertEquals(setOf(BibleContentUse.PLAYBACK), audio.allowedUses)
        assertTrue(sources.getAudio(added.metadataId, added.version, "other").isEmpty())
        assertEquals(added, bibles.getBible(added.metadataId, added.version, added.variant))
        service.refresh(audio.id)
        assertEquals(1L, assertNotNull(sources.getById(audio.id)).version)
        assertEquals(added, bibles.getBible(added.metadataId, added.version, added.variant))
        coVerify(exactly = 0) { provider.getChapter(any(), any(), any()) }
        coVerify(exactly = 0) { provider.downloadAudioChapter(any(), any(), any(), any()) }
        assertEquals(0, count("bible_providers.chapters"))
    }

    @Test
    fun `audio rejects incompatible publication language references and reviewed uses`() = request {
        val added = service.addBible(input())
        val audioInput = AddProviderAudioInput(configurationId, "recording", setOf(BibleContentUse.PLAYBACK))
        assertFailsWith<IllegalArgumentException> { service.addAudio(added, audioInput.copy(allowedUses = setOf(BibleContentUse.DISPLAY))) }
        coVerify(exactly = 0) { provider.getBible(any(), any(), BibleSourceKind.AUDIO) }
        audioCatalog(relatedSystemId = "different")
        assertTrue(service.getAudioTargets(configurationId, "recording").isEmpty())
        assertFailsWith<IllegalArgumentException> { service.addAudio(added, audioInput) }
        audioCatalog(recording = SharedBible(BibleMetadata(remote.metadata.identification, remote.metadata.publication,
            BibleLanguage("spa", "Spanish", "Spanish", "Latin", "Latn", "LTR")), remote.books, remote.styles))
        assertTrue(service.getAudioTargets(configurationId, "recording").isEmpty())
        audioCatalog(recording = SharedBible(remote.metadata,
            remote.books.map { it.copy(chapters = listOf(Chapter(Reference("JHN.99"), null))) }, remote.styles))
        assertFailsWith<IllegalArgumentException> { service.addAudio(added, audioInput) }
        assertTrue(sources.getAudio(added.metadataId, added.version, added.variant).isEmpty())
    }

    @Test
    fun `configuration removal requires explicit purge and preserves registered Bible content`() = request {
        val added = service.addBible(input())
        val source = assertNotNull(sources.getText(added.metadataId, added.version, added.variant))
        cache(source)
        assertFailsWith<IllegalStateException> { service.removeConfiguration(configurationId, false) }
        assertEquals(1, service.getConfigurations().size)
        assertEquals(source, sources.getById(source.id))
        assertEquals(1, count("bible_providers.chapters"))
        service.removeConfiguration(configurationId, true)
        assertTrue(service.getConfigurations().isEmpty())
        assertNull(sources.getById(source.id))
        assertEquals(0, count("bible_providers.chapters"))
        assertNotNull(metadata.getById(added.metadataId, added.version))
        assertEquals(added, bibles.getBible(added.metadataId, added.version, added.variant))
        val empty = service.saveConfiguration(BibleProviderConfigurationInput("Empty", "API_BIBLE", true, apiKey = "key"))
        service.removeConfiguration(empty.id, false)
        assertTrue(service.getConfigurations().isEmpty())
    }

    private fun audioCatalog(relatedSystemId: String = "publication", recording: SharedBible = remote) {
        coEvery { provider.getCatalog(configurationId, BibleSourceKind.AUDIO, null, 0, Int.MAX_VALUE) } returns listOf(
            BibleCatalog("recording", "audio-publication", BibleSourceKind.AUDIO, "Recording", "Local recording", "R", "LR",
                "Description", listOf(language), attribution, relatedSystemId),
        )
        coEvery { provider.getBible(configurationId, "recording", BibleSourceKind.AUDIO) } coAnswers {
            assertFalse(connection().inTransaction)
            BibleProviderResponse(recording, attribution)
        }
    }

    private suspend fun cache(source: BibleSource, expired: Boolean = false, usfm: String = "JHN.1") {
        val created = OffsetDateTime.now().minusHours(1)
        ProviderRegistry.get<BibleChapterCacheRepository>().put(BibleChapterCache(
            source.id, "API_BIBLE", usfm, Chapter(Reference(usfm), null), attribution, 1, "response-token",
            created, if (expired) created.plusMinutes(1) else created.plusDays(1),
        ))
    }

    private fun input() = AddProviderBibleInput(configurationId, "edition", "study",
        setOf(BibleContentUse.DISPLAY, BibleContentUse.AI_CONTEXT))

    @Test
    fun `BibleService fetches and caches the exact chapter with attribution and a private response token`() = request {
        val bible = service.addBible(input())
        chapterResponse()
        val first = bibles.getChapterContent(bible, Reference("JHN.1.1"), BibleContentUse.DISPLAY).asSerializable()
        assertEquals("Chapter copyright", first.attribution?.copyright)
        assertEquals(Reference("JHN.1"), first.chapter.reference)
        val source = assertNotNull(sources.getText(bible.metadataId, bible.version, bible.variant))
        val cache = assertNotNull(ProviderRegistry.get<BibleChapterCacheRepository>().get(source.id, "JHN.1", OffsetDateTime.now()))
        assertEquals(cache.created.plusDays(14), cache.expires)
        assertEquals("chapter-token", service.getFumsToken(bible.metadataId, bible.version, bible.variant, "JHN.1"))
        val second = bibles.getChapterContent(bibles.getBooks(bible).single(), "JHN.1", BibleContentUse.AI_CONTEXT).asSerializable()
        assertEquals(bibleJson.encodeToJsonElement(BibleChapterContent.serializer(), first),
            bibleJson.encodeToJsonElement(BibleChapterContent.serializer(), second))
        assertEquals(cache.created, assertNotNull(ProviderRegistry.get<BibleChapterCacheRepository>().get(source.id, "JHN.1", OffsetDateTime.now())).created)
        assertFalse(bibleJson.encodeToString(BibleChapterContent.serializer(), second).contains("chapter-token"))
        assertEquals(JsonNull, bibles.getChapter(bibles.getBooks(bible).single(), "JHN.1").components)
        coVerify(exactly = 1) { provider.getChapter(configurationId, "edition", "JHN.1") }
    }

    @Test
    fun `external use checks apply to both new responses and cache hits`() = request {
        val bible = service.addBible(input())
        chapterResponse()
        for (use in BibleContentUse.entries.filter { it !in input().allowedUses }) {
            assertFailsWith<IllegalArgumentException> { bibles.getChapterContent(bible, Reference("JHN.1"), use) }
        }
        coVerify(exactly = 0) { provider.getChapter(any(), any(), any()) }
        bibles.getChapterContent(bible, Reference("JHN.1"), BibleContentUse.DISPLAY)
        assertFailsWith<IllegalArgumentException> { bibles.getChapterContent(bible, Reference("JHN.1"), BibleContentUse.EXPORT) }
        coVerify(exactly = 1) { provider.getChapter(any(), any(), any()) }
    }

    @Test
    fun `disabled configurations and removed sources cannot serve cached external text`() = request {
        val bible = service.addBible(input())
        chapterResponse()
        bibles.getChapterContent(bible, Reference("JHN.1"), BibleContentUse.DISPLAY)
        val source = assertNotNull(sources.getText(bible.metadataId, bible.version, bible.variant))
        service.saveConfiguration(BibleProviderConfigurationInput("Provider", "API_BIBLE", false, configurationId))
        assertFailsWith<IllegalStateException> { bibles.getChapterContent(bible, Reference("JHN.1"), BibleContentUse.DISPLAY) }
        service.removeSource(source.id, source.version)
        assertFailsWith<IllegalStateException> { bibles.getChapterContent(bible, Reference("JHN.1"), BibleContentUse.DISPLAY) }
        assertEquals(0, count("bible_providers.chapters"))
        coVerify(exactly = 1) { provider.getChapter(any(), any(), any()) }
    }

    @Test
    fun `disabling a configuration in another request prevents an in-flight chapter from being cached`() = request {
        val bible = service.addBible(input())
        coEvery { provider.getChapter(any(), any(), any()) } coAnswers {
            request { service.saveConfiguration(BibleProviderConfigurationInput("Provider", "API_BIBLE", false, configurationId)) }
            response("JHN.1")
        }

        assertFailsWith<IllegalStateException> { bibles.getChapterContent(bible, Reference("JHN.1"), BibleContentUse.DISPLAY) }
        assertEquals(0, count("bible_providers.chapters"))
        assertNull(service.getFumsToken(bible.metadataId, bible.version, bible.variant, "JHN.1"))
    }

    @Test
    fun `disabling a configuration in another request prevents further reads with previously cached settings`() = request {
        val bible = service.addBible(input())
        chapterResponse()
        bibles.getChapterContent(bible, Reference("JHN.1"), BibleContentUse.DISPLAY)
        request { service.saveConfiguration(BibleProviderConfigurationInput("Provider", "API_BIBLE", false, configurationId)) }

        assertFailsWith<IllegalStateException> { bibles.getChapterContent(bible, Reference("JHN.1"), BibleContentUse.DISPLAY) }
        assertFailsWith<IllegalStateException> { bibles.getChapterContent(bible, Reference("JHN.2"), BibleContentUse.DISPLAY) }
        coVerify(exactly = 1) { provider.getChapter(any(), any(), any()) }
        assertEquals(1, count("bible_providers.chapters"))
    }

    @Test
    fun `renaming with previously cached settings preserves a concurrent key rotation and its responses`() = request {
        val bible = service.addBible(input())
        val configurations = ProviderRegistry.get<ConfigurationService>()
        assertNotNull(configurations.getValue(configurationId))
        chapterResponse()
        val rotatedSource = request {
            service.saveConfiguration(BibleProviderConfigurationInput("Provider", "API_BIBLE", true, configurationId, "rotated-key"))
            bibles.getChapterContent(bible, Reference("JHN.1"), BibleContentUse.DISPLAY)
            assertNotNull(sources.getText(bible.metadataId, bible.version, bible.variant))
        }

        service.saveConfiguration(BibleProviderConfigurationInput("Renamed", "API_BIBLE", true, configurationId))

        request {
            val settings = json.decodeFromJsonElement(BibleProviderSettings.serializer(), assertNotNull(configurations.getValue(configurationId)))
            assertEquals("rotated-key", settings.apiKey)
            assertEquals("Renamed", settings.name)
            assertEquals(rotatedSource.version, assertNotNull(sources.getById(rotatedSource.id)).version)
            assertEquals("chapter-token", service.getFumsToken(bible.metadataId, bible.version, bible.variant, "JHN.1"))
        }
    }

    @Test
    fun `disabled exact variants and missing references fail before fetching chapters`() = request {
        val bible = service.addBible(input())
        bibles.setBible(bible.metadataId, bible.version, remote.toInput(false, "other"))
        bibles.setDefaultVariant(bible.metadataId, bible.version, "other")
        val book = bibles.getBooks(bible).single()
        assertEquals("JHN.1", bibles.getChapter(book, "JHN.1").usfm)
        bibles.setVariantEnabled(bible.metadataId, bible.version, bible.variant, false)
        assertFailsWith<IllegalStateException> { bibles.getChapter(book, "JHN.1") }
        assertFailsWith<IllegalStateException> { bibles.getChapterContent(book, "JHN.1", BibleContentUse.DISPLAY) }
        assertFailsWith<IllegalStateException> { bibles.getChapterContent(bible, Reference("JHN.1"), BibleContentUse.DISPLAY) }
        bibles.setVariantEnabled(bible.metadataId, bible.version, bible.variant, true)
        assertEquals("JHN.1", bibles.getChapter(book, "JHN.1").usfm)
        assertFailsWith<IllegalStateException> { bibles.getChapterContent(bible, Reference("JHN.99"), BibleContentUse.DISPLAY) }
        coVerify(exactly = 0) { provider.getChapter(any(), any(), any()) }
    }

    @Test
    fun `expired responses are replaced and absent response attribution falls back to the source`() = request {
        val bible = service.addBible(input())
        val source = assertNotNull(sources.getText(bible.metadataId, bible.version, bible.variant))
        cache(source, expired = true)
        chapterResponse(attribution = null)
        val content = bibles.getChapterContent(bible, Reference("JHN.1"), BibleContentUse.DISPLAY)
        assertEquals(attribution, content.attribution)
        assertEquals("chapter-token", service.getFumsToken(bible.metadataId, bible.version, bible.variant, "JHN.1"))
        assertEquals(1, count("bible_providers.chapters"))
        coVerify(exactly = 1) { provider.getChapter(any(), any(), any()) }
    }

    @Test
    fun `generated maintenance job deletes expired responses and tokens while their configuration is disabled`(): Unit = request {
        val bible = service.addBible(input())
        val source = assertNotNull(sources.getText(bible.metadataId, bible.version, bible.variant))
        cache(source, expired = true)
        cache(source, usfm = "JHN.2")
        service.saveConfiguration(BibleProviderConfigurationInput("Provider", "API_BIBLE", false, configurationId))
        provides<JobQueue>(name = JobQueueNames.commonJobQueue) { mockk(relaxed = true) }
        val enqueuer = provideProvider<JobConfigurationEnqueuer>("delete-expired-bible-chapters")
        assertTrue(enqueuer.exists)
        assertEquals(JobQueueNames.commonJobQueue, enqueuer.get().queueName)
        val job = enqueuer.get().prepare(JsonObject(emptyMap()))
        job.newExecutor().execute()
        assertEquals(0, sources.removeExpired())
        assertEquals(1, count("bible_providers.chapters"))
        assertNull(service.getFumsToken(bible.metadataId, bible.version, bible.variant, "JHN.1"))
        assertEquals("response-token", service.getFumsToken(bible.metadataId, bible.version, bible.variant, "JHN.2"))
        assertNotNull(sources.getById(source.id))
        coVerify(exactly = 0) { provider.getChapter(any(), any(), any()) }
    }

    @Test
    fun `chapter failures and cancellation never persist a response or token`() = request {
        val bible = service.addBible(input())
        coEvery { provider.getChapter(any(), any(), any()) } throws IOException("Unavailable")
        assertFailsWith<IOException> { bibles.getChapterContent(bible, Reference("JHN.1"), BibleContentUse.DISPLAY) }
        coEvery { provider.getChapter(any(), any(), any()) } throws CancellationException("Cancelled chapter")
        assertFailsWith<CancellationException> { bibles.getChapterContent(bible, Reference("JHN.1"), BibleContentUse.DISPLAY) }
        assertEquals(0, count("bible_providers.chapters"))
        assertNull(service.getFumsToken(bible.metadataId, bible.version, bible.variant, "JHN.1"))
    }

    @Test
    fun `a source changed during chapter fetching cannot persist stale content`() = request {
        val bible = service.addBible(input())
        val source = assertNotNull(sources.getText(bible.metadataId, bible.version, bible.variant))
        coEvery { provider.getChapter(any(), any(), any()) } coAnswers {
            sources.update(source.copy(name = "Changed"))
            response("JHN.1")
        }
        assertFailsWith<IllegalStateException> { bibles.getChapterContent(bible, Reference("JHN.1"), BibleContentUse.DISPLAY) }
        assertEquals(0, count("bible_providers.chapters"))
    }

    @Test
    fun `a fetch that loses its distributed lock cannot persist content`() = request {
        val bible = service.addBible(input())
        val lock = locks.create("bible-test-lost-lock:${UUID.random()}")
        val factory = mockk<DistributedLockFactory>()
        coEvery { factory.create(any()) } returns lock
        val sourceService = BibleSourceServiceImpl(
            ProviderRegistry.get<BibleSourceRepository>(), ProviderRegistry.get<BibleChapterCacheRepository>(),
            ProviderRegistry.get<ConfigurationService>(), factory, json,
        )
        coEvery { provider.getChapter(any(), any(), any()) } coAnswers {
            assertTrue(lock.release())
            response("JHN.1")
        }
        assertFailsWith<IllegalStateException> {
            sourceService.getChapterContent(bible.metadataId, bible.version, bible.variant, "JHN.1", BibleContentUse.DISPLAY)
        }
        assertEquals(0, count("bible_providers.chapters"))
    }

    @Test
    fun `mismatched chapters and missing or invalid verse counts cannot be cached`() = request {
        val bible = service.addBible(input())
        for (response in listOf(response("JHN.2"), response("JHN.1").copy(verseCount = null), response("JHN.1", -1))) {
            coEvery { provider.getChapter(any(), any(), any()) } returns response
            assertFailsWith<IllegalStateException> { bibles.getChapterContent(bible, Reference("JHN.1"), BibleContentUse.DISPLAY) }
            assertEquals(0, count("bible_providers.chapters"))
        }
    }

    @Test
    fun `credential changes purge responses and prevent an in-flight response from being persisted`() = request {
        val bible = service.addBible(input())
        chapterResponse()
        bibles.getChapterContent(bible, Reference("JHN.1"), BibleContentUse.DISPLAY)
        val source = assertNotNull(sources.getText(bible.metadataId, bible.version, bible.variant))
        service.saveConfiguration(BibleProviderConfigurationInput("Provider", "API_BIBLE", true, configurationId, "new-key"))
        assertEquals(0, count("bible_providers.chapters"))
        assertEquals(source.version + 1, assertNotNull(sources.getById(source.id)).version)
        coEvery { provider.getChapter(any(), any(), any()) } coAnswers {
            service.saveConfiguration(BibleProviderConfigurationInput("Provider", "API_BIBLE", true, configurationId, "another-key"))
            response("JHN.1")
        }
        assertFailsWith<IllegalStateException> { bibles.getChapterContent(bible, Reference("JHN.1"), BibleContentUse.DISPLAY) }
        assertEquals(0, count("bible_providers.chapters"))
    }

    @Test
    fun `text cache bounds apply across configurations for the same upstream edition`() = request {
        val bible = service.addBible(input())
        bibles.setBible(bible.metadataId, bible.version, remote.toInput(false, "other"))
        val otherConfiguration = service.saveConfiguration(BibleProviderConfigurationInput("Other", "API_BIBLE", true, apiKey = "other-key"))
        val source = assertNotNull(sources.getText(bible.metadataId, bible.version, bible.variant))
        sources.add(source.copy(id = UUID.random(), configurationId = otherConfiguration.id, variant = "other"))
        coEvery { provider.getChapter(any(), "edition", any()) } coAnswers { response(thirdArg(), verseCount = 250) }
        bibles.getChapterContent(bible, Reference("JHN.1"), BibleContentUse.DISPLAY)
        val other = assertNotNull(bibles.getBible(bible.metadataId, bible.version, "other"))
        bibles.getChapterContent(other, Reference("JHN.2"), BibleContentUse.DISPLAY)
        assertNull(service.getFumsToken(bible.metadataId, bible.version, bible.variant, "JHN.1"))
        assertEquals("chapter-token", service.getFumsToken(bible.metadataId, bible.version, "other", "JHN.2"))
        assertEquals(1, count("bible_providers.chapters"))
        assertEquals(listOf("JHN.1", "JHN.2"), bibles.getChapters(bibles.getBooks(bible).single()).map { it.usfm })
    }

    @Test
    fun `text cache retains 499 verses and keeps separate upstream editions independent`() = request {
        val bible = service.addBible(input())
        coEvery { provider.getChapter(configurationId, "edition", "JHN.1") } returns response("JHN.1", 250)
        coEvery { provider.getChapter(configurationId, "edition", "JHN.2") } returns response("JHN.2", 249)
        bibles.getChapterContent(bible, Reference("JHN.1"), BibleContentUse.DISPLAY)
        bibles.getChapterContent(bible, Reference("JHN.2"), BibleContentUse.DISPLAY)
        assertEquals(2, count("bible_providers.chapters"))
        coEvery { provider.getBible(configurationId, "other-edition", BibleSourceKind.TEXT) } returns BibleProviderResponse(remote, attribution)
        val other = service.addBible(input().copy(providerBibleId = "other-edition"))
        coEvery { provider.getChapter(configurationId, "other-edition", "JHN.1") } returns response("JHN.1", 499)
        bibles.getChapterContent(other, Reference("JHN.1"), BibleContentUse.DISPLAY)
        assertEquals(3, count("bible_providers.chapters"))
        assertEquals("chapter-token", service.getFumsToken(bible.metadataId, bible.version, bible.variant, "JHN.1"))
        assertEquals("chapter-token", service.getFumsToken(bible.metadataId, bible.version, bible.variant, "JHN.2"))
        assertEquals("chapter-token", service.getFumsToken(other.metadataId, other.version, other.variant, "JHN.1"))
    }

    @Test
    fun `an oversized response cannot be cached and provider work cannot run in a database transaction`() = request {
        val bible = service.addBible(input())
        chapterResponse(verseCount = 500)
        assertFailsWith<IllegalStateException> { bibles.getChapterContent(bible, Reference("JHN.1"), BibleContentUse.DISPLAY) }
        assertEquals(0, count("bible_providers.chapters"))
        bosca.db.transaction {
            assertFailsWith<IllegalStateException> { bibles.getChapterContent(bible, Reference("JHN.2"), BibleContentUse.DISPLAY) }
        }
        coVerify(exactly = 1) { provider.getChapter(any(), any(), any()) }
    }

    @Test
    fun `concurrent requests share one fetch and response token`() = runBlocking {
        val bible = request { service.addBible(input()) }
        coEvery { provider.getChapter(any(), any(), any()) } coAnswers {
            assertFalse(connection().inTransaction)
            delay(200)
            response("JHN.1")
        }
        val first = async(Dispatchers.IO) { request { bibles.getChapterContent(bible, Reference("JHN.1"), BibleContentUse.DISPLAY).asSerializable() } }
        val second = async(Dispatchers.IO) { request { bibles.getChapterContent(bible, Reference("JHN.1"), BibleContentUse.DISPLAY).asSerializable() } }
        assertEquals(bibleJson.encodeToJsonElement(BibleChapterContent.serializer(), first.await()),
            bibleJson.encodeToJsonElement(BibleChapterContent.serializer(), second.await()))
        coVerify(exactly = 1) { provider.getChapter(any(), any(), any()) }
    }

    private fun chapterResponse(verseCount: Int = 2, attribution: BibleAttribution? = BibleAttribution("Chapter copyright")) {
        coEvery { provider.getChapter(configurationId, "edition", "JHN.1") } coAnswers {
            assertFalse(connection().inTransaction)
            response("JHN.1", verseCount, attribution)
        }
    }

    private fun response(usfm: String, verseCount: Int = 2, attribution: BibleAttribution? = BibleAttribution("Chapter copyright")) =
        BibleProviderResponse(Chapter(Reference(usfm), Text("Provider chapter", null)), attribution, verseCount, "chapter-token")

    private suspend fun count(table: String): Int = connection().useStatement("select count(*) from $table") {
        it.executeQuery().use { result -> assertTrue(result.next()); result.getInt(1) }
    }

    private fun <T> request(block: suspend () -> T): T = runBlocking {
        val manager = pool.connection()
        val events = EventManager().apply { filter = DisabledEventManagerFilter }
        try {
            withContext(manager.asCoroutineContext() + events.asCoroutineContext()) { withRequestCache { block() } }
        } finally {
            withContext(NonCancellable) { manager.release() }
        }
    }
}
