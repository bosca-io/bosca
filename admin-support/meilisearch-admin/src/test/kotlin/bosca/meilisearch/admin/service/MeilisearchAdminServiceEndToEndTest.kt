@file:OptIn(ExperimentalUuidApi::class, InternalDI::class)

package bosca.meilisearch.admin.service

import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.use
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.meilisearch.admin.model.MeilisearchNode
import bosca.meilisearch.admin.repository.MeilisearchNodeRepository
import bosca.security.encryption.EncryptionService
import bosca.serialization.UUID
import bosca.storage.model.StorageSystemType
import bosca.meilisearch.client.MeilisearchClient
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import bosca.test.resources.SharedPostgreSQLContainer
import bosca.test.resources.SharedMeilisearchContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.lifecycle.Startables
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * End-to-end integration test that exercises [MeilisearchAdminServiceImpl] against
 * real PostgreSQL and Meilisearch containers. Verifies the service can create indexes,
 * manage settings, track tasks, and persist node topology to the database.
 */
class MeilisearchAdminServiceEndToEndTest {

    private lateinit var postgresContainer: SharedPostgreSQLContainer
    private lateinit var meilisearchContainer: SharedMeilisearchContainer
    private lateinit var connectionPool: ConnectionPool
    private lateinit var service: MeilisearchAdminServiceImpl
    private lateinit var nodeRepository: MeilisearchNodeRepository

    private val meilisearchApiKey = "test-master-key"

