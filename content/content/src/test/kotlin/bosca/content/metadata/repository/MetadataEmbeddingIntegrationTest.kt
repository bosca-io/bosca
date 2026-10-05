@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.content.metadata.repository

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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.AfterClass
import bosca.test.resources.SharedPostgreSQLContainer

/**
 * Real-Postgres (pgvector) tests for chunked `metadata_embeddings`: multi-chunk replacement, the
 * Trino-facing `metadata_embedding` text view, the vector(768) dimension constraint, and cascade delete.
 */
@OptIn(ExperimentalUuidApi::class)
class MetadataEmbeddingIntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("bosca_content_embedding_test")
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

        @AfterClass
        @JvmStatic
        fun shutdown() {
            try {
                runBlocking {
                    pool.close()
                }
            } finally {
                postgres.stop()
            }
        }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val repository = MetadataRepositoryImpl()

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { json }

        if (!schemaInitialized) {
            runBlocking { FlywayMigration(pool).migrate(listOf(CoreMigration())) }
            schemaInitialized = true
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

    private suspend fun addMetadata(): Metadata = repository.add(
        Metadata(
            name = "Embedding Test Metadata",
            type = MetadataType.STANDARD,
            contentType = "text/plain",
            contentLength = null,
            languageTag = "en",
            workflowStateId = "pending",
            attributes = JsonObject(emptyMap()),
        ),
    )

    // MetadataServiceImpl.setEmbeddings binds vectors in this exact text form; keep in sync.
    private fun List<Float>.asVectorText() = joinToString(prefix = "[", postfix = "]", separator = ",")

    private suspend fun addEmbeddingChunk(
        metadataId: UUID,
        index: Int,
        tokenStart: Int,
        tokenCount: Int,
        vector: List<Float>,
    ) {
        repository.addEmbeddingChunks(metadataId, buildJsonArray {
            add(buildJsonObject {
                put("chunk_index", index)
                put("token_start", tokenStart)
                put("token_end", tokenStart + tokenCount)
                put("token_count", tokenCount)
                put("aggregation_weight", tokenCount.toDouble())
                put("embedding", vector.asVectorText())
            })
        })
    }

    // Multiples of 1/64 are exactly representable in float32, so the pgvector round-trip is lossless.
    private fun vectorOf(seed: Int): List<Float> = List(768) { ((it + seed) % 64) / 64.0f }

    private suspend fun readEmbeddingText(metadataId: UUID, chunkIndex: Int = 0): String? =
        connection().useStatement(
            "select embedding from metadata_embedding where id = ?::uuid and chunk_index = ?",
        ) { stmt ->
            stmt.setString(1, metadataId.toString())
            stmt.setInt(2, chunkIndex)
            stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    private suspend fun countEmbeddingRows(metadataId: UUID): Int =
        connection().useStatement("select count(*) from metadata_embeddings where metadata_id = ?::uuid") { stmt ->
            stmt.setString(1, metadataId.toString())
            stmt.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }

    private suspend fun readTokenCount(metadataId: UUID, chunkIndex: Int = 0): Int? =
        connection().useStatement(
            "select token_count from metadata_embedding where id = ?::uuid and chunk_index = ?",
        ) { stmt ->
            stmt.setString(1, metadataId.toString())
            stmt.setInt(2, chunkIndex)
            stmt.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else null }
        }

    private fun parseVectorText(text: String): List<Float> =
        text.removePrefix("[").removeSuffix("]").split(",").map { it.trim().toFloat() }

    @Test
    fun `embedding chunk round-trips its vector through the vector column and the view`() {
        val vector = vectorOf(seed = 1)
        var metadataId: UUID? = null
        var readBack: String? = null
        var tokenCount: Int? = null
        withDb {
            transaction {
                val metadata = addMetadata()
                metadataId = metadata.id
                addEmbeddingChunk(metadata.id, 0, 0, 128, vector)
            }
        }
        withDb {
            transaction {
                readBack = metadataId?.let { readEmbeddingText(it) }
                tokenCount = metadataId?.let { readTokenCount(it) }
            }
        }
        val text = readBack ?: error("no embedding row found via the metadata_embedding view")
        assertEquals(vector, parseVectorText(text))
        assertEquals(128, tokenCount)
    }

    @Test
    fun `multiple chunks coexist and replacement removes stale trailing chunks`() {
        val first = vectorOf(seed = 2)
        val second = vectorOf(seed = 3)
        var metadataId: UUID? = null
        var readBack: String? = null
        var rowsBeforeReplacement = -1
        var rows = -1
        withDb {
            transaction {
                val metadata = addMetadata()
                metadataId = metadata.id
                addEmbeddingChunk(metadata.id, 0, 0, 100, first)
                addEmbeddingChunk(metadata.id, 1, 100, 50, second)
                rowsBeforeReplacement = countEmbeddingRows(metadata.id)
                repository.deleteEmbeddings(metadata.id)
                addEmbeddingChunk(metadata.id, 0, 0, 80, second)
            }
        }
        withDb {
            transaction {
                val id = metadataId ?: error("metadata was not created")
                readBack = readEmbeddingText(id)
                rows = countEmbeddingRows(id)
            }
        }
        assertEquals(2, rowsBeforeReplacement)
        assertEquals(1, rows)
        val text = readBack ?: error("no embedding row found via the metadata_embedding view")
        assertEquals(second, parseVectorText(text))
    }

    @Test
    fun `getEmbeddingIds returns only requested metadata with stored vectors`() {
        var embeddedId: UUID? = null
        var missingId: UUID? = null
        var result = emptyList<UUID>()
        withDb {
            transaction {
                val embedded = addMetadata()
                val missing = addMetadata()
                embeddedId = embedded.id
                missingId = missing.id
                addEmbeddingChunk(embedded.id, 0, 0, 64, vectorOf(seed = 5))
                result = repository.getEmbeddingIds(listOf(embedded.id, missing.id, UUID.random()))
            }
        }

        assertEquals(listOf(embeddedId), result)
        assertEquals(false, result.contains(missingId))
    }

    @Test
    fun `embedding chunk rejects a vector whose dimension does not match vector(768)`() {
        var metadataId: UUID? = null
        withDb { transaction { metadataId = addMetadata().id } }
        val id = metadataId ?: error("metadata was not created")
        assertFails {
            withDb {
                transaction {
                    addEmbeddingChunk(id, 0, 0, 3, listOf(0.5f, 0.25f, 0.125f))
                }
            }
        }
    }

    @Test
    fun `deleting the metadata row cascades to its embedding`() {
        var metadataId: UUID? = null
        var rows = -1
        withDb {
            transaction {
                val metadata = addMetadata()
                metadataId = metadata.id
                addEmbeddingChunk(metadata.id, 0, 0, 64, vectorOf(seed = 4))
            }
        }
        withDb {
            transaction {
                val id = metadataId ?: error("metadata was not created")
                connection().useStatement("delete from metadata where id = ?::uuid") { stmt ->
                    stmt.setString(1, id.toString())
                    stmt.executeUpdate()
                }
                rows = countEmbeddingRows(id)
            }
        }
        assertEquals(0, rows)
    }
}
