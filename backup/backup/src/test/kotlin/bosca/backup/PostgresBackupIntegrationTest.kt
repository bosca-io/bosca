package bosca.backup

import bosca.cache.CacheManager
import bosca.content.model.ContentStorageReference
import bosca.content.model.ContentStorageReferenceKind
import bosca.content.metadata.repository.MetadataRepositoryImpl
import bosca.content.metadata.service.MetadataService
import bosca.content.metadata.service.MetadataServiceImpl
import bosca.content.collection.repository.CollectionRepositoryImpl
import bosca.content.collection.service.CollectionService
import bosca.content.collection.service.CollectionServiceImpl
import bosca.db.ConnectionManager
import bosca.db.asCoroutineContext
import bosca.db.migrations.CoreMigration
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.migrations.FlywayMigration
import bosca.db.ConnectionPool
import bosca.db.connection
import bosca.db.transaction
import bosca.db.withConnectionManager
import bosca.di.BackupProviderRegistrar
import bosca.di.ProviderRegistry
import bosca.di.StorageProviderRegistrar
import bosca.di.annotation.InternalDI
import bosca.di.provide
import bosca.di.provides
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.storage.configuration.StorageConfiguration
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.ObjectStorageServiceFactory
import bosca.storage.service.ObjectPath
import java.io.InputStream
import java.io.IOException
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import java.util.Properties
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Generated service wiring, real pg_dump/pg_restore and isolated pgvector databases. */
@OptIn(InternalDI::class)
class PostgresBackupIntegrationTest {
    private lateinit var root: Path
    private lateinit var postgres: GenericContainer<Nothing>
    private lateinit var pool: ConnectionPool
    private lateinit var application: BoscaApplication
    private lateinit var service: PostgresBackupService
    private lateinit var tool: Path
    private lateinit var restoreTool: Path
    private lateinit var psqlTool: Path
    private var targetName = "recovered"
    private var retained: StorageConfiguration? = null
    private val output get() = root.resolve("capture")
    private val user = "backup_test"
    private val password = "local-test-only"
    private val url get() = "jdbc:postgresql://${postgres.host}:${postgres.getMappedPort(5432)}/"

