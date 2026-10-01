@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.profile.relationship.repository

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
import bosca.profile.relationship.model.ProfileRelationshipRequestStatus
import bosca.profile.relationship.service.ProfileRelationshipRequestServiceImpl
import bosca.profile.relationship.service.ProfileRelationshipService
import bosca.serialization.UUID
import bosca.test.resources.SharedPostgreSQLContainer
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.AfterClass
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ProfileRelationshipRequestRepositoryIntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("bosca_profile_relationship_request_test")
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
                key = "profile-relationship-request-test",
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

    private val repository = ProfileRelationshipRequestRepositoryImpl()
    private val relationshipRepository = ProfileRelationshipRepositoryImpl()
    private val requesterProfileId = UUID.parse("aaaaaaaa-1000-0000-0000-000000000001")
    private val targetProfileId = UUID.parse("aaaaaaaa-1000-0000-0000-000000000002")

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { Json { ignoreUnknownKeys = true } }

        if (!schemaInitialized) {
            runBlocking {
                FlywayMigration(pool).migrate(listOf(CoreMigration(), ProfileMigration()))
            }
            schemaInitialized = true
        }

        withDb {
            transaction {
                connection().useStatement("delete from public.profile_relationship_requests") { it.execute() }
                connection().useStatement(
                    "delete from public.profile_relationships where profile_id_1 in ('$requesterProfileId', '$targetProfileId') or profile_id_2 in ('$requesterProfileId', '$targetProfileId')"
                ) { it.execute() }
                connection().useStatement(
                    "insert into public.profiles (id, name) values ('$requesterProfileId', 'Requester'), ('$targetProfileId', 'Target') on conflict (id) do nothing"
                ) { it.execute() }
            }
        }
    }

    @Test
    fun `generated repository maps and pages pending requests`() = withDb {
        val attributes = buildJsonObject { put("message", "hello") }
        val created = transaction {
            repository.add(requesterProfileId, targetProfileId, "friend", attributes)
        }

        assertEquals(ProfileRelationshipRequestStatus.PENDING, created.status)
        assertEquals(attributes, created.attributes)
        assertEquals(
            listOf(created),
            repository.getIncomingByType(targetProfileId, "friend", offset = 0, limit = 10),
        )
        assertEquals(
            listOf(created),
            repository.getOutgoing(requesterProfileId, offset = 0, limit = 10),
        )
    }

    @Test
    fun `relationship repository distinguishes inserts from attribute updates`() = withDb {
        val original = buildJsonObject { put("source", "original") }
        val updated = buildJsonObject { put("source", "updated") }

        assertEquals(
            1,
            transaction {
                relationshipRepository.addRelationship(requesterProfileId, targetProfileId, "friend", original)
            },
        )
        assertEquals(
            0,
            transaction {
                relationshipRepository.addRelationship(requesterProfileId, targetProfileId, "friend", updated)
            },
        )
        assertEquals(
            1,
            transaction {
                relationshipRepository.updateRelationship(requesterProfileId, targetProfileId, "friend", updated)
            },
        )
        assertEquals(
            updated,
            relationshipRepository.getRelationship(requesterProfileId, targetProfileId, "friend")?.attributes,
        )
    }

    @Test
    fun `pending uniqueness allows a new request only after the prior request is decided`() = withDb {
        val first = transaction {
            repository.add(requesterProfileId, targetProfileId, "friend", null)
        }

        assertFailsWith<Exception> {
            transaction {
                repository.add(requesterProfileId, targetProfileId, "friend", null)
            }
        }

        val approved = transaction {
            repository.transition(first.id, first.version, ProfileRelationshipRequestStatus.APPROVED)
        }
        assertNotNull(approved)
        val second = transaction {
            repository.add(requesterProfileId, targetProfileId, "friend", null)
        }
        assertEquals(ProfileRelationshipRequestStatus.PENDING, second.status)
    }

    @Test
    fun `approval rolls back its status when relationship creation fails`() = withDb {
        val pending = transaction {
            repository.add(requesterProfileId, targetProfileId, "friend", null)
        }
        val relationshipService = mockk<ProfileRelationshipService>()
        coEvery {
            relationshipService.addRelationship(requesterProfileId, targetProfileId, "friend", null)
        } throws IllegalStateException("relationship write failed")
        val service = ProfileRelationshipRequestServiceImpl(repository, relationshipService)

        assertFailsWith<IllegalStateException> { service.approve(pending.id) }

        val persisted = repository.getById(pending.id)
        assertNotNull(persisted)
        assertEquals(ProfileRelationshipRequestStatus.PENDING, persisted.status)
        assertEquals(0, persisted.version)
    }

    @Test
    fun `optimistic transition rejects a stale request version`() = withDb {
        val pending = transaction {
            repository.add(requesterProfileId, targetProfileId, "friend", null)
        }
        val approved = transaction {
            repository.transition(pending.id, pending.version, ProfileRelationshipRequestStatus.APPROVED)
        }
        assertNotNull(approved)

        val stale = transaction {
            repository.transition(pending.id, pending.version, ProfileRelationshipRequestStatus.CANCELLED)
        }
        assertNull(stale)
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
