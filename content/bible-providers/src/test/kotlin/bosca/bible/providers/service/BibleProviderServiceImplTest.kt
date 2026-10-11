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
import bosca.bible.components.ComponentContainer
import bosca.bible.components.ContainerType
import bosca.bible.components.VerseStart
import bosca.bible.components.VerseEnd
import bosca.bible.providers.configuration.BibleProvidersMigration
import bosca.bible.providers.model.AddProviderBibleInput
import bosca.bible.providers.model.AddProviderAudioInput
import bosca.bible.providers.model.BibleCatalog
import bosca.bible.providers.model.BibleProviderConfigurationInput
import bosca.bible.providers.model.BibleProviderResponse
import bosca.bible.providers.model.BibleProviderSettings
import bosca.bible.providers.model.BibleSourceKind
import bosca.bible.providers.model.BibleSource
import bosca.bible.providers.repository.BibleSourceRepository
import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.cache.RequestCacheSerializerImpl
import bosca.cache.redis.RedisCacheManager
import bosca.cache.redis.RedisCacheScripts
import bosca.cache.withRequestCache
import bosca.configuration.service.ConfigurationService
import bosca.configuration.repository.ConfigurationValueRepository
import bosca.configuration.repository.ConfigurationValueRepositoryImpl
import bosca.bible.providers.repository.BibleSourceRepositoryImpl
import bosca.content.collection.model.Collection
import bosca.content.configuration.JobQueueNames as ContentJobQueueNames
import bosca.content.collection.model.CollectionInput
import bosca.content.collection.repository.*
import bosca.content.collection.service.CollectionService
import bosca.content.collection.service.CollectionServiceImpl
import bosca.content.metadata.model.toJson
import bosca.content.metadata.model.toInput
import bosca.content.metadata.graphql.BibleController
import bosca.content.metadata.graphql.BibleControllerDispatcher
import bosca.content.metadata.graphql.BibleChapterController
import bosca.content.metadata.graphql.BibleChapterControllerDispatcher
import bosca.content.metadata.graphql.BibleAttributionController
import bosca.content.metadata.graphql.BibleAttributionControllerDispatcher
import bosca.content.metadata.graphql.BibleReferenceController
import bosca.content.metadata.graphql.BibleReferenceControllerDispatcher
import bosca.content.metadata.graphql.FindBibleResultController
import bosca.content.metadata.graphql.FindBibleResultControllerDispatcher
import bosca.content.metadata.routes.GetBibleBookChapterVerses
import bosca.content.metadata.routes.ChapterContent
import bosca.content.metadata.jobs.LimitBibleChaptersJob
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
import bosca.di.ContentProviderRegistrar
import bosca.di.ProviderRegistry
import bosca.di.provideProvider
import bosca.di.provides
import bosca.events.DisabledEventManagerFilter
import bosca.events.EventManager
import bosca.events.asCoroutineContext
import bosca.graphql.server.ExecutableSchema
import bosca.graphql.server.ExtendedScalars
import bosca.graphql.server.GraphQL
import bosca.graphql.server.GraphQLContext
import bosca.graphql.server.GraphQLRequest
import bosca.graphql.server.RuntimeWiringBuilder
import bosca.security.service.AuthenticationContext
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Tracer
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
import bosca.sharedqueue.jobs.asCoroutineContext
import bosca.sharedqueue.jobs.redis.RedisJobQueueFactory
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
import io.mockk.spyk
import io.mockk.clearMocks
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
import kotlinx.serialization.json.jsonArray
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
    private lateinit var chapterRepository: BibleChapterRepository
    private lateinit var sourceRepository: BibleSourceRepository
    private lateinit var configurationValues: ConfigurationValueRepository
    private lateinit var encryption: EncryptionService
    private val cleanupQueue = mockk<JobQueue>(relaxed = true)
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
        encryption = spyk(EncryptionServiceImpl(application))
        provides<EncryptionService> { encryption }
        ConfigurationProviderRegistrar().register()
        BibleProvidersProviderRegistrar().register()
        ContentProviderRegistrar().register()
        chapterRepository = spyk(BibleChapterRepositoryImpl())
        sourceRepository = spyk(BibleSourceRepositoryImpl())
        configurationValues = spyk(ConfigurationValueRepositoryImpl())
        provides<BibleSourceRepository>(overrideExisting = true) { sourceRepository }
        provides<ConfigurationValueRepository>(overrideExisting = true) { configurationValues }
        provides<BibleProvider>(name = "API_BIBLE", overrideExisting = true) { provider }
        sources = ProviderRegistry.get<BibleSourceService>()
        slugs = SlugServiceImpl(SlugRepositoryImpl())
        bibles = BibleServiceImpl(BibleRepositoryImpl(), BibleBookRepositoryImpl(), chapterRepository, BibleLanguageRepositoryImpl(), sources, locks)
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
        provides<SlugService>(overrideExisting = true) { slugs }
        provides<BibleService>(overrideExisting = true) { bibles }
        provides<MetadataService>(overrideExisting = true) { metadata }
        provides<CollectionService>(overrideExisting = true) { collections }
        provides<JobQueue>(name = ContentJobQueueNames.contentJobQueue, overrideExisting = true) { cleanupQueue }
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
            assertNull(bibles.getChapter(book, "JHN.1").components)
            val source = assertNotNull(sources.getText(added.metadataId, added.version, added.variant))
            assertEquals(configurationId, source.configurationId)
            assertEquals("edition", source.providerBibleId)
            assertEquals(attribution, source.attribution)
            assertEquals(input().allowedUses, source.allowedUses)
            assertEquals(listOf(source), service.getSources(configurationId))
            assertEquals(0, countRetained())
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
    fun `source versions invalidate old responses and removing through the provider service clears content`(): Unit = request {
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
        assertNull(service.getFumsToken(added.metadataId, added.version, added.variant, "JHN.1"))
        assertEquals(1, countRetained())
        cache(updated)
        service.removeSource(updated.id, updated.version)
        assertNull(sources.getById(source.id))
        assertNull(sources.getText(added.metadataId, added.version, added.variant))
        assertEquals(0, countRetained())
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
        assertEquals(0, countRetained())
        bibles.setVariantEnabled(added.metadataId, added.version, added.variant, true)
        assertNull(bibles.getChapter(book, "JHN.3").components)
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
        assertEquals(1, countRetained())
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
        assertEquals(0, countRetained())
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
        assertEquals(1, countRetained())
        service.removeConfiguration(configurationId, true)
        assertTrue(service.getConfigurations().isEmpty())
        assertNull(sources.getById(source.id))
        assertEquals(0, countRetained())
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
        val repository = BibleChapterRepositoryImpl()
        val bible = bibles.getVariants(source.metadataId, source.metadataVersion).single { it.variant == source.variant }
        val reference = bibles.getChapters(bibles.getBooks(bible).single { it.usfm == Reference(usfm).bookUsfm })
            .single { it.usfm == usfm }
        assertEquals(1, repository.putContent(reference.copy(
            sourceId = source.id, sourceVersion = source.version, components = Text("Cached text", null).toJson(),
            attribution = attribution, verseCount = 1, fumsToken = "response-token",
            created = created, expires = if (expired) created.plusMinutes(1) else created.plusDays(1),
        )))
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
        val cache = assertNotNull(BibleChapterRepositoryImpl().getContent(source.id, "JHN.1", OffsetDateTime.now()))
        assertEquals(assertNotNull(cache.created).plusDays(14), cache.expires)
        assertEquals("chapter-token", service.getFumsToken(bible.metadataId, bible.version, bible.variant, "JHN.1"))
        val second = bibles.getChapterContent(bibles.getBooks(bible).single(), "JHN.1", BibleContentUse.AI_CONTEXT).asSerializable()
        assertEquals(bibleJson.encodeToJsonElement(BibleChapterContent.serializer(), first),
            bibleJson.encodeToJsonElement(BibleChapterContent.serializer(), second))
        assertEquals(cache.created, assertNotNull(BibleChapterRepositoryImpl().getContent(source.id, "JHN.1", OffsetDateTime.now())).created)
        assertFalse(bibleJson.encodeToString(BibleChapterContent.serializer(), second).contains("chapter-token"))
        assertNull(bibles.getChapter(bibles.getBooks(bible).single(), "JHN.1").components)
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
    fun `warm provider chapters across requests do not decrypt settings read database rows or open transactions`() {
        val bible = request {
            val bible = service.addBible(input())
            chapterResponse()
            bibles.getChapterContent(bible, Reference("JHN.1"), BibleContentUse.DISPLAY)
            bible
        }
        clearMocks(chapterRepository, sourceRepository, configurationValues, encryption, cleanupQueue, answers = false)
        repeat(2) {
            request {
                for (use in listOf(BibleContentUse.DISPLAY, BibleContentUse.AI_CONTEXT)) {
                    val content = bibles.getChapterContent(bible, Reference("JHN.1"), use)
                    assertNotNull(content.chapter[Reference("JHN.1")])
                    assertFalse(connection().inTransaction)
                }
                assertFailsWith<IllegalArgumentException> {
                    bibles.getChapterContent(bible, Reference("JHN.1"), BibleContentUse.EXPORT)
                }
                val descriptor = bibles.getChapter(bible, Reference("JHN.1"))
                assertNull(descriptor.components)
                assertNull(descriptor.attribution)
                assertNull(descriptor.sourceVersion)
                assertNull(descriptor.expires)
                assertNull(descriptor.fumsToken)
            }
        }
        coVerify(exactly = 0) { chapterRepository.getByIdAndVariantAndUsfm(any(), any(), any(), any()) }
        coVerify(exactly = 0) { chapterRepository.getContent(any(), any(), any()) }
        coVerify(exactly = 0) { sourceRepository.get(any()) }
        coVerify(exactly = 0) { configurationValues.getById(any()) }
        coVerify(exactly = 0) { encryption.decrypt(any(), any()) }
        coVerify(exactly = 0) { cleanupQueue.enqueue(any()) }
        coVerify(exactly = 1) { provider.getChapter(any(), any(), any()) }
    }

    @Test
    fun `source updates and removal invalidate cached validation across requests`() {
        val original = request {
            val bible = service.addBible(input())
            val source = assertNotNull(sources.getText(bible.metadataId, bible.version, bible.variant))
            sources.getForUse(source.id, BibleContentUse.DISPLAY)
        }
        val updated = request { sources.update(original.copy(name = "Updated source")) }
        request { assertEquals(updated, sources.getForUse(original.id, BibleContentUse.DISPLAY)) }
        request { service.removeSource(updated.id, updated.version) }
        request {
            assertNull(sources.getById(original.id))
            assertFailsWith<IllegalStateException> { sources.getForUse(original.id, BibleContentUse.DISPLAY) }
        }
    }

    @Test
    fun `a read finishing after configuration disable cannot restore usable source validation`() {
        val source = request {
            val bible = service.addBible(input())
            assertNotNull(sources.getText(bible.metadataId, bible.version, bible.variant))
        }
        request {
            sources.getForUse(source.id, BibleContentUse.DISPLAY)
            request { service.saveConfiguration(BibleProviderConfigurationInput("Provider", "API_BIBLE", false, configurationId)) }
        }
        request {
            assertFailsWith<IllegalStateException> { sources.getForUse(source.id, BibleContentUse.DISPLAY) }
        }
        request { service.saveConfiguration(BibleProviderConfigurationInput("Provider", "API_BIBLE", true, configurationId)) }
        request {
            assertEquals(source.version + 2, sources.getForUse(source.id, BibleContentUse.DISPLAY).version)
        }
    }

    @Test
    fun `disabled configurations and removed sources cannot serve cached external text`() = request {
        val bible = service.addBible(input())
        chapterResponse()
        bibles.getChapterContent(bible, Reference("JHN.1"), BibleContentUse.DISPLAY)
        val source = assertNotNull(sources.getText(bible.metadataId, bible.version, bible.variant))
        service.saveConfiguration(BibleProviderConfigurationInput("Provider", "API_BIBLE", false, configurationId))
        assertFailsWith<IllegalStateException> { bibles.getChapterContent(bible, Reference("JHN.1"), BibleContentUse.DISPLAY) }
        service.removeSource(source.id, assertNotNull(sources.getById(source.id)).version)
        assertFailsWith<IllegalStateException> { bibles.getChapterContent(bible, Reference("JHN.1"), BibleContentUse.DISPLAY) }
        assertEquals(0, countRetained())
        coVerify(exactly = 1) { provider.getChapter(any(), any(), any()) }
    }

    @Test
    fun `disabling a configuration in another request prevents an in-flight chapter from being cached`(): Unit = request {
        val bible = service.addBible(input())
        coEvery { provider.getChapter(any(), any(), any()) } coAnswers {
            request { service.saveConfiguration(BibleProviderConfigurationInput("Provider", "API_BIBLE", false, configurationId)) }
            response("JHN.1")
        }

        val content = bibles.getChapterContent(bible, Reference("JHN.1"), BibleContentUse.DISPLAY)
        assertNotNull(content.chapter[Reference("JHN.1")])
        assertEquals(0, countRetained())
        assertNull(service.getFumsToken(bible.metadataId, bible.version, bible.variant, "JHN.1"))
        coVerify(exactly = 0) { cleanupQueue.enqueue(any()) }
        request {
            assertFailsWith<IllegalStateException> { bibles.getChapterContent(bible, Reference("JHN.1"), BibleContentUse.DISPLAY) }
        }
    }

    @Test
    fun `disabling a configuration invalidates previously cached settings for subsequent requests`() = request {
        val bible = service.addBible(input())
        chapterResponse()
        bibles.getChapterContent(bible, Reference("JHN.1"), BibleContentUse.DISPLAY)
        request { service.saveConfiguration(BibleProviderConfigurationInput("Provider", "API_BIBLE", false, configurationId)) }

        request {
            assertFailsWith<IllegalStateException> { bibles.getChapterContent(bible, Reference("JHN.1"), BibleContentUse.DISPLAY) }
            assertFailsWith<IllegalStateException> { bibles.getChapterContent(bible, Reference("JHN.2"), BibleContentUse.DISPLAY) }
        }
        coVerify(exactly = 1) { provider.getChapter(any(), any(), any()) }
        assertEquals(0, countRetained())
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
        assertEquals(1, countRetained())
        coVerify(exactly = 1) { provider.getChapter(any(), any(), any()) }
    }

    @Test
    fun `generated maintenance job deletes expired responses and tokens while their configuration is disabled`(): Unit = request {
        val bible = service.addBible(input())
        bibles.setBible(bible.metadataId, bible.version, remote.toInput(false, "usx"))
        val local = assertNotNull(bibles.getBible(bible.metadataId, bible.version, "usx"))
        val localContent = bibles.getChapterContent(local, Reference("JHN.1"), BibleContentUse.EXPORT).asSerializable()
        service.saveConfiguration(BibleProviderConfigurationInput("Provider", "API_BIBLE", false, configurationId))
        val source = assertNotNull(sources.getText(bible.metadataId, bible.version, bible.variant))
        cache(source, expired = true)
        cache(source, usfm = "JHN.2")
        provides<JobQueue>(name = JobQueueNames.commonJobQueue) { mockk(relaxed = true) }
        val enqueuer = provideProvider<JobConfigurationEnqueuer>("delete-expired-bible-chapters")
        assertTrue(enqueuer.exists)
        assertEquals(JobQueueNames.commonJobQueue, enqueuer.get().queueName)
        val job = enqueuer.get().prepare(JsonObject(emptyMap()))
        job.newExecutor().execute()
        assertEquals(0, bibles.removeExpiredChapterContent())
        assertEquals(1, countRetained())
        assertNull(service.getFumsToken(bible.metadataId, bible.version, bible.variant, "JHN.1"))
        assertEquals("response-token", service.getFumsToken(bible.metadataId, bible.version, bible.variant, "JHN.2"))
        assertNotNull(sources.getById(source.id))
        val preserved = bibles.getChapterContent(local, Reference("JHN.1"), BibleContentUse.EXPORT).asSerializable()
        assertEquals(bibleJson.encodeToJsonElement(BibleChapterContent.serializer(), localContent),
            bibleJson.encodeToJsonElement(BibleChapterContent.serializer(), preserved))
        assertEquals(listOf("JHN.1", "JHN.2"), bibles.getChapters(bibles.getBooks(bible).single()).map { it.usfm })
        coVerify(exactly = 0) { provider.getChapter(any(), any(), any()) }
    }

    @Test
    fun `chapter failures and cancellation never persist a response or token`() = request {
        val bible = service.addBible(input())
        coEvery { provider.getChapter(any(), any(), any()) } throws IOException("Unavailable")
        assertFailsWith<IOException> { bibles.getChapterContent(bible, Reference("JHN.1"), BibleContentUse.DISPLAY) }
        coEvery { provider.getChapter(any(), any(), any()) } throws CancellationException("Cancelled chapter")
        assertFailsWith<CancellationException> { bibles.getChapterContent(bible, Reference("JHN.1"), BibleContentUse.DISPLAY) }
        assertEquals(0, countRetained())
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
        val content = bibles.getChapterContent(bible, Reference("JHN.1"), BibleContentUse.DISPLAY)
        assertNotNull(content.chapter[Reference("JHN.1")])
        assertEquals(0, countRetained())
        coVerify(exactly = 0) { cleanupQueue.enqueue(any()) }
        chapterResponse()
        request { bibles.getChapterContent(bible, Reference("JHN.1"), BibleContentUse.DISPLAY) }
        assertEquals(1, countRetained())
        coVerify(exactly = 2) { provider.getChapter(configurationId, "edition", "JHN.1") }
        coVerify(exactly = 1) { cleanupQueue.enqueue(any()) }
    }

    @Test
    fun `a fetch that loses its distributed lock cannot persist content`() = request {
        val bible = service.addBible(input())
        val lock = locks.create("bible-test-lost-lock:${UUID.random()}")
        val factory = mockk<DistributedLockFactory>()
        coEvery { factory.create(any()) } returns lock
        val reader = BibleServiceImpl(BibleRepositoryImpl(), BibleBookRepositoryImpl(), BibleChapterRepositoryImpl(), BibleLanguageRepositoryImpl(), sources, factory)
        coEvery { provider.getChapter(any(), any(), any()) } coAnswers {
            assertTrue(lock.release())
            response("JHN.1")
        }
        assertFailsWith<IllegalStateException> {
            reader.getChapterContent(bible, Reference("JHN.1"), BibleContentUse.DISPLAY)
        }
        assertEquals(0, countRetained())
    }

    @Test
    fun `mismatched chapters and missing or invalid verse counts cannot be cached`() = request {
        val bible = service.addBible(input())
        for (response in listOf(response("JHN.2"), response("JHN.1").copy(verseCount = null), response("JHN.1", -1))) {
            coEvery { provider.getChapter(any(), any(), any()) } returns response
            assertFailsWith<IllegalStateException> { bibles.getChapterContent(bible, Reference("JHN.1"), BibleContentUse.DISPLAY) }
            assertEquals(0, countRetained())
        }
    }

    @Test
    fun `credential changes purge responses and prevent an in-flight response from being persisted`() = request {
        val bible = service.addBible(input())
        chapterResponse()
        bibles.getChapterContent(bible, Reference("JHN.1"), BibleContentUse.DISPLAY)
        val source = assertNotNull(sources.getText(bible.metadataId, bible.version, bible.variant))
        service.saveConfiguration(BibleProviderConfigurationInput("Provider", "API_BIBLE", true, configurationId, "new-key"))
        assertEquals(0, countRetained())
        assertEquals(source.version + 1, assertNotNull(sources.getById(source.id)).version)
        coEvery { provider.getChapter(any(), any(), any()) } coAnswers {
            service.saveConfiguration(BibleProviderConfigurationInput("Provider", "API_BIBLE", true, configurationId, "another-key"))
            response("JHN.1")
        }
        val content = bibles.getChapterContent(bible, Reference("JHN.1"), BibleContentUse.DISPLAY)
        assertNotNull(content.chapter[Reference("JHN.1")])
        assertEquals(0, countRetained())
        assertNull(service.getFumsToken(bible.metadataId, bible.version, bible.variant, "JHN.1"))
        coVerify(exactly = 1) { cleanupQueue.enqueue(any()) }
    }

    @Test
    fun `background retention bounds apply across configurations using current edition responses`() = request {
        val queue = realCleanupQueue()
        val enqueuer = provideProvider<JobConfigurationEnqueuer>("limit-bible-chapters")
        assertTrue(enqueuer.exists)
        assertEquals(ContentJobQueueNames.contentJobQueue, enqueuer.get().queueName)
        val bible = service.addBible(input())
        bibles.setBible(bible.metadataId, bible.version, remote.toInput(false, "other"))
        val otherConfiguration = service.saveConfiguration(BibleProviderConfigurationInput("Other", "API_BIBLE", true, apiKey = "other-key"))
        val source = assertNotNull(sources.getText(bible.metadataId, bible.version, bible.variant))
        val otherSource = sources.add(source.copy(id = UUID.random(), configurationId = otherConfiguration.id, variant = "other"))
        bibles.setChapterSource(bible.metadataId, bible.version, "other", otherSource.id)
        coEvery { provider.getChapter(any(), "edition", any()) } coAnswers { response(thirdArg(), verseCount = 250) }
        bibles.getChapterContent(bible, Reference("JHN.1"), BibleContentUse.DISPLAY)
        val other = assertNotNull(bibles.getBible(bible.metadataId, bible.version, "other"))
        bibles.getChapterContent(other, Reference("JHN.2"), BibleContentUse.DISPLAY)
        assertEquals(2, countRetained())
        coVerify(exactly = 0) { chapterRepository.getByEdition(any(), any(), any()) }
        coVerify(exactly = 0) { chapterRepository.removeContent(any(), any()) }
        assertEquals(2, runCleanup(queue))
        assertNull(service.getFumsToken(bible.metadataId, bible.version, bible.variant, "JHN.1"))
        assertEquals("chapter-token", service.getFumsToken(bible.metadataId, bible.version, "other", "JHN.2"))
        assertEquals(1, countRetained())
        assertEquals(listOf("JHN.1", "JHN.2"), bibles.getChapters(bibles.getBooks(bible).single()).map { it.usfm })
        coVerify(exactly = 1) { chapterRepository.removeContent(source.id, "JHN.1") }
        request { bibles.getChapterContent(bible, Reference("JHN.1"), BibleContentUse.DISPLAY) }
        coVerify(exactly = 2) { provider.getChapter(configurationId, "edition", "JHN.1") }
    }

    @Test
    fun `text cache retains 499 verses and keeps separate upstream editions independent`() = request {
        val queue = realCleanupQueue()
        val bible = service.addBible(input())
        bibles.setBible(bible.metadataId, bible.version, remote.toInput(false, "usx"))
        val local = assertNotNull(bibles.getBible(bible.metadataId, bible.version, "usx"))
        val localContent = bibles.getChapterContent(local, Reference("JHN.1"), BibleContentUse.EXPORT).asSerializable()
        coEvery { provider.getChapter(configurationId, "edition", "JHN.1") } returns response("JHN.1", 250)
        coEvery { provider.getChapter(configurationId, "edition", "JHN.2") } returns response("JHN.2", 249)
        bibles.getChapterContent(bible, Reference("JHN.1"), BibleContentUse.DISPLAY)
        bibles.getChapterContent(bible, Reference("JHN.2"), BibleContentUse.DISPLAY)
        assertEquals(2, countRetained())
        coEvery { provider.getBible(configurationId, "other-edition", BibleSourceKind.TEXT) } returns BibleProviderResponse(remote, attribution)
        val other = service.addBible(input().copy(providerBibleId = "other-edition"))
        coEvery { provider.getChapter(configurationId, "other-edition", "JHN.1") } returns response("JHN.1", 499)
        bibles.getChapterContent(other, Reference("JHN.1"), BibleContentUse.DISPLAY)
        assertEquals(3, countRetained())
        assertEquals(3, runCleanup(queue))
        assertEquals(3, countRetained())
        assertEquals("chapter-token", service.getFumsToken(bible.metadataId, bible.version, bible.variant, "JHN.1"))
        assertEquals("chapter-token", service.getFumsToken(bible.metadataId, bible.version, bible.variant, "JHN.2"))
        assertEquals("chapter-token", service.getFumsToken(other.metadataId, other.version, other.variant, "JHN.1"))
        val preserved = bibles.getChapterContent(local, Reference("JHN.1"), BibleContentUse.EXPORT).asSerializable()
        assertEquals(bibleJson.encodeToJsonElement(BibleChapterContent.serializer(), localContent),
            bibleJson.encodeToJsonElement(BibleChapterContent.serializer(), preserved))
    }

    private fun realCleanupQueue(): JobQueue = RedisJobQueueFactory(
        valkey.newConnectionPool(), json, locks, null, emptyList(), processExpiredJobs = false,
    ).create("bible-limit-${UUID.random()}").also {
        provides<ConnectionPool> { pool }
        provides<JobQueue>(name = ContentJobQueueNames.contentJobQueue, overrideExisting = true) { it }
    }

    private suspend fun runCleanup(queue: JobQueue): Int {
        var executed = 0
        while (true) {
            val job = queue.dequeue() ?: break
            try {
                val definition = json.decodeFromJsonElement(LimitBibleChaptersJob.serializer(), job.getDefinition())
                assertEquals("API_BIBLE", definition.provider)
                assertFalse(job.getDefinition().toString().contains("server-secret"))
                assertFalse(job.getDefinition().toString().contains("chapter-token"))
                repeat(2) {
                    request { withContext(queue.asCoroutineContext(job)) { job.newExecutor().execute() } }
                }
                queue.markComplete(job)
                executed++
            } finally {
                queue.releaseJobLock(job)
            }
        }
        return executed
    }

    @Test
    fun `oversized chapters are returned and pruned by the queued retention job`() = request {
        val queue = realCleanupQueue()
        val bible = service.addBible(input())
        val source = assertNotNull(sources.getText(bible.metadataId, bible.version, bible.variant))
        for (count in listOf(500, 501)) {
            chapterResponse(verseCount = count)
            val content = request {
                bibles.getChapterContent(bible, Reference("JHN.1"), BibleContentUse.DISPLAY).asSerializable()
            }
            assertEquals(Reference("JHN.1"), content.chapter.reference)
            assertEquals("Provider chapter", (assertNotNull(content.chapter[Reference("JHN.1")]) as Text).text)
            assertEquals(BibleAttribution("Chapter copyright"), content.attribution)
            assertEquals(1, countRetained())
            assertEquals("chapter-token", service.getFumsToken(bible.metadataId, bible.version, bible.variant, "JHN.1"))
            assertEquals(1, runCleanup(queue))
            assertEquals(0, countRetained())
            assertNull(service.getFumsToken(bible.metadataId, bible.version, bible.variant, "JHN.1"))
            val chapter = assertNotNull(chapterRepository.getByIdAndVariantAndUsfm(
                bible.metadataId, bible.version, bible.variant, "JHN.1",
            ))
            assertEquals(source.id, chapter.sourceId)
            assertNull(chapter.components)
            assertNull(chapter.attribution)
            assertNull(chapter.verseCount)
            assertNull(chapter.fumsToken)
            assertNull(chapter.expires)
        }
        coVerify(exactly = 2) { provider.getChapter(configurationId, "edition", "JHN.1") }
        assertEquals(listOf("JHN.1", "JHN.2"), bibles.getChapters(bibles.getBooks(bible).single()).map { it.usfm })
    }

    @Test
    fun `provider work cannot run in a database transaction`() = request {
        val bible = service.addBible(input())
        bosca.db.transaction {
            assertFailsWith<IllegalStateException> { bibles.getChapterContent(bible, Reference("JHN.2"), BibleContentUse.DISPLAY) }
        }
        assertEquals(0, countRetained())
        coVerify(exactly = 0) { provider.getChapter(any(), any(), any()) }
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

    @Test
    fun `generated GraphQL readers use existing chapter storage with exact response attribution`() {
        val bible = request { service.addBible(input()) }
        val chapterAttribution = BibleAttribution("Exact chapter copyright", "Chapter citation", "https://example.com/chapter")
        coEvery { provider.getChapter(configurationId, "edition", "JHN.1") } coAnswers {
            assertFalse(connection().inTransaction)
            BibleProviderResponse(Chapter(Reference("JHN.1"), ComponentContainer(ContainerType.PARAGRAPH, listOf(
                VerseStart(Reference("JHN.1.1")), Text("First provider verse", null), VerseEnd(),
                VerseStart(Reference("JHN.1.2")), Text("Second provider verse", null), VerseEnd(),
            ), null)), chapterAttribution, 2, "private-fums-token")
        }
        provides<ConnectionPool> { pool }
        provides<Tracer> { GlobalOpenTelemetry.getTracer("bible-reader-test") }
        val sdl = javaClass.getResourceAsStream("/graphql/metadata/bible.graphqls")?.bufferedReader()?.use { it.readText() }
            ?: error("Bible schema not found")
        val wiring = RuntimeWiringBuilder().apply {
            scalar("JSON", ExtendedScalars.Json)
            type("Query") { field("bible") { bible } }
            type(BibleControllerDispatcher(BibleController(bibles, metadata, mockk())).type)
            type(BibleChapterControllerDispatcher(BibleChapterController(bibles)).type)
            type(BibleAttributionControllerDispatcher(BibleAttributionController()).type)
            type(BibleReferenceControllerDispatcher(BibleReferenceController()).type)
            type(FindBibleResultControllerDispatcher(FindBibleResultController()).type)
        }.build()
        val graphql = GraphQL(ExecutableSchema.fromSdl("scalar JSON\ntype Query { bible: Bible! }\n$sdl", wiring))
        val context = GraphQLContext(mapOf("authenticationContext" to mockk<AuthenticationContext>()))
        request {
            val references = graphql.execute(GraphQLRequest("{ bible { chapter(usfm: \"JHN.1\") { reference { usfm } } } }", context = context))
            assertTrue(references.errors.isEmpty(), references.errors.toString())
        }
        coVerify(exactly = 0) { provider.getChapter(any(), any(), any()) }

        request {
            val result = graphql.execute(GraphQLRequest("""
                { bible {
                    chapter(usfm: "JHN.1") {
                        component
                        verses { usfm }
                        attribution { copyright citation informationUrl }
                    }
                    find(human: "John 1:2") {
                        component
                        attribution { copyright citation informationUrl }
                    }
                } }
            """.trimIndent(), context = context))
            assertTrue(result.errors.isEmpty(), result.errors.toString())
            val data = assertNotNull(result.data).jsonObject.getValue("bible").jsonObject
            val chapter = data.getValue("chapter").jsonObject
            assertTrue(chapter.getValue("component").toString().contains("First provider verse"))
            assertEquals(listOf("JHN.1.1", "JHN.1.2"), chapter.getValue("verses").jsonArray.map {
                it.jsonObject.getValue("usfm").jsonPrimitive.content
            })
            val expectedAttribution = json.encodeToJsonElement(BibleAttribution.serializer(), chapterAttribution)
            assertEquals(expectedAttribution, chapter.getValue("attribution"))
            val found = data.getValue("find").jsonArray.single().jsonObject
            assertTrue(found.getValue("component").toString().contains("Second provider verse"))
            assertFalse(found.getValue("component").toString().contains("First provider verse"))
            assertEquals(expectedAttribution, found.getValue("attribution"))
            assertFalse(result.data.toString().contains("private-fums-token"))
            assertFalse(result.data.toString().contains("server-secret"))
            val source = assertNotNull(sources.getText(bible.metadataId, bible.version, bible.variant))
            val stored = assertNotNull(BibleChapterRepositoryImpl().getContent(source.id, "JHN.1", OffsetDateTime.now()))
            assertNotNull(stored.components)
            assertFalse(json.encodeToString(bosca.content.metadata.model.BibleChapter.serializer(), stored).contains("private-fums-token"))
            val legacyTable = connection().useStatement("select to_regclass('bible_providers.chapters')") {
                it.executeQuery().use { result -> assertTrue(result.next()); result.getString(1) }
            }
            assertNull(legacyTable)
            val storedComponents = bibles.getChapter(bible, Reference("JHN.1")).components
            assertTrue(storedComponents == null || storedComponents == JsonNull)
            assertEquals("private-fums-token", bibles.getFumsToken(bible.metadataId, bible.version, bible.variant, "JHN.1"))
        }
        coVerify(exactly = 1) { provider.getChapter(configurationId, "edition", "JHN.1") }
        coVerify(exactly = 1) { provider.getBible(configurationId, "edition", BibleSourceKind.TEXT) }
    }

    @Test
    fun `REST verse reader delivers persisted provider attribution without private response data`() {
        chapterResponse()
        val bible = request { service.addBible(input()) }
        request {
            val result = GetBibleBookChapterVerses.getChapterVerses(Reference("JHN.1"), bible, bibles).single()
            val encoded = json.encodeToJsonElement(ChapterContent.serializer(), result).toString()
            assertTrue(encoded.contains("Provider chapter"))
            assertEquals(BibleAttribution("Chapter copyright"), result.attribution)
            assertNull(result.chapter.components)
            assertFalse(encoded.contains("chapter-token"))
            assertFalse(encoded.contains("server-secret"))
        }
        request {
            val result = GetBibleBookChapterVerses.getChapterVerses(Reference("JHN.1"), bible, bibles).single()
            assertEquals(BibleAttribution("Chapter copyright"), result.attribution)
        }
        coVerify(exactly = 1) { provider.getChapter(configurationId, "edition", "JHN.1") }
    }

    private fun response(usfm: String, verseCount: Int = 2, attribution: BibleAttribution? = BibleAttribution("Chapter copyright")) =
        BibleProviderResponse(Chapter(Reference(usfm), Text("Provider chapter", null)), attribution, verseCount, "chapter-token")

    private suspend fun countRetained(): Int = connection().useStatement("select count(*) from bible_chapters where expires is not null") {
        it.executeQuery().use { result -> assertTrue(result.next()); result.getInt(1) }
    }

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