    @BeforeTest
    fun setUp(): Unit = runBlocking {
        root = createTempDirectory("bosca-postgres-backup-").toRealPath()
        postgres = GenericContainer<Nothing>("pgvector/pgvector:pg18").apply {
            withEnv("POSTGRES_USER", user)
            withEnv("POSTGRES_PASSWORD", password)
            withEnv("POSTGRES_DB", "source")
            withExposedPorts(5432)
            withFileSystemBind(root.toString(), root.toString())
            waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2))
            start()
        }
        // The tool container has the same pg_dump major version as the real database and sees
        // the caller's scratch files. Production invokes the configured native executable directly.
        root.resolve("services.conf").writeText(
            "[native capture]\nhost=127.0.0.1\nport=5432\ndbname=source\nuser=$user\npassword=$password\n",
        )
        tool = writeTool("exec docker exec -e PGSERVICEFILE=\"\$PGSERVICEFILE\" ${postgres.containerId} pg_dump \"\$@\"")
        restoreTool = writeTool("exec docker exec -e PGSERVICEFILE=\"\$PGSERVICEFILE\" ${postgres.containerId} pg_restore \"\$@\"")
        psqlTool = writeTool("exec docker exec -e PGSERVICEFILE=\"\$PGSERVICEFILE\" ${postgres.containerId} psql \"\$@\"")
        root.resolve("restore.conf").writeText(
            "[native restore]\nhost=127.0.0.1\nport=5432\ndbname=recovered\nuser=recovered_owner\npassword=$password\n",
        )
        pool = ConnectionPool(ConnectionFactoryImpl(ConnectionConfig(url + "source", user, password, maxConnections = 2), "native-capture"))
        ProviderRegistry.clear()
        install()
        sql("""
            create extension vector;
            create schema workops;
            create schema git;
            create schema artifacts;
            create schema content;
            create schema migrations;
            create table workops.tasks (id bigint generated by default as identity primary key, body jsonb not null);
            insert into workops.tasks values (1, '{"name":"captured"}');
            select setval('workops.tasks_id_seq', 41);
            create table git.commits (id text primary key, task_id bigint references workops.tasks(id));
            insert into git.commits values ('commit', 1);
            create table artifacts.events (id int, value text) partition by range (id);
            create table artifacts.events_2026 partition of artifacts.events for values from (0) to (100);
            insert into artifacts.events values (1, 'retained');
            create table content.permissions (group_id text primary key, permissions text[] not null);
            insert into content.permissions values ('editors', array['VIEW','EDIT']);
            create table content.embeddings (id int primary key, value vector(3));
            insert into content.embeddings values (1, '[1,2,3]');
            create table content.large_objects (id oid);
            insert into content.large_objects values (lo_from_bytea(0, decode('01020304','hex')));
            create table migrations.schema_history (version text primary key);
            insert into migrations.schema_history values ('current');
        """.trimIndent())
    }

    @AfterTest
    fun tearDown(): Unit = runBlocking {
        try {
            if (::application.isInitialized) application.shutdown()
            if (::pool.isInitialized) pool.close()
        } finally {
            ProviderRegistry.clear()
            if (::postgres.isInitialized) postgres.stop()
            if (::root.isInitialized) root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `native artifact restores all schemas after source database is dropped`(): Unit = runBlocking {
        val manifest = service.capture("source-instance", output)
        assertEquals("source-instance", manifest.sourceId)
        assertEquals("source", manifest.database)
        assertTrue(manifest.serverVersionNumber >= 180000)
        assertEquals("UTF8", manifest.encoding)
        assertTrue(manifest.extensions.getValue("vector").isNotBlank())
        assertTrue(manifest.snapshotId.isNotBlank() && manifest.snapshot.isNotBlank())
        assertTrue(Instant.parse(manifest.completed) >= Instant.parse(manifest.created))
        assertEquals(manifest, service.verify(output))
        assertEquals(setOf("database.dump", "manifest.json"), output.toFile().list()?.toSet())
        assertFalse(output.resolve("manifest.json").readText().contains(password))
        assertFalse(output.resolve("manifest.json").readText().contains("services.conf"))
        assertFalse(output.resolve("manifest.json").readText().contains("native capture"))
        assertEquals(0, pool.activeConnections)

        pool.close()
        sql("drop database source with (force)", "postgres")
        install(configured = false)
        assertEquals(manifest, service.verify(output))
        restore()
        assertEquals("captured", scalar("select body->>'name' from workops.tasks", "recovered"))
        assertEquals("1", scalar("select task_id::text from git.commits", "recovered"))
        assertEquals("retained", scalar("select value from artifacts.events", "recovered"))
        assertEquals("{VIEW,EDIT}", scalar("select permissions::text from content.permissions", "recovered"))
        assertEquals("[1,2,3]", scalar("select value::text from content.embeddings", "recovered"))
        assertEquals("01020304", scalar("select encode(lo_get(id),'hex') from content.large_objects", "recovered"))
        assertEquals("current", scalar("select version from migrations.schema_history", "recovered"))
        assertEquals("42", scalar("select nextval('workops.tasks_id_seq')::text", "recovered"))
        assertEquals("recovered_owner", scalar("select tableowner from pg_tables where schemaname='workops' and tablename='tasks'", "recovered"))
        assertEquals("1", scalar("select count(*)::text from pg_constraint where conrelid='git.commits'::regclass and contype='f'", "recovered"))
    }

    @Test
    fun `writes continue after exported boundary and are excluded from restored data`(): Unit = runBlocking {
        val entered = root.resolve("entered")
        val release = root.resolve("release")
        tool = writeTool("""
            touch ${quote(entered)}
            while [ ! -f ${quote(release)} ]; do sleep 0.03; done
            exec docker exec -e PGSERVICEFILE="${'$'}PGSERVICEFILE" ${postgres.containerId} pg_dump "${'$'}@"
        """.trimIndent())
        install()
        val capture = async { service.capture("live", output) }
        awaitFile(entered)
        assertFalse(Files.exists(output.resolve("manifest.json")))
        assertEquals(1, pool.activeConnections)
        assertFailsWith<java.nio.file.FileAlreadyExistsException> { service.capture("other", output) }
        sql("update workops.tasks set body='{\"name\":\"new\"}'; insert into workops.tasks values (2,'{}'); delete from artifacts.events")
        release.writeText("go")
        val manifest = withTimeout(30_000) { capture.await() }
        assertEquals(0, pool.activeConnections)
        assertEquals("new", scalar("select body->>'name' from workops.tasks where id=1"))
        restore()
        assertEquals("captured", scalar("select body->>'name' from workops.tasks where id=1", "recovered"))
        assertEquals("1", scalar("select count(*)::text from workops.tasks", "recovered"))
        assertEquals("retained", scalar("select value from artifacts.events", "recovered"))
        assertEquals(manifest, service.verify(output))
    }

    @Test
    fun `content service references in the exported view match the independently restored native artifact`(): Unit = runBlocking {
        FlywayMigration(pool).migrate(listOf(CoreMigration()))
        provides<CacheManager>(singleton = true) { mockk(relaxed = true) }
        val metadata: MetadataService = metadataReferenceService()
        val collections: CollectionService = collectionReferenceService()
        provides<MetadataService>(singleton = true) { metadata }
        provides<CollectionService>(singleton = true) { collections }
        service = PostgresBackupServiceImplProvider().get()
        val id = "10000000-0000-0000-0000-000000000001"
        val deletedId = "10000000-0000-0000-0000-000000000002"
        val supplementaryId = "20000000-0000-0000-0000-000000000001"
        val collectionId = "30000000-0000-0000-0000-000000000001"
        sql("""
            insert into metadata (id, version, name, content_type, content_length, uploaded)
            values ('$id', 8, 'Current', 'image/png', 42, '2026-10-20T10:00:00Z');
            insert into metadata (id, name, content_type, deleted)
            values ('$deletedId', 'Deleted pending upload', 'image/png', true);
            insert into metadata_supplementary (id, metadata_id, key, name, content_type, content_length, uploaded)
            values ('$supplementaryId', '$id', 'thumbnail', 'Current thumbnail', 'image/png', 12, '2026-10-20T10:00:00Z');
            insert into metadata_versions (id, version, name, content_type, content_length)
            values ('$id', 7, 'Historical', 'image/png', 17);
            insert into metadata_versions_supplementary
                (metadata_id, version, key, name, content_type, content_length, uploaded)
            values ('$id', 7, 'thumbnail', 'Historical thumbnail', 'image/png', 18, '2026-10-20T10:00:00Z');
            insert into collections (id, name, deleted) values ('$collectionId', 'Deleted collection', true);
            insert into collection_supplementary (collection_id, key, name, content_type, content_length, uploaded)
            values ('$collectionId', 'cover', 'Cover', 'image/png', 5, '2026-10-20T10:00:00Z');
        """.trimIndent())
        suspend fun references(): List<ContentStorageReference> = buildList {
            var offset = 0L
            while (true) {
                val page = metadata.getStorageReferences(offset, 1)
                if (page.isEmpty()) break
                addAll(page)
                offset += page.size
            }
            offset = 0
            while (true) {
                val page = collections.getStorageReferences(offset, 1)
                if (page.isEmpty()) break
                addAll(page)
                offset += page.size
            }
        }
        var observed = emptyList<ContentStorageReference>()
        val manifest = service.capture("source", output, includeContentReferences = true) {
            observed = references()
            assertEquals(6, observed.size)
            val legacy = observed.single { it.kind == ContentStorageReferenceKind.METADATA_VERSION_SUPPLEMENTARY }
            assertEquals("thumbnail", legacy.supplementaryKey)
            assertEquals(null, legacy.supplementaryId)
            sql("""
                update metadata set content_length = 99, uploaded = now() where id = '$id';
                delete from metadata_supplementary where id = '$supplementaryId';
                insert into metadata_supplementary (metadata_id, key, name, content_type, uploaded)
                values ('$id', 'new', 'Later upload', 'image/png', now());
                update collection_supplementary set content_length = 9 where collection_id = '$collectionId';
            """.trimIndent())
            assertEquals(observed, references())
        }
        val inventory = assertNotNull(manifest.contentInventory)
        assertEquals(6L, inventory.count)
        val saved = application.json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(ContentStorageReference.serializer()),
            output.resolve(inventory.path).readText(),
        )
        assertEquals(observed, saved)
        assertEquals(manifest, service.verify(output))
        withConnectionManager { assertFalse(observed == references()) }
        val independent = StorageConfiguration("fs", "", basePath = root.resolve("content-inventory-retained").toString())
        val factory = provide<ObjectStorageServiceFactory>()
        factory.withStorage(independent) { store -> service.upload(output, store, "with-references") }
        output.toFile().deleteRecursively()
        pool.close()
        sql("drop database source with (force)", "postgres")
        val downloaded = root.resolve("content-inventory-download")
        factory.withStorage(independent) { store ->
            assertEquals(manifest, service.download(store, "with-references", downloaded))
        }
        assertEquals(saved, application.json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(ContentStorageReference.serializer()),
            downloaded.resolve(inventory.path).readText(),
        ))
        val assetBytes = ByteArray(42) { it.toByte() }
        val asset = BackupObject(
            "content", "metadata/$id/8/content", "captured-observation", assetBytes.size.toLong(),
            java.security.MessageDigest.getInstance("SHA-256").digest(assetBytes).toHexString(), "assets/image",
        )
        val objects = ObjectBackupManifest("retained-assets", manifest.sourceId, "content", manifest.created, listOf(asset))
        val inspected = mutableListOf<PostgresBackupContentInspection>()
        factory.withStorage(independent) { store ->
            store.setInputStream(bosca.storage.service.StringObjectPath(asset.payloadPath), assetBytes.inputStream(), asset.length)
            val result = service.inspectContent(downloaded, objects, "content", store) { inspected.add(it) }
            assertEquals(manifest.id, result.databaseId)
            assertEquals(objects.id, result.objectManifestId)
            assertEquals(1L, result.counts.getValue(PostgresBackupContentStatus.VERIFIED))
            assertEquals(1L, result.counts.getValue(PostgresBackupContentStatus.UNRESOLVED))
            assertEquals(4L, result.counts.getValue(PostgresBackupContentStatus.NOT_RETAINED))
        }
        assertEquals(saved, inspected.map { it.reference })
        restore(downloaded)
        val recoveredPool = ConnectionPool(ConnectionFactoryImpl(
            ConnectionConfig(url + "recovered", user, password), "restored-content-inventory",
        ))
        try {
            val manager = ConnectionManager(recoveredPool)
            try {
                withContext(manager.asCoroutineContext()) { assertEquals(observed, references()) }
            } finally {
                withContext(NonCancellable) { manager.release() }
            }
        } finally {
            recoveredPool.close()
        }
        assertEquals(0, pool.activeConnections)
    }

    @Test
    fun `content inventory enumeration failure leaves no artifact or snapshot`(): Unit = runBlocking {
        val metadata = mockk<MetadataService>()
        provides<MetadataService>(singleton = true) { metadata }
        provides<CollectionService>(singleton = true) { mockk() }
        service = PostgresBackupServiceImplProvider().get()
        io.mockk.coEvery { metadata.getStorageReferences(0, any()) } throws IOException("inventory failed")
        assertFailsWith<IOException> { service.capture("source", output, includeContentReferences = true) }
        assertFalse(Files.exists(output))
        assertEquals(0, pool.activeConnections)
    }

    @Test
    fun `content inventory cancellation removes staged references and releases the snapshot`(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val metadata = mockk<MetadataService>()
        provides<MetadataService>(singleton = true) { metadata }
        provides<CollectionService>(singleton = true) { mockk() }
        service = PostgresBackupServiceImplProvider().get()
        io.mockk.coEvery { metadata.getStorageReferences(0, any()) } coAnswers {
            entered.complete(Unit)
            awaitCancellation()
        }
        val attempt = async { service.capture("source", output, includeContentReferences = true) }
        withTimeout(5_000) { entered.await() }
        withTimeout(5_000) { attempt.cancelAndJoin() }
        assertFalse(Files.exists(output))
        assertEquals(0, pool.activeConnections)
    }

    @Test
    fun `native dump failure removes a staged content inventory without publishing`(): Unit = runBlocking {
        tool = writeTool("exit 7")
        install()
        val metadata = mockk<MetadataService>()
        val collections = mockk<CollectionService>()
        io.mockk.coEvery { metadata.getStorageReferences(0, any()) } returns emptyList()
        io.mockk.coEvery { collections.getStorageReferences(0, any()) } returns emptyList()
        provides<MetadataService>(singleton = true) { metadata }
        provides<CollectionService>(singleton = true) { collections }
        service = PostgresBackupServiceImplProvider().get()
        assertFailsWith<IllegalStateException> { service.capture("source", output, includeContentReferences = true) }
        assertFalse(Files.exists(output))
        assertEquals(0, pool.activeConnections)
    }

    private fun metadataReferenceService() = MetadataServiceImpl(
        repository = MetadataRepositoryImpl(),
        collectionService = mockk(),
        collectionTemplateService = mockk(),
        find = mockk(),
        permissions = mockk(),
        traits = mockk(),
        categories = mockk(),
        plans = mockk(),
        profiles = mockk(),
        supplementary = mockk(),
        relationships = mockk(),
        transitionHistory = mockk(),
        documentService = mockk(),
        documentTemplateService = mockk(),
        dataTemplateService = mockk(),
        guideService = mockk(),
        guideTemplateService = mockk(),
        dataService = mockk(),
        bibleService = mockk(),
        objectService = mockk(),
        slugService = mockk(),
        traitService = mockk(),
        categoriesService = mockk(),
        securityService = mockk(),
        transitioner = mockk(),
        videoService = mockk(),
    )

    private fun collectionReferenceService() = CollectionServiceImpl(
        repository = CollectionRepositoryImpl(),
        items = mockk(),
        find = mockk(),
        categories = mockk(),
        traits = mockk(),
        relationships = mockk(),
        supplementary = mockk(),
        plans = mockk(),
        permissions = mockk(),
        transitionHistory = mockk(),
        objectService = mockk(),
        json = Json,
        slugService = mockk(),
        collaborations = mockk(),
        variants = mockk(),
        collectionTemplates = mockk(),
        collectionTemplateAttributes = mockk(),
        metadataRepository = mockk(),
        transitioner = mockk(),
        securityService = mockk(),
    )

    @Test
    fun `snapshot callback enumerates the exact rows restored from the exported database view`(): Unit = runBlocking {
        sql("insert into workops.tasks values (3, '{\"name\":\"removed\"}')")
        var observed = emptyList<String>()
        var calls = 0
        val manifest = service.capture("source", output) {
            calls++
            assertEquals("on", connection().useStatement("show transaction_read_only") { sql ->
                sql.executeQuery().use { rows -> assertTrue(rows.next()); rows.getString(1) }
            })
            observed = snapshotTaskNames()
            sql("""
                update workops.tasks set body='{"name":"later"}' where id=1;
                insert into workops.tasks values (2, '{"name":"new"}');
                delete from workops.tasks where id=3
            """.trimIndent())
            assertEquals(observed, snapshotTaskNames())
            assertFalse(Files.exists(output.resolve("manifest.json")))
        }
        assertEquals(1, calls)
        assertEquals(listOf("1:captured", "3:removed"), observed)
        assertEquals(manifest, service.verify(output))
        assertEquals("later", scalar("select body->>'name' from workops.tasks where id=1"))
        assertEquals(0, pool.activeConnections)
        restore()
        assertEquals("captured", scalar("select body->>'name' from workops.tasks where id=1", "recovered"))
        assertEquals("removed", scalar("select body->>'name' from workops.tasks where id=3", "recovered"))
        assertEquals("2", scalar("select count(*)::text from workops.tasks", "recovered"))
    }

    @Test
    fun `snapshot callback failure prevents dump execution and publication`(): Unit = runBlocking {
        val entered = root.resolve("dump-entered")
        tool = writeTool("touch ${quote(entered)}; exit 7")
        install()
        val failure = assertFailsWith<IOException> {
            service.capture("source", output) { throw IOException("reference enumeration failed") }
        }
        assertEquals("reference enumeration failed", failure.message)
        assertFalse(Files.exists(entered))
        assertFalse(Files.exists(output))
        assertEquals(0, pool.activeConnections)
    }

    @Test
    fun `snapshot callback cancellation releases its view without publishing an artifact`(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val attempt = async {
            service.capture("source", output) {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        try {
            withTimeout(5_000) { entered.await() }
            assertFalse(Files.exists(output.resolve("manifest.json")))
            assertEquals(1, pool.activeConnections)
        } finally {
            withTimeout(5_000) { attempt.cancelAndJoin() }
        }
        assertFalse(Files.exists(output))
        assertEquals(0, pool.activeConnections)
        service.capture("fresh", output)
        assertEquals("fresh", service.verify(output).sourceId)
    }

    @Test
    fun `snapshot callback cannot mutate the exported read-only database view`(): Unit = runBlocking {
        assertFailsWith<java.sql.SQLException> {
            service.capture("source", output) {
                connection().useStatement("update workops.tasks set body='{}'") { it.executeUpdate() }
            }
        }
        assertFalse(Files.exists(output))
        assertEquals(0, pool.activeConnections)
        assertEquals("captured", scalar("select body->>'name' from workops.tasks"))
    }

    private suspend fun snapshotTaskNames(): List<String> = connection().useStatement(
        "select id::text || ':' || (body->>'name') from workops.tasks order by id",
    ) { sql ->
        sql.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
    }

    @Test
    fun `a libpq service for another database cannot capture the primary snapshot`(): Unit = runBlocking {
        root.resolve("services.conf").writeText(root.resolve("services.conf").readText().replace("dbname=source", "dbname=postgres"))
        val failure = assertFailsWith<IllegalStateException> { service.capture("source", output) }
        assertTrue(failure.message.orEmpty().contains("pg_dump"))
        assertFalse(Files.exists(output))
        assertEquals(0, pool.activeConnections)
    }

    @Test
    fun `failed native process removes incomplete output and releases its snapshot`(): Unit = runBlocking {
        tool = writeTool("""
            for argument in "${'$'}@"; do
                case "${'$'}argument" in --file=*) printf partial > "${'$'}{argument#--file=}" ;; esac
            done
            echo 'deliberate dump failure' >&2
            exit 7
        """.trimIndent())
        install()
        val failure = assertFailsWith<IllegalStateException> { service.capture("source", output) }
        assertTrue(failure.message.orEmpty().contains("7"))
        assertTrue(failure.message.orEmpty().contains("deliberate dump failure"))
        assertFalse(Files.exists(output))
        assertEquals(0, pool.activeConnections)
        assertEquals("1", scalar("select count(*)::text from workops.tasks"))
    }

    @Test
    fun `cancellation stops the process removes partial output and releases the database`(): Unit = runBlocking {
        val entered = root.resolve("process")
        tool = writeTool("echo \$\$ > ${quote(entered)}\nexec sleep 60")
        install()
        val capture = async { service.capture("source", output) }
        awaitFile(entered)
        val pid = entered.readText().trim().toLong()
        withTimeout(5_000) { capture.cancelAndJoin() }
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        assertFalse(Files.exists(output))
        assertEquals(0, pool.activeConnections)
    }

    @Test
    fun `missing tools do not leave a snapshot or a completed artifact`(): Unit = runBlocking {
        tool = root.resolve("missing-pg-dump")
        install()
        assertFailsWith<java.io.IOException> { service.capture("source", output) }
        assertFalse(Files.exists(output))
        assertEquals(0, pool.activeConnections)
    }

    @Test
    fun `existing output and active transactions fail without damaging files`(): Unit = runBlocking {
        Files.createDirectory(output)
        output.resolve("original").writeText("keep")
        assertFailsWith<java.nio.file.FileAlreadyExistsException> { service.capture("source", output) }
        assertEquals("keep", output.resolve("original").readText())
        assertFailsWith<IllegalStateException> {
            withConnectionManager { transaction { service.capture("source", root.resolve("transaction")) } }
        }
        assertFalse(Files.exists(root.resolve("transaction")))
        assertEquals(0, pool.activeConnections)
    }

    @Test
    fun `invalid source or missing runtime configuration fails before creating output`(): Unit = runBlocking {
        assertFailsWith<IllegalArgumentException> { service.capture(" ", output) }
        install(configured = false)
        assertFailsWith<IllegalStateException> { service.capture("source", output) }
        assertFalse(Files.exists(output))
        assertEquals(0, pool.activeConnections)
    }

    @Test
    fun `offline integrity verification rejects changed and truncated payloads`(): Unit = runBlocking {
        service.capture("source", output)
        pool.close()
        sql("drop database source with (force)", "postgres")
        install(configured = false)
        val dump = output.resolve("database.dump")
        val original = Files.readAllBytes(dump)
        val changed = original.clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        Files.write(dump, changed)
        assertFailsWith<BackupPayloadMismatchException> { service.verify(output) }
        Files.write(dump, original.copyOf(original.size - 1))
        assertFailsWith<BackupPayloadMismatchException> { service.verify(output) }
        Files.write(dump, original + byteArrayOf(0))
        assertFailsWith<BackupPayloadMismatchException> { service.verify(output) }
    }

    @Test
    fun `a successful process without a native dump cannot publish a manifest`(): Unit = runBlocking {
        tool = writeTool("exit 0")
        install()
        assertFailsWith<IllegalStateException> { service.capture("source", output) }
        assertFalse(Files.exists(output))
        assertEquals(0, pool.activeConnections)
    }

    @Test
    fun `foreign table data needs explicit coverage before native capture`(): Unit = runBlocking {
        sql("create extension postgres_fdw; create server elsewhere foreign data wrapper postgres_fdw options (host '127.0.0.1'); create foreign table public.external_data(id int) server elsewhere")
        val failure = assertFailsWith<IllegalArgumentException> { service.capture("source", output) }
        assertTrue(failure.message.orEmpty().contains("Foreign"))
        assertFalse(Files.exists(output))
        assertEquals(0, pool.activeConnections)
    }

    @Test
    fun `verification requires a completed supported manifest and a regular native payload`(): Unit = runBlocking {
        Files.createDirectory(output)
        assertFailsWith<java.io.IOException> { service.verify(output) }
        val manifest = PostgresBackupManifest("id", "source", "db", "2026-10-18T00:00:00Z", "2026-10-18T00:00:01Z", "snapshot", "1:2:", "18", 180000, "UTF8", emptyMap(), 5, "a".repeat(64))
        output.resolve("manifest.json").writeText(Json.encodeToString(PostgresBackupManifest.serializer(), manifest.copy(payloadPath = "../services.conf")))
        assertFailsWith<IllegalArgumentException> { service.verify(output) }
        output.resolve("manifest.json").writeText(Json.encodeToString(PostgresBackupManifest.serializer(), manifest))
        Files.createSymbolicLink(output.resolve("database.dump"), root.resolve("services.conf"))
        assertFailsWith<IllegalArgumentException> { service.verify(output) }
    }

    @Test
    fun `retained native backup restores through signed S3 calls after source and local capture are removed`(): Unit = runBlocking {
        val manifest = service.capture("original-instance", output)
        val factory = provide<ObjectStorageServiceFactory>()
        PostgresBackupS3TestStore { assertEquals(0, pool.activeConnections) }.use { retained ->
            val prefix = "postgres/${manifest.id}"
            factory.withStorage(retained.configuration) { backup ->
                assertEquals(manifest, service.upload(output, backup, prefix))
                assertEquals(manifest, service.upload(output, backup, prefix))
            }
            assertEquals(1, retained.writes.count { it.endsWith("database.dump") })
            pool.close()
            sql("drop database source with (force)", "postgres")
            output.toFile().deleteRecursively()
            install(configured = false)
            val recovered = root.resolve("recovered-files")
            factory.withStorage(retained.configuration) { backup ->
                assertEquals(manifest, service.download(backup, prefix, recovered))
            }
            assertEquals(manifest, service.verify(recovered))
            restore(recovered)
            assertEquals("captured", scalar("select body->>'name' from workops.tasks", "recovered"))
            assertEquals("retained", scalar("select value from artifacts.events", "recovered"))
            assertEquals("[1,2,3]", scalar("select value::text from content.embeddings", "recovered"))
            assertEquals("{VIEW,EDIT}", scalar("select permissions::text from content.permissions", "recovered"))
            assertTrue(retained.signatures.isNotEmpty())
            assertTrue(retained.signatures.all { it.contains("Credential=POSTGRES_TRANSFER_KEY/") })
        }
    }

    @Test
    fun `independent transfer rejects active caller transactions before storage access`(): Unit = runBlocking {
        val backup = mockk<bosca.storage.service.ObjectStorageService>()
        assertFailsWith<IllegalStateException> {
            withConnectionManager { transaction { service.upload(output, backup, "postgres/capture") } }
        }
        assertFailsWith<IllegalStateException> {
            withConnectionManager { transaction { service.download(backup, "postgres/capture", root.resolve("download")) } }
        }
        assertFailsWith<IllegalStateException> {
            withConnectionManager { transaction {
                service.inspectContent(output,
                    ObjectBackupManifest("objects", "source", "content", "2026-10-20T00:00:00Z", emptyList()),
                    "content", backup)
            } }
        }
        assertFailsWith<IllegalStateException> {
            withConnectionManager { transaction { service.selectContent(output, "content", "snapshot", backup) } }
        }
        assertFailsWith<IllegalStateException> {
            withConnectionManager { transaction {
                service.writeContentReport(output, "database",
                    ObjectBackupManifest("objects", "source", "content", "2026-10-20T00:00:00Z", emptyList()),
                    emptyMap(), "content", backup, root.resolve("report.json"))
            } }
        }
        assertFalse(Files.exists(root.resolve("download")))
        assertFalse(Files.exists(root.resolve("report.json")))
        assertEquals(0, pool.activeConnections)
    }

    @Test
    fun `restore rejects occupied targets without invoking pg_restore or changing their data`(): Unit = runBlocking {
        service.capture("source", output)
        createTarget()
        val entered = root.resolve("restore-entered")
        restoreTool = writeTool("touch ${quote(entered)}; exit 0")
        install()
        val occupied = listOf(
            "create table public.existing(id int); insert into public.existing values (123)",
            "create schema existing",
            "create function public.existing() returns int language sql as 'select 1'",
            "create type public.existing as enum ('value')",
            "create extension vector",
            "select lo_from_bytea(0, decode('0102', 'hex'))",
            "create publication existing",
            "alter default privileges grant select on tables to public",
        )
        for (query in occupied) {
            sql(query, "recovered")
            val failure = assertFailsWith<IllegalStateException> { service.restore(output) }
            assertTrue(failure.message.orEmpty().contains("empty"), "$query: $failure")
            assertFalse(Files.exists(entered))
            if (query.startsWith("create table")) assertEquals("123", scalar("select id::text from public.existing", "recovered"))
            sql("drop database recovered with (force)", "postgres")
            sql("create database recovered owner recovered_owner template template0", "postgres")
        }
        assertEquals(setOf("database.dump", "manifest.json"), output.toFile().list()?.toSet())
    }

    @Test
    fun `restore refuses a libpq service whose database differs from the declared target`(): Unit = runBlocking {
        service.capture("source", output)
        createTarget()
        targetName = "somewhere_else"
        install()
        val failure = assertFailsWith<IllegalStateException> { service.restore(output) }
        assertTrue(failure.message.orEmpty().contains("destination database"), failure.toString())
        assertEquals("0", scalar("select count(*)::text from pg_class where relnamespace='public'::regnamespace", "recovered"))
        assertEquals("captured", scalar("select body->>'name' from workops.tasks"))
    }

    @Test
    fun `corrupt restore artifacts fail before any destination process`(): Unit = runBlocking {
        service.capture("source", output)
        val entered = root.resolve("destination-entered")
        psqlTool = writeTool("touch ${quote(entered)}; exit 0")
        restoreTool = psqlTool
        install()
        val dump = output.resolve("database.dump")
        Files.write(dump, Files.readAllBytes(dump).dropLast(1).toByteArray())
        assertFailsWith<BackupPayloadMismatchException> { service.restore(output) }
        assertFalse(Files.exists(entered))
        assertTrue(Files.exists(output.resolve("manifest.json")))
    }

    @Test
    fun `restore requires separate target configuration and no caller transaction`(): Unit = runBlocking {
        service.capture("source", output)
        install(restoreConfigured = false)
        val failure = assertFailsWith<IllegalStateException> { service.restore(output) }
        assertTrue(failure.message.orEmpty().contains("backup.postgres.restore"), failure.toString())
        val transactionFailure = assertFailsWith<IllegalStateException> {
            withConnectionManager { transaction { service.restore(output) } }
        }
        assertTrue(transactionFailure.message.orEmpty().contains("active caller transaction"))
        assertEquals(0, pool.activeConnections)
    }

    @Test
    fun `failed native restore rolls back all restored schemas and rows`(): Unit = runBlocking {
        sql("create table public.only_source(id int check (current_database() <> 'recovered')); insert into public.only_source values (1)")
        val manifest = service.capture("source", output)
        createTarget()
        val failure = assertFailsWith<IllegalStateException> { service.restore(output) }
        assertTrue(failure.message.orEmpty().contains("pg_restore"), failure.toString())
        assertTrue(failure.message.orEmpty().contains("check constraint"), failure.toString())
        assertEquals("0", scalar("select count(*)::text from pg_namespace where nspname in ('workops','git','content','artifacts','migrations')", "recovered"))
        assertEquals("0", scalar("select count(*)::text from pg_class where relnamespace='public'::regnamespace", "recovered"))
        assertEquals(manifest, service.verify(output))
        assertEquals(0, pool.activeConnections)
    }

    @Test
    fun `missing restore tools fail without altering target or artifact`(): Unit = runBlocking {
        val manifest = service.capture("source", output)
        createTarget()
        restoreTool = root.resolve("missing-pg-restore")
        install()
        assertFailsWith<java.io.IOException> { service.restore(output) }
        psqlTool = root.resolve("missing-psql")
        install()
        assertFailsWith<java.io.IOException> { service.restore(output) }
        assertEquals("0", scalar("select count(*)::text from pg_class where relnamespace='public'::regnamespace", "recovered"))
        assertEquals(manifest, service.verify(output))
    }

    @Test
    fun `restore cancellation stops local native process and preserves the artifact`(): Unit = runBlocking {
        val manifest = service.capture("source", output)
        createTarget()
        val entered = root.resolve("restore-process")
        restoreTool = writeTool("echo \$\$ > ${quote(entered)}\nexec sleep 60")
        install()
        val attempt = async { service.restore(output) }
        awaitFile(entered)
        val pid = entered.readText().trim().toLong()
        withTimeout(5_000) { attempt.cancelAndJoin() }
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        assertEquals(manifest, service.verify(output))
        assertEquals(setOf("database.dump", "manifest.json"), output.toFile().list()?.toSet())
        assertEquals("0", scalar("select count(*)::text from pg_class where relnamespace='public'::regnamespace", "recovered"))
    }

    @Test
    fun `restore treats quoted database names and dollar delimiters as literal configuration`(): Unit = runBlocking {
        val manifest = service.capture("source", output)
        createTarget()
        targetName = "recovered'${'$'}bosca${'$'}"
        sql("alter database recovered rename to \"$targetName\"", "postgres")
        root.resolve("restore.conf").writeText(root.resolve("restore.conf").readText().replace("dbname=recovered", "dbname=$targetName"))
        install()
        assertEquals(manifest, service.restore(output))
        assertEquals("captured", scalar("select body->>'name' from workops.tasks", targetName))
    }

    @Test
    fun `restore preflight rejects incompatible server encoding and extension requirements`(): Unit = runBlocking {
        val manifest = service.capture("source", output)
        createTarget()
        val variants = listOf(
            manifest.copy(serverVersionNumber = 990000),
            manifest.copy(encoding = "LATIN1"),
            manifest.copy(extensions = manifest.extensions + ("vector" to "9999.0")),
        )
        for (variant in variants) {
            output.resolve("manifest.json").writeText(Json.encodeToString(PostgresBackupManifest.serializer(), variant))
            val failure = assertFailsWith<IllegalStateException> { service.restore(output) }
            assertTrue(failure.message.orEmpty().contains("psql restore preflight"), failure.toString())
            assertEquals("0", scalar("select count(*)::text from pg_class where relnamespace='public'::regnamespace", "recovered"))
        }
    }

    @Test
    fun `a restored destination rejects replay without changing recovered content`(): Unit = runBlocking {
        val manifest = service.capture("source", output)
        restore()
        sql("update workops.tasks set body='{\"name\":\"after-restore\"}'", "recovered")
        val failure = assertFailsWith<IllegalStateException> { service.restore(output) }
        assertTrue(failure.message.orEmpty().contains("empty"), failure.toString())
        assertEquals("after-restore", scalar("select body->>'name' from workops.tasks", "recovered"))
        assertEquals(manifest, service.verify(output))
    }

    private suspend fun runService(): PostgresBackupRunService {
        retained = StorageConfiguration("fs", "", basePath = root.resolve("retained").toString())
        install()
        FlywayMigration(pool).migrate(listOf(BackupMigration()))
        return provide()
    }

    @Test
    fun `configured database backup publishes a durable receipt and survives source removal`(): Unit = runBlocking {
        val runs = runService()
        val receipt = runs.backupConfigured("request", "instance", "retained", "database-backups")
        val saved = withConnectionManager { assertNotNull(runs.get("request")) }
        assertEquals(PostgresBackupRunStatus.COMPLETED, saved.status)
        assertEquals(1, saved.attempts)
        assertEquals(receipt, saved.result)
        assertEquals("retained", saved.storageKey)
        assertEquals("instance", receipt.manifest.sourceId)
        assertEquals("source", receipt.manifest.database)
        assertEquals("0", scalar("select count(*)::text from backup.object_store_writers"))
        assertEquals("0", scalar("select count(*)::text from backup.postgres_runs where writer_token is not null"))
        assertTrue(Files.exists(root.resolve("retained/${receipt.path}/manifest.json")))
        assertFalse(root.toFile().list().orEmpty().any { it.startsWith("postgres-backup-") })
        pool.close()
        sql("drop database source with (force)", "postgres")
        val downloaded = root.resolve("run-download")
        provide<ObjectStorageServiceFactory>().withStorage(assertNotNull(retained)) { store ->
            service.download(store, receipt.path, downloaded)
        }
        restore(downloaded)
        assertEquals("captured", scalar("select body->>'name' from workops.tasks", "recovered"))
    }

    @Test
    fun `matching completed requests replay after service restart without recapture or storage writes`(): Unit = runBlocking {
        val runs = runService()
        val first = runs.backupConfigured("request", "instance", "retained", "database-backups")
        tool = root.resolve("missing-dump")
        retained = null
        install(configured = false)
        val restarted = provide<PostgresBackupRunService>()
        assertEquals(first, restarted.backupConfigured("request", "instance", "retained", "database-backups"))
        assertEquals(1, withConnectionManager { assertNotNull(restarted.get("request")).attempts })
        assertFailsWith<IllegalArgumentException> { restarted.backupConfigured("request", "other", "retained", "database-backups") }
        assertFailsWith<IllegalArgumentException> { restarted.backupConfigured("request", "instance", "different", "database-backups") }
        assertFailsWith<IllegalArgumentException> { restarted.backupConfigured("request", "instance", "retained", "different") }
    }

    @Test
    fun `native job checkpoint resumes its request with a fresh snapshot and fenced acknowledgements`(): Unit = runBlocking {
        val runs = runService()
        val checkpoint = "backing-job"
        val token = "$checkpoint:stopped"
        val old = service.capture("instance", root.resolve("abandoned-capture"))
        val abandoned = root.resolve("retained/backups/request/abandoned")
        Files.createDirectories(abandoned)
        Files.writeString(abandoned.resolve("intent.json"), Json.encodeToString(PostgresBackupManifest.serializer(), old))
        val partial = Files.readAllBytes(root.resolve("abandoned-capture/database.dump")).take(10).toByteArray()
        Files.write(abandoned.resolve("database.dump"), partial)
        seedNativeWriter("request", token)
        val stopped = withConnectionManager { assertNotNull(runs.get("request")) }
        sql("update workops.tasks set body='{\"name\":\"after-crash\"}'")
        install()
        val restarted = provide<PostgresBackupRunService>()
        var observed = false
        faultStorage { actual -> object : ObjectStorageService by actual {
            override suspend fun setInputStream(path: ObjectPath, stream: InputStream, length: Long?): Long {
                if (!observed) {
                    observed = true
                    val active = withConnectionManager { assertNotNull(restarted.get("request")) }
                    assertTrue(assertNotNull(active.writerToken).startsWith("$checkpoint:"))
                    assertTrue(active.writerToken != token)
                    assertTrue(active.version > stopped.version)
                    withConnectionManager {
                        assertEquals(0, provide<PostgresBackupRunRepository>().complete(
                            "request", token, PostgresBackupReceipt("request", "retained", "backups/request/abandoned", old),
                        ))
                    }
                    assertFailsWith<IllegalStateException> { restarted.recover("request", token) }
                    assertEquals(active, withConnectionManager { restarted.get("request") })
                    assertEquals("1", scalar("select count(*)::text from backup.object_store_writers where postgres_key='request'"))
                }
                return actual.setInputStream(path, stream, length)
            }
        } }
        val receipt = provide<PostgresBackupRunService>().backupConfigured("request", "instance", "retained", "backups", checkpoint)
        assertTrue(observed)
        assertTrue(receipt.manifest.id != old.id)
        assertTrue(Instant.parse(receipt.manifest.created) >= Instant.parse(old.completed))
        assertTrue(partial.contentEquals(Files.readAllBytes(abandoned.resolve("database.dump"))))
        assertFalse(Files.exists(abandoned.resolve("manifest.json")))
        assertEquals(old, Json.decodeFromString(PostgresBackupManifest.serializer(), abandoned.resolve("intent.json").readText()))
        assertEquals(2, withConnectionManager { assertNotNull(runs.get("request")).attempts })
        assertEquals(receipt, withConnectionManager { assertNotNull(runs.get("request")).result })
        assertEquals("0", scalar("select count(*)::text from backup.object_store_writers"))
        provide<ObjectStorageServiceFactory>().withStorage(assertNotNull(retained)) { store -> service.download(store, receipt.path, output) }
        restore()
        assertEquals("after-crash", scalar("select body->>'name' from workops.tasks", "recovered"))
        tool = root.resolve("missing-dump")
        retained = null
        install(configured = false)
        assertEquals(receipt, provide<PostgresBackupRunService>().backupConfigured("request", "instance", "retained", "backups", checkpoint))
    }

    @Test
    fun `native checkpoint cannot adopt another job or an unqueued writer`(): Unit = runBlocking {
        val runs = runService()
        seedNativeWriter("request", "backing-job:stopped")
        val stopped = withConnectionManager { assertNotNull(runs.get("request")) }
        for (checkpoint in listOf(null, "another-job", "backing")) {
            assertFailsWith<IllegalStateException> { runs.backupConfigured("request", "instance", "retained", "backups", checkpoint) }
            assertEquals(stopped, withConnectionManager { runs.get("request") })
        }
        sql("update backup.postgres_runs set writer_token='manual-token' where key='request'")
        assertFailsWith<IllegalStateException> { runs.backupConfigured("request", "instance", "retained", "backups", "backing-job") }
        assertEquals("manual-token", withConnectionManager { assertNotNull(runs.get("request")).writerToken })
        assertEquals("1", scalar("select count(*)::text from backup.object_store_writers"))
    }

    @Test
    fun `native checkpoint identities are validated before recording intent`(): Unit = runBlocking {
        val runs = runService()
        for ((index, checkpoint) in listOf("", " ", "job:attempt").withIndex()) {
            assertFailsWith<IllegalArgumentException> { runs.backupConfigured("invalid-$index", "instance", "retained", "backups", checkpoint) }
        }
        assertEquals(emptyList(), withConnectionManager { runs.list("instance", "retained", 0, 10) })
        assertFalse(Files.exists(root.resolve("retained")))
    }

    @Test
    fun `native checkpoint resume rolls back when the destination belongs to another request`(): Unit = runBlocking {
        val runs = runService()
        seedNativeWriter("request", "job:stopped", claim = false)
        seedNativeWriter("other", "unrelated")
        val stopped = withConnectionManager { assertNotNull(runs.get("request")) }
        val other = withConnectionManager { assertNotNull(runs.get("other")) }
        assertFailsWith<ObjectBackupDestinationBusyException> { runs.backupConfigured("request", "instance", "retained", "backups", "job") }
        assertEquals(stopped, withConnectionManager { runs.get("request") })
        assertEquals(other, withConnectionManager { runs.get("other") })
        assertEquals("other", scalar("select postgres_key from backup.object_store_writers"))
    }

    @Test
    fun `native checkpoint identity is saved on the first attempt`(): Unit = runBlocking {
        val runs = runService()
        faultStorage { actual -> object : ObjectStorageService by actual {
            override suspend fun setInputStream(path: ObjectPath, stream: InputStream, length: Long?): Long {
                val active = withConnectionManager { assertNotNull(runs.get("request")) }
                assertTrue(assertNotNull(active.writerToken).startsWith("backing-job:"))
                return actual.setInputStream(path, stream, length)
            }
        } }
        provide<PostgresBackupRunService>().backupConfigured("request", "instance", "retained", "backups", "backing-job")
    }

    private suspend fun seedNativeWriter(
        key: String,
        token: String,
        claim: Boolean = true,
        prefix: String = "backups",
        includeContentReferences: Boolean = false,
    ) = withConnectionManager {
        transaction {
            provide<ObjectBackupStorageService>().bindConfigured(setOf("retained"))
            val repository = provide<PostgresBackupRunRepository>()
            assertEquals(1, repository.add(key, "instance", "retained", prefix, includeContentReferences))
            assertEquals(1, repository.begin(key, token))
            if (claim) assertEquals(1, repository.claim(key, "retained"))
        }
    }

    @Test
    fun `native checkpoint survives forced worker termination and restores the later captured state`(): Unit = runBlocking {
        val runs = runService()
        val settingsPath = root.resolve("worker.properties")
        Properties().apply {
            setProperty("root", root.toString())
            setProperty("jdbcUrl", url + "source")
            setProperty("username", user)
            setProperty("password", password)
            setProperty("nativeKey", "interrupted")
            setProperty("checkpointKey", "backing-job")
        }.also { settings -> Files.newOutputStream(settingsPath).use { settings.store(it, null) } }
        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp",
            checkNotNull(System.getProperty("bosca.backup.testClasspath")),
            ObjectBackupCheckpointWorker::class.java.name, settingsPath.toString(),
        ).redirectErrorStream(true).redirectOutput(root.resolve("worker.log").toFile()).start()
        try {
            withTimeout(30_000) {
                while (!Files.exists(root.resolve("worker-ready"))) {
                    check(process.isAlive) { "Native worker exited before checkpoint: ${root.resolve("worker.log").readText()}" }
                    delay(25)
                }
            }
            val stopped = withConnectionManager { assertNotNull(runs.get("interrupted")) }
            assertEquals(PostgresBackupRunStatus.RUNNING, stopped.status)
            assertTrue(assertNotNull(stopped.writerToken).startsWith("backing-job:"))
            val partial = Files.walk(root.resolve("retained")).use { paths ->
                paths.filter { it.fileName.toString() == "database.dump" }.findFirst().orElseThrow()
            }
            val bytes = Files.readAllBytes(partial)
            assertEquals(10, bytes.size)
            val old = Json.decodeFromString(PostgresBackupManifest.serializer(), partial.parent.resolve("intent.json").readText())
            process.destroyForcibly()
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            assertTrue(process.exitValue() != 0)
            assertEquals(stopped, withConnectionManager { runs.get("interrupted") })
            assertEquals("interrupted", scalar("select postgres_key from backup.object_store_writers"))
            sql("update workops.tasks set body='{\"name\":\"after-worker-death\"}'")
            install()
            val receipt = provide<PostgresBackupRunService>().backupConfigured("interrupted", "instance", "retained", "backups", "backing-job")
            assertTrue(receipt.manifest.id != old.id)
            assertTrue(Instant.parse(receipt.manifest.created) >= Instant.parse(old.completed))
            assertTrue(bytes.contentEquals(Files.readAllBytes(partial)))
            assertFalse(Files.exists(partial.parent.resolve("manifest.json")))
            assertEquals(2, withConnectionManager { assertNotNull(runs.get("interrupted")).attempts })
            assertEquals(receipt, withConnectionManager { assertNotNull(runs.get("interrupted")).result })
            provide<ObjectStorageServiceFactory>().withStorage(assertNotNull(retained)) { store -> service.download(store, receipt.path, output) }
            pool.close()
            sql("drop database source with (force)", "postgres")
            install(configured = false)
            restore()
            assertEquals("after-worker-death", scalar("select body->>'name' from workops.tasks", "recovered"))
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun `native checkpoint redelivery resumes the original Pipeline child through the real runner`(): Unit = runBlocking {
        val runs = runService()
        PostgresBackupPipelineFixture.create(application, pool, root).use { pipeline ->
            val initial = pipeline.start()
            pipeline.execute(pipeline.next())
            val child = pipeline.next()
            val key = objectBackupIdentity(ObjectBackupOperation.BACKUP, listOf("postgresql", child.getId().toString(), "backup"))
            seedNativeWriter(key, "$key:stopped", prefix = "postgresql")
            sql("update workops.tasks set body='{\"name\":\"redelivered\"}'")
            pipeline.redeliver(child)
            install()
            pipeline.register()
            val runner = pipeline.runner()
            runner.run()
            try {
                val completed = pipeline.awaitComplete(initial.id)
                assertEquals(bosca.pipelines.model.PipelineRunStatus.OK, completed.status)
                val receipt = application.json.decodeFromJsonElement(PostgresBackupReceipt.serializer(), assertNotNull(completed.output))
                assertEquals(key, receipt.key)
                assertEquals(2, withConnectionManager { assertNotNull(runs.get(key)).attempts })
                assertEquals(receipt, withConnectionManager { assertNotNull(runs.get(key)).result })
                assertEquals("0", scalar("select count(*)::text from backup.object_store_writers"))
                assertTrue(withConnectionManager { provide<bosca.pipelines.service.PipelineRunService>().nodeTimeline(initial.id) }.none {
                    it.nodeId == "backup" && it.status == bosca.pipelines.model.NodeExecutionStatus.FAILED
                })
                pipeline.verifyNoServiceAccountFallback()
                provide<ObjectStorageServiceFactory>().withStorage(assertNotNull(retained)) { store -> service.download(store, receipt.path, output) }
                restore()
                assertEquals("redelivered", scalar("select body->>'name' from workops.tasks", "recovered"))
            } finally {
                runner.shutdown()
            }
        }
    }

    @Test
    fun `later database requests capture a new point without replacing old recovery data`(): Unit = runBlocking {
        val runs = runService()
        val first = runs.backupConfigured("first", "instance", "retained", "database-backups")
        sql("update workops.tasks set body='{\"name\":\"later\"}'")
        val second = runs.backupConfigured("second", "instance", "retained", "database-backups")
        assertTrue(first.path != second.path)
        assertTrue(first.manifest.id != second.manifest.id)
        val saved = withConnectionManager { runs.list("instance", "retained", 0, 10) }
        assertEquals(listOf("second", "first"), saved.map { it.key })
        assertEquals(listOf("first"), withConnectionManager { runs.list("instance", "retained", 1, 1) }.map { it.key })
        assertTrue(Files.exists(root.resolve("retained/${first.path}/manifest.json")))
        assertTrue(Files.exists(root.resolve("retained/${second.path}/manifest.json")))
    }

    @Test
    fun `failed native attempt records failure cleans scratch and retries through a fresh capture`(): Unit = runBlocking {
        val runs = runService()
        val real = tool
        tool = writeTool("echo 'native failure' >&2; exit 7")
        install()
        val failure = assertFailsWith<IllegalStateException> { provide<PostgresBackupRunService>().backupConfigured("retry", "instance", "retained", "database-backups") }
        assertTrue(failure.message.orEmpty().contains("native failure"))
        val failed = withConnectionManager { assertNotNull(runs.get("retry")) }
        assertEquals(PostgresBackupRunStatus.FAILED, failed.status)
        assertTrue(failed.error.orEmpty().contains("native failure"))
        assertEquals(null, failed.result)
        assertEquals(null, failed.writerToken)
        assertEquals("0", scalar("select count(*)::text from backup.object_store_writers"))
        assertFalse(root.toFile().list().orEmpty().any { it.startsWith("postgres-backup-") })
        tool = real
        install()
        provide<PostgresBackupRunService>().backupConfigured("retry", "instance", "retained", "database-backups")
        assertEquals(2, withConnectionManager { assertNotNull(runs.get("retry")).attempts })
    }

    @Test
    fun `configured requests reject invalid intent and active caller transactions`(): Unit = runBlocking {
        val runs = runService()
        for ((key, prefix) in listOf("../escape" to "backups", "valid" to "../backups", "valid" to "/absolute", "valid" to "backups//bad")) {
            assertFailsWith<IllegalArgumentException> { runs.backupConfigured(key, "instance", "retained", prefix) }
        }
        assertFailsWith<IllegalStateException> { withConnectionManager {
            transaction { runs.backupConfigured("transaction", "instance", "retained", "backups") }
        } }
        assertEquals(emptyList(), withConnectionManager { runs.list("instance", "retained", 0, 10) })
        assertFalse(Files.exists(root.resolve("retained")))
    }

    private suspend fun contentReferenceServices() {
        provides<CacheManager>(singleton = true) { mockk(relaxed = true) }
        provides<MetadataService>(singleton = true) { metadataReferenceService() }
        provides<CollectionService>(singleton = true) { collectionReferenceService() }
        BackupProviderRegistrar().register()
        service = provide()
    }

    @Test
    fun `saved content selection feeds incremental capture and restores database and assets after source removal`(): Unit = runBlocking {
        FlywayMigration(pool).migrate(listOf(CoreMigration()))
        contentReferenceServices()
        val id = "10000000-0000-0000-0000-000000000001"
        val bytes = "retained image bytes".toByteArray()
        val key = "metadata/$id/7/content"
        sql("insert into metadata (id, version, name, content_type, content_length, uploaded) values ('$id', 7, 'Saved image', 'image/png', ${bytes.size}, now())")
        val factory = provide<ObjectStorageServiceFactory>()
        val sourceDirectory = root.resolve("source-assets")
        val sourceConfiguration = StorageConfiguration("fs", "", basePath = sourceDirectory.toString())
        val retainedConfiguration = StorageConfiguration("fs", "", basePath = root.resolve("selected-retained").toString())
        val targetConfiguration = StorageConfiguration("fs", "", basePath = root.resolve("restored-assets").toString())
        Files.createDirectories(sourceDirectory.resolve(key).parent)
        Files.write(sourceDirectory.resolve(key), bytes)
        val manifest = service.capture("source", output, includeContentReferences = true)
        sql("create database recovery_journal", "postgres")
        val journal = ConnectionPool(ConnectionFactoryImpl(ConnectionConfig(url + "recovery_journal", user, password), "content-selection-journal"))
        try {
            provides<ConnectionPool>(singleton = true, overrideExisting = true) { journal }
            provides<Json>(singleton = true, overrideExisting = true) { backupTestJson }
            BackupProviderRegistrar().register()
            FlywayMigration(journal).migrate(listOf(BackupMigration()))
            val captures = provide<ObjectBackupCaptureService>()
            factory.withStorage(sourceConfiguration) { actualSource ->
                var sourceReads = 0
                val source = object : ObjectStorageService by actualSource {
                    override suspend fun getInputStream(path: ObjectPath): InputStream {
                        assertEquals(0, journal.activeConnections)
                        sourceReads++
                        return actualSource.getInputStream(path)
                    }
                }
                val selected = service.selectContent(output, "content", "snapshot", source)
                assertEquals(manifest.id, selected.inventory.id)
                assertEquals(1L, selected.counts.getValue(PostgresBackupContentSelectionStatus.SELECTED))
                assertEquals(0, sourceReads)
                val plan = withConnectionManager { captures.prepareInventory(selected, "retained", "assets", "objects.json") }
                assertEquals(selected.counts, plan.contentSelectionCounts)
                BackupProviderRegistrar().register()
                assertEquals(plan, withConnectionManager { provide<ObjectBackupCaptureService>().getPlan(plan.key) })
                factory.withStorage(retainedConfiguration) { backup ->
                    val objects = captures.captureInventory(plan.key, mapOf("content" to source), backup, null)
                    assertEquals(1, sourceReads)
                    assertEquals(key, objects.objects.single().key)
                    service.upload(output, backup, "database")
                    assertTrue(sourceDirectory.toFile().deleteRecursively())
                    assertEquals(objects, captures.captureInventory(plan.key, mapOf("content" to source), backup, null))
                    assertEquals(1, sourceReads)
                    pool.close()
                    sql("drop database source with (force)", "postgres")
                    assertTrue(output.toFile().deleteRecursively())
                    assertEquals(manifest, service.download(backup, "database", output))
                    val reportPath = root.resolve("content-report.json")
                    val exported = service.writeContentReport(output, "database", objects,
                        assertNotNull(plan.contentSelectionCounts), "content", backup, reportPath)
                    val report = service.readContentReport(reportPath)
                    assertEquals(exported, report)
                    assertEquals(plan.contentSelectionCounts, report.selectionCounts)
                    assertEquals(manifest, report.database)
                    assertEquals(1L, report.inspection.counts.getValue(PostgresBackupContentStatus.VERIFIED))
                    assertTrue(report.inspection.counts.filterKeys { it != PostgresBackupContentStatus.VERIFIED }.values.all { it == 0L })
                    assertTrue(output.toFile().deleteRecursively())
                    assertEquals(manifest, service.download(backup, report.databasePath, output))
                    factory.withStorage(targetConfiguration) { target ->
                        assertEquals(objects, provide<ObjectBackupRestoreService>().restore(report.objects, backup, mapOf("content" to target), "recovered-assets"))
                        assertEquals(bytes.decodeToString(), target.getString(bosca.storage.service.StringObjectPath(key)))
                    }
                    restore()
                    assertEquals("Saved image", scalar("select name from metadata where id='$id'", "recovered"))
                }
            }
        } finally {
            journal.close()
        }
    }

    @Test
    fun `selected content inventory checkpoint resumes only its original request intent`(): Unit = runBlocking {
        runService()
        FlywayMigration(pool).migrate(listOf(CoreMigration()))
        contentReferenceServices()
        sql("insert into metadata (name, content_type) values ('Pending', 'image/png')")
        seedNativeWriter("selected", "job:stopped", includeContentReferences = true)
        val runs = provide<PostgresBackupRunService>()
        val stopped = withConnectionManager { assertNotNull(runs.get("selected")) }
        assertTrue(stopped.includeContentReferences)
        assertFailsWith<IllegalArgumentException> {
            runs.backupConfigured("selected", "instance", "retained", "backups", "job")
        }
        assertEquals(stopped, withConnectionManager { runs.get("selected") })
        install()
        contentReferenceServices()
        val restarted = provide<PostgresBackupRunService>()
        val receipt = restarted.backupConfigured("selected", "instance", "retained", "backups", "job", true)
        assertEquals(1L, assertNotNull(receipt.manifest.contentInventory).count)
        val completed = withConnectionManager { assertNotNull(restarted.get("selected")) }
        assertTrue(completed.includeContentReferences)
        assertEquals(2, completed.attempts)
        assertEquals(receipt, completed.result)
        assertEquals("0", scalar("select count(*)::text from backup.object_store_writers"))
        assertFalse(root.toFile().list().orEmpty().any { it.startsWith("postgres-backup-") })
    }

    @Test
    fun `queued native content inventory is fixed retained cleaned and recoverable without the source`(): Unit = runBlocking {
        runService()
        FlywayMigration(pool).migrate(listOf(CoreMigration()))
        contentReferenceServices()
        val runs = provide<PostgresBackupRunService>()
        val id = "10000000-0000-0000-0000-000000000001"
        sql("insert into metadata (id, name, content_type, content_length, uploaded) values ('$id', 'Saved image', 'image/png', 42, now())")
        val configuration = assertNotNull(retained)
        PostgresBackupPipelineFixture.create(application, pool, root, includeContentReferences = true).use { pipeline ->
            val initial = pipeline.start()
            pipeline.execute(pipeline.next())
            pipeline.execute(pipeline.next())
            val complete = pipeline.get(initial.id)
            assertEquals(bosca.pipelines.model.PipelineRunStatus.OK, complete.status)
            val receipt = application.json.decodeFromJsonElement(PostgresBackupReceipt.serializer(), assertNotNull(complete.output))
            val inventory = assertNotNull(receipt.manifest.contentInventory)
            assertEquals(1L, inventory.count)
            val saved = withConnectionManager { assertNotNull(runs.get(receipt.key)) }
            assertTrue(saved.includeContentReferences)
            assertEquals(receipt, saved.result)
            assertFalse(root.toFile().list().orEmpty().any { it.startsWith("postgres-backup-") })
            assertEquals("0", scalar("select count(*)::text from backup.object_store_writers"))
            tool = root.resolve("missing-dump")
            retained = null
            install(configured = false)
            val restarted = provide<PostgresBackupRunService>()
            assertEquals(receipt, restarted.backupConfigured(receipt.key, "instance", "retained", "postgresql", includeContentReferences = true))
            assertFailsWith<IllegalArgumentException> { restarted.backupConfigured(receipt.key, "instance", "retained", "postgresql") }
            pool.close()
            sql("drop database source with (force)", "postgres")
            provide<ObjectStorageServiceFactory>().withStorage(configuration) { store -> service.download(store, receipt.path, output) }
            val references = application.json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(ContentStorageReference.serializer()),
                output.resolve(inventory.path).readText())
            assertEquals(id, references.single().contentId.toString())
            restore()
            assertEquals("Saved image", scalar("select name from metadata where id='$id'", "recovered"))
        }
    }

    @Test
    fun `selected inventory upload failures and cancellation clean scratch and retain fixed retry intent`(): Unit = runBlocking {
        runService()
        FlywayMigration(pool).migrate(listOf(CoreMigration()))
        contentReferenceServices()
        sql("insert into metadata (name, content_type) values ('Pending', 'image/png')")
        var cancel = false
        val entered = CompletableDeferred<Unit>()
        faultStorage { actual -> object : ObjectStorageService by actual {
            override suspend fun setInputStream(path: ObjectPath, stream: InputStream, length: Long?): Long {
                if (path.toString().endsWith("/content-references.json")) {
                    if (cancel) { entered.complete(Unit); awaitCancellation() }
                    throw IOException("inventory upload failed")
                }
                return actual.setInputStream(path, stream, length)
            }
        } }
        val runs = provide<PostgresBackupRunService>()
        val failure = assertFailsWith<IOException> { runs.backupConfigured("selected", "instance", "retained", "backups", includeContentReferences = true) }
        assertEquals("inventory upload failed", failure.message)
        assertFalse(root.toFile().list().orEmpty().any { it.startsWith("postgres-backup-") })
        val failed = withConnectionManager { assertNotNull(runs.get("selected")) }
        assertTrue(failed.includeContentReferences)
        assertEquals(PostgresBackupRunStatus.FAILED, failed.status)
        assertEquals(null, failed.result)
        assertEquals("0", scalar("select count(*)::text from backup.object_store_writers"))
        assertFailsWith<IllegalArgumentException> { runs.backupConfigured("selected", "instance", "retained", "backups") }
        assertEquals(failed, withConnectionManager { runs.get("selected") })
        cancel = true
        val attempt = async { runs.backupConfigured("cancelled-inventory", "instance", "retained", "backups", includeContentReferences = true) }
        withTimeout(30_000) { entered.await() }
        withTimeout(5_000) { attempt.cancelAndJoin() }
        assertFalse(root.toFile().list().orEmpty().any { it.startsWith("postgres-backup-") })
        assertEquals(PostgresBackupRunStatus.CANCELLED, withConnectionManager { assertNotNull(runs.get("cancelled-inventory")).status })
        assertEquals("0", scalar("select count(*)::text from backup.object_store_writers"))
        install()
        contentReferenceServices()
        val restarted = provide<PostgresBackupRunService>()
        for (key in listOf("selected", "cancelled-inventory")) {
            val receipt = restarted.backupConfigured(key, "instance", "retained", "backups", includeContentReferences = true)
            assertEquals(1L, assertNotNull(receipt.manifest.contentInventory).count)
            assertEquals(2, withConnectionManager { assertNotNull(restarted.get(key)).attempts })
        }
        assertFalse(root.toFile().list().orEmpty().any { it.startsWith("postgres-backup-") })
    }

    @Test
    fun `queued native backup preserves caller and completes with a source-independent artifact`(): Unit = runBlocking {
        val runService = runService()
        PostgresBackupPipelineFixture.create(application, pool, root).use { pipeline ->
            val initial = pipeline.start()
            assertEquals(pipeline.caller.id, initial.principalId)
            val parent = pipeline.next()
            pipeline.execute(parent)
            assertEquals(bosca.pipelines.model.PipelineRunStatus.SUSPENDED, pipeline.get(initial.id).status)
            val child = pipeline.next()
            assertEquals(parent.getId(), child.getParentId())
            assertFalse(child.getDefinition().toString().contains(password))
            pipeline.execute(child)
            val complete = pipeline.get(initial.id)
            assertEquals(bosca.pipelines.model.PipelineRunStatus.OK, complete.status)
            val receipt = application.json.decodeFromJsonElement(PostgresBackupReceipt.serializer(), assertNotNull(complete.output))
            assertEquals(receipt, withConnectionManager { assertNotNull(runService.get(receipt.key)).result })
            assertEquals("0", scalar("select count(*)::text from backup.object_store_writers"))
            pipeline.verifyNoServiceAccountFallback()
            provide<ObjectStorageServiceFactory>().withStorage(assertNotNull(retained)) { store ->
                service.download(store, receipt.path, output)
            }
            pool.close()
            sql("drop database source with (force)", "postgres")
            install(configured = false)
            restore()
            assertEquals("captured", scalar("select body->>'name' from workops.tasks", "recovered"))
        }
    }

    @Test
    fun `native backing job retry survives service restart and an unauthorized child does no capture`(): Unit = runBlocking {
        val runService = runService()
        val blocked = root.resolve("fail-dump")
        blocked.writeText("fail")
        tool = writeTool("""
            if [ -f ${quote(blocked)} ]; then echo 'queued native failure' >&2; exit 7; fi
            exec docker exec -e PGSERVICEFILE="${'$'}PGSERVICEFILE" ${postgres.containerId} pg_dump "${'$'}@"
        """.trimIndent())
        install()
        PostgresBackupPipelineFixture.create(application, pool, root).use { pipeline ->
            val initial = pipeline.start()
            pipeline.execute(pipeline.next())
            val child = pipeline.next()
            pipeline.fail(child, retry = true)
            val failed = withConnectionManager { runService.list("instance", "retained", 0, 10).single() }
            assertEquals(PostgresBackupRunStatus.FAILED, failed.status)
            assertEquals(null, pipeline.get(initial.id).output)
            Files.delete(blocked)
            install()
            pipeline.register()
            val retry = pipeline.retry()
            assertEquals(child.getId(), retry.getId())
            pipeline.execute(retry)
            assertEquals(bosca.pipelines.model.PipelineRunStatus.OK, pipeline.get(initial.id).status)
            assertEquals(2, withConnectionManager { assertNotNull(runService.get(failed.key)).attempts })
            pipeline.denied = true
            val denied = pipeline.start()
            pipeline.execute(pipeline.next())
            pipeline.fail(pipeline.next(), retry = false)
            assertEquals(bosca.pipelines.model.PipelineRunStatus.FAILED, pipeline.get(denied.id).status)
            assertEquals(1, withConnectionManager { runService.list("instance", "retained", 0, 10).size })
            pipeline.verifyNoServiceAccountFallback()
        }
    }

    @Test
    fun `native destinations contend with object runs capture plans and other native requests`(): Unit = runBlocking {
        val runs = runService()
        withConnectionManager { provide<ObjectBackupStorageService>().bindConfigured(setOf("retained")) }
        val objectRun = withConnectionManager { provide<ObjectBackupRunService>().prepare(
            ObjectBackupManifest("inventory", "instance", "scope", "2026-10-19T00:00:00Z", emptyList()), "retained",
        ) }
        withConnectionManager { assertEquals(1, provide<ObjectBackupRunRepository>().claimWriter("retained", objectRun.key)) }
        assertFailsWith<ObjectBackupDestinationBusyException> { runs.backupConfigured("native", "instance", "retained", "backups") }
        assertEquals(null, withConnectionManager { runs.get("native") })
        withConnectionManager { provide<ObjectBackupRunRepository>().releaseWriters(objectRun.key) }
        val plan = withConnectionManager { provide<ObjectBackupCaptureService>().prepareInventory(
            ObjectBackupCaptureInventory("selection", "instance", "scope", "2026-10-19T00:00:00Z", emptyMap()),
            "retained", "payloads", "selected.json",
        ) }
        withConnectionManager { provide<ObjectBackupCaptureRepository>().claimWriter(plan.key, "retained", "capture-token") }
        assertFailsWith<ObjectBackupDestinationBusyException> { runs.backupConfigured("native", "instance", "retained", "backups") }
        withConnectionManager { provide<ObjectBackupCaptureService>().releaseWriter(plan.key, "capture-token") }
        val repository = provide<PostgresBackupRunRepository>()
        withConnectionManager { transaction {
            repository.add("owner", "instance", "retained", "backups")
            repository.begin("owner", "native-token")
            repository.claim("owner", "retained")
        } }
        assertFailsWith<ObjectBackupDestinationBusyException> { runs.backupConfigured("native", "instance", "retained", "backups") }
        withConnectionManager {
            assertEquals(0, provide<ObjectBackupRunRepository>().claimWriter("retained", objectRun.key))
            assertEquals(0, provide<ObjectBackupCaptureRepository>().claimWriter(plan.key, "retained", "later"))
        }
        assertEquals("native-token", withConnectionManager { assertNotNull(runs.get("owner")).writerToken })
        assertFailsWith<IllegalStateException> { runs.recover("owner", "wrong-token") }
        assertEquals("1", scalar("select count(*)::text from backup.object_store_writers"))
        runs.recover("owner", "native-token")
        assertEquals(PostgresBackupRunStatus.FAILED, withConnectionManager { assertNotNull(runs.get("owner")).status })
        runs.backupConfigured("owner", "instance", "retained", "backups")
        assertEquals(2, withConnectionManager { assertNotNull(runs.get("owner")).attempts })
    }

    @Test
    fun `native backup cancellation releases ownership cleans scratch and can be retried`(): Unit = runBlocking {
        runService()
        val real = tool
        val entered = root.resolve("native-running")
        tool = writeTool("echo \$\$ > ${quote(entered)}\nexec sleep 60")
        install()
        val runs = provide<PostgresBackupRunService>()
        val attempt = async { runs.backupConfigured("cancelled", "instance", "retained", "backups") }
        awaitFile(entered)
        val pid = entered.readText().trim().toLong()
        withTimeout(5_000) { attempt.cancelAndJoin() }
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        val cancelled = withConnectionManager { assertNotNull(runs.get("cancelled")) }
        assertEquals(PostgresBackupRunStatus.CANCELLED, cancelled.status)
        assertEquals(null, cancelled.result)
        assertEquals(null, cancelled.writerToken)
        assertEquals("0", scalar("select count(*)::text from backup.object_store_writers"))
        assertFalse(root.toFile().list().orEmpty().any { it.startsWith("postgres-backup-") })
        tool = real
        install()
        provide<PostgresBackupRunService>().backupConfigured("cancelled", "instance", "retained", "backups")
        assertEquals(2, withConnectionManager { assertNotNull(runs.get("cancelled")).attempts })
    }

    @Test
    fun `partial native upload is not published and a later attempt retains different output`(): Unit = runBlocking {
        runService()
        var fail = true
        val paths = mutableListOf<String>()
        faultStorage { actual -> object : ObjectStorageService by actual {
            override suspend fun setInputStream(path: ObjectPath, stream: InputStream, length: Long?): Long {
                assertEquals(0, pool.activeConnections)
                if (path.toString().endsWith("database.dump")) {
                    paths.add(path.toString())
                    if (fail) {
                        actual.setInputStream(path, stream.readNBytes(10).inputStream(), 10)
                        throw IOException("interrupted upload")
                    }
                }
                return actual.setInputStream(path, stream, length)
            }
        } }
        val runs = provide<PostgresBackupRunService>()
        assertFailsWith<IOException> { runs.backupConfigured("partial", "instance", "retained", "backups") }
        assertEquals(null, withConnectionManager { assertNotNull(runs.get("partial")).result })
        val partial = root.resolve("retained").resolve(paths.single())
        val original = Files.readAllBytes(partial)
        val abandoned = Json.decodeFromString(PostgresBackupManifest.serializer(), partial.parent.resolve("intent.json").readText())
        assertFalse(Files.exists(partial.parent.resolve("manifest.json")))
        assertFalse(root.toFile().list().orEmpty().any { it.startsWith("postgres-backup-") })
        fail = false
        sql("update workops.tasks set body='{\"name\":\"later-attempt\"}'")
        val receipt = runs.backupConfigured("partial", "instance", "retained", "backups")
        assertTrue(abandoned.id != receipt.manifest.id)
        assertTrue(Instant.parse(receipt.manifest.created) >= Instant.parse(abandoned.completed))
        assertEquals(2, paths.size)
        assertTrue(paths[0] != paths[1])
        assertTrue(original.contentEquals(Files.readAllBytes(partial)))
        assertTrue(Files.exists(root.resolve("retained/${receipt.path}/manifest.json")))
        assertEquals(2, withConnectionManager { assertNotNull(runs.get("partial")).attempts })
        provide<ObjectStorageServiceFactory>().withStorage(assertNotNull(retained)) { store ->
            service.download(store, receipt.path, output)
        }
        restore()
        assertEquals("later-attempt", scalar("select body->>'name' from workops.tasks", "recovered"))
    }

    @Test
    fun `native request cannot complete after short writes or storage close failures`(): Unit = runBlocking {
        runService()
        var short = true
        faultStorage { actual -> object : ObjectStorageService by actual {
            override suspend fun setInputStream(path: ObjectPath, stream: InputStream, length: Long?): Long {
                val written = actual.setInputStream(path, stream, length)
                return if (short && path.toString().endsWith("database.dump")) written - 1 else written
            }
            override fun close() {
                actual.close()
                if (!short) throw IOException("backup store close failed")
            }
        } }
        val runs = provide<PostgresBackupRunService>()
        assertFailsWith<IllegalStateException> { runs.backupConfigured("short", "instance", "retained", "backups") }
        short = false
        assertFailsWith<IOException> { runs.backupConfigured("close", "instance", "retained", "backups") }
        for (key in listOf("short", "close")) {
            val failed = withConnectionManager { assertNotNull(runs.get(key)) }
            assertEquals(PostgresBackupRunStatus.FAILED, failed.status)
            assertEquals(null, failed.result)
        }
        assertEquals("0", scalar("select count(*)::text from backup.object_store_writers"))
    }

    @Test
    fun `competing execution cannot replace the active native owner`(): Unit = runBlocking {
        runService()
        val entered = root.resolve("native-entered")
        val release = root.resolve("native-release")
        tool = writeTool("""
            touch ${quote(entered)}
            while [ ! -f ${quote(release)} ]; do sleep 0.03; done
            exec docker exec -e PGSERVICEFILE="${'$'}PGSERVICEFILE" ${postgres.containerId} pg_dump "${'$'}@"
        """.trimIndent())
        install()
        val runs = provide<PostgresBackupRunService>()
        val owner = async { runs.backupConfigured("owned", "instance", "retained", "backups") }
        awaitFile(entered)
        val running = withConnectionManager { assertNotNull(runs.get("owned")) }
        assertEquals(PostgresBackupRunStatus.RUNNING, running.status)
        assertNotNull(running.writerToken)
        assertFailsWith<IllegalStateException> { runs.backupConfigured("owned", "instance", "retained", "backups") }
        assertFailsWith<ObjectBackupDestinationBusyException> { runs.backupConfigured("contender", "instance", "retained", "backups") }
        assertEquals(running.writerToken, withConnectionManager { assertNotNull(runs.get("owned")).writerToken })
        release.writeText("go")
        withTimeout(30_000) { owner.await() }
        assertEquals(1, withConnectionManager { assertNotNull(runs.get("owned")).attempts })
    }

    private suspend fun faultStorage(wrap: (ObjectStorageService) -> ObjectStorageService) {
        val actual = provide<ObjectStorageServiceFactory>()
        provides<ObjectStorageServiceFactory>(singleton = true, overrideExisting = true) {
            object : ObjectStorageServiceFactory by actual {
                override suspend fun create(configuration: StorageConfiguration): ObjectStorageService = wrap(actual.create(configuration))
                override suspend fun <T> withStorage(configuration: StorageConfiguration, block: suspend (ObjectStorageService) -> T): T =
                    create(configuration).use { block(it) }
            }
        }
        BackupProviderRegistrar().register()
    }

    private suspend fun install(configured: Boolean = true, restoreConfigured: Boolean = true) {
        if (::application.isInitialized) application.shutdown()
        val config = buildJsonObject {
            put("backup", buildJsonObject {
                retained?.let { put("objectStores", buildJsonObject {
                    put("retained", Json.encodeToJsonElement(StorageConfiguration.serializer(), it))
                }) }
                put("postgres", buildJsonObject {
                    put("scratchDirectory", root.toString())
                    if (configured) {
                        put("service", "native capture")
                        put("serviceFile", root.resolve("services.conf").toString())
                        put("pgDump", tool.toString())
                    }
                    if (restoreConfigured) put("restore", buildJsonObject {
                        put("service", "native restore")
                        put("database", targetName)
                        put("serviceFile", root.resolve("restore.conf").toString())
                        put("pgRestore", restoreTool.toString())
                        put("psql", psqlTool.toString())
                    })
                })
            })
        }
        root.resolve("application.json").writeText(config.toString())
        application = BoscaApplication(ApplicationConfig.load(config.toString().byteInputStream()), ownsServices = false)
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { Json }
        provides<BoscaApplication>(singleton = true) { application }
        StorageProviderRegistrar().register()
        BackupProviderRegistrar().register()
        service = provide()
    }

    private fun writeTool(body: String): Path = root.resolve("pg-dump-${System.nanoTime()}").also {
        it.writeText("#!/bin/sh\nset -eu\n$body\n")
        assertTrue(it.toFile().setExecutable(true))
    }

    private suspend fun awaitFile(path: Path) = withTimeout(10_000) {
        while (!Files.exists(path)) delay(20)
    }

    private fun sql(query: String, database: String = "source") {
        DriverManager.getConnection(url + database, user, password).use { connection ->
            connection.createStatement().use { it.execute(query) }
        }
    }

    private fun scalar(query: String, database: String = "source"): String =
        DriverManager.getConnection(url + database, user, password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(query).use { rows ->
                    assertTrue(rows.next())
                    assertNotNull(rows.getString(1))
                }
            }
        }

    private fun createTarget() {
        sql("create role recovered_owner superuser login password '$password'", "postgres")
        sql("create database recovered owner recovered_owner template template0", "postgres")
    }

    private suspend fun restore(directory: Path = output) {
        createTarget()
        val expected = service.verify(directory)
        assertEquals(expected, service.restore(directory))
    }

    private fun quote(path: Path) = "'" + path.toString().replace("'", "'\\''") + "'"
}
