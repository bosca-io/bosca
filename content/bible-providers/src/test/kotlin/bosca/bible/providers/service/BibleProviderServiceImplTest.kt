@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.bible.providers.service

import bosca.bible.Bible as SharedBible
import bosca.bible.BibleAttribution
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
import bosca.bible.components.Text
import bosca.bible.providers.configuration.BibleProvidersMigration
import bosca.bible.providers.model.AddProviderBibleInput
import bosca.bible.providers.model.AddProviderAudioInput
import bosca.bible.providers.model.BibleCatalog
import bosca.bible.providers.model.BibleChapterCache
import bosca.bible.providers.model.BibleProviderConfigurationInput
import bosca.bible.providers.model.BibleProviderResponse
import bosca.bible.providers.model.BibleSourceKind
import bosca.bible.providers.model.BibleSource
import bosca.bible.providers.repository.BibleChapterCacheRepository
import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.cache.RequestCacheSerializerImpl
import bosca.cache.redis.RedisCacheManager
import bosca.cache.redis.RedisCacheScripts
import bosca.cache.withRequestCache
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
import bosca.di.provides
import bosca.events.DisabledEventManagerFilter
import bosca.events.EventManager
import bosca.events.asCoroutineContext
import bosca.security.encryption.EncryptionService
import bosca.security.encryption.EncryptionServiceImpl
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
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

        @BeforeClass
        @JvmStatic
        fun start() = runBlocking {
            postgres.start()
            valkey.start()
            pool = ConnectionPool(ConnectionFactoryImpl(ConnectionConfig(
                postgres.jdbcUrl, postgres.username, postgres.password,
            ), key = "bible-registration-test"))
            val connections = valkey.newConnectionPool()
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
        provides<RequestCacheSerializer> { RequestCacheSerializerImpl(json) }
        provides<EncryptionService> { EncryptionServiceImpl(application) }
        ConfigurationProviderRegistrar().register()
        BibleProvidersProviderRegistrar().register()
        provides<BibleProvider>(name = "API_BIBLE", overrideExisting = true) { provider }
        sources = ProviderRegistry.get<BibleSourceService>()
        slugs = SlugServiceImpl(SlugRepositoryImpl())
        bibles = BibleServiceImpl(BibleRepositoryImpl(), BibleBookRepositoryImpl(), BibleChapterRepositoryImpl(), BibleLanguageRepositoryImpl())
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
        assertEquals(JsonNull, bibles.getChapter(book, "JHN.3").components)
        val updated = assertNotNull(sources.getById(source.id))
        assertEquals(1L, updated.version)
        assertEquals(source.allowedUses, updated.allowedUses)
        assertEquals(source.providerBibleId, updated.providerBibleId)
        assertEquals("Updated copyright", updated.attribution.copyright)
        assertEquals(0, count("bible_providers.chapters"))
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

    private suspend fun cache(source: BibleSource, expired: Boolean = false) {
        val created = OffsetDateTime.now().minusHours(1)
        ProviderRegistry.get<BibleChapterCacheRepository>().put(BibleChapterCache(
            source.id, "API_BIBLE", "JHN.1", Chapter(Reference("JHN.1"), null), attribution, 1, "response-token",
            created, if (expired) created.plusMinutes(1) else created.plusDays(1),
        ))
    }

    private fun input() = AddProviderBibleInput(configurationId, "edition", "study",
        setOf(BibleContentUse.DISPLAY, BibleContentUse.AI_CONTEXT))

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
