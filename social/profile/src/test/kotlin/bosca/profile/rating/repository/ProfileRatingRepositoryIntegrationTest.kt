@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.profile.rating.repository

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
import bosca.profile.persona.repository.ProfileMigration
import bosca.profile.rating.model.ProfileRating
import bosca.serialization.UUID
import kotlin.test.assertTrue
import bosca.test.resources.SharedPostgreSQLContainer
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.junit.AfterClass
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ProfileRatingRepositoryIntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("bosca_profile_rating_test")
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
                key = "profile-rating-test",
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

    private val repository = ProfileRatingRepositoryImpl()
    private val profileId = UUID.parse("aaaaaaaa-2000-0000-0000-000000000001")
    private val metadataId = UUID.parse("aaaaaaaa-2000-0000-0000-000000000002")

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { Json { ignoreUnknownKeys = true } }
        if (!schemaInitialized) {
            runBlocking { FlywayMigration(pool).migrate(listOf(CoreMigration(), ProfileMigration())) }
            schemaInitialized = true
        }
        withDb {
            transaction {
                connection().useStatement("delete from profile_ratings where profile_id = '$profileId'") { it.execute() }
                connection().useStatement("insert into profiles (id, name) values ('$profileId', 'Rating test') on conflict (id) do nothing") { it.execute() }
                connection().useStatement("insert into metadata (id, name, content_type) values ('$metadataId', 'Rating test', 'text/plain') on conflict (id) do nothing") { it.execute() }
            }
        }
    }

    @Test
    fun `insert returns persisted rating and edit preserves identity without duplicating feedback`() = withDb {
        val created = transaction {
            repository.add(ProfileRating(profileId = profileId, metadataId = metadataId, metadataVersion = 1, rating = 5))
        }
        assertTrue(created.id > 0)
        assertEquals(profileId, created.profileId)
        assertEquals(metadataId, created.metadataId)
        assertEquals(1, created.metadataVersion)
        assertEquals(5, created.rating)
        assertEquals(created, repository.findByProfileAndMetadata(profileId, metadataId, 1))
        val updated = transaction { repository.update(created.copy(rating = 2)) }
        assertEquals(created.copy(rating = 2), updated)
        assertEquals(listOf(updated), repository.findByProfileId(profileId))
        assertEquals(2.0, repository.getAverageRating(metadataId, 1))
    }

    private fun <T> withDb(block: suspend () -> T): T = runBlocking {
        val manager = pool.connection()
        try {
            withContext(manager.asCoroutineContext()) { block() }
        } finally {
            withContext(NonCancellable) { manager.release() }
        }
    }
}
