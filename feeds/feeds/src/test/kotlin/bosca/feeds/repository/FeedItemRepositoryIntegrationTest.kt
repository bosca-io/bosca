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
import bosca.feeds.model.FeedItem
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

/** Real-Postgres tests for `feeds.feed_items`: mapping round-trip and the (source_id, guid) PK dedup. */
@OptIn(ExperimentalUuidApi::class)
class FeedItemRepositoryIntegrationTest {

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
    private val repository = FeedItemRepositoryImpl()
    private val sources = FeedSourceRepositoryImpl()
    private val subscriptions = FeedSubscriptionRepositoryImpl()

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
                connection().useStatement("DELETE FROM feeds.feed_items") { it.execute() }
                connection().useStatement("DELETE FROM feeds.feed_subscriptions") { it.execute() }
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
    fun `mapping round-trips by source and guid`() {
        val sourceId = UUID.random()
        val metadataId = UUID.random()
        withDb { transaction { repository.add(FeedItem(sourceId = sourceId, guid = "g1", metadataId = metadataId)) } }

        var fetched: FeedItem? = null
        withDb { transaction { fetched = repository.get(sourceId, "g1") } }
        assertEquals(metadataId, fetched?.metadataId)

        var missing: FeedItem? = FeedItem(sourceId = sourceId, guid = "x", metadataId = UUID.random())
        withDb { transaction { missing = repository.get(sourceId, "absent") } }
        assertNull(missing)
    }

    @Test
    fun `getBySource pages a source's items and excludes other sources`() {
        val sourceId = UUID.random()
        val other = UUID.random()
        withDb {
            transaction {
                repository.add(FeedItem(sourceId = sourceId, guid = "a", metadataId = UUID.random()))
                repository.add(FeedItem(sourceId = sourceId, guid = "b", metadataId = UUID.random()))
                repository.add(FeedItem(sourceId = sourceId, guid = "c", metadataId = UUID.random()))
                repository.add(FeedItem(sourceId = other, guid = "a", metadataId = UUID.random()))
            }
        }

        var page1: List<FeedItem> = emptyList()
        var page2: List<FeedItem> = emptyList()
        withDb { transaction { page1 = repository.getBySource(sourceId, 0, 2) } }
        withDb { transaction { page2 = repository.getBySource(sourceId, 2, 2) } }

        assertEquals(2, page1.size)
        assertEquals(1, page2.size)
        assertEquals(true, (page1 + page2).all { it.sourceId == sourceId })
        assertEquals(setOf("a", "b", "c"), (page1 + page2).map { it.guid }.toSet())
    }

    @Test
    fun `the (source_id, guid) pair is unique`() {
        val sourceId = UUID.random()
        withDb { transaction { repository.add(FeedItem(sourceId = sourceId, guid = "dup", metadataId = UUID.random())) } }
        assertFailsWith<Exception> {
            withDb { transaction { repository.add(FeedItem(sourceId = sourceId, guid = "dup", metadataId = UUID.random())) } }
        }
    }

    @Test
    fun `getForProfile unions items from subscribed and owned sources, excluding others`() {
        val profileId = UUID.random()
        val owned = UUID.random()
        val subscribed = UUID.random()
        val other = UUID.random()
        val ownedItem = UUID.random()
        val subscribedItem = UUID.random()
        val otherItem = UUID.random()
        withDb {
            transaction {
                sources.add(FeedSource(sourceId = owned, url = "https://owned.example/feed", ownerProfileId = profileId))
                sources.add(FeedSource(sourceId = other, url = "https://other.example/feed"))
                subscriptions.add(profileId, subscribed)
                repository.add(FeedItem(sourceId = owned, guid = "o1", metadataId = ownedItem))
                repository.add(FeedItem(sourceId = subscribed, guid = "s1", metadataId = subscribedItem))
                repository.add(FeedItem(sourceId = other, guid = "x1", metadataId = otherItem))
            }
        }

        var feed: List<FeedItem> = emptyList()
        withDb { transaction { feed = repository.getForProfile(profileId, 0, 25) } }

        assertEquals(setOf(ownedItem, subscribedItem), feed.map { it.metadataId }.toSet())
    }
}
