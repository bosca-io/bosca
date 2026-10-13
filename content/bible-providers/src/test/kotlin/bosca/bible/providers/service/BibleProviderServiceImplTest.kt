@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.bible.providers.service

import bosca.bible.Bible as SharedBible
import bosca.bible.BibleAttribution
import bosca.bible.BibleAudioContent
import bosca.bible.BibleAudioTimecode
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
import bosca.configuration.service.getValueAs
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
import bosca.content.metadata.graphql.BibleProviders
import bosca.content.metadata.graphql.BibleProvidersMutation
import bosca.content.metadata.graphql.BibleProvidersControllerDispatcher
import bosca.content.metadata.graphql.BibleProvidersMutationControllerDispatcher
import bosca.content.metadata.graphql.BibleProviderConfigurationControllerDispatcher
import bosca.content.metadata.graphql.BibleSourceControllerDispatcher
import bosca.content.metadata.graphql.BibleCatalogControllerDispatcher
import bosca.content.metadata.graphql.BibleLanguageControllerDispatcher
import bosca.content.metadata.graphql.BibleAudioControllerDispatcher
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
import bosca.security.service.GroupEvaluator
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.security.model.Principal
import bosca.security.model.Group
import bosca.security.model.GroupType
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
import kotlin.test.assertNotEquals
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
    private val objects = mutableMapOf<String, ByteArray>()
    private val storage = mockk<bosca.storage.service.ObjectStorageService>()
    private val cleanupQueue = mockk<JobQueue>(relaxed = true)
    private lateinit var sources: BibleSourceService
    private lateinit var service: BibleProviderService
    private lateinit var bibles: BibleService
    private lateinit var metadata: MetadataService
    private lateinit var collections: CollectionService
    private lateinit var slugs: SlugService
    private lateinit var parent: Collection
    private lateinit var configurationId: UUID
    private lateinit var application: BoscaApplication
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
        objects.clear()
        coEvery { storage.setInputStream(any(), any(), any()) } coAnswers {
            val bytes = secondArg<java.io.InputStream>().readBytes()
            objects[firstArg<bosca.storage.service.ObjectPath>().toString()] = bytes
            bytes.size.toLong()
        }
        coEvery { storage.getInputStream(any()) } coAnswers {
            java.io.ByteArrayInputStream(objects.getValue(firstArg<bosca.storage.service.ObjectPath>().toString()))
        }
        coEvery { storage.getInputStreamRange(any(), any()) } coAnswers {
            val bytes = objects.getValue(firstArg<bosca.storage.service.ObjectPath>().toString())
            val range = secondArg<LongRange>()
            java.io.ByteArrayInputStream(bytes.copyOfRange(range.first.toInt(), range.last.toInt() + 1))
        }
        coEvery { storage.delete(any()) } coAnswers { objects.remove(firstArg<bosca.storage.service.ObjectPath>().toString()); Unit }
        ProviderRegistry.clear()
        application = BoscaApplication(ApplicationConfig.load("""
            content:
              storage:
                urlPrefix: https://media.bosca.test/platform/
            security:
              encryption:
                key: bible-registration-test-key
        """.trimIndent().byteInputStream()))
        provides<Json>(overrideExisting = true) { json }
        provides<CacheManager> { cacheManager }
        provides<DistributedLockFactory> { locks }
        provides<RequestCacheSerializer> { RequestCacheSerializerImpl(json) }
        provides<bosca.storage.service.ObjectStorageService> { storage }
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
        bibles = BibleServiceImpl(BibleRepositoryImpl(), BibleBookRepositoryImpl(), chapterRepository, BibleLanguageRepositoryImpl(), sources, locks, storage, json, application)
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
    fun `audio targets exclude deleted metadata and retain live disabled variants`() = request {
        val live = service.addBible(input())
        bibles.setBible(live.metadataId, live.version, remote.toInput(false, "other"))
        bibles.setDefaultVariant(live.metadataId, live.version, "other")
        bibles.setVariantEnabled(live.metadataId, live.version, live.variant, false)
        val deleted = service.addBible(input())
        bosca.db.transaction { metadata.markDeleted(deleted.metadataId) }
        audioCatalog()

        val targets = service.getAudioTargets(configurationId, "recording")
        assertEquals(setOf(live.metadataId), targets.map { it.metadataId }.toSet())
        assertEquals(setOf("study", "other"), targets.map { it.variant }.toSet())
        val disabled = targets.single { it.variant == "study" }
        assertFalse(disabled.enabled)
        val audio = service.addAudio(disabled, AddProviderAudioInput(configurationId, "recording", setOf(BibleContentUse.PLAYBACK)))
        assertEquals(audio.id, sources.getAudio(live.metadataId, live.version, live.variant).single().id)
        assertTrue(sources.getAudio(deleted.metadataId, deleted.version, deleted.variant).isEmpty())
    }

    @Test
    fun `audio registration rejects deleted metadata before requesting provider content`() = request {
        val bible = service.addBible(input())
        assertFalse(assertNotNull(metadata.getById(bible.metadataId, bible.version)).deleted)
        bosca.db.transaction { metadata.markDeleted(bible.metadataId) }

        assertFailsWith<IllegalStateException> {
            service.addAudio(bible, AddProviderAudioInput(configurationId, "recording", setOf(BibleContentUse.PLAYBACK)))
        }
        assertEquals(1, service.getSources(configurationId).size)
        assertTrue(sources.getAudio(bible.metadataId, bible.version, bible.variant).isEmpty())
        coVerify(exactly = 0) { provider.getCatalog(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { provider.getBible(any(), any(), BibleSourceKind.AUDIO) }
    }

    @Test
    fun `audio targets and registration preserve Bible versions that differ from the metadata row version`() = request {
        val first = service.addBible(input())
        bibles.setBible(first.metadataId, 2, remote.toInput(true, first.variant))
        audioCatalog()

        val targets = service.getAudioTargets(configurationId, "recording")
        assertEquals(setOf(1, 2), targets.map { it.version }.toSet())
        val second = targets.single { it.version == 2 }
        assertEquals(1, assertNotNull(metadata.getById(second.metadataId)).version)
        val audio = service.addAudio(second, AddProviderAudioInput(configurationId, "recording", setOf(BibleContentUse.PLAYBACK)))
        assertEquals(audio.id, sources.getAudio(second.metadataId, second.version, second.variant).single().id)
        assertTrue(sources.getAudio(first.metadataId, first.version, first.variant).isEmpty())
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

    @Test
    fun `audio registration rejects metadata deleted during provider lookup`() = request {
        val bible = service.addBible(input())
        audioCatalog()
        coEvery { provider.getBible(configurationId, "recording", BibleSourceKind.AUDIO) } coAnswers {
            assertFalse(connection().inTransaction)
            request { bosca.db.transaction { metadata.markDeleted(bible.metadataId) } }
            BibleProviderResponse(remote, attribution)
        }

        assertFailsWith<IllegalStateException> {
            service.addAudio(bible, AddProviderAudioInput(configurationId, "recording", setOf(BibleContentUse.PLAYBACK)))
        }
        assertEquals(1, service.getSources(configurationId).size)
        assertTrue(sources.getAudio(bible.metadataId, bible.version, bible.variant).isEmpty())
        coVerify(exactly = 1) { provider.getBible(configurationId, "recording", BibleSourceKind.AUDIO) }
    }

    @Test
    fun `registered audio chapter is downloaded once and keeps its exact response metadata private`() {
        val (bible, audio) = request {
            val bible = service.addBible(input())
            audioCatalog()
            bible to service.addAudio(bible, AddProviderAudioInput(configurationId, "recording", setOf(BibleContentUse.PLAYBACK)))
        }
        val bytes = "recording bytes".toByteArray()
        val content = BibleAudioContent("audio/mpeg", bytes.size.toLong(), listOf(BibleAudioTimecode("JHN.1.1", 0.0, 1.5)))
        coEvery { provider.downloadAudioChapter(configurationId, "recording", "JHN.1", any()) } coAnswers {
            assertFalse(connection().inTransaction)
            arg<java.io.OutputStream>(3).write(bytes)
            BibleProviderResponse(content, attribution, fumsToken = "private-audio-token")
        }
        val first = request { bibles.getAudioChapter(bible, audio.id, "JHN.1").asSerializable() }
        assertEquals(audio, first.audio)
        assertEquals("JHN.1", first.usfm)
        assertEquals(content, first.content)
        assertEquals(attribution, first.attribution)
        assertEquals("https://media.bosca.test/platform/api/v1/content/metadata/bible/audio" +
            "?id=${bible.metadataId}&version=${bible.version}&variant=${bible.variant}&audioId=${audio.id}&usfm=JHN.1" +
            "&recordingId=${recordingId(first.url)}", first.url)
        assertEquals(first, request { bibles.getAudioChapter(bible, audio.id, "JHN.1").asSerializable() })
        request { assertEquals("private-audio-token", service.getFumsToken(bible.metadataId, bible.version, bible.variant, "JHN.1", audio.id)) }
        coVerify(exactly = 1) { provider.downloadAudioChapter(configurationId, "recording", "JHN.1", any()) }
        coVerify(exactly = 1) { provider.getBible(configurationId, "recording", BibleSourceKind.AUDIO) }
        coVerify(exactly = 0) { provider.getChapter(any(), any(), any()) }
    }

    @Test
    fun `warm recordings serve bytes and ranges across requests without database reads or transactions`() {
        val (bible, audio) = registeredAudio()
        val bytes = audioResponse()
        val description = request { bibles.getAudioChapter(bible, audio.id, "JHN.1") }
        clearMocks(chapterRepository, sourceRepository, configurationValues, encryption, cleanupQueue, answers = false)
        repeat(2) {
            request {
                val response = bibles.getAudioPlayback(bible, audio.id, "JHN.1", recordingId(description.url))
                assertEquals(response.content, bibles.getAudioPlayback(bible, audio.id, "JHN.1").content)
                assertEquals(bytes.toList(), response.openStream().use { it.readBytes().toList() })
                assertEquals(bytes.slice(2..5), response.openStream(2L..5L).use { it.readBytes().toList() })
                assertFalse(connection().inTransaction)
            }
        }
        coVerify(exactly = 0) { chapterRepository.getByIdAndVariantAndUsfm(any(), any(), any(), any()) }
        coVerify(exactly = 0) { chapterRepository.getAudioChapter(any(), any(), any(), any()) }
        coVerify(exactly = 0) { sourceRepository.get(any()); configurationValues.getById(any()); encryption.decrypt(any(), any()) }
        coVerify(exactly = 0) { cleanupQueue.enqueue(any()) }
        coVerify(exactly = 1) { provider.downloadAudioChapter(any(), any(), any(), any()) }
    }

    @Test
    fun `recording playback recovers from a cache miss without downloading again`() {
        val (bible, audio) = registeredAudio()
        val bytes = audioResponse()
        val description = request { bibles.getAudioChapter(bible, audio.id, "JHN.1") }
        request { cacheManager.getCache<String>("bible:audio:chapter").clear() }
        clearMocks(chapterRepository, cleanupQueue, answers = false)
        repeat(2) {
            request {
                val playback = bibles.getAudioPlayback(bible, audio.id, "JHN.1", recordingId(description.url))
                assertEquals(description.content, playback.content)
                assertEquals(bytes.toList(), playback.openStream().use { it.readBytes().toList() })
            }
        }
        coVerify(exactly = 1) { chapterRepository.getAudioChapter(bible.metadataId, bible.version, bible.variant, "JHN.1") }
        coVerify(exactly = 0) { chapterRepository.updateAudioContent(any(), any(), any(), any(), any(), any(), any()); cleanupQueue.enqueue(any()) }
        coVerify(exactly = 1) { provider.downloadAudioChapter(any(), any(), any(), any()) }
    }

    @Test
    fun `recording checks reject missing playback rights wrong identity and disabled variants before downloading`() {
        val (bible, audio) = registeredAudio(emptySet())
        request { assertFailsWith<IllegalArgumentException> { bibles.getAudioChapter(bible, audio.id, "JHN.1") } }
        val (allowedBible, allowedAudio) = registeredAudio()
        request {
            assertFailsWith<IllegalArgumentException> { bibles.getAudioChapter(bible, allowedAudio.id, "JHN.1") }
            assertFailsWith<IllegalArgumentException> { bibles.getAudioChapter(allowedBible.copy(version = 2), allowedAudio.id, "JHN.1") }
            assertFailsWith<IllegalArgumentException> { bibles.getAudioChapter(allowedBible.copy(variant = "other"), allowedAudio.id, "JHN.1") }
            bibles.setDefaultVariant(allowedBible.metadataId, allowedBible.version, allowedBible.variant)
            // A nondefault variant may be disabled while its registered recording remains attached.
            val variant = allowedBible.copy(variant = "disabled", defaultVariant = false)
            bibles.setBible(variant.metadataId, variant.version, remote.toInput(false, variant.variant))
            val attached = service.addAudio(variant, AddProviderAudioInput(configurationId, "recording", setOf(BibleContentUse.PLAYBACK)))
            bibles.setVariantEnabled(variant.metadataId, variant.version, variant.variant, false)
            assertFailsWith<IllegalStateException> { bibles.getAudioChapter(variant, attached.id, "JHN.1") }
        }
        coVerify(exactly = 0) { provider.downloadAudioChapter(any(), any(), any(), any()) }
    }

    @Test
    fun `disabling a variant invalidates its warm recordings without evicting other variants`() {
        val (bible, audio) = registeredAudio()
        val (variant, attached) = request {
            val variant = bible.copy(variant = "reader", defaultVariant = false)
            bibles.setBible(variant.metadataId, variant.version, remote.toInput(false, variant.variant))
            variant to service.addAudio(variant, AddProviderAudioInput(configurationId, "recording", setOf(BibleContentUse.PLAYBACK)))
        }
        audioResponse()
        request {
            bibles.getAudioPlayback(bible, audio.id, "JHN.1")
            bibles.getAudioPlayback(variant, attached.id, "JHN.1")
        }
        request { bibles.setVariantEnabled(variant.metadataId, variant.version, variant.variant, false) }
        request {
            assertFailsWith<IllegalStateException> { bibles.getAudioPlayback(variant, attached.id, "JHN.1") }
        }
        clearMocks(chapterRepository, sourceRepository, configurationValues, encryption, answers = false)
        request { bibles.getAudioPlayback(bible, audio.id, "JHN.1").openStream().use { it.readBytes() } }
        coVerify(exactly = 0) { chapterRepository.getAudioChapter(any(), any(), any(), any()) }
        coVerify(exactly = 0) { chapterRepository.getByIdAndVariantAndUsfm(any(), any(), any(), any()) }
        coVerify(exactly = 0) { sourceRepository.get(any()); configurationValues.getById(any()); encryption.decrypt(any(), any()) }
        coVerify(exactly = 2) { provider.downloadAudioChapter(any(), any(), any(), any()) }
    }

    @Test
    fun `recording refresh and removal clear only its audio while preserving chapter text`() {
        val (bible, audio) = registeredAudio()
        audioResponse()
        request {
            chapterResponse()
            bibles.getChapterContent(bible, Reference("JHN.1"), BibleContentUse.DISPLAY)
            bibles.getAudioChapter(bible, audio.id, "JHN.1")
            service.refresh(audio.id)
            assertNull(service.getFumsToken(bible.metadataId, bible.version, bible.variant, "JHN.1", audio.id))
            assertNotNull(bibles.getFumsToken(bible.metadataId, bible.version, bible.variant, "JHN.1"))
            bibles.getAudioChapter(bible, audio.id, "JHN.1")
            val source = assertNotNull(sources.getById(audio.id))
            service.removeSource(audio.id, source.version)
            assertTrue(bibles.getAudio(bible).isEmpty())
            assertNull(service.getFumsToken(bible.metadataId, bible.version, bible.variant, "JHN.1", audio.id))
            assertFailsWith<IllegalStateException> { bibles.getAudioChapter(bible, audio.id, "JHN.1") }
            assertNotNull(bibles.getFumsToken(bible.metadataId, bible.version, bible.variant, "JHN.1"))
        }
        coVerify(exactly = 2) { provider.downloadAudioChapter(any(), any(), any(), any()) }
    }

    @Test
    fun `recording eviction preserves resolved media until delayed cleanup`() {
        val (bible, audio) = registeredAudio()
        val bytes = audioResponse()
        coEvery { storage.getInputStream(any()) } coAnswers {
            val path = firstArg<bosca.storage.service.ObjectPath>()
            (objects[path.toString()] ?: throw bosca.storage.service.ObjectNotFoundException(path)).inputStream()
        }
        request {
            val response = bibles.getAudioPlayback(bible, audio.id, "JHN.1")
            val stored = chapterRepository.getAudioBySource(audio.id).flatMap { it.audio.orEmpty() }.single()
            clearMocks(cleanupQueue, answers = false)
            request {
                val source = assertNotNull(sources.getById(audio.id))
                val now = OffsetDateTime.now()
                for (index in 2..51) {
                    if (index > 2) chapterRepository.add(bosca.content.metadata.model.BibleChapter(bible.metadataId, bible.version, bible.variant, "JHN", "JHN.$index", null, index))
                    val cached = bosca.content.metadata.model.BibleChapterAudio(UUID.random(), source.id, source.version, "JHN.$index",
                        BibleAudioContent("audio/mpeg", 10), attribution, "token", now, now.plusDays(14))
                    chapterRepository.updateAudioContent(bible.metadataId, bible.version, bible.variant, cached.usfm, source.id, source.version,
                        json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(bosca.content.metadata.model.BibleChapterAudio.serializer()), listOf(cached)))
                }
                assertEquals(1, bibles.limitAudioChapterContent("API_BIBLE", "recording"))
                assertTrue(chapterRepository.getAudioByEdition("API_BIBLE", "recording").flatMap { it.audio.orEmpty() }.none { it.id == stored.id })
            }
            assertEquals(bytes.toList(), response.openStream().use { it.readBytes().toList() })
            request {
                assertFailsWith<bosca.storage.service.ObjectNotFoundException> {
                    bibles.getAudioPlayback(bible, audio.id, "JHN.1", stored.id)
                }
            }
            coVerify(exactly = 0) { cleanupQueue.enqueue(any()) }
            coVerify(exactly = 1) { cleanupQueue.enqueueLater(any(), kotlin.time.Duration.parse("5m")) }
            val delete = provideProvider<JobConfigurationEnqueuer>("delete-bible-audio").get()
                .prepare(JsonObject(mapOf("id" to kotlinx.serialization.json.JsonPrimitive(stored.id.toString()))))
            withContext(cleanupQueue.asCoroutineContext(delete)) { delete.newExecutor().execute() }
            assertFalse(objects.containsKey("bible/audio/${stored.id}"))
            request {
                assertFailsWith<bosca.storage.service.ObjectNotFoundException> {
                    bibles.getAudioPlayback(bible, audio.id, "JHN.1", stored.id).openStream().use { it.readBytes() }
                }
            }
            coVerify(exactly = 1) { provider.downloadAudioChapter(any(), any(), any(), any()) }
        }
    }

    @Test
    fun `rolled back audio responses are discarded before orphan cleanup`() {
        val (bible, audio) = registeredAudio()
        val bytes = audioResponse()
        coEvery { storage.getInputStream(any()) } coAnswers {
            val path = firstArg<bosca.storage.service.ObjectPath>()
            (objects[path.toString()] ?: throw bosca.storage.service.ObjectNotFoundException(path)).inputStream()
        }
        val expiryDelays = listOf(kotlin.time.Duration.parse("14d"), kotlin.time.Duration.parse("14d") + kotlin.time.Duration.parse("5m"))
        for (delay in expiryDelays) {
            coEvery { cleanupQueue.enqueueLater(any(), delay) } throws kotlinx.coroutines.CancellationException("cancelled before commit")
        }
        request {
            assertFailsWith<kotlinx.coroutines.CancellationException> { bibles.getAudioPlayback(bible, audio.id, "JHN.1") }
            assertTrue(chapterRepository.getAudioBySource(audio.id).isEmpty(), "The audio write should have rolled back")
        }
        val objectId = UUID.parse(objects.keys.single().substringAfterLast('/'))
        request {
            val delete = provideProvider<JobConfigurationEnqueuer>("delete-bible-audio").get()
                .prepare(JsonObject(mapOf("id" to kotlinx.serialization.json.JsonPrimitive(objectId.toString()))))
            withContext(cleanupQueue.asCoroutineContext(delete)) { delete.newExecutor().execute() }
        }
        for (delay in expiryDelays) {
            coEvery { cleanupQueue.enqueueLater(any(), delay) } returns UUID.random()
        }
        request {
            val response = bibles.getAudioPlayback(bible, audio.id, "JHN.1")
            assertEquals(bytes.toList(), response.openStream().use { it.readBytes().toList() })
        }
        coVerify(exactly = 2) { provider.downloadAudioChapter(any(), any(), any(), any()) }
    }

    @Test
    fun `source changes during audio downloads return the resolved media without caching or downloading again`() {
        val (bible, audio) = registeredAudio()
        val bytes = "recording bytes".toByteArray()
        coEvery { provider.downloadAudioChapter(configurationId, "recording", "JHN.1", any()) } coAnswers {
            arg<java.io.OutputStream>(3).write(bytes)
            val source = assertNotNull(sources.getById(audio.id))
            sources.update(source.copy(name = "Updated recording"))
            BibleProviderResponse(BibleAudioContent("audio/mpeg", bytes.size.toLong()), attribution, fumsToken = "private-audio-token")
        }
        request {
            val response = bibles.getAudioPlayback(bible, audio.id, "JHN.1")
            assertEquals(bytes.size.toLong(), response.content.contentLength)
            assertEquals(bytes.toList(), response.openStream().use { it.readBytes().toList() })
            assertEquals(bytes.slice(2..5), response.openStream(2L..5L).use { it.readBytes().toList() })
            assertTrue(chapterRepository.getAudioBySource(audio.id).isEmpty())
            assertNull(bibles.getAudioFumsToken(bible.metadataId, bible.version, bible.variant, "JHN.1", audio.id))
        }
        coVerify(exactly = 1) { provider.downloadAudioChapter(any(), any(), any(), any()) }
        coVerify(exactly = 1) { cleanupQueue.enqueueLater(any(), kotlin.time.Duration.parse("5m")) }
    }

    @Test
    fun `recording retention separates responses from multiple sources on the same chapter`() {
        val (bible, audio) = registeredAudio()
        request {
            val source = assertNotNull(sources.getById(audio.id))
            val otherConfiguration = service.saveConfiguration(BibleProviderConfigurationInput("Other", "API_BIBLE", true, apiKey = "other-key"))
            val sameRecording = sources.add(source.copy(id = UUID.random(), configurationId = otherConfiguration.id))
            val otherRecording = sources.add(source.copy(id = UUID.random(), providerBibleId = "other-recording"))
            val now = OffsetDateTime.now()
            val responses = listOf(source, sameRecording, otherRecording).map { selected ->
                val response = bosca.content.metadata.model.BibleChapterAudio(UUID.random(), selected.id, selected.version, "JHN.1",
                    BibleAudioContent("audio/mpeg", 10), attribution, "token", now, now.plusDays(14))
                chapterRepository.updateAudioContent(bible.metadataId, bible.version, bible.variant, response.usfm, selected.id, selected.version,
                    json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(bosca.content.metadata.model.BibleChapterAudio.serializer()), listOf(response)))
                response
            }
            val retained = chapterRepository.getAudioByEdition("API_BIBLE", "recording").flatMap { it.audio.orEmpty() }
            assertEquals(2, retained.size)
            assertEquals(responses.take(2).map { it.id }.toSet(), retained.map { it.id }.toSet())
            assertEquals(listOf(responses.last()), chapterRepository.getAudioByEdition("API_BIBLE", "other-recording").flatMap { it.audio.orEmpty() })
        }
    }

    @Test
    fun `recording retention uses the audio index without scanning unrelated chapters`() {
        val (bible, audio) = registeredAudio()
        audioResponse()
        request {
            bibles.getAudioChapter(bible, audio.id, "JHN.1")
            connection().useStatement("insert into bible_chapters (metadata_id, version, variant, book_usfm, usfm, components, sort) select ?::uuid, ?::int, ?, 'JHN', 'JHN.' || i, null, i from generate_series(3, 10002) i") {
                it.setString(1, bible.metadataId.toString())
                it.setInt(2, bible.version)
                it.setString(3, bible.variant)
                it.executeUpdate()
            }
            connection().useStatement("analyze bible_chapters") { it.execute() }
            val query = bosca.content.metadata.repository.BibleChapterRepository::class.java.methods
                .single { it.name == "getAudioByEdition" }.getAnnotation(bosca.db.annotation.Query::class.java).value
                .replace(":providerBibleId", "?").replace(":provider", "?")
            val plan = connection().useStatement("explain (analyze, format json) $query") {
                it.setString(1, "API_BIBLE")
                it.setString(2, "recording")
                it.executeQuery().use { result -> assertTrue(result.next()); json.parseToJsonElement(result.getString(1)) }
            }
            fun usesAudioIndex(node: JsonObject): Boolean =
                (node["Index Name"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "bible_chapters_audio" ||
                    (node["Plans"] as? kotlinx.serialization.json.JsonArray).orEmpty().any { usesAudioIndex(it as JsonObject) }
            val root = ((plan as kotlinx.serialization.json.JsonArray).single() as JsonObject)["Plan"] as JsonObject
            assertTrue(usesAudioIndex(root), "Recording retention should find its responses through the audio GIN index: $plan")
            assertEquals(1, chapterRepository.getAudioByEdition("API_BIBLE", "recording").flatMap { it.audio.orEmpty() }.size)
        }
    }

    @Test
    fun `recording retention spans configurations and expiry clears private metadata`() {
        val (bible, audio) = registeredAudio()
        request {
            val source = assertNotNull(sources.getById(audio.id))
            val otherConfiguration = service.saveConfiguration(BibleProviderConfigurationInput("Other", "API_BIBLE", true, apiKey = "other-key"))
            val other = sources.add(source.copy(id = UUID.random(), configurationId = otherConfiguration.id))
            val now = OffsetDateTime.now()
            for (index in 1..53) {
                if (index > 2) chapterRepository.add(bosca.content.metadata.model.BibleChapter(bible.metadataId, bible.version, bible.variant, "JHN", "JHN.$index", null, index))
                val selected = if (index % 2 == 0) source else other
                val response = bosca.content.metadata.model.BibleChapterAudio(UUID.random(), selected.id, selected.version, "JHN.$index",
                    BibleAudioContent("audio/mpeg", 10), attribution, "token-$index", now.minusMinutes(index.toLong()), now.plusDays(14))
                chapterRepository.updateAudioContent(bible.metadataId, bible.version, bible.variant, response.usfm, selected.id, selected.version,
                    json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(bosca.content.metadata.model.BibleChapterAudio.serializer()), listOf(response)))
            }
            assertEquals(3, bibles.limitAudioChapterContent("API_BIBLE", "recording"))
            val retained = chapterRepository.getAudioByEdition("API_BIBLE", "recording").flatMap { it.audio.orEmpty() }
            assertEquals(50, retained.size)
            assertEquals((1..50).map { "JHN.$it" }.toSet(), retained.map { it.usfm }.toSet())
            val expired = retained.first().copy(expires = now.minusSeconds(1))
            chapterRepository.updateAudioContent(bible.metadataId, bible.version, bible.variant, expired.usfm, expired.sourceId, expired.sourceVersion,
                json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(bosca.content.metadata.model.BibleChapterAudio.serializer()), listOf(expired)))
            assertEquals(1, bibles.removeExpiredChapterContent())
            assertNull(bibles.getAudioFumsToken(bible.metadataId, bible.version, bible.variant, expired.usfm, expired.sourceId))
        }
    }

    @Test
    fun `generated audio jobs clean orphaned objects and trim retained responses through owning services`() {
        val (bible, audio) = registeredAudio()
        audioResponse()
        val stored = request {
            bibles.getAudioChapter(bible, audio.id, "JHN.1")
            chapterRepository.getAudioBySource(audio.id).flatMap { it.audio.orEmpty() }.single()
        }
        assertTrue(objects.containsKey("bible/audio/${stored.id}"))
        request {
            val limit = provideProvider<JobConfigurationEnqueuer>("limit-bible-audio-chapters")
            assertTrue(limit.exists)
            val prepared = limit.get().prepare(JsonObject(mapOf("provider" to kotlinx.serialization.json.JsonPrimitive("API_BIBLE"),
                "providerBibleId" to kotlinx.serialization.json.JsonPrimitive("recording"))))
            withContext(cleanupQueue.asCoroutineContext(prepared)) { prepared.newExecutor().execute() }
            assertEquals(1, chapterRepository.getAudioBySource(audio.id).flatMap { it.audio.orEmpty() }.size)
            val current = assertNotNull(metadata.getById(bible.metadataId))
            metadata.delete(current)
            val delete = provideProvider<JobConfigurationEnqueuer>("delete-bible-audio")
            assertTrue(delete.exists)
            val job = delete.get().prepare(JsonObject(mapOf("id" to kotlinx.serialization.json.JsonPrimitive(stored.id.toString()))))
            withContext(cleanupQueue.asCoroutineContext(job)) {
                job.newExecutor().execute()
                job.newExecutor().execute()
            }
        }
        assertFalse(objects.containsKey("bible/audio/${stored.id}"))
        coVerify(exactly = 1) { cleanupQueue.enqueueLater(any(), kotlin.time.Duration.parse("14d") + kotlin.time.Duration.parse("5m")) }
    }

    @Test
    fun `replacing Bible references evicts audio responses while leaving their source attached`() {
        val (bible, audio) = registeredAudio()
        audioResponse()
        request {
            bibles.getAudioChapter(bible, audio.id, "JHN.1")
            bibles.setBible(bible.metadataId, bible.version, remote.toInput(true, bible.variant))
            assertNotNull(sources.getById(audio.id))
            assertTrue(chapterRepository.getAudioBySource(audio.id).isEmpty())
            assertNull(bibles.getAudioFumsToken(bible.metadataId, bible.version, bible.variant, "JHN.1", audio.id))
            bibles.getAudioChapter(bible, audio.id, "JHN.1")
        }
        coVerify(exactly = 2) { provider.downloadAudioChapter(any(), any(), any(), any()) }
    }

    private fun registeredAudio(uses: Set<BibleContentUse> = setOf(BibleContentUse.PLAYBACK)) = request {
        val bible = service.addBible(input())
        audioCatalog()
        bible to service.addAudio(bible, AddProviderAudioInput(configurationId, "recording", uses))
    }

    @Test
    fun `a variant disabled during download cannot repopulate the recording cache`() {
        val (bible, _) = registeredAudio()
        val (variant, audio) = request {
            val variant = bible.copy(variant = "reader", defaultVariant = false)
            bibles.setBible(variant.metadataId, variant.version, remote.toInput(false, variant.variant))
            variant to service.addAudio(variant, AddProviderAudioInput(configurationId, "recording", setOf(BibleContentUse.PLAYBACK)))
        }
        val bytes = "recording bytes".toByteArray()
        coEvery { provider.downloadAudioChapter(configurationId, "recording", "JHN.1", any()) } coAnswers {
            arg<java.io.OutputStream>(3).write(bytes)
            request { bibles.setVariantEnabled(variant.metadataId, variant.version, variant.variant, false) }
            BibleProviderResponse(BibleAudioContent("audio/mpeg", bytes.size.toLong()), attribution, fumsToken = "token")
        }
        request {
            val playback = bibles.getAudioPlayback(variant, audio.id, "JHN.1")
            assertEquals(bytes.toList(), playback.openStream().use { it.readBytes().toList() })
        }
        val disabled = request { bibles.getVariants(variant.metadataId, variant.version).single { it.variant == variant.variant } }
        assertFalse(disabled.enabled)
        request {
            assertTrue(chapterRepository.getAudioBySource(audio.id).isEmpty())
            assertFailsWith<IllegalStateException> { bibles.getAudioChapter(disabled, audio.id, "JHN.1") }
            assertFailsWith<IllegalStateException> { bibles.getAudioChapter(variant, audio.id, "JHN.1") }
        }
    }

    @Test
    fun `chapter playback URLs reject retired recordings instead of serving a replacement`() {
        val (bible, audio) = registeredAudio()
        val original = "original recording".toByteArray()
        val originalContent = BibleAudioContent("audio/mpeg", original.size.toLong(), listOf(BibleAudioTimecode("JHN.1.1", 0.0, 1.5)))
        val originalAttribution = BibleAttribution("Original recording copyright")
        coEvery { provider.downloadAudioChapter(configurationId, "recording", "JHN.1", any()) } coAnswers {
            arg<java.io.OutputStream>(3).write(original)
            BibleProviderResponse(originalContent, originalAttribution, fumsToken = "original-token")
        }
        val description = request { bibles.getAudioChapter(bible, audio.id, "JHN.1") }
        request {
            val playback = bibles.getAudioPlayback(bible, audio.id, "JHN.1", recordingId(description.url))
            assertEquals(originalContent, playback.content)
            assertEquals(originalAttribution, description.attribution)
            assertEquals(original.toList(), playback.openStream().use { it.readBytes().toList() })
        }
        request { service.refresh(audio.id) }
        val replacement = "replacement recording with different timing".toByteArray()
        coEvery { provider.downloadAudioChapter(configurationId, "recording", "JHN.1", any()) } coAnswers {
            arg<java.io.OutputStream>(3).write(replacement)
            BibleProviderResponse(BibleAudioContent("audio/ogg", replacement.size.toLong(), listOf(BibleAudioTimecode("JHN.1.1", 5.0, 10.0))),
                attribution, fumsToken = "replacement-token")
        }
        val uri = java.net.URI(description.url)
        val query = uri.rawQuery.split('&').associate {
            val name = it.substringBefore('=')
            name to java.net.URLDecoder.decode(it.substringAfter('='), java.nio.charset.StandardCharsets.UTF_8)
        }
        request {
            val target = assertNotNull(bibles.getBible(UUID.parse(query.getValue("id")), query.getValue("version").toInt(), query.getValue("variant")))
            assertFailsWith<bosca.storage.service.ObjectNotFoundException> {
                bibles.getAudioPlayback(target, UUID.parse(query.getValue("audioId")), query.getValue("usfm"), UUID.parse(query.getValue("recordingId")))
            }
        }
        coVerify(exactly = 1) { provider.downloadAudioChapter(any(), any(), any(), any()) }
        val updated = request { bibles.getAudioChapter(bible, audio.id, "JHN.1") }
        assertEquals("audio/ogg", updated.content.contentType)
        assertNotEquals(description.url, updated.url)
        request {
            assertFailsWith<bosca.storage.service.ObjectNotFoundException> {
                bibles.getAudioPlayback(bible, audio.id, "JHN.1", recordingId(description.url))
            }
            val playback = bibles.getAudioPlayback(bible, audio.id, "JHN.1", recordingId(updated.url))
            assertEquals(updated.content, playback.content)
            assertEquals(replacement.toList(), playback.openStream().use { it.readBytes().toList() })
        }
        coVerify(exactly = 2) { provider.downloadAudioChapter(any(), any(), any(), any()) }
    }

    @Test
    fun `recording identifiers cannot read media from another attached source`() {
        val (bible, audio) = registeredAudio()
        audioResponse()
        val original = request { bibles.getAudioChapter(bible, audio.id, "JHN.1") }
        val (otherBible, otherAudio) = registeredAudio()
        request {
            assertFailsWith<bosca.storage.service.ObjectNotFoundException> {
                bibles.getAudioPlayback(otherBible, otherAudio.id, "JHN.1", recordingId(original.url))
            }
            assertFailsWith<IllegalArgumentException> {
                bibles.getAudioPlayback(otherBible, audio.id, "JHN.1", recordingId(original.url))
            }
        }
        coVerify(exactly = 1) { provider.downloadAudioChapter(any(), any(), any(), any()) }
    }

    private fun recordingId(url: String): UUID = UUID.parse(java.net.URI(url).rawQuery.split('&')
        .single { it.startsWith("recordingId=") }.substringAfter('='))

    private fun audioResponse(): ByteArray {
        val bytes = "recording bytes".toByteArray()
        coEvery { provider.downloadAudioChapter(configurationId, "recording", any(), any()) } coAnswers {
            assertFalse(connection().inTransaction)
            arg<java.io.OutputStream>(3).write(bytes)
            BibleProviderResponse(BibleAudioContent("audio/mpeg", bytes.size.toLong()), attribution, fumsToken = "private-audio-token")
        }
        return bytes
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
        val reader = BibleServiceImpl(BibleRepositoryImpl(), BibleBookRepositoryImpl(), BibleChapterRepositoryImpl(), BibleLanguageRepositoryImpl(), sources, factory, storage, json, application)
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
            scalar("UUID", ExtendedScalars.Uuid)
            scalar("Long", ExtendedScalars.Long)
            type("Query") { field("bible") { bible } }
            type(BibleControllerDispatcher(BibleController(bibles, metadata, mockk())).type)
            type(BibleChapterControllerDispatcher(BibleChapterController(bibles)).type)
            type(BibleAttributionControllerDispatcher(BibleAttributionController()).type)
            type(BibleReferenceControllerDispatcher(BibleReferenceController()).type)
            type(FindBibleResultControllerDispatcher(FindBibleResultController()).type)
        }.build()
        val graphql = GraphQL(ExecutableSchema.fromSdl("scalar JSON\nscalar UUID\nscalar Long\ntype Query { bible: Bible! }\n$sdl", wiring))
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

    private suspend fun providerGraphQL(): GraphQL {
        provides<ConnectionPool> { pool }
        provides<Tracer> { GlobalOpenTelemetry.getTracer("bible-provider-admin-integration") }
        provides<GroupEvaluator>(overrideExisting = true) { GroupEvaluator(mockk()) }
        val wiring = RuntimeWiringBuilder().apply {
            scalar("JSON", ExtendedScalars.Json)
            scalar("UUID", ExtendedScalars.Uuid)
            scalar("Long", ExtendedScalars.Long)
            scalar("DateTime", bosca.graphql.scalars.DateTime.Type)
            type("Query") { field("providers") { BibleProviders } }
            type("Mutation") { field("providers") { BibleProvidersMutation } }
            type(ProviderRegistry.get<BibleProvidersControllerDispatcher>().type)
            type(ProviderRegistry.get<BibleProvidersMutationControllerDispatcher>().type)
            type(ProviderRegistry.get<BibleProviderConfigurationControllerDispatcher>().type)
            type(ProviderRegistry.get<BibleSourceControllerDispatcher>().type)
            type(ProviderRegistry.get<BibleCatalogControllerDispatcher>().type)
            type(ProviderRegistry.get<BibleLanguageControllerDispatcher>().type)
            type(ProviderRegistry.get<BibleAttributionControllerDispatcher>().type)
            type(ProviderRegistry.get<BibleAudioControllerDispatcher>().type)
            type(BibleControllerDispatcher(BibleController(bibles, metadata, mockk())).type)
        }.build()
        val schema = listOf("bible", "bible-providers").joinToString("\n") { name ->
            assertNotNull(javaClass.getResourceAsStream("/graphql/metadata/$name.graphqls")).bufferedReader().use { it.readText() }
        }
        return GraphQL(ExecutableSchema.fromSdl("""
            scalar JSON
            scalar UUID
            scalar Long
            scalar DateTime
            type Query { providers: BibleProviders! }
            type Mutation { providers: BibleProvidersMutation! }
            type Content { unused: Boolean }
            type ContentMutation { unused: Boolean }
            $schema
        """.trimIndent(), wiring))
    }

    @Test
    fun `generated administrator GraphQL preserves encrypted keys and manages real text and audio sources`() {
        val graphql = request { providerGraphQL() }
        val authentication = ImpersonatedAuthenticationContext(Principal(id = UUID.random()), listOf(
            Group(name = "administrators", description = "Administrators", type = GroupType.SYSTEM),
        ))
        val context = GraphQLContext(mapOf("authenticationContext" to authentication))
        fun execute(query: String) = request { graphql.execute(GraphQLRequest(query, context = context)) }
        val updated = execute("""
            mutation { providers { saveConfiguration(input: {
                id: "$configurationId", name: "Renamed", provider: "API_BIBLE", enabled: true
            }) { id name provider enabled hasApiKey } } }
        """.trimIndent())
        assertTrue(updated.errors.isEmpty(), updated.errors.toString())
        assertFalse(updated.data.toString().contains("server-secret"))
        request {
            val settings = assertNotNull(ProviderRegistry.get<ConfigurationService>().getValueAs<BibleProviderSettings>(configurationId, json))
            assertEquals("server-secret", settings.apiKey)
            assertEquals("Renamed", settings.name)
        }
        val added = execute("""
            mutation { providers { addBible(input: { configurationId: "$configurationId", providerBibleId: "edition",
                variant: "study", allowedUses: [DISPLAY, AI_CONTEXT] }) { metadataId version variant name } } }
        """.trimIndent())
        assertTrue(added.errors.isEmpty(), added.errors.toString())
        val bibleData = assertNotNull(added.data).jsonObject.getValue("providers").jsonObject.getValue("addBible").jsonObject
        val metadataId = UUID.parse(bibleData.getValue("metadataId").jsonPrimitive.content)
        val source = request { service.getSources(configurationId).single() }
        assertEquals(metadataId, source.metadataId)
        assertEquals(setOf(BibleContentUse.DISPLAY, BibleContentUse.AI_CONTEXT), source.allowedUses)
        request {
            val stored = assertNotNull(metadata.getById(metadataId, 1))
            assertFalse(stored.isPublished)
            assertFalse(stored.public)
            assertEquals(0, countRetained())
        }
        request { audioCatalog() }
        val recording = execute("""
            mutation { providers { addAudio(bibleId: "$metadataId", version: 1, variant: "study",
                input: { configurationId: "$configurationId", providerBibleId: "recording", allowedUses: [PLAYBACK] }) {
                id name attribution { copyright }
            } } }
        """.trimIndent())
        assertTrue(recording.errors.isEmpty(), recording.errors.toString())
        val listing = execute("""
            { providers { configurations { id name hasApiKey }
                sources(configurationId: "$configurationId") { id metadataId metadataVersion variant kind version allowedUses created modified }
            } }
        """.trimIndent())
        assertTrue(listing.errors.isEmpty(), listing.errors.toString())
        assertEquals(2, assertNotNull(listing.data).jsonObject.getValue("providers").jsonObject.getValue("sources").jsonArray.size)
        assertFalse(listing.data.toString().contains("server-secret"))
        val blocked = execute("mutation { providers { removeConfiguration(configurationId: \"$configurationId\") } }")
        assertTrue(blocked.errors.isNotEmpty())
        assertEquals(2, request { service.getSources(configurationId).size })
        val refreshed = execute("mutation { providers { refresh(sourceId: \"${source.id}\") } }")
        assertTrue(refreshed.errors.isEmpty(), refreshed.errors.toString())
        val stale = execute("mutation { providers { removeSource(sourceId: \"${source.id}\", expectedVersion: 0) } }")
        assertTrue(stale.errors.isNotEmpty())
        val removed = execute("mutation { providers { removeSource(sourceId: \"${source.id}\", expectedVersion: 1) } }")
        assertTrue(removed.errors.isEmpty(), removed.errors.toString())
        val purged = execute("mutation { providers { removeConfiguration(configurationId: \"$configurationId\", purgeSources: true) } }")
        assertTrue(purged.errors.isEmpty(), purged.errors.toString())
        request { assertTrue(service.getConfigurations().isEmpty()) }
        coVerify(exactly = 0) { provider.getChapter(any(), any(), any()); provider.downloadAudioChapter(any(), any(), any(), any()) }
    }

    @Test
    fun `generated GraphQL with real group authorization rejects non-administrators without changing storage`() {
        val graphql = request { providerGraphQL() }
        for (authentication in listOf(
            AuthenticationContext(null, null),
            ImpersonatedAuthenticationContext(Principal(id = UUID.random()), emptyList()),
        )) {
            val context = GraphQLContext(mapOf("authenticationContext" to authentication))
            for (query in listOf(
                "{ providers { configurations { id name hasApiKey } } }",
                "mutation { providers { removeConfiguration(configurationId: \"$configurationId\", purgeSources: true) } }",
            )) {
                val result = request { graphql.execute(GraphQLRequest(query, context = context)) }
                assertTrue(result.errors.isNotEmpty())
                assertFalse(result.data.toString().contains("server-secret"))
            }
        }
        request { assertEquals(1, service.getConfigurations().size); assertEquals(0, count("metadata")) }
        coVerify(exactly = 0) { provider.getBible(any(), any(), any()); provider.getChapter(any(), any(), any()) }
    }

    @Test
    fun `generated GraphQL excludes deleted audio targets and rejects registration without persisting a source`() {
        val bible = request { service.addBible(input()) }
        request {
            bosca.db.transaction { metadata.markDeleted(bible.metadataId) }
            audioCatalog()
        }
        val graphql = request { providerGraphQL() }
        val authentication = ImpersonatedAuthenticationContext(Principal(id = UUID.random()), listOf(
            Group(name = "administrators", description = "Administrators", type = GroupType.SYSTEM),
        ))
        val context = GraphQLContext(mapOf("authenticationContext" to authentication))
        val targets = request { graphql.execute(GraphQLRequest(
            "{ providers { audioTargets(configurationId: \"$configurationId\", providerBibleId: \"recording\") { metadataId version variant } } }",
            context = context,
        )) }
        assertTrue(targets.errors.isEmpty(), targets.errors.toString())
        assertTrue(assertNotNull(targets.data).jsonObject.getValue("providers").jsonObject.getValue("audioTargets").jsonArray.isEmpty())
        val registered = request { graphql.execute(GraphQLRequest(
            """mutation { providers { addAudio(bibleId: "${bible.metadataId}", version: ${bible.version}, variant: "${bible.variant}",
                input: { configurationId: "$configurationId", providerBibleId: "recording", allowedUses: [PLAYBACK] }) { id name }
            } }""".trimIndent(),
            context = context,
        )) }
        assertTrue(registered.errors.isNotEmpty(), registered.errors.toString())
        request {
            assertEquals(1, service.getSources(configurationId).size)
            assertTrue(sources.getAudio(bible.metadataId, bible.version, bible.variant).isEmpty())
        }
        coVerify(exactly = 1) { provider.getCatalog(configurationId, BibleSourceKind.AUDIO, null, 0, Int.MAX_VALUE) }
        coVerify(exactly = 1) { provider.getBible(configurationId, "recording", BibleSourceKind.AUDIO) }
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
