@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.recommendations.service

import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.connection
import bosca.db.migrations.FlywayMigration
import bosca.db.transaction
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.recommendations.configuration.RecommendationsMigration
import bosca.recommendations.createProfileAttributeSignalsPrerequisites
import bosca.recommendations.model.RecommendationPlacementInput
import bosca.recommendations.repository.RecommendationPlacementRepositoryImpl
import bosca.recommendations.repository.RecommendationPlacementStrategyRepositoryImpl
import bosca.serialization.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.toJavaUuid
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.junit.AfterClass
import bosca.test.resources.SharedPostgreSQLContainer

/**
 * Real-Postgres tests for [RecommendationPlacementServiceImpl] — the placement CRUD and its ordered
 * strategy links, run within the service's transactions against a TestContainers instance.
 */
@OptIn(ExperimentalUuidApi::class)
class RecommendationPlacementServiceImplIntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("bosca_rec_placement_test")
            withReuse(true)
            start()
        }

        private val pool = ConnectionPool(
            ConnectionFactoryImpl(
                ConnectionConfig(url = postgres.jdbcUrl, user = postgres.username, password = postgres.password, maxConnections = 5),
                key = "test",
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

    private val json = Json { ignoreUnknownKeys = true }
    private val placementRepo = RecommendationPlacementRepositoryImpl()
    private val strategyLinkRepo = RecommendationPlacementStrategyRepositoryImpl()
    private lateinit var service: RecommendationPlacementServiceImpl

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { json }

        if (!schemaInitialized) {
            withDb {
                connection().useStatement("CREATE TABLE IF NOT EXISTS public.profiles (id uuid PRIMARY KEY)") { it.execute() }
                connection().useStatement("CREATE TABLE IF NOT EXISTS public.analytics_queries (id uuid PRIMARY KEY)") { it.execute() }
                connection().useStatement("CREATE SCHEMA IF NOT EXISTS segmentation") { it.execute() }
                connection().useStatement("CREATE TABLE IF NOT EXISTS segmentation.segments (id uuid PRIMARY KEY)") { it.execute() }
                connection().useStatement("CREATE TABLE IF NOT EXISTS public.scheduled_jobs (id uuid PRIMARY KEY)") { it.execute() }
                createProfileAttributeSignalsPrerequisites()  // for the profile_cohort view (V8)
            }
            runBlocking { FlywayMigration(pool).migrate(listOf(RecommendationsMigration())) }
            schemaInitialized = true
        }

        withDb {
            transaction {
                connection().useStatement("DELETE FROM recommendations.placement_strategies") { it.execute() }
                connection().useStatement("DELETE FROM recommendations.placements") { it.execute() }
                connection().useStatement("DELETE FROM recommendations.strategies") { it.execute() }
            }
        }
        service = RecommendationPlacementServiceImpl(placementRepo, strategyLinkRepo)
    }

    @AfterTest
    fun teardown() = ProviderRegistry.clear()

    private fun withDb(block: suspend () -> Unit) {
        runBlocking {
            val manager = pool.connection()
            try {
                withContext(manager.asCoroutineContext()) { block() }
            } finally {
                withContext(NonCancellable) { manager.release() }
            }
        }
    }

    /** A strategy row the placement links can FK to. */
    private suspend fun seedStrategy(): UUID {
        val id = UUID.random()
        connection().useStatement("INSERT INTO recommendations.strategies (id, name, type) VALUES (?, 'S', 'trending')") {
            it.setObject(1, id.toJavaUuid()); it.execute()
        }
        return id
    }

    private fun input(slug: String) = RecommendationPlacementInput(name = "P", description = "d", slug = slug, maxItems = 6)

    @Test
    fun `add creates a placement with ordered strategy links, read back by id and slug`() {
        lateinit var createdId: UUID
        var strategyId = UUID.NIL
        var links: List<UUID> = emptyList()
        withDb {
            strategyId = seedStrategy()
            val created = service.add(input("home"), listOf(strategyId))
            createdId = created.id
            links = service.getStrategyIds(created.id)
        }
        assertEquals(listOf(strategyId), links)
        withDb {
            assertEquals("home", service.getById(createdId)?.slug)
            assertEquals(createdId, service.getBySlug("home")?.id)
            assertEquals(1, service.getAll().size)
        }
    }

    @Test
    fun `add with no strategy links leaves the placement without associations`() {
        var links: List<UUID> = listOf(UUID.random())
        withDb {
            val created = service.add(input("bare"), emptyList())
            links = service.getStrategyIds(created.id)
        }
        assertTrue(links.isEmpty())
    }

    @Test
    fun `edit updates the placement and replaces its strategy links`() {
        var newLinks: List<UUID> = emptyList()
        var editedSlug: String? = null
        withDb {
            val s1 = seedStrategy()
            val s2 = seedStrategy()
            val created = service.add(input("p"), listOf(s1))
            val edited = service.edit(created.id, input("p-renamed"), listOf(s2))
            editedSlug = edited.slug
            newLinks = service.getStrategyIds(created.id)
        }
        assertEquals("p-renamed", editedSlug)
        // The old link (s1) is replaced by the new one (s2).
        assertEquals(1, newLinks.size)
    }

    @Test
    fun `edit throws when the placement does not exist`() {
        assertFailsWith<NoSuchElementException> {
            withDb { service.edit(UUID.random(), input("x"), emptyList()) }
        }
    }

    @Test
    fun `delete removes the placement and its strategy links`() {
        withDb {
            val strategyId = seedStrategy()
            val created = service.add(input("gone"), listOf(strategyId))
            service.delete(created.id)
            assertNull(service.getById(created.id))
            assertTrue(service.getStrategyIds(created.id).isEmpty())
        }
    }
}
