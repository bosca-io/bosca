@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.feeds.repository

import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.connection
import bosca.db.migrations.FlywayMigration
import bosca.db.transaction
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.feeds.migration.FeedsMigration
import bosca.feeds.model.FeedSource
import bosca.serialization.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import bosca.test.resources.SharedPostgreSQLContainer

/**
 * Real-Postgres tests for the `feeds.feed_sources` record: round-trip, the partial unique
 * `url` index that backs the overlap rule, and not-found semantics.
 */
@OptIn(ExperimentalUuidApi::class)
class FeedSourceRepositoryIntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg18").apply {
            withDatabaseName("bosca_feeds_test")
            withReuse(true)
            start()
        }

        private val pool = ConnectionPool(
            ConnectionFactoryImpl(
                ConnectionConfig(
                    url = postgres.jdbcUrl,
                    user = postgres.username,
                    password = postgres.password,
                    maxConnections = 5,
                ),
                key = "test",
            ),
        )

        private var schemaInitialized = false
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val repository = FeedSourceRepositoryImpl()

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { json }

        if (!schemaInitialized) {
            runBlocking { FlywayMigration(pool).migrate(listOf(FeedsMigration())) }
            schemaInitialized = true
        }

        withDb {
            transaction {
                connection().useStatement("DELETE FROM feeds.feed_sources") { it.execute() }
            }
        }
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

    @Test
    fun `feed_sources record round-trips and reads back by source id`() {
        val sourceId = UUID.random()
        val ownerId = UUID.random()
        lateinit var added: FeedSource
        withDb {
            transaction {
                added = repository.add(
                    FeedSource(
                        sourceId = sourceId,
                        enabled = true,
                        ownerProfileId = ownerId,
                        url = "https://example.com/feed.xml",
                    ),
                )
            }
        }
        assertEquals(sourceId, added.sourceId)
        assertEquals("https://example.com/feed.xml", added.url)

        var fetched: FeedSource? = null
        withDb { transaction { fetched = repository.get(sourceId) } }
        assertEquals(sourceId, fetched?.sourceId)
        assertEquals(ownerId, fetched?.ownerProfileId)
        assertEquals(true, fetched?.enabled)
    }

    @Test
    fun `url is unique across live sources (overlap rule)`() {
        withDb {
            transaction {
                repository.add(FeedSource(sourceId = UUID.random(), url = "https://dup.example.com/feed"))
            }
        }
        assertFailsWith<Exception> {
            withDb {
                transaction {
                    repository.add(FeedSource(sourceId = UUID.random(), url = "https://dup.example.com/feed"))
                }
            }
        }
    }

    @Test
    fun `get returns null for an unknown source`() {
        var fetched: FeedSource? = FeedSource(sourceId = UUID.random(), url = "x")
        withDb { transaction { fetched = repository.get(UUID.random()) } }
        assertNull(fetched)
    }

    @Test
    fun `setEnabled disables a source`() {
        val sourceId = UUID.random()
        withDb { transaction { repository.add(FeedSource(sourceId = sourceId, url = "https://e.example/disable")) } }
        var updated: FeedSource? = null
        withDb { transaction { updated = repository.setEnabled(sourceId, false) } }
        assertEquals(false, updated?.enabled)
    }

    @Test
    fun `softDelete removes a source from reads and frees the canonical url`() {
        val sourceId = UUID.random()
        withDb { transaction { repository.add(FeedSource(sourceId = sourceId, url = "https://e.example/del")) } }
        withDb { transaction { repository.softDelete(sourceId) } }

        var fetched: FeedSource? = FeedSource(sourceId = sourceId, url = "x")
        withDb { transaction { fetched = repository.get(sourceId) } }
        assertNull(fetched)

        // the partial unique index excludes soft-deleted rows, so the url can be reused
        withDb { transaction { repository.add(FeedSource(sourceId = UUID.random(), url = "https://e.example/del")) } }
    }
}
