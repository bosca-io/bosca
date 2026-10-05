@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.content.metadata.repository

import bosca.content.find.ExtensionFilterType
import bosca.content.find.FindAttributeInput
import bosca.content.find.FindAttributesInput
import bosca.content.find.FindQueryInput
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.model.SourceStatus
import bosca.content.ordering.Order
import bosca.content.ordering.OrderingInput
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
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.toKotlinUuid
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.AfterClass
import bosca.test.resources.SharedPostgreSQLContainer

/**
 * Real-Postgres (pgvector) coverage for [MetadataFindRepository]: exercises `find`, `findBySystem`,
 * and `findCount` end-to-end, plus the full `map` ResultSet projection across two rows — one with
 * every nullable column populated and one with them all null — so both sides of each nullable
 * mapper are covered. Filter/attribute/paging branches drive the value-binding loops.
 */
@OptIn(ExperimentalUuidApi::class)
class MetadataFindRepositoryCoverageTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("bosca_content_find_test")
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
    private val findRepository = MetadataFindRepository(json)

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

    // ── Seeding helpers ──────────────────────────────────────────────────────

    /**
     * Inserts a row in `sources` so a metadata row can reference it via source_id (FK).
     * `sources.name` has a UNIQUE index (`sources_name_idx`), and these tests share one reused
     * container, so a hardcoded name would collide across tests — use a unique name per insert.
     */
    private suspend fun insertSource(): UUID {
        val uniqueName = "src-" + java.util.UUID.randomUUID()
        return connection().useStatement("insert into sources (name, description) values (?, 'desc') returning id") { stmt ->
            stmt.setString(1, uniqueName)
            stmt.executeQuery().use { rs ->
                rs.next()
                (rs.getObject("id") as java.util.UUID).toKotlinUuid()
            }
        }
    }

    /**
     * Inserts a row in `workflows` so a metadata row can reference it via delete_workflow_id (FK
     * metadata_delete_workflow_id_fkey). Idempotent — ignores the conflict when the id already exists.
     */
    private suspend fun insertDeleteWorkflow(id: String) {
        connection().useStatement(
            "insert into workflows (id, name, description, queue) values (?, 'Delete WF', 'delete', 'metadata') on conflict (id) do nothing",
        ) { stmt ->
            stmt.setString(1, id)
            stmt.executeUpdate()
        }
    }

    private suspend fun setWorkflowStatePending(id: UUID, state: String, valid: OffsetDateTime) {
        connection().useStatement(
            "update metadata set workflow_state_pending_id = ?, workflow_state_valid = ? where id = ?::uuid",
        ) { stmt ->
            stmt.setString(1, state)
            stmt.setObject(2, valid)
            stmt.setString(3, id.toString())
            stmt.executeUpdate()
        }
    }

    /** A metadata row with every nullable column populated. */
    private suspend fun addFullMetadata(parentId: UUID?): Metadata {
        val sourceId = insertSource()
        insertDeleteWorkflow("delete-wf")
        val added = repository.add(
            Metadata(
                parentId = parentId,
                name = "Full Metadata",
                type = MetadataType.STANDARD,
                contentType = "text/html",
                contentLength = 1234L,
                languageTag = "en",
                labels = listOf("alpha", "beta"),
                attributes = buildJsonObject { put("color", "red") },
                systemAttributes = buildJsonObject { put("scolor", "blue") },
                public = true,
                publicContent = true,
                publicSupplementary = true,
                sourceId = sourceId,
                sourceIdentifier = "ext-42",
                sourceUrl = "https://example.com/x",
                deleteWorkflowId = "delete-wf",
                permissionMutation = 3,
                etag = "etag-abc",
                locked = true,
                workflowStateId = "pending",
            ),
        )
        // source_status, ready, uploaded, and the pending-state columns are not written by add();
        // set them so map() reads their non-null branches.
        repository.setSourceStatus(added.id, SourceStatus.IMPORTED)
        repository.setReady(added.id)
        repository.setUploaded(added.id, "text/html", 1234L, emptyList())
        setWorkflowStatePending(added.id, "draft", OffsetDateTime.now())
        return added
    }

    /** A minimal metadata row: every nullable column left null. */
    private suspend fun addMinimalMetadata(): Metadata =
        repository.add(
            Metadata(
                name = "Minimal Metadata",
                type = MetadataType.STANDARD,
                contentType = "text/plain",
                contentLength = null,
                languageTag = "en",
                attributes = JsonObject(emptyMap()),
                workflowStateId = "pending",
            ),
        )

    // ── find / findBySystem / map ───────────────────────────────────────────

    @Test
    fun `find maps a fully-populated row across every non-null branch`() {
        var full: Metadata? = null
        var results: List<Metadata> = emptyList()
        withDb {
            transaction {
                val minimal = addMinimalMetadata()
                full = addFullMetadata(parentId = minimal.id)
            }
        }
        withDb {
            results = findRepository.find(
                FindQueryInput(contentTypes = listOf("text/html")),
            )
        }
        val id = full?.id ?: error("full row not created")
        val row = results.firstOrNull { it.id == id } ?: error("full row not returned by find")

        assertEquals("Full Metadata", row.name)
        assertEquals(MetadataType.STANDARD, row.type)
        assertEquals("text/html", row.contentType)
        assertEquals(1234L, row.contentLength)
        assertEquals("en", row.languageTag)
        assertEquals(listOf("alpha", "beta"), row.labels)
        assertNotNull(row.parentId)
        assertNotNull(row.attributes)
        assertNotNull(row.systemAttributes)
        assertTrue(row.public)
        assertTrue(row.publicContent)
        assertTrue(row.publicSupplementary)
        assertNotNull(row.created)
        assertNotNull(row.modified)
        assertNotNull(row.uploaded)
        assertNotNull(row.ready)
        assertNotNull(row.workflowStatePendingId)
        assertNotNull(row.workflowStateValid)
        assertNotNull(row.sourceId)
        assertEquals("ext-42", row.sourceIdentifier)
        assertEquals("https://example.com/x", row.sourceUrl)
        assertEquals(SourceStatus.IMPORTED, row.sourceStatus)
        assertEquals("delete-wf", row.deleteWorkflowId)
        assertEquals(3, row.permissionMutation)
        // setUploaded() recomputes etag as md5(id || contentLength || now()), so it is NOT the
        // literal "etag-abc" set on the model — assert the map() reads a non-blank computed hash.
        assertNotNull(row.etag)
        assertTrue(row.etag?.isNotBlank() == true, "etag should be a non-blank computed hash")
        assertTrue(row.locked)
    }

    @Test
    fun `find maps a minimal row leaving every nullable column null`() {
        var minimal: Metadata? = null
        var results: List<Metadata> = emptyList()
        withDb {
            transaction { minimal = addMinimalMetadata() }
        }
        withDb {
            results = findRepository.find(
                FindQueryInput(contentTypes = listOf("text/plain")),
            )
        }
        val id = minimal?.id ?: error("minimal row not created")
        val row = results.firstOrNull { it.id == id } ?: error("minimal row not returned by find")

        assertNull(row.parentId)
        assertNull(row.contentLength)
        assertNull(row.systemAttributes)
        assertNull(row.uploaded)
        assertNull(row.ready)
        assertNull(row.workflowStatePendingId)
        assertNull(row.workflowStateValid)
        assertNull(row.sourceId)
        assertNull(row.sourceIdentifier)
        assertNull(row.sourceUrl)
        assertNull(row.sourceStatus)
        assertNull(row.deleteWorkflowId)
        assertNull(row.etag)
        assertTrue(row.labels.isEmpty())
    }

    @Test
    fun `findBySystem filters on the system_attributes column`() {
        var full: Metadata? = null
        var matched: List<Metadata> = emptyList()
        var unmatched: List<Metadata> = emptyList()
        withDb {
            transaction { full = addFullMetadata(parentId = null) }
        }
        withDb {
            matched = findRepository.findBySystem(
                FindQueryInput(
                    attributes = listOf(
                        FindAttributesInput(listOf(FindAttributeInput(key = "scolor", value = "blue"))),
                    ),
                ),
            )
            unmatched = findRepository.findBySystem(
                FindQueryInput(
                    attributes = listOf(
                        FindAttributesInput(listOf(FindAttributeInput(key = "scolor", value = "green"))),
                    ),
                ),
            )
        }
        val id = full?.id ?: error("full row not created")
        assertTrue(matched.any { it.id == id }, "row should match its system attribute")
        assertTrue(unmatched.none { it.id == id }, "row should not match a different system attribute value")
    }

    @Test
    fun `find applies attribute filter on the attributes column`() {
        var full: Metadata? = null
        var matched: List<Metadata> = emptyList()
        withDb {
            transaction { full = addFullMetadata(parentId = null) }
        }
        withDb {
            matched = findRepository.find(
                FindQueryInput(
                    attributes = listOf(
                        FindAttributesInput(listOf(FindAttributeInput(key = "color", value = "red"))),
                    ),
                ),
            )
        }
        val id = full?.id ?: error("full row not created")
        assertTrue(matched.any { it.id == id })
    }

    @Test
    fun `find honors ordering language and paging filters`() {
        withDb {
            transaction {
                addMinimalMetadata()
                addMinimalMetadata()
            }
        }
        var results: List<Metadata> = emptyList()
        withDb {
            results = findRepository.find(
                FindQueryInput(
                    languageTags = listOf("en"),
                    contentTypes = listOf("text/plain"),
                    ordering = listOf(OrderingInput(field = "name", order = Order.ASCENDING)),
                    offset = 0L,
                    limit = 1,
                ),
            )
        }
        assertEquals(1, results.size, "limit of 1 should return exactly one row")
    }

    @Test
    fun `find with an extension filter returns only rows with the joined extension`() {
        var minimal: Metadata? = null
        var results: List<Metadata> = emptyList()
        withDb {
            transaction { minimal = addMinimalMetadata() }
        }
        withDb {
            // No documents rows exist, so the DOCUMENT inner join must exclude everything.
            results = findRepository.find(
                FindQueryInput(extensionFilter = ExtensionFilterType.DOCUMENT),
            )
        }
        val id = minimal?.id ?: error("minimal row not created")
        assertTrue(results.none { it.id == id }, "row without a document should be excluded by the extension join")
    }

    // ── findCount ─────────────────────────────────────────────────────────────

    @Test
    fun `findCount returns the number of matching rows`() {
        withDb {
            transaction {
                addMinimalMetadata()
                addFullMetadata(parentId = null)
            }
        }
        var htmlCount = 0L
        var allCount = 0L
        withDb {
            htmlCount = findRepository.findCount(FindQueryInput(contentTypes = listOf("text/html")))
            allCount = findRepository.findCount(FindQueryInput())
        }
        assertTrue(htmlCount >= 1L, "at least the html row should be counted")
        assertTrue(allCount >= 2L, "all non-deleted rows should be counted")
    }

    @Test
    fun `findCount returns zero when no rows match`() {
        var count = -1L
        withDb {
            count = findRepository.findCount(
                FindQueryInput(contentTypes = listOf("application/does-not-exist")),
            )
        }
        assertEquals(0L, count)
    }
}