    @BeforeTest
    fun setup() = runBlocking {
        postgresContainer = SharedPostgreSQLContainer()
            .withExposedPorts(5432)
            .withEnv("POSTGRES_USER", "test")
            .withEnv("POSTGRES_PASSWORD", "test")
            .withEnv("POSTGRES_DB", "test")
            .withReuse(true)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2))

        meilisearchContainer = SharedMeilisearchContainer()

        Startables.deepStart(postgresContainer, meilisearchContainer).join()

        val factory = ConnectionFactoryImpl(
            ConnectionConfig(
                url = postgresContainer.jdbcUrl,
                user = postgresContainer.username,
                password = postgresContainer.password,
            ),
            key = "test"
        )
        connectionPool = ConnectionPool(factory)

        // Create the meilisearch_nodes and storage_system_nodes tables directly
        ConnectionManager(connectionPool).use { cm ->
            cm.useStatement(SCHEMA_SQL) { it.execute() }
        }

        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { connectionPool }
        provides<Json>(singleton = true) { Json { ignoreUnknownKeys = true } }

        val json = Json { ignoreUnknownKeys = true }
        val client = MeilisearchClient(
            url = meilisearchContainer.url,
            apiKey = meilisearchContainer.apiKey,
            json = json,
        )

        // The repository is KSP-generated; we need to construct it manually for tests.
        // Since we can't instantiate the generated impl directly without KSP output,
        // we test through the service which uses a simplified repository approach.
        nodeRepository = TestMeilisearchNodeRepository(connectionPool)
        val encryption = TestEncryptionService("test-encryption-key")
        service = MeilisearchAdminServiceImpl(client, nodeRepository, encryption, json)
    }

    @AfterTest
    fun teardown() = runBlocking {
        if (::connectionPool.isInitialized) connectionPool.close()
        if (::meilisearchContainer.isInitialized) meilisearchContainer.stop()
        if (::postgresContainer.isInitialized) postgresContainer.stop()
        ProviderRegistry.clear()
    }

    private suspend fun <T> withConnectionManager(block: suspend () -> T): T {
        val cm = ConnectionManager(connectionPool)
        return withContext(cm.asCoroutineContext()) {
            try {
                block()
            } finally {
                withContext(NonCancellable) {
                    cm.release()
                }
            }
        }
    }

    // ── Server Info Tests ──

    @Test
    fun `getVersion returns Meilisearch version info`() = runBlocking {
        val version = service.getVersion()
        assertNotNull(version.pkgVersion)
        assertTrue(version.pkgVersion.startsWith("1."), "Expected v1.x but got ${version.pkgVersion}")
    }

    @Test
    fun `getHealth returns true for healthy instance`() = runBlocking {
        val healthy = service.getHealth()
        assertTrue(healthy, "Meilisearch should be healthy")
    }

    @Test
    fun `getStats returns global statistics`() = runBlocking {
        val stats = service.getStats()
        assertTrue(stats.databaseSize > 0, "Database size should be positive")
    }

    // ── Index Lifecycle Tests ──

    @Test
    fun `createIndex and getIndex round-trips index metadata`() = runBlocking {
        val uid = meilisearchContainer.indexUid("create-index")
        val task = service.createIndex(uid, "id")
        assertEquals("indexCreation", task.type)

        // Wait for the task to complete
        waitForTask(task.uid)

        val index = service.getIndex(uid)
        assertNotNull(index, "Index should exist after creation")
        assertEquals(uid, index.uid)
        assertEquals("id", index.primaryKey)

        // Cleanup
        service.deleteIndex(uid)
        Unit
    }

    @Test
    fun `getIndexes lists created indexes`() = runBlocking {
        val uid = meilisearchContainer.indexUid("list-indexes")
        val task = service.createIndex(uid, null)
        waitForTask(task.uid)

        val indexes = service.getIndexes(null, null)
        assertTrue(indexes.any { it.uid == uid }, "Created index should appear in list")

        service.deleteIndex(uid)
        Unit
    }

    @Test
    fun `deleteIndex removes an index`() = runBlocking {
        val uid = meilisearchContainer.indexUid("delete-index")
        val createTask = service.createIndex(uid, null)
        waitForTask(createTask.uid)

        val deleteTask = service.deleteIndex(uid)
        assertEquals("indexDeletion", deleteTask.type)
        waitForTask(deleteTask.uid)

        val index = service.getIndex(uid)
        assertEquals(null, index, "Index should not exist after deletion")
    }

    // ── Settings Tests ──

    @Test
    fun `updateSettings and getIndexSettings round-trips configuration`() = runBlocking {
        val uid = meilisearchContainer.indexUid("settings")
        val createTask = service.createIndex(uid, "id")
        waitForTask(createTask.uid)

        val settingsInput = MeilisearchSettingsInput(
            searchableAttributes = listOf("title", "description"),
            sortableAttributes = listOf("createdAt"),
            stopWords = listOf("the", "a"),
        )
        val settingsTask = service.updateSettings(uid, settingsInput)
        waitForTask(settingsTask.uid)

        val settings = service.getIndexSettings(uid)
        assertEquals(listOf("title", "description"), settings.searchableAttributes)
        assertTrue(settings.sortableAttributes.contains("createdAt"))
        assertTrue(settings.stopWords.containsAll(listOf("the", "a")))

        service.deleteIndex(uid)
        Unit
    }

    // ── Task Tests ──

    @Test
    fun `getTasks returns recent tasks`() = runBlocking {
        val uid = meilisearchContainer.indexUid("tasks")
        service.createIndex(uid, null)

        val tasks = service.getTasks(limit = 10, from = null, status = null, type = null, indexUid = null)
        assertTrue(tasks.isNotEmpty(), "Should have at least one task")

        service.deleteIndex(uid)
        Unit
    }

    @Test
    fun `getTask retrieves a specific task`() = runBlocking {
        val uid = meilisearchContainer.indexUid("task-detail")
        val createTask = service.createIndex(uid, null)

        val task = service.getTask(createTask.uid)
        assertEquals(createTask.uid, task.uid)
        assertEquals("indexCreation", task.type)

        service.deleteIndex(uid)
        Unit
    }

    // ── Keys Tests ──

    @Test
    fun `getKeys returns API keys`() = runBlocking {
        val keys = service.getKeys()
        assertTrue(keys.isNotEmpty(), "Should have at least one API key (default keys)")
    }

    // ── Node Management Tests ──

    @Test
    fun `addNode persists and getNodes retrieves nodes`() = runBlocking {
        withConnectionManager {
            val node = service.addNode(
                name = "Test Node",
                description = "A test node",
                url = "http://localhost:7700",
                apiKey = "test-key",
                types = listOf(StorageSystemType.SEARCH, StorageSystemType.VECTOR),
            )
            assertNotNull(node)
            assertEquals("Test Node", node.name)

            val nodes = service.getNodes()
            assertTrue(nodes.any { it.name == "Test Node" }, "Added node should appear in list")
        }
    }

    @Test
    fun `editNode updates node properties`() = runBlocking {
        withConnectionManager {
            val node = service.addNode(
                name = "Edit Test",
                description = "Before edit",
                url = "http://localhost:7700",
                apiKey = "key-1",
                types = listOf(StorageSystemType.SEARCH),
            )

            val updated = service.editNode(
                id = node.id,
                name = "Edited Node",
                description = "After edit",
                url = null,
                apiKey = null,
                types = listOf(StorageSystemType.SEARCH, StorageSystemType.VECTOR),
            )
            assertEquals("Edited Node", updated.name)
            assertEquals("After edit", updated.description)
            assertEquals("http://localhost:7700", updated.url)
            assertTrue(updated.types.contains(StorageSystemType.VECTOR))
        }
    }

    @Test
    fun `deleteNode removes a node`() = runBlocking {
        withConnectionManager {
            val node = service.addNode(
                name = "Delete Test",
                description = "",
                url = "http://localhost:7700",
                apiKey = "key",
                types = listOf(StorageSystemType.SEARCH),
            )

            val result = service.deleteNode(node.id)
            assertTrue(result, "Delete should return true")

            val found = service.getNode(node.id)
            assertEquals(null, found, "Node should not exist after deletion")
        }
    }

    @Test
    fun `getNodeHealth checks live node health`() = runBlocking {
        val url = "http://${meilisearchContainer.host}:${meilisearchContainer.getMappedPort(7700)}"
        withConnectionManager {
            val node = service.addNode("health-test", "", url, meilisearchApiKey, listOf(StorageSystemType.SEARCH))
            val healthy = service.getNodeHealth(node.id)
            assertTrue(healthy, "Running Meilisearch node should be healthy")
        }
    }

    @Test
    fun `getNodeHealth returns false for unreachable node`() = runBlocking {
        withConnectionManager {
            val node = service.addNode("unreachable", "", "http://localhost:19999", "bad-key", listOf(StorageSystemType.SEARCH))
            val healthy = service.getNodeHealth(node.id)
            assertTrue(!healthy, "Unreachable node should not be healthy")
        }
    }

    @Test
    fun `getNodeVersion returns version from a live node`() = runBlocking {
        val url = "http://${meilisearchContainer.host}:${meilisearchContainer.getMappedPort(7700)}"
        withConnectionManager {
            val node = service.addNode("version-test", "", url, meilisearchApiKey, listOf(StorageSystemType.SEARCH))
            val version = service.getNodeVersion(node.id)
            assertNotNull(version, "Should get version from live node")
            assertTrue(version.pkgVersion.startsWith("1."))
        }
    }

    @Test
    fun `getNodeIndexes returns index UIDs from a live node`() = runBlocking {
        val url = "http://${meilisearchContainer.host}:${meilisearchContainer.getMappedPort(7700)}"
        val uid = meilisearchContainer.indexUid("node-indexes")
        val task = service.createIndex(uid, null)
        waitForTask(task.uid)

        withConnectionManager {
            val node = service.addNode("indexes-test", "", url, meilisearchApiKey, listOf(StorageSystemType.SEARCH))
            val indexes = service.getNodeIndexes(node.id)
            assertTrue(indexes.contains(uid), "Node should list the created index")
        }

        service.deleteIndex(uid)
        Unit
    }

    // ── Document Tests ──

    @Test
    fun `deleteAllDocuments enqueues a deletion task`() = runBlocking {
        val uid = meilisearchContainer.indexUid("delete-docs")
        val createTask = service.createIndex(uid, "id")
        waitForTask(createTask.uid)

        val task = service.deleteAllDocuments(uid)
        assertNotNull(task)
        assertEquals("documentDeletion", task.type)

        service.deleteIndex(uid)
        Unit
    }

    // ── Instance Operations ──

    @Test
    fun `createDump enqueues a dump task`() = runBlocking {
        val task = service.createDump()
        assertNotNull(task)
        assertEquals("dumpCreation", task.type)
    }

    // ── Storage System Assignment Tests ──

    @Test
    fun `assignNodeToStorageSystem and removeNodeFromStorageSystem round-trip`() = runBlocking {
        withConnectionManager {
            val node = service.addNode("storage-test", "", "http://localhost:7700", "key", listOf(StorageSystemType.SEARCH))
            val storageSystemId = Uuid.random()

            val assigned = service.assignNodeToStorageSystem(storageSystemId, node.id)
            assertTrue(assigned, "Should assign successfully")

            val removed = service.removeNodeFromStorageSystem(storageSystemId, node.id)
            assertTrue(removed, "Should remove successfully")
        }
    }

    @Test
    fun `assignNodeToStorageSystem returns false for non-existent node`() = runBlocking {
        withConnectionManager {
            val fakeNodeId = Uuid.random()
            val fakeStorageId = Uuid.random()
            val result = service.assignNodeToStorageSystem(fakeStorageId, fakeNodeId)
            assertTrue(!result, "Should return false for non-existent node")
        }
    }

    @Test
    fun `removeNodeFromStorageSystem returns false for non-existent node`() = runBlocking {
        withConnectionManager {
            val fakeNodeId = Uuid.random()
            val fakeStorageId = Uuid.random()
            val result = service.removeNodeFromStorageSystem(fakeStorageId, fakeNodeId)
            assertTrue(!result, "Should return false for non-existent node")
        }
    }

    // ── Error/Edge-Case Tests ──

    @Test
    fun `editNode throws for non-existent node`(): Unit = runBlocking {
        withConnectionManager {
            val fakeId = Uuid.random()
            assertFailsWith<IllegalStateException> {
                service.editNode(fakeId, "name", null, null, null, null)
            }
        }
    }

    @Test
    fun `deleteNode returns false for non-existent node`() = runBlocking {
        withConnectionManager {
            val fakeId = Uuid.random()
            val result = service.deleteNode(fakeId)
            assertTrue(!result, "Should return false for non-existent node")
        }
    }

    @Test
    fun `getIndex returns null for non-existent index`() = runBlocking {
        val index = service.getIndex(meilisearchContainer.indexUid("non-existent-index"))
        assertEquals(null, index, "Should return null for non-existent index")
    }

    @Test
    fun `editNode updates API key when provided`(): Unit = runBlocking {
        withConnectionManager {
            val node = service.addNode("key-update-test", "", "http://localhost:7700", "old-key", listOf(StorageSystemType.SEARCH))

            val updated = service.editNode(
                id = node.id,
                name = null,
                description = null,
                url = null,
                apiKey = "new-key",
                types = null,
            )
            assertEquals("new-key", updated.decryptedApiKey)
            assertNotNull(updated.apiKey, "Encrypted API key should be set")
        }
    }

    @Test
    fun `swapIndexes rejects invalid pairs`(): Unit = runBlocking {
        assertFailsWith<IllegalArgumentException> {
            service.swapIndexes(listOf(listOf("only-one")))
        }
    }

    // ── Helpers ──

    private suspend fun waitForTask(uid: Int, maxWaitMs: Long = 10_000) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < maxWaitMs) {
            val task = service.getTask(uid)
            if (task.status == "succeeded" || task.status == "failed") return
            kotlinx.coroutines.delay(100)
        }
        error("Task $uid did not complete within ${maxWaitMs}ms")
    }

    companion object {
        /**
         * SQL to create the required tables for Meilisearch node management.
         * Uses text[] instead of storage_system_type[] since the enum type
         * is not available in the test database.
         */
        private val SCHEMA_SQL = """
            CREATE TABLE meilisearch_nodes (
                id uuid NOT NULL DEFAULT gen_random_uuid(),
                name varchar NOT NULL,
                description varchar NOT NULL DEFAULT '',
                url varchar NOT NULL,
                api_key bytea,
                api_key_nonce bytea,
                types text[] NOT NULL DEFAULT '{}',
                configuration jsonb NOT NULL DEFAULT '{}'::jsonb,
                PRIMARY KEY (id)
            );

            CREATE TABLE storage_system_nodes (
                storage_system_id uuid NOT NULL,
                node_id uuid NOT NULL,
                PRIMARY KEY (storage_system_id, node_id),
                FOREIGN KEY (node_id) REFERENCES meilisearch_nodes (id) ON DELETE CASCADE
            );
        """.trimIndent()
    }
}

/**
 * Lightweight AES-GCM encryption service for tests, avoiding the need for a
 * full Ktor [Application] instance required by [EncryptionServiceImpl].
 */
private class TestEncryptionService(key: String) : EncryptionService {
    private val random = SecureRandom()
    private val secretKey: SecretKeySpec

    init {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(key.toByteArray(Charsets.UTF_8))
        secretKey = SecretKeySpec(hash.copyOf(32), "AES")
    }

    override suspend fun encrypt(plaintext: ByteArray, id: UUID): EncryptionService.Encrypted {
        val nonce = ByteArray(12)
        random.nextBytes(nonce)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, nonce))
        return EncryptionService.Encrypted(nonce = nonce, data = cipher.doFinal(plaintext))
    }

    override suspend fun decrypt(encrypted: EncryptionService.Encrypted, id: UUID): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, encrypted.nonce))
        return cipher.doFinal(encrypted.data)
    }
}
