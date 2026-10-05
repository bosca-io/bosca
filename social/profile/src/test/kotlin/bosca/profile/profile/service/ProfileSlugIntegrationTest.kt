@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.profile.profile.service

import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.cache.RequestCacheSerializerImpl
import bosca.cache.withRequestCache
import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.connection
import bosca.db.migrations.CoreMigration
import bosca.db.migrations.FlywayMigration
import bosca.db.transaction
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.persona.repository.ProfileMigration
import bosca.profile.profile.events.ProfileCreatedEvent
import bosca.profile.profile.events.ProfileUpdatedEvent
import bosca.profile.profile.events.dispatch
import bosca.profile.profile.model.ProfileInput
import bosca.profile.profile.repository.ProfileRepositoryImpl
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.slug.model.Slug
import bosca.slug.repository.SlugRepositoryImpl
import bosca.slug.service.SlugService
import bosca.slug.service.SlugServiceImpl
import bosca.test.resources.SharedPostgreSQLContainer
import io.mockk.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import org.junit.AfterClass
import kotlin.test.*

class ProfileSlugIntegrationTest {
    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("bosca_profile_slug_test")
            withReuse(true)
            start()
        }
        private val pool = ConnectionPool(
            ConnectionFactoryImpl(
                ConnectionConfig(url = postgres.jdbcUrl, user = postgres.username, password = postgres.password, maxConnections = 3),
                key = "profile-slug-test",
            ),
        )
        private var schemaInitialized = false

        @AfterClass
        @JvmStatic
        fun shutdown() {
            runBlocking { pool.close() }
            postgres.stop()
        }
    }

    private val profiles = ProfileRepositoryImpl()
    private val slugs = SlugRepositoryImpl()
    private lateinit var slugService: SlugServiceImpl
    private lateinit var service: ProfileServiceImpl

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        val json = Json {
            serializersModule = SerializersModule {
                contextual(UUIDSerializer())
                contextual(OffsetDateTimeSerializer())
            }
        }
        provides<ConnectionPool> { pool }
        provides<Json> { json }
        provides<CacheManager> { mockk(relaxed = true) }
        provides<RequestCacheSerializer> { RequestCacheSerializerImpl(json) }
        mockkStatic("bosca.profile.profile.events.ProfileCreatedEventExtKt")
        mockkStatic("bosca.profile.profile.events.ProfileUpdatedEventExtKt")
        coEvery { any<ProfileCreatedEvent>().dispatch() } just runs
        coEvery { any<ProfileUpdatedEvent>().dispatch() } just runs
        if (!schemaInitialized) {
            runBlocking { FlywayMigration(pool).migrate(listOf(CoreMigration(), ProfileMigration())) }
            schemaInitialized = true
        }
        withDb { transaction { connection().useStatement("delete from public.profiles") { it.execute() } } }
        slugService = SlugServiceImpl(slugs)
        service = profileService(slugService)
    }

    @AfterTest
    fun teardown() {
        unmockkStatic("bosca.profile.profile.events.ProfileCreatedEventExtKt")
        unmockkStatic("bosca.profile.profile.events.ProfileUpdatedEventExtKt")
        ProviderRegistry.clear()
    }

    @Test
    fun `creating profiles with the same name persists one distinct slug each`() = withDb {
        val input = ProfileInput(name = "Example Family", visibility = ProfileVisibility.PUBLIC)
        val first = service.add(input, ProfileType.ORGANIZATION)
        val second = service.add(input, ProfileType.ORGANIZATION)

        assertEquals(listOf("example-family"), mappings(first.id))
        assertEquals(1, mappings(second.id).size)
        assertNotEquals(mappings(first.id), mappings(second.id))
    }

    @Test
    fun `creating an organization generates its slug from the name`() = withDb {
        val profile = service.add(ProfileInput(name = "Example Family", slug = "example", visibility = ProfileVisibility.PUBLIC), ProfileType.ORGANIZATION)

        assertEquals(listOf("example-family"), mappings(profile.id))
        assertEquals("example-family", slugService.getProfileSlug(profile.id))
    }

    @Test
    fun `changing a slug removes duplicate mappings and invalidates cached aliases`() = withDb {
        val profile = seed("Example", "old-name", "old-name123", "old-name124")
        for (alias in mappings(profile.id)) assertNotNull(slugService.get(alias))
        assertNotNull(slugService.getProfileSlug(profile.id))

        service.edit(profile.id, ProfileInput(name = profile.name, slug = "example", visibility = profile.visibility))

        assertEquals(listOf("example"), mappings(profile.id))
        assertEquals("example", slugService.getProfileSlug(profile.id))
        assertEquals(profile.id, slugService.get("example")?.profileId)
        assertNull(slugService.get("old-name"))
        assertNull(slugService.get("old-name123"))
        assertNull(slugService.get("old-name124"))
    }

    @Test
    fun `repeated explicit edits leave one exact slug and remove the previous URL`() = withDb {
        val profile = seed("Example", "old-name")
        val input = ProfileInput(name = profile.name, slug = "example", visibility = profile.visibility)

        repeat(2) { service.edit(profile.id, input) }

        assertEquals(listOf("example"), mappings(profile.id))
        assertNull(slugs.get("old-name"))
    }

    @Test
    fun `renaming without an explicit slug replaces the generated slug once`() = withDb {
        val profile = seed("Old Name", "old-name", "old-name123")

        service.edit(profile.id, ProfileInput(name = "Example Family", visibility = profile.visibility))

        assertEquals(listOf("example-family"), mappings(profile.id))
        assertNull(slugs.get("old-name"))
        assertNull(slugs.get("old-name123"))
    }

    @Test
    fun `an unavailable explicit slug saves the edit with one unique fallback`() = withDb {
        val owner = seed("Owner", "example")
        val profile = seed("Original Name", "original-name")

        assertNotNull(slugService.get("original-name"))
        service.edit(profile.id, ProfileInput(name = "Changed Name", slug = "example", visibility = profile.visibility))

        assertEquals("Changed Name", profiles.getById(profile.id)?.name)
        val fallback = mappings(profile.id).single()
        assertTrue(fallback.startsWith("example"))
        assertNotEquals("example", fallback)
        assertEquals(fallback, slugService.getProfileSlug(profile.id))
        assertEquals(profile.id, slugService.get(fallback)?.profileId)
        assertNull(slugService.get("original-name"))
        assertEquals(listOf("example"), mappings(owner.id))
    }

    @Test
    fun `creation uses the name when the supplied slug is already owned`() = withDb {
        val owner = seed("Owner", "example")
        val profile = service.add(ProfileInput(name = "Example Family", slug = "example", visibility = ProfileVisibility.PUBLIC), ProfileType.ORGANIZATION)

        assertEquals(listOf("example-family"), mappings(profile.id))
        assertEquals("example-family", slugService.getProfileSlug(profile.id))
        assertEquals(listOf("example"), mappings(owner.id))
    }

    @Test
    fun `a collision after the slug lookup rolls back to a savepoint and persists the UUID fallback`() = withDb {
        val owner = seed("Owner", "example")
        val profile = seed("Original Name", "original-name", "old-alias")
        val staleLookup = spyk(slugs)
        coEvery { staleLookup.get("example") } returns null
        val retrying = profileService(SlugServiceImpl(staleLookup))

        retrying.edit(profile.id, ProfileInput(name = "Example", visibility = profile.visibility))

        val fallback = "example-${profile.id.toString().take(8)}"
        assertEquals(listOf(fallback), mappings(profile.id))
        assertEquals("Example", profiles.getById(profile.id)?.name)
        assertEquals(listOf("example"), mappings(owner.id))
        assertEquals(fallback, slugService.getProfileSlug(profile.id))
        assertNull(slugService.get("original-name"))
        assertNull(slugService.get("old-alias"))
    }

    @Test
    fun `blank slug on creation generates a slug from the profile name`() = withDb {
        val profile = service.add(ProfileInput(name = "Example Family", slug = " ", visibility = ProfileVisibility.PUBLIC), ProfileType.ORGANIZATION)

        assertEquals(listOf("example-family"), mappings(profile.id))
    }

    @Test
    fun `an explicit current slug stays unchanged when the profile is renamed`() = withDb {
        val profile = seed("Original Name", "original-name")

        service.edit(profile.id, ProfileInput(name = "Changed Name", slug = "original-name", visibility = profile.visibility))

        assertEquals("Changed Name", profiles.getById(profile.id)?.name)
        assertEquals(listOf("original-name"), mappings(profile.id))
    }

    @Test
    fun `failed slug insertion restores the previous mapping`() = withDb {
        val profile = seed("Original Name", "original-name")
        val failing = spyk(slugService)
        coEvery { failing.add(any()) } throws IllegalStateException("write failed")

        assertFailsWith<IllegalStateException> {
            profileService(failing).edit(profile.id, ProfileInput(name = "Changed Name", slug = "example", visibility = profile.visibility))
        }

        assertEquals("Original Name", profiles.getById(profile.id)?.name)
        assertEquals(listOf("original-name"), mappings(profile.id))
    }

    private fun profileService(slugService: SlugService) = ProfileServiceImpl(profiles, mockk(), mockk(), mockk(), slugService)

    private suspend fun seed(name: String, vararg aliases: String): Profile = transaction {
        val profile = profiles.add(Profile(name = name, visibility = ProfileVisibility.PUBLIC, type = ProfileType.ORGANIZATION))
        aliases.forEach { slugs.add(Slug(slug = it, profileId = profile.id)) }
        profile
    }

    private suspend fun mappings(id: UUID) = slugs.getSlugByProfileId(listOf(id)).map { it.slug }.sorted()

    private fun <T> withDb(block: suspend () -> T): T = runBlocking {
        val manager = pool.connection()
        try {
            withContext(manager.asCoroutineContext()) { withRequestCache { block() } }
        } finally {
            withContext(NonCancellable) { manager.release() }
        }
    }
}
