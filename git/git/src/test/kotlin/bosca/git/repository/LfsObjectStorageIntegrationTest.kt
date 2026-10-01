@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.git.repository

import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.connection
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.git.model.LfsUploadValidationException
import bosca.git.service.LfsObjectServiceImpl
import bosca.serialization.UUID
import bosca.storage.service.FileSystemObjectStorageService
import bosca.test.resources.SharedPostgreSQLContainer
import io.mockk.mockk
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.*

/** Exercises the production migrations, generated JDBC mapping, and filesystem multipart storage. */
class LfsObjectStorageIntegrationTest {
    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("lfs_storage_test")
            withReuse(true)
            start()
        }
        private val pool = ConnectionPool(ConnectionFactoryImpl(ConnectionConfig(
            url = postgres.jdbcUrl, user = postgres.username, password = postgres.password, maxConnections = 4,
        ), key = "lfs-storage-test"))
    }

    private val repositoryId = UUID.random()
    private val repository = LfsObjectRepositoryImpl()
    private val directory = Files.createTempDirectory("lfs-storage-test").toFile()
    private val storage = FileSystemObjectStorageService("", "", mockk(), directory.path, mockk())
    private val service = LfsObjectServiceImpl(repository, storage)
    private val data = "verified LFS bytes".toByteArray()
    private val oid = digest(data)

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { Json }
        withDb {
            connection().useStatement("drop schema if exists git cascade; create schema git; create table git.repositories (id uuid primary key)") { it.execute() }
            migrate("V5__lfs_and_deploy_tokens.sql")
            connection().useStatement("insert into git.repositories(id) values ('$repositoryId')") { it.execute() }
        }
    }

    @AfterTest
    fun cleanup() {
        directory.deleteRecursively()
        ProviderRegistry.clear()
    }

    @Test
    fun `migration preserves the storage paths of existing objects`() = withDb {
        connection().useStatement("insert into git.lfs_objects(repository_id, oid, size) values ('$repositoryId', '$oid', ${data.size})") { it.execute() }
        val path = directory.resolve("git-lfs/$repositoryId/$oid")
        path.parentFile.mkdirs()
        path.writeBytes(data)
        migrate("V39__lfs_storage_path.sql")
        assertEquals("git-lfs/$repositoryId/$oid", repository.findByOid(repositoryId, oid)?.storagePath)
        assertContentEquals(data, service.download(repositoryId, oid).use { it.readBytes() })
    }

    @Test
    fun `verified multipart upload round trips through PostgreSQL and storage`() = withDb {
        migrate("V39__lfs_storage_path.sql")
        val uploaded = service.upload(repositoryId, oid, data.inputStream(), data.size.toLong())
        val loaded = assertNotNull(repository.findByOid(repositoryId, oid))
        assertEquals(uploaded.storagePath, loaded.storagePath)
        assertContentEquals(data, service.download(repositoryId, oid).use { it.readBytes() })
        assertEquals(listOf(loaded.storagePath), storedFiles())
    }

    @Test
    fun `duplicate upload retains the winning path and removes the new object`() = withDb {
        migrate("V39__lfs_storage_path.sql")
        val first = service.upload(repositoryId, oid, data.inputStream(), data.size.toLong())
        val second = service.upload(repositoryId, oid, data.inputStream(), data.size.toLong())
        assertEquals(first.id, second.id)
        assertEquals(first.storagePath, second.storagePath)
        assertEquals(1, repository.findByRepository(repositoryId).size)
        assertEquals(listOf(first.storagePath), storedFiles())
    }

    @Test
    fun `invalid upload cannot replace an existing object or leave partial files`() = withDb {
        migrate("V39__lfs_storage_path.sql")
        val original = service.upload(repositoryId, oid, data.inputStream(), data.size.toLong())
        assertFailsWith<LfsUploadValidationException> {
            service.upload(repositoryId, oid, ByteArray(data.size).inputStream(), data.size.toLong())
        }
        assertEquals(listOf(original.storagePath), storedFiles())
        assertContentEquals(data, service.download(repositoryId, oid).use { it.readBytes() })
    }

    @Test
    fun `database failure removes the completed storage object`() = withDb {
        migrate("V39__lfs_storage_path.sql")
        assertFails {
            service.upload(UUID.random(), oid, data.inputStream(), data.size.toLong())
        }
        assertEquals(emptyList(), storedFiles())
    }

    private fun storedFiles() = directory.walkTopDown().filter { it.isFile }.map { it.relativeTo(directory).invariantSeparatorsPath }.toList()

    private suspend fun migrate(name: String) {
        val sql = javaClass.getResource("/db/migrations/$name")?.readText() ?: error("Missing migration $name")
        connection().useStatement(sql) { it.execute() }
    }

    private fun withDb(block: suspend () -> Unit) = runBlocking {
        val manager = pool.connection()
        try {
            withContext(manager.asCoroutineContext()) { block() }
        } finally {
            withContext(NonCancellable) { manager.release() }
        }
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
