package bosca.backup

import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.storage.service.ManagedFileSystemObjectStorageService
import bosca.storage.service.ObjectPath
import bosca.storage.service.ObjectStorageInfo
import bosca.storage.service.ManagedObjectStorageService
import io.mockk.mockk
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Real filesystem stores with fault injection at the object-storage boundary. */
class PostgresBackupTransferTest {
    private lateinit var root: Path
    private lateinit var local: Path
    private lateinit var retained: Path
    private lateinit var application: BoscaApplication
    private lateinit var service: PostgresBackupService
    private lateinit var storage: ManagedFileSystemObjectStorageService
    private lateinit var backup: ManagedObjectStorageService
    private val prefix = "postgres/capture"
    private val bytes = "PGDMP".toByteArray() + ByteArray(65537) { (it % 251).toByte() }
    private val manifest = PostgresBackupManifest(
        "capture", "source", "bosca", "2026-10-18T00:00:00Z", "2026-10-18T00:01:00Z",
        "exported", "1:2:", "18", 180000, "UTF8", mapOf("vector" to "0.8.1"),
        bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes).toHexString(),
    )
    private val writes = mutableListOf<String>()
    private val reads = mutableListOf<String>()
    private val stats = mutableListOf<String>()
    private var opened = 0
    private var closed = 0
    private var storageClosed = false
    private var write: (suspend (ObjectPath, InputStream, Long?) -> Long)? = null
    private var read: (suspend (ObjectPath) -> InputStream)? = null
    private val downloaded get() = root.resolve("download")
    private val payload get() = retained.resolve("$prefix/database.dump")
    private val intent get() = retained.resolve("$prefix/intent.json")
    private val completed get() = retained.resolve("$prefix/manifest.json")
    private val prettyJson = Json { prettyPrint = true }

    @BeforeTest
    fun setUp() {
        root = createTempDirectory("bosca-postgres-transfer-")
        local = Files.createDirectory(root.resolve("local"))
        retained = Files.createDirectory(root.resolve("independent"))
        Files.write(local.resolve("database.dump"), bytes)
        local.resolve("manifest.json").writeText(encoded())
        application = BoscaApplication(ApplicationConfig.load("{}".byteInputStream()), ownsServices = false)
        service = PostgresBackupServiceImpl(application, mockk())
        storage = ManagedFileSystemObjectStorageService("", "", mockk(), retained.toString(), mockk())
        backup = object : ManagedObjectStorageService by storage {
            override suspend fun getObjectInfo(path: ObjectPath): ObjectStorageInfo {
                stats.add(path.toString())
                return storage.getObjectInfo(path)
            }

            override suspend fun getInputStream(path: ObjectPath): InputStream {
                reads.add(path.toString())
                val input = read?.invoke(path) ?: storage.getInputStream(path)
                opened++
                return object : FilterInputStream(input) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        assertTrue(length <= 8192, "Unbounded payload read: $length")
                        return super.read(buffer, offset, length)
                    }

                    override fun close() {
                        closed++
                        super.close()
                    }
                }
            }

            override suspend fun setInputStream(path: ObjectPath, stream: InputStream, length: Long?): Long {
                writes.add(path.toString())
                return write?.invoke(path, stream, length) ?: storage.setInputStream(path, stream, length)
            }

            override suspend fun delete(path: ObjectPath) = error("Retained objects must not be deleted: $path")

            override fun close() { storageClosed = true }
        }
    }

    @AfterTest
    fun tearDown(): Unit = runBlocking {
        if (::application.isInitialized) application.shutdown()
        if (::root.isInitialized) root.toFile().deleteRecursively()
    }

    @Test
    fun `verified native artifacts round trip through independent storage`(): Unit = runBlocking {
        assertEquals(manifest, service.upload(local, backup, prefix))
        assertEquals(listOf("$prefix/intent.json", "$prefix/database.dump", "$prefix/manifest.json"), writes)
        assertEquals(manifest, decode(intent))
        local.toFile().deleteRecursively()
        assertEquals(manifest, service.download(backup, prefix, downloaded))
        assertEquals(manifest, service.verify(downloaded))
        assertContentEquals(bytes, Files.readAllBytes(downloaded.resolve("database.dump")))
        assertEquals(setOf("database.dump", "manifest.json"), downloaded.toFile().list()?.toSet())
        assertEquals(opened, closed)
        assertFalse(storageClosed)
    }

    @Test
    fun `matching publication replay verifies bytes without rewriting them`(): Unit = runBlocking {
        service.upload(local, backup, prefix)
        writes.clear()
        assertEquals(manifest, service.upload(local, backup, prefix))
        assertTrue(writes.isEmpty())
        assertEquals(opened, closed)
    }

    @Test
    fun `different local intent cannot replace an existing backup`(): Unit = runBlocking {
        service.upload(local, backup, prefix)
        local.resolve("manifest.json").writeText(encoded(manifest.copy(id = "different")))
        writes.clear()
        assertFailsWith<IllegalArgumentException> { service.upload(local, backup, prefix) }
        assertTrue(writes.isEmpty())
        assertEquals(manifest, decode(completed))
        assertContentEquals(bytes, Files.readAllBytes(payload))
    }

    @Test
    fun `unknown preexisting payload is never adopted or overwritten`(): Unit = runBlocking {
        Files.createDirectories(payload.parent)
        Files.write(payload, bytes)
        assertFailsWith<IllegalArgumentException> { service.upload(local, backup, prefix) }
        assertTrue(writes.isEmpty())
        assertFalse(Files.exists(intent))
        assertFalse(Files.exists(completed))
    }

    @Test
    fun `partial payload retries preserve intent and repair only owned output`(): Unit = runBlocking {
        write = { path, stream, length ->
            if (path.toString().endsWith("database.dump")) {
                assertEquals(manifest, decode(intent))
                assertFalse(Files.exists(completed))
                storage.setInputStream(path, stream.readNBytes(32).inputStream(), 32)
                throw IOException("interrupted payload")
            }
            storage.setInputStream(path, stream, length)
        }
        assertFailsWith<IOException> { service.upload(local, backup, prefix) }
        assertEquals(manifest, decode(intent))
        assertFalse(Files.exists(completed))
        write = null
        service = PostgresBackupServiceImpl(application, mockk())
        assertEquals(manifest, service.upload(local, backup, prefix))
        assertEquals(2, writes.count { it.endsWith("database.dump") })
        assertContentEquals(bytes, Files.readAllBytes(payload))
    }

    @Test
    fun `partial completion manifest retries reuse the fully retained dump`(): Unit = runBlocking {
        write = { path, stream, length ->
            if (path.toString().endsWith("manifest.json")) {
                storage.setInputStream(path, "{\"id\":".byteInputStream(), 6)
                6
            } else storage.setInputStream(path, stream, length)
        }
        assertFailsWith<IllegalStateException> { service.upload(local, backup, prefix) }
        write = null
        assertEquals(manifest, service.upload(local, backup, prefix))
        assertEquals(1, writes.count { it.endsWith("database.dump") })
        assertEquals(manifest, decode(completed))
    }

    @Test
    fun `short reported payload writes cannot publish but retry reuses verified output`(): Unit = runBlocking {
        write = { path, stream, length ->
            val written = storage.setInputStream(path, stream, length)
            if (path.toString().endsWith("database.dump")) written - 1 else written
        }
        assertFailsWith<IllegalStateException> { service.upload(local, backup, prefix) }
        assertFalse(Files.exists(completed))
        write = null
        service.upload(local, backup, prefix)
        assertEquals(1, writes.count { it.endsWith("database.dump") })
    }

    @Test
    fun `unconsumed source streams cannot be acknowledged as uploaded`(): Unit = runBlocking {
        write = { path, stream, length ->
            if (path.toString().endsWith("database.dump")) length ?: 0
            else storage.setInputStream(path, stream, length)
        }
        assertFailsWith<BackupPayloadMismatchException> { service.upload(local, backup, prefix) }
        assertFalse(Files.exists(completed))
    }

    @Test
    fun `corrupt retained bytes prevent completion and can be repaired from the same local artifact`(): Unit = runBlocking {
        write = { path, stream, length ->
            if (path.toString().endsWith("database.dump")) {
                val content = stream.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
                storage.setInputStream(path, content.inputStream(), length)
            } else storage.setInputStream(path, stream, length)
        }
        assertFailsWith<BackupPayloadMismatchException> { service.upload(local, backup, prefix) }
        assertFalse(Files.exists(completed))
        write = null
        service.upload(local, backup, prefix)
        assertContentEquals(bytes, Files.readAllBytes(payload))
    }

    @Test
    fun `matching completed artifact can repair missing retained bytes`(): Unit = runBlocking {
        service.upload(local, backup, prefix)
        Files.delete(payload)
        service.upload(local, backup, prefix)
        assertEquals(2, writes.count { it.endsWith("database.dump") })
        assertEquals(manifest, decode(completed))
    }

    @Test
    fun `a valid conflicting completion manifest cannot be replaced using intent`(): Unit = runBlocking {
        service.upload(local, backup, prefix)
        completed.writeText(encoded(manifest.copy(id = "someone-else")))
        writes.clear()
        assertFailsWith<IllegalArgumentException> { service.upload(local, backup, prefix) }
        assertTrue(writes.isEmpty())
        assertEquals("someone-else", decode(completed).id)
    }

    @Test
    fun `completion writes must be read back and compared`(): Unit = runBlocking {
        write = { path, stream, length ->
            if (path.toString().endsWith("manifest.json")) {
                stream.readBytes()
                val wrong = encoded(manifest.copy(id = "wrong")).toByteArray()
                storage.setInputStream(path, wrong.inputStream(), wrong.size.toLong())
                length ?: 0
            } else storage.setInputStream(path, stream, length)
        }
        assertFailsWith<IllegalStateException> { service.upload(local, backup, prefix) }
        assertEquals("wrong", decode(completed).id)
    }

    @Test
    fun `intent must be acknowledged and readable before payload writes`(): Unit = runBlocking {
        write = { path, stream, length ->
            if (path.toString().endsWith("intent.json")) {
                stream.readBytes()
                0
            } else storage.setInputStream(path, stream, length)
        }
        assertFailsWith<IllegalStateException> { service.upload(local, backup, prefix) }
        assertFalse(Files.exists(payload))
        assertFalse(Files.exists(completed))
        assertEquals(listOf("$prefix/intent.json"), writes)
    }

    @Test
    fun `malformed intent cannot authorize replacement of unknown payloads`(): Unit = runBlocking {
        Files.createDirectories(intent.parent)
        intent.writeText("{")
        Files.write(payload, bytes)
        assertFailsWith<SerializationException> { service.upload(local, backup, prefix) }
        assertTrue(writes.isEmpty())
        assertContentEquals(bytes, Files.readAllBytes(payload))
    }

    @Test
    fun `changed source bytes during upload prevent completion`(): Unit = runBlocking {
        write = { path, stream, length ->
            val written = storage.setInputStream(path, stream, length)
            if (path.toString().endsWith("intent.json")) Files.write(local.resolve("database.dump"), bytes + 1.toByte())
            written
        }
        assertFailsWith<BackupPayloadMismatchException> { service.upload(local, backup, prefix) }
        assertFalse(Files.exists(completed))
    }

    @Test
    fun `invalid local payload or destination prefix fails before remote access`(): Unit = runBlocking {
        for (invalid in listOf("", "/absolute", "../parent", "a/../b", "a//b", "a/./b", "a/")) {
            assertFailsWith<IllegalArgumentException> { service.upload(local, backup, invalid) }
            assertFailsWith<IllegalArgumentException> { service.download(backup, invalid, downloaded) }
        }
        Files.write(local.resolve("database.dump"), bytes.copyOf(bytes.size - 1))
        assertFailsWith<BackupPayloadMismatchException> { service.upload(local, backup, prefix) }
        assertTrue(reads.isEmpty() && stats.isEmpty() && writes.isEmpty())
        assertFalse(Files.exists(downloaded))
    }

    @Test
    fun `cancelled upload preserves retry intent and caller storage ownership`(): Unit = runBlocking {
        write = { path, stream, length ->
            if (path.toString().endsWith("database.dump")) {
                throw CancellationException("upload cancelled")
            }
            storage.setInputStream(path, stream, length)
        }
        assertFailsWith<CancellationException> { service.upload(local, backup, prefix) }
        assertEquals(manifest, decode(intent))
        assertFalse(Files.exists(completed))
        assertFalse(storageClosed)
        write = null
        assertEquals(manifest, service.upload(local, backup, prefix))
    }

    @Test
    fun `download requires a completed manifest and never accepts intent as completion`(): Unit = runBlocking {
        Files.createDirectories(intent.parent)
        intent.writeText(encoded())
        Files.write(payload, bytes)
        assertFailsWith<bosca.storage.service.ObjectNotFoundException> { service.download(backup, prefix, downloaded) }
        assertFalse(Files.exists(downloaded))
        assertEquals(listOf("$prefix/manifest.json"), reads)
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `corrupt truncated and oversized retained payloads cannot produce local artifacts`(): Unit = runBlocking {
        service.upload(local, backup, prefix)
        val changed = bytes.clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        for (bad in listOf(changed, bytes.copyOf(bytes.size - 1), bytes + 1.toByte())) {
            Files.write(payload, bad)
            assertFailsWith<BackupPayloadMismatchException> { service.download(backup, prefix, downloaded) }
            assertFalse(Files.exists(downloaded))
        }
        assertEquals(opened, closed)
        assertEquals(manifest, decode(completed))
    }

    @Test
    fun `unsupported metadata and missing payloads fail without creating completed local output`(): Unit = runBlocking {
        Files.createDirectories(completed.parent)
        for (bad in listOf(manifest.copy(version = 3), manifest.copy(payloadPath = "../database.dump"))) {
            completed.writeText(encoded(bad))
            assertFailsWith<IllegalArgumentException> { service.download(backup, prefix, downloaded) }
            assertFalse(Files.exists(downloaded))
        }
        completed.writeText(encoded())
        assertFailsWith<bosca.storage.service.ObjectNotFoundException> { service.download(backup, prefix, downloaded) }
        assertFalse(Files.exists(downloaded))
        assertEquals(opened, closed)
    }

    @Test
    fun `existing local output is never touched and requires no remote reads`(): Unit = runBlocking {
        Files.createDirectory(downloaded)
        downloaded.resolve("keep").writeText("original")
        assertFailsWith<java.nio.file.FileAlreadyExistsException> { service.download(backup, prefix, downloaded) }
        assertEquals("original", downloaded.resolve("keep").readText())
        assertTrue(reads.isEmpty() && stats.isEmpty() && writes.isEmpty())
    }

    @Test
    fun `metadata backend failures are never treated as missing completion`(): Unit = runBlocking {
        service.upload(local, backup, prefix)
        writes.clear()
        read = { path ->
            if (path.toString().endsWith("manifest.json")) throw IOException("completion unavailable")
            storage.getInputStream(path)
        }
        val failure = assertFailsWith<IOException> { service.upload(local, backup, prefix) }
        assertEquals("completion unavailable", failure.message)
        assertTrue(writes.isEmpty())
        assertEquals(manifest, decode(completed))
    }

    @Test
    fun `backend serialization failures are not mistaken for partially written JSON`(): Unit = runBlocking {
        service.upload(local, backup, prefix)
        writes.clear()
        reads.clear()
        read = { path ->
            if (path.toString().endsWith("manifest.json")) throw SerializationException("backend response failed")
            storage.getInputStream(path)
        }
        val failure = assertFailsWith<SerializationException> { service.upload(local, backup, prefix) }
        assertEquals("backend response failed", failure.message)
        assertTrue(writes.isEmpty())
        assertEquals(listOf("$prefix/manifest.json"), reads)
    }

    @Test
    fun `unsupported completed formats cannot be repaired using matching intent`(): Unit = runBlocking {
        service.upload(local, backup, prefix)
        completed.writeText(encoded(manifest.copy(version = 3)))
        writes.clear()
        assertFailsWith<IllegalArgumentException> { service.upload(local, backup, prefix) }
        assertTrue(writes.isEmpty())
        assertEquals(3, decode(completed).version)
    }

    @Test
    fun `a matching digest without a native header is insufficient for download`(): Unit = runBlocking {
        Files.createDirectories(completed.parent)
        val invalid = "not a PostgreSQL archive".toByteArray()
        Files.write(payload, invalid)
        completed.writeText(encoded(manifest.copy(
            length = invalid.size.toLong(), sha256 = MessageDigest.getInstance("SHA-256").digest(invalid).toHexString(),
        )))
        assertFailsWith<IllegalStateException> { service.download(backup, prefix, downloaded) }
        assertFalse(Files.exists(downloaded))
        assertEquals(opened, closed)
    }

    @Test
    fun `download cancellation and backend failures close streams remove partial files and preserve retained data`(): Unit = runBlocking {
        service.upload(local, backup, prefix)
        val savedManifest = completed.readText()
        for (failure in listOf(CancellationException("cancelled read"), IOException("backend failed"))) {
            read = { path ->
                val actual = storage.getInputStream(path)
                if (path.toString().endsWith("database.dump")) object : FilterInputStream(actual) {
                    private var count = 0
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (count > 0) throw failure
                        return super.read(buffer, offset, minOf(length, 32)).also { count += it }
                    }
                } else actual
            }
            if (failure is CancellationException) assertFailsWith<CancellationException> { service.download(backup, prefix, downloaded) }
            else assertFailsWith<IOException> { service.download(backup, prefix, downloaded) }
            assertFalse(Files.exists(downloaded))
            assertContentEquals(bytes, Files.readAllBytes(payload))
            assertEquals(savedManifest, completed.readText())
        }
        assertEquals(opened, closed)
        assertFalse(storageClosed)
    }

    private fun encoded(value: PostgresBackupManifest = manifest) = Json.encodeToString(PostgresBackupManifest.serializer(), value)
    private fun decode(path: Path) = Json.decodeFromString(PostgresBackupManifest.serializer(), path.readText())
}
