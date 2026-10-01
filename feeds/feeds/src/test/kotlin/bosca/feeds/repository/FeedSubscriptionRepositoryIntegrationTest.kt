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
import bosca.feeds.model.FeedSubscription
import bosca.serialization.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import bosca.test.resources.SharedPostgreSQLContainer

/** Real-Postgres tests for `feeds.feed_subscriptions`: idempotent subscribe, the source join, unsubscribe. */
@OptIn(ExperimentalUuidApi::class)
class FeedSubscriptionRepositoryIntegrationTest {

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
    fun `subscribe round-trips and is idempotent`() {
        val profileId = UUID.random()
        val sourceId = UUID.random()
        withDb {
            transaction {
                subscriptions.add(profileId, sourceId)
                subscriptions.add(profileId, sourceId) // on conflict do nothing -> no error
            }
        }

        var got: FeedSubscription? = null
        withDb { transaction { got = subscriptions.get(profileId, sourceId) } }
        assertEquals(sourceId, got?.sourceId)
    }

    @Test
    fun `getSubscribedSources joins through to live sources`() {
        val profileId = UUID.random()
        val sourceId = UUID.random()
        withDb {
            transaction {
                sources.add(FeedSource(sourceId = sourceId, url = "https://s.example/feed"))
                subscriptions.add(profileId, sourceId)
            }
        }

        var result: List<FeedSource> = emptyList()
        withDb { transaction { result = subscriptions.getSubscribedSources(profileId, 0, 25) } }
        assertEquals(listOf(sourceId), result.map { it.sourceId })
    }

    @Test
    fun `unsubscribe removes the subscription`() {
        val profileId = UUID.random()
        val sourceId = UUID.random()
        withDb { transaction { subscriptions.add(profileId, sourceId) } }
        withDb { transaction { subscriptions.delete(profileId, sourceId) } }

        var got: FeedSubscription? = FeedSubscription(profileId = profileId, sourceId = sourceId)
        withDb { transaction { got = subscriptions.get(profileId, sourceId) } }
        assertNull(got)
    }
}
