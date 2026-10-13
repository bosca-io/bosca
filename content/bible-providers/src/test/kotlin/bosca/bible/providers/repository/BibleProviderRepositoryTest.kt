@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.bible.providers.repository

import bosca.bible.BibleAttribution
import bosca.bible.BibleContentUse
import bosca.bible.bibleJson
import bosca.bible.Chapter
import bosca.bible.Reference
import bosca.bible.components.ComponentContainer
import bosca.bible.components.ContainerType
import bosca.bible.components.Text
import bosca.bible.components.VerseEnd
import bosca.bible.components.VerseStart
import bosca.bible.style.StyleReference
import bosca.bible.providers.configuration.BibleProvidersMigration
import bosca.content.metadata.model.BibleChapter
import bosca.content.metadata.model.toJson
import bosca.content.metadata.repository.BibleChapterRepositoryImpl
import bosca.bible.providers.model.BibleSource
import bosca.bible.providers.model.BibleSourceKind
import bosca.bible.providers.service.BibleProvider
import bosca.configuration.service.ConfigurationService
import io.mockk.mockk
import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.connection
import bosca.db.transaction
import bosca.db.migrations.CoreMigration
import bosca.db.migrations.FlywayMigration
import bosca.db.migrations.Migration
import bosca.di.BibleProvidersProviderRegistrar
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.serialization.OffsetDateTime
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.test.resources.SharedPostgreSQLContainer
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import org.junit.AfterClass
import org.junit.BeforeClass
import java.sql.SQLException
import kotlin.test.BeforeTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.uuid.toJavaUuid

class BibleProviderRepositoryTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17")
            .withDatabaseName("bible_providers")
        private lateinit var pool: ConnectionPool

        @BeforeClass
        @JvmStatic
        fun start() = runBlocking {
            postgres.start()
            pool = ConnectionPool(ConnectionFactoryImpl(ConnectionConfig(
                url = postgres.jdbcUrl, user = postgres.username, password = postgres.password,
            ), key = "bible-providers-test"))
            FlywayMigration(pool).migrate(listOf(CoreMigration(), BibleProvidersMigration()))
        }

        @AfterClass
        @JvmStatic
        fun stop() = runBlocking {
            pool.close()
            postgres.stop()
        }
    }

    private val json = Json {
        serializersModule = SerializersModule {
            contextual(UUIDSerializer())
            contextual(OffsetDateTimeSerializer())
        }
    }
    private val repository = BibleSourceRepositoryImpl()
    private val chapters = BibleChapterRepositoryImpl()
    private val metadataId = UUID.random()
    private val configurationId = UUID.random()

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { json }
        withDb {
            connection().useStatement("insert into configurations (id, key, description) values (?, ?, 'Bible test')") {
                it.setObject(1, configurationId.toJavaUuid())
                it.setString(2, "bible-test:$configurationId")
                it.executeUpdate()
            }
            connection().useStatement("insert into metadata (id, name, content_type, version) values (?, 'Bible test', 'bible', 1)") {
                it.setObject(1, metadataId.toJavaUuid())
                it.executeUpdate()
            }
            for ((version, variant) in listOf(1 to "default", 1 to "study", 2 to "default")) {
                connection().useStatement("""
                    insert into bibles (metadata_id, version, variant, system_id, name, name_local,
                        description, abbreviation, abbreviation_local)
                    values (?, ?, ?, 'system', 'Bible', 'Bible', '', 'B', 'B')
                """.trimIndent()) {
                    it.setObject(1, metadataId.toJavaUuid())
                    it.setInt(2, version)
                    it.setString(3, variant)
                    it.executeUpdate()
                }
                connection().useStatement("insert into bible_books (metadata_id, version, variant, usfm, name_short, name_long, abbreviation, sort) values (?, ?, ?, 'JHN', 'Jn', 'John', 'JHN', 0)") {
                    it.setObject(1, metadataId.toJavaUuid()); it.setInt(2, version); it.setString(3, variant); it.executeUpdate()
                }
                connection().useStatement("insert into bible_chapters (metadata_id, version, variant, book_usfm, usfm, sort) values (?, ?, ?, 'JHN', 'JHN.1', 0)") {
                    it.setObject(1, metadataId.toJavaUuid()); it.setInt(2, version); it.setString(3, variant); it.executeUpdate()
                }
            }
        }
    }

    @AfterTest
    fun cleanup() = withDb {
        connection().useStatement("delete from configurations where id = ?") {
            it.setObject(1, configurationId.toJavaUuid())
            it.executeUpdate()
        }
        connection().useStatement("delete from metadata where id = ?") {
            it.setObject(1, metadataId.toJavaUuid())
            it.executeUpdate()
        }
        Unit
    }

    @Test
    fun `source round trip retains exact identity attribution and reviewed uses`() = withDb {
        val source = source().copy(metadataVersion = 2)
        repository.add(source)

        assertEquals(source, repository.get(source.id))
        assertEquals(source, repository.getText(metadataId, 2, "default"))
        assertNull(repository.getText(metadataId, 1, "default"))
        assertNull(repository.getText(metadataId, 2, "study"))
        assertEquals(listOf(source), repository.getByConfiguration(configurationId))
    }

    @Test
    fun `one active text source is enforced while variants and audio sources stay independent`() = withDb {
        repository.add(source())
        val duplicate = assertFailsWith<SQLException> { repository.add(source()) }
        assertEquals("23505", duplicate.sqlState)
        repository.add(source().copy(variant = "study"))
        val firstAudio = source(BibleSourceKind.AUDIO)
        val secondAudio = source(BibleSourceKind.AUDIO)
        repository.add(firstAudio)
        repository.add(secondAudio)

        assertEquals(setOf(firstAudio.id, secondAudio.id), repository.getAudio(metadataId, 1, "default").map { it.id }.toSet())
        assertEquals(4, repository.getByConfiguration(configurationId).size)
    }

    @Test
    fun `soft deletion checks the version and permits a replacement text source`() = withDb {
        val source = source()
        repository.add(source)
        assertNull(repository.remove(source.id, 1))
        assertNotNull(repository.get(source.id))

        val deleted = assertNotNull(repository.remove(source.id, 0))

        assertEquals(1L, deleted.version)
        assertNotNull(deleted.deletedAt)
        assertNull(repository.get(source.id))
        assertNull(repository.getText(metadataId, 1, "default"))
        assertEquals(emptyList(), repository.getByConfiguration(configurationId))
        assertNull(repository.remove(source.id, 1))
        val replacement = source()
        repository.add(replacement)
        assertEquals(replacement.id, repository.getText(metadataId, 1, "default")?.id)
    }

    @Test
    fun `refresh checks the version and preserves source identity and reviewed uses`() = withDb {
        val source = source()
        repository.add(source)
        val changed = source.copy(name = "Updated Bible", nameLocal = "Local name", abbreviation = "UB",
            attribution = BibleAttribution("Updated copyright"))

        val updated = assertNotNull(repository.update(changed))

        assertEquals(1L, updated.version)
        assertEquals(changed.name, updated.name)
        assertEquals(changed.nameLocal, updated.nameLocal)
        assertEquals(changed.abbreviation, updated.abbreviation)
        assertEquals(changed.attribution, updated.attribution)
        assertEquals(source.allowedUses, updated.allowedUses)
        assertEquals(source.configurationId, updated.configurationId)
        assertNull(repository.update(changed))
    }

    @Test
    fun `generated registrar provides named provider migration and working repositories`() = withDb {
        BibleProvidersProviderRegistrar().register()
        provides<ConfigurationService> { mockk() }
        val provider = ProviderRegistry.get<BibleProvider>("API_BIBLE")
        assertEquals("API_BIBLE", provider.key)
        assertSame(provider, ProviderRegistry.get<BibleProvider>("API_BIBLE"))
        val migration = ProviderRegistry.get<Migration>("bible-providers-migrations")
        assertEquals("bible_providers", migration.schema)
        val sources = ProviderRegistry.get<BibleSourceRepository>()
        val caches = chapters
        val source = assertNotNull(sources.add(source()))
        chapters.setSource(metadataId, 1, "default", source.id)
        val cache = cache(source)
        caches.putContent(cache)
        assertEquals(source, sources.get(source.id))
        assertEquals(cache.fumsToken, caches.getContent(source.id, cache.usfm, assertNotNull(cache.created))?.fumsToken)
    }

    @Test
    fun `source requires an existing exact Bible variant`() = withDb {
        val error = assertFailsWith<SQLException> { repository.add(source().copy(variant = "missing")) }
        assertEquals("23503", error.sqlState)
    }

    @Test
    fun `source survives replacement of Bible metadata within a transaction`() = withDb {
        val source = source()
        repository.add(source)
        transaction {
            connection().useStatement("delete from bibles where metadata_id = ? and version = 1 and variant = 'default'") {
                it.setObject(1, metadataId.toJavaUuid())
                it.executeUpdate()
            }
            connection().useStatement("""
                insert into bibles (metadata_id, version, variant, system_id, name, name_local,
                    description, abbreviation, abbreviation_local)
                values (?, 1, 'default', 'system', 'Refreshed', 'Refreshed', '', 'B', 'B')
            """.trimIndent()) {
                it.setObject(1, metadataId.toJavaUuid())
                it.executeUpdate()
            }
        }
        assertEquals(source, repository.getText(metadataId, 1, "default"))
    }

    @Test
    fun `cached chapter retains the exact response and Bible identity`() = withDb {
        val source = assertNotNull(repository.add(source().copy(metadataVersion = 2)))
        val cache = cache(source)
        chapters.putContent(cache)

        val stored = assertNotNull(chapters.getContent(source.id, "JHN.1", assertNotNull(cache.created)))
        assertEquals(cache.components, stored.components)
        assertEquals(cache.attribution, stored.attribution)
        assertEquals(cache.verseCount, stored.verseCount)
        assertEquals(cache.fumsToken, stored.fumsToken)
        assertEquals(cache.created, stored.created)
        assertEquals(cache.expires, stored.expires)
        assertEquals(cache.fumsToken, chapters.getFumsToken(metadataId, 2, "default", "JHN.1", assertNotNull(cache.created)))
        assertNull(chapters.getFumsToken(metadataId, 1, "default", "JHN.1", assertNotNull(cache.created)))
        assertNull(chapters.getFumsToken(metadataId, 2, "study", "JHN.1", assertNotNull(cache.created)))
        assertNull(chapters.getFumsToken(metadataId, 2, "default", "JHN.2", assertNotNull(cache.created)))
        assertFalse(stored.toString().contains("response-token"))
    }

    @Test
    fun `replacing a cached response replaces its content token attribution and expiry together`() = withDb {
        val source = assertNotNull(repository.add(source()))
        val cache = cache(source)
        chapters.putContent(cache)
        val replacement = cache.copy(components = Text("Updated text", null).toJson(),
            attribution = null, verseCount = 2, fumsToken = "new-response-token",
            created = assertNotNull(cache.created).plusDays(1), expires = assertNotNull(cache.expires).plusDays(1))

        chapters.putContent(replacement)

        val stored = assertNotNull(chapters.getContent(source.id, "JHN.1", assertNotNull(replacement.created)))
        assertEquals("new-response-token", stored.fumsToken)
        assertNull(stored.attribution)
        assertEquals(2, stored.verseCount)
        assertEquals(replacement.components, stored.components)
        assertEquals(replacement.created, stored.created)
        assertEquals(replacement.expires, stored.expires)
    }

    @Test
    fun `cache reads never extend expiry and expired tokens cannot be returned`() = withDb {
        val source = assertNotNull(repository.add(source()))
        val cache = cache(source)
        chapters.putContent(cache)

        assertEquals(cache.expires, chapters.getContent(source.id, "JHN.1", assertNotNull(cache.expires).minusSeconds(1))?.expires)
        assertEquals(cache.expires, chapters.getContent(source.id, "JHN.1", assertNotNull(cache.expires).minusNanos(1000))?.expires)
        assertNull(chapters.getContent(source.id, "JHN.1", assertNotNull(cache.expires)))
        assertNull(chapters.getFumsToken(metadataId, 1, "default", "JHN.1", assertNotNull(cache.expires)))
        assertNull(chapters.getContent(source.id, "JHN.1", assertNotNull(cache.expires).plusDays(1)))
        assertEquals(1, chapters.removeExpired(assertNotNull(cache.expires)).size)
        assertEquals(0, chapters.removeExpired(assertNotNull(cache.expires)).size)
    }

    @Test
    fun `cache retention cannot exceed fourteen days or end before creation`() = withDb {
        val source = assertNotNull(repository.add(source()))
        val cache = cache(source)
        for (expires in listOf(assertNotNull(cache.created), assertNotNull(cache.created).minusDays(1), assertNotNull(cache.created).plusDays(14).plusSeconds(1))) {
            val error = assertFailsWith<SQLException> { chapters.putContent(cache.copy(expires = expires)) }
            assertEquals("23514", error.sqlState)
        }
        chapters.putContent(cache.copy(expires = assertNotNull(cache.created).plusDays(14)))
        assertNotNull(chapters.getContent(source.id, "JHN.1", assertNotNull(cache.created)))
        Unit
    }

    @Test
    fun `removed source hides its cached content and token until maintenance purges it`() = withDb {
        val source = assertNotNull(repository.add(source()))
        val cache = cache(source)
        chapters.putContent(cache)
        assertNotNull(repository.remove(source.id, 0))

        assertNull(chapters.getContent(source.id, "JHN.1", assertNotNull(cache.created)))
        assertNull(chapters.getFumsToken(metadataId, 1, "default", "JHN.1", assertNotNull(cache.created)))
        assertEquals(1, chapters.removeExpired(assertNotNull(cache.expires)).size)
    }

    @Test
    fun `deleting a Bible version removes its active and removed sources`() = withDb {
        val active = assertNotNull(repository.add(source().copy(metadataVersion = 2)))
        val removed = assertNotNull(repository.add(source(BibleSourceKind.AUDIO).copy(metadataVersion = 2)))
        assertNotNull(repository.remove(removed.id, removed.version))
        val other = assertNotNull(repository.add(source()))

        assertEquals(setOf(active.id, removed.id), repository.deleteByBible(metadataId, 2).map { it.id }.toSet())
        connection().useStatement("delete from bibles where metadata_id = ? and version = 2") {
            it.setObject(1, metadataId.toJavaUuid())
            assertEquals(1, it.executeUpdate())
        }
        assertNull(repository.get(active.id))
        assertEquals(other, repository.get(other.id))
    }

    @Test
    fun `sources are not added to deleted metadata`() = withDb {
        connection().useStatement("update metadata set deleted = true where id = ?") {
            it.setObject(1, metadataId.toJavaUuid())
            it.executeUpdate()
        }
        assertNull(repository.add(source()))
    }

    @Test
    fun `retained responses may last fourteen days across a daylight saving change`() = withDb {
        val source = assertNotNull(repository.add(source()))
        val created = OffsetDateTime.parse("2026-03-01T12:00:00Z")
        val chapter = cache(source).copy(created = created, expires = created.plusDays(14))
        connection().useStatement("set time zone 'America/Chicago'") { it.execute() }
        try {
            assertEquals(1, chapters.putContent(chapter))
        } finally {
            connection().useStatement("reset time zone") { it.execute() }
        }
    }

    private suspend fun cache(source: BibleSource): BibleChapter {
        chapters.setSource(source.metadataId, source.metadataVersion, source.variant, source.id)
        return BibleChapter(
            metadataId = source.metadataId, version = source.metadataVersion, variant = source.variant,
            bookUsfm = "JHN", usfm = "JHN.1", sort = 0, sourceId = source.id, sourceVersion = source.version,
            components = ComponentContainer(ContainerType.PARAGRAPH, listOf(
                VerseStart(Reference("JHN.1.1")), Text("In the beginning", StyleReference("it")), VerseEnd(),
            ), StyleReference("p")).toJson(),
            attribution = BibleAttribution("Response copyright"), verseCount = 1, fumsToken = "response-token",
            created = OffsetDateTime.parse("2026-10-01T00:00:00Z"), expires = OffsetDateTime.parse("2026-10-15T00:00:00Z"),
        )
    }

    private fun source(kind: BibleSourceKind = BibleSourceKind.TEXT) = BibleSource(
        id = UUID.random(), configurationId = configurationId, provider = "API_BIBLE", providerBibleId = "edition",
        metadataId = metadataId, metadataVersion = 1, variant = "default", kind = kind,
        name = "Bible", nameLocal = "Local Bible", abbreviation = "B",
        attribution = BibleAttribution("Copyright", "Citation", "https://example.com"),
        allowedUses = if (kind == BibleSourceKind.AUDIO) setOf(BibleContentUse.PLAYBACK)
            else setOf(BibleContentUse.DISPLAY, BibleContentUse.AI_CONTEXT),
        created = OffsetDateTime.parse("2026-10-01T00:00:00Z"), modified = OffsetDateTime.parse("2026-10-01T00:00:00Z"),
    )

    private fun <T> withDb(block: suspend () -> T): T = runBlocking {
        val manager = pool.connection()
        try {
            withContext(manager.asCoroutineContext()) { block() }
        } finally {
            withContext(NonCancellable) { manager.release() }
        }
    }
}
