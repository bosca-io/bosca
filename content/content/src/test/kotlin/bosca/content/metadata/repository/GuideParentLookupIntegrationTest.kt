@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.content.metadata.repository

import bosca.content.metadata.model.Guide
import bosca.content.metadata.model.GuideStep
import bosca.content.metadata.model.GuideType
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
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
import bosca.serialization.UUID
import bosca.test.resources.SharedPostgreSQLContainer
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.AfterClass
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class GuideParentLookupIntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("bosca_guide_parent_test")
            withReuse(true)
            start()
        }
        private val pool = ConnectionPool(
            ConnectionFactoryImpl(
                ConnectionConfig(url = postgres.jdbcUrl, user = postgres.username, password = postgres.password, maxConnections = 5),
                key = "guide-parent-test",
            )
        )

        @AfterClass
        @JvmStatic
        fun shutdown() {
            try {
                runBlocking { pool.close() }
            } finally {
                postgres.stop()
            }
        }
    }

    private val metadata = MetadataRepositoryImpl()
    private val guides = GuideRepositoryImpl()
    private val steps = GuideStepRepositoryImpl()

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { Json { ignoreUnknownKeys = true } }
        runBlocking { FlywayMigration(pool).migrate(listOf(CoreMigration())) }
    }

    @AfterTest
    fun teardown() = ProviderRegistry.clear()

    @Test
    fun `reverse lookup maps shared step memberships and excludes old and deleted guides`() = runBlocking {
        val manager = pool.connection()
        try {
            withContext(manager.asCoroutineContext()) {
                transaction {
                    val created = mutableListOf<UUID>()
                    suspend fun addMetadata(): Metadata = metadata.add(
                        Metadata(name = "Guide parent lookup", type = MetadataType.STANDARD, contentType = "text/plain",
                            contentLength = null, languageTag = "en", workflowStateId = "pending", attributes = JsonObject(emptyMap()))
                    ).also { created.add(it.id) }
                    suspend fun addGuide(parent: Metadata, version: Int = parent.version) {
                        guides.add(Guide(parent.id, version, null, GuideType.LINEAR, null, null))
                    }
                    try {
                        val child = addMetadata()
                        val parent = addMetadata()
                        val sharedParent = addMetadata()
                        val deletedParent = addMetadata()
                        addGuide(parent)
                        addGuide(parent, version = 2)
                        addGuide(sharedParent)
                        addGuide(deletedParent)
                        val first = steps.add(GuideStep(metadataId = parent.id, version = 1, stepMetadataId = child.id, stepMetadataVersion = 1, sort = 2))
                        val shared = steps.add(GuideStep(metadataId = sharedParent.id, version = 1, stepMetadataId = child.id, stepMetadataVersion = 2, sort = 0))
                        steps.add(GuideStep(metadataId = parent.id, version = 2, stepMetadataId = child.id, stepMetadataVersion = 1, sort = 0))
                        steps.add(GuideStep(metadataId = deletedParent.id, version = 1, stepMetadataId = child.id, stepMetadataVersion = 1, sort = 0))
                        metadata.markDeleted(deletedParent.id)

                        val results = steps.getByStepMetadataIds(listOf(child.id, UUID.random()))
                        assertEquals(setOf(first.id, shared.id), results.map { it.id }.toSet())
                        val mapped = results.single { it.id == first.id }
                        assertEquals(parent.id, mapped.metadataId)
                        assertEquals(1, mapped.version)
                        assertEquals(child.id, mapped.stepMetadataId)
                        assertEquals(1, mapped.stepMetadataVersion)
                        assertEquals(2, mapped.sort)
                        assertEquals(2, results.single { it.id == shared.id }.stepMetadataVersion)
                        assertEquals(emptyList(), steps.getByStepMetadataIds(listOf(UUID.random())))

                        steps.deleteGuideStepByMetadataIdAndVersionAndStepId(parent.id, 1, first.id)
                        assertEquals(listOf(shared.id), steps.getByStepMetadataIds(listOf(child.id)).map { it.id })
                    } finally {
                        created.asReversed().forEach { metadata.deleteById(it) }
                    }
                }
            }
        } finally {
            withContext(NonCancellable) { manager.release() }
        }
    }
}
