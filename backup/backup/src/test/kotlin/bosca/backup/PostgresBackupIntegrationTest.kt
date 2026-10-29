package bosca.backup

import bosca.backup.pipeline.PostgresRestoreNode
import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.migrations.CoreMigration
import bosca.db.migrations.FlywayMigration
import bosca.db.transaction
import bosca.db.withConnectionManager
import bosca.di.BackupProviderRegistrar
import bosca.di.ProviderRegistry
import bosca.di.StorageProviderRegistrar
import bosca.di.annotation.InternalDI
import bosca.di.provide
import bosca.di.provides
import bosca.pipelines.builtin.ToStringNode
import bosca.pipelines.model.Pipeline
import bosca.pipelines.model.PipelineEdge
import bosca.pipelines.model.PipelineRunStatus
import bosca.pipelines.node.InputNode
import bosca.pipelines.node.OutputNode
import bosca.pipelines.node.PipelineValue
import bosca.serialization.UUID
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.serialize
import bosca.storage.configuration.StorageConfiguration
import bosca.storage.service.ObjectPath
import bosca.storage.service.ManagedObjectStorageService
import bosca.storage.service.ObjectStorageServiceFactory
import bosca.storage.service.StringObjectPath
import io.mockk.mockk
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import java.util.Properties
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.containers.wait.strategy.WaitAllStrategy

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
    private var contentStorage: StorageConfiguration? = null
    private var recoveryStorage: StorageConfiguration? = null
    private var scratchDirectory: String? = null
    private var sharedContentStorage: StorageConfiguration? = null
    private var encryptionKey: String? = "local-recovery-test-encryption-key"
    private val output get() = root.resolve("capture")
    private val user = "backup_test"
    private val password = "local-test-only"
    private val url get() = "jdbc:postgresql://${postgres.host}:${postgres.getMappedPort(5432)}/"
    private fun jdbcUrl(database: String) = "$url$database?connectTimeout=10&loginTimeout=15&socketTimeout=60"

    @BeforeTest
    fun setUp(): Unit = runBlocking {
        root = createTempDirectory("bosca-postgres-backup-").toRealPath()
        postgres = GenericContainer<Nothing>("pgvector/pgvector:pg18").apply {
            withEnv("POSTGRES_USER", user)
            withEnv("POSTGRES_PASSWORD", password)
            withEnv("POSTGRES_DB", "source")
            withExposedPorts(5432)
            withFileSystemBind(root.toString(), root.toString())
            waitingFor(WaitAllStrategy()
                .withStrategy(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2))
                .withStrategy(Wait.forListeningPort()))
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
        pool = ConnectionPool(ConnectionFactoryImpl(ConnectionConfig(jdbcUrl("source"), user, password, maxConnections = 2), "native-capture"))
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
    fun `native key inventory records populated encrypted stores without copying secrets`(): Unit = runBlocking {
        createKeyInventoryTables()
        sql("""
            insert into configuration_values values (decode('0102', 'hex'));
            insert into meilisearch_nodes values (decode('0304', 'hex'));
            insert into kubernetes.cluster_credential values (decode('0506', 'hex'));
            insert into kubernetes.helm_repo_credential values (decode('0708', 'hex'));
            insert into pipelines.pipeline_secret values ('private-pipeline-ciphertext');
            insert into git.pipeline_secrets values ('private-git-ciphertext');
        """.trimIndent())
        val manifest = service.capture("source-instance", output)
        val fields = backupTestJson.encodeToJsonElement(PostgresBackupManifest.serializer(), manifest).jsonObject
        assertEquals(backupTestJson.parseToJsonElement("""{
            "security.encryption.key":["public.configuration_values","public.meilisearch_nodes","kubernetes.cluster_credential","kubernetes.helm_repo_credential"],
            "PIPELINE_SECRET_KEY":["pipelines.pipeline_secret","git.pipeline_secrets"]
        }"""), fields["requiredKeys"])
        val encoded = output.resolve("manifest.json").readText()
        assertFalse(encoded.contains("private-pipeline-ciphertext"))
        assertFalse(encoded.contains("private-git-ciphertext"))
        assertEquals(manifest, service.verify(output))
        assertEquals(0, pool.activeConnections)
    }

    @Test
    fun `native key inventory distinguishes absent empty and unencrypted stores`(): Unit = runBlocking {
        val absent = service.capture("source-instance", output)
        assertEquals(backupTestJson.parseToJsonElement("{}"),
            backupTestJson.encodeToJsonElement(PostgresBackupManifest.serializer(), absent).jsonObject["requiredKeys"])
        createKeyInventoryTables()
        sql("insert into meilisearch_nodes values (null)")
        val empty = service.capture("source-instance", root.resolve("empty-key-inventory"))
        assertEquals(backupTestJson.parseToJsonElement("{}"),
            backupTestJson.encodeToJsonElement(PostgresBackupManifest.serializer(), empty).jsonObject["requiredKeys"])
    }

    @Test
    fun `native key inventory shares the dump snapshot while encrypted rows change`(): Unit = runBlocking {
        createKeyInventoryTables()
        sql("insert into configuration_values values (decode('0102', 'hex'))")
        val entered = root.resolve("dump-entered")
        val release = root.resolve("dump-release")
        tool = writeTool("""
            touch ${quote(entered)}
            while [ ! -f ${quote(release)} ]; do sleep 0.03; done
            exec docker exec -e PGSERVICEFILE="${'$'}PGSERVICEFILE" ${postgres.containerId} pg_dump "${'$'}@"
        """.trimIndent())
        install()
        val capture = async { service.capture("source-instance", output) }
        try {
            awaitFile(entered)
            sql("delete from configuration_values; insert into pipelines.pipeline_secret values ('later-ciphertext')")
        } finally {
            release.writeText("go")
        }
        val manifest = withTimeout(30_000) { capture.await() }
        assertEquals(backupTestJson.parseToJsonElement("""{"security.encryption.key":["public.configuration_values"]}"""),
            backupTestJson.encodeToJsonElement(PostgresBackupManifest.serializer(), manifest).jsonObject["requiredKeys"])
        pool.close()
        sql("drop database source with (force)", "postgres")
        install(configured = false)
        restore()
        assertEquals("1", scalar("select count(*) from configuration_values", "recovered"))
        assertEquals("0", scalar("select count(*) from pipelines.pipeline_secret", "recovered"))
    }

    @Test
    fun `native key inventory errors prevent artifact publication and release the snapshot`(): Unit = runBlocking {
        sql("create table configuration_values (opaque text)")
        val failure = assertFailsWith<java.sql.SQLException> { service.capture("source-instance", output) }
        assertTrue(failure.message.orEmpty().contains("value"))
        assertFalse(Files.exists(output))
        assertEquals(0, pool.activeConnections)
    }

    private fun createKeyInventoryTables() {
        // Minimal encrypted columns exercise native relation discovery; application recovery uses CoreMigration.
        sql("""
            create table configuration_values (value bytea);
            create table meilisearch_nodes (api_key bytea);
            create schema kubernetes;
            create schema pipelines;
            create table kubernetes.cluster_credential (data bytea);
            create table kubernetes.helm_repo_credential (data bytea);
            create table pipelines.pipeline_secret (encrypted_value text);
            create table git.pipeline_secrets (encrypted_value text);
        """.trimIndent())
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
        sql("insert into workops.tasks values (3, '{\"name\":\"removed\"}')")
        val entered = root.resolve("entered")
        val release = root.resolve("release")
        tool = writeTool("""
            touch ${quote(entered)}
            while [ ! -f ${quote(release)} ]; do sleep 0.03; done
            exec docker exec -e PGSERVICEFILE="${'$'}PGSERVICEFILE" ${postgres.containerId} pg_dump "${'$'}@"
        """.trimIndent())
        install()
        val capture = async { service.capture("live", output) }
        try {
            awaitFile(entered)
            assertFalse(Files.exists(output.resolve("manifest.json")))
            assertEquals(1, pool.activeConnections)
            assertFailsWith<java.nio.file.FileAlreadyExistsException> { service.capture("other", output) }
            sql("""
                update workops.tasks set body='{"name":"new"}' where id=1;
                insert into workops.tasks values (2,'{}');
                delete from workops.tasks where id=3;
                delete from artifacts.events
            """.trimIndent())
        } finally {
            release.writeText("go")
        }
        val manifest = withTimeout(30_000) { capture.await() }
        assertEquals(0, pool.activeConnections)
        assertEquals("new", scalar("select body->>'name' from workops.tasks where id=1"))
        restore()
        assertEquals("captured", scalar("select body->>'name' from workops.tasks where id=1", "recovered"))
        assertEquals("removed", scalar("select body->>'name' from workops.tasks where id=3", "recovered"))
        assertEquals("2", scalar("select count(*)::text from workops.tasks", "recovered"))
        assertEquals("retained", scalar("select value from artifacts.events", "recovered"))
        assertEquals(manifest, service.verify(output))
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

    private suspend fun withNativeRecovery(
        backupConfiguration: StorageConfiguration? = null,
        beforeCapture: suspend () -> Unit = {},
        beforeRecovery: () -> Unit = {},
        block: suspend (PostgresBackupManifest, String) -> Unit,
    ) {
        beforeCapture()
        val manifest = service.capture("original-instance", output)
        val path = "native/${manifest.id}"
        retained = backupConfiguration ?: StorageConfiguration("fs", "", basePath = root.resolve("native-retained").toString())
        provide<ObjectStorageServiceFactory>().withStorage(assertNotNull(retained)) { backup -> service.upload(output, backup, path) }
        createTarget()
        tool = root.resolve("missing-pg-dump")
        beforeRecovery()
        install(configured = false)
        pool.close()
        sql("drop database source with (force)", "postgres")
        assertTrue(output.toFile().deleteRecursively())
        root.resolve("operation-source").toFile().deleteRecursively()
        sql("create database native_recovery_journal", "postgres")
        val journal = ConnectionPool(ConnectionFactoryImpl(
            ConnectionConfig(jdbcUrl("native_recovery_journal"), user, password), "native-recovery-journal",
        ))
        try {
            provides<ConnectionPool>(singleton = true, overrideExisting = true) { journal }
            provides<Json>(singleton = true, overrideExisting = true) { backupTestJson }
            BackupProviderRegistrar().register()
            FlywayMigration(journal).migrate(listOf(BackupMigration()))
            service = provide()
            block(manifest, path)
            assertEquals(0, journal.activeConnections)
            assertFalse(Files.exists(output))
        } finally {
            journal.close()
        }
    }

    private fun assertNativeRecoveryEmpty() {
        assertEquals("0", scalar("select count(*)::text from pg_class where relnamespace='public'::regnamespace", "recovered"))
        assertRecoveryScratchClean()
    }

    @Test
    fun `queued native restore recovers a database-only receipt after source removal`(): Unit = runBlocking {
        withNativeRecovery { manifest, path ->
            PostgresBackupPipelineFixture.create(application, provide<ConnectionPool>(), root, graph = nativeRestorePipeline()).use { pipeline ->
                val initial = pipeline.start(PipelineValue.ofJson(JsonPrimitive(path)))
                val runner = pipeline.runner()
                runner.run()
                try {
                    val completed = pipeline.awaitComplete(initial.id)
                    assertEquals(PipelineRunStatus.OK, completed.status)
                    assertEquals(manifest, application.json.decodeFromJsonElement(PostgresBackupManifest.serializer(), assertNotNull(completed.output)))
                    assertEquals("captured", scalar("select body->>'name' from workops.tasks", "recovered"))
                    assertEquals("retained", scalar("select value from artifacts.events", "recovered"))
                    assertFalse(Files.exists(root.resolve("content-recovered-assets")))
                    assertRecoveryScratchClean()
                    pipeline.verifyNoServiceAccountFallback()
                } finally {
                    runner.shutdown()
                }
            }
        }
    }

    @Test
    fun `queued native restore rechecks revoked caller authorization before recovery writes`(): Unit = runBlocking {
        withNativeRecovery { _, path ->
            PostgresBackupPipelineFixture.create(application, provide<ConnectionPool>(), root, graph = nativeRestorePipeline()).use { pipeline ->
                val initial = pipeline.start(PipelineValue.ofJson(JsonPrimitive(path)))
                pipeline.execute(pipeline.next())
                pipeline.denied = true
                pipeline.fail(pipeline.next(), retry = false)
                assertEquals(PipelineRunStatus.FAILED, pipeline.get(initial.id).status)
                assertEquals(null, pipeline.get(initial.id).output)
                assertNativeRecoveryEmpty()
                pipeline.verifyNoServiceAccountFallback()
            }
        }
    }

    private fun nativeRestorePipeline() = Pipeline(
        id = UUID.NIL, name = "restore retained native database", acceptedInputType = "JSON",
        nodes = listOf(
            InputNode("in", acceptedType = "JSON"), ToStringNode("path"),
            PostgresRestoreNode("restore", backupKey = "retained"), OutputNode("out"),
        ),
        edges = listOf(
            PipelineEdge("input", source = "in", target = "path"),
            PipelineEdge("path", source = "path", target = "restore", targetPort = "path"),
            PipelineEdge("output", source = "restore", target = "out"),
        ),
    )

    @Test
    fun `configured native restore recovers retained artifact and cleans scratch without a source`(): Unit = runBlocking {
        withNativeRecovery { manifest, path ->
            val untouched = root.resolve("operator-owned.txt").also { it.writeText("keep") }
            var opened = 0
            var closed = 0
            faultStorage { actual ->
                opened++
                object : ManagedObjectStorageService by actual {
                    override fun close() { closed++; actual.close() }
                }
            }
            service = provide()
            assertEquals(manifest, service.restoreConfigured(path, "retained"))
            assertEquals(1, opened)
            assertEquals(1, closed)
            assertEquals("captured", scalar("select body->>'name' from workops.tasks", "recovered"))
            assertEquals("{VIEW,EDIT}", scalar("select permissions::text from content.permissions", "recovered"))
            assertEquals("keep", untouched.readText())
            assertRecoveryScratchClean()
            assertNotNull(withConnectionManager { provide<ObjectBackupStorageService>().get("retained") })
        }
    }

    @Test
    fun `configured native restore reads signed independent S3 storage without local capture`(): Unit = runBlocking {
        var selectedPool = pool
        PostgresBackupS3TestStore { assertEquals(0, selectedPool.activeConnections) }.use { remote ->
            withNativeRecovery(backupConfiguration = remote.configuration) { manifest, path ->
                selectedPool = provide<ConnectionPool>()
                assertEquals(manifest, service.restoreConfigured(path, "retained"))
                assertEquals("captured", scalar("select body->>'name' from workops.tasks", "recovered"))
                assertTrue(remote.signatures.isNotEmpty())
                assertTrue(remote.signatures.all { "Credential=POSTGRES_TRANSFER_KEY/" in it })
                assertRecoveryScratchClean()
            }
        }
    }

    @Test
    fun `configured native restore rejects corrupt downloads before any target data`(): Unit = runBlocking {
        withNativeRecovery { _, path ->
            root.resolve("native-retained/$path/database.dump").writeText("corrupt")
            assertFailsWith<Exception> { service.restoreConfigured(path, "retained") }
            assertNativeRecoveryEmpty()
        }
    }

    @Test
    fun `configured native restore requires a published manifest and preserves retained bytes`(): Unit = runBlocking {
        withNativeRecovery { _, path ->
            val dump = root.resolve("native-retained/$path/database.dump")
            val before = Files.readAllBytes(dump).toList()
            Files.delete(root.resolve("native-retained/$path/manifest.json"))
            assertFailsWith<Exception> { service.restoreConfigured(path, "retained") }
            assertEquals(before, Files.readAllBytes(dump).toList())
            assertNativeRecoveryEmpty()
        }
    }

    @Test
    fun `configured native restore validates paths identities and caller transactions before storage opens`(): Unit = runBlocking {
        withNativeRecovery { _, path ->
            faultStorage { error("Invalid request must not open storage") }
            service = provide()
            for (invalid in listOf("", "/absolute", "native/../capture", "native//capture")) {
                assertFailsWith<IllegalArgumentException> { service.restoreConfigured(invalid, "retained") }
            }
            assertFailsWith<IllegalArgumentException> { service.restoreConfigured(path, " ") }
            assertFailsWith<IllegalStateException> {
                withConnectionManager { transaction { service.restoreConfigured(path, "retained") } }
            }
            assertNativeRecoveryEmpty()
        }
    }

    @Test
    fun `configured native restore missing selected storage fails before opening any store`(): Unit = runBlocking {
        withNativeRecovery { _, path ->
            faultStorage { error("Unknown storage must not be opened") }
            service = provide()
            assertFailsWith<IllegalArgumentException> { service.restoreConfigured(path, "unknown") }
            assertNativeRecoveryEmpty()
        }
    }

    @Test
    fun `configured native restore missing catalog fails without a source fallback`(): Unit = runBlocking {
        withNativeRecovery(beforeRecovery = { retained = null }) { _, path ->
            assertFailsWith<IllegalStateException> { service.restoreConfigured(path, "retained") }
            assertNativeRecoveryEmpty()
        }
    }

    @Test
    fun `configured native restore preserves a conflicting bound address before storage access`(): Unit = runBlocking {
        withNativeRecovery { _, path ->
            val original = withConnectionManager {
                provide<ObjectBackupStorageService>().bind("retained", StorageConfiguration("fs", "", basePath = root.resolve("elsewhere").toString()))
            }
            faultStorage { error("Conflicting storage must not be opened") }
            service = provide()
            assertFailsWith<IllegalArgumentException> { service.restoreConfigured(path, "retained") }
            assertEquals(original, withConnectionManager { provide<ObjectBackupStorageService>().get("retained") })
            assertNativeRecoveryEmpty()
        }
    }

    @Test
    fun `configured native restore invalid scratch parent preserves caller files without opening storage`(): Unit = runBlocking {
        withNativeRecovery(beforeRecovery = { scratchDirectory = root.resolve("missing-parent").toString() }) { _, path ->
            val untouched = root.resolve("operator-owned.txt").also { it.writeText("keep") }
            faultStorage { error("Invalid scratch must not open storage") }
            service = provide()
            assertFailsWith<IOException> { service.restoreConfigured(path, "retained") }
            assertEquals("keep", untouched.readText())
            assertNativeRecoveryEmpty()
        }
    }

    @Test
    fun `configured native restore cancelled download closes storage cleans scratch and can retry`(): Unit = runBlocking {
        withNativeRecovery { manifest, path ->
            var cancelled = true
            var closed = 0
            faultStorage { actual -> object : ManagedObjectStorageService by actual {
                override suspend fun getInputStream(path: ObjectPath): InputStream {
                    if (cancelled && path.toString().endsWith("database.dump")) throw kotlinx.coroutines.CancellationException("download cancelled")
                    return actual.getInputStream(path)
                }
                override fun close() { closed++; actual.close() }
            } }
            service = provide()
            assertFailsWith<kotlinx.coroutines.CancellationException> { service.restoreConfigured(path, "retained") }
            assertEquals(1, closed)
            assertNativeRecoveryEmpty()
            cancelled = false
            assertEquals(manifest, service.restoreConfigured(path, "retained"))
            assertEquals(2, closed)
            assertRecoveryScratchClean()
        }
    }

    @Test
    fun `configured native restore process failure preserves empty target and permits a later attempt`(): Unit = runBlocking {
        val allowed = root.resolve("allow-native-restore")
        withNativeRecovery(beforeRecovery = {
            val real = restoreTool
            restoreTool = writeTool("if [ ! -f ${quote(allowed)} ]; then echo 'native restore unavailable' >&2; exit 7; fi\nexec ${quote(real)} \"\$@\"")
        }) { manifest, path ->
            assertFailsWith<IllegalStateException> { service.restoreConfigured(path, "retained") }
            assertNativeRecoveryEmpty()
            allowed.writeText("go")
            assertEquals(manifest, service.restoreConfigured(path, "retained"))
            assertEquals("captured", scalar("select body->>'name' from workops.tasks", "recovered"))
            assertRecoveryScratchClean()
        }
    }

    @Test
    fun `configured native restore close failure after commit stays a failure and populated replay is blocked`(): Unit = runBlocking {
        withNativeRecovery { _, path ->
            var fail = true
            var closed = 0
            faultStorage { actual -> object : ManagedObjectStorageService by actual {
                override fun close() {
                    actual.close()
                    closed++
                    if (fail) { fail = false; throw IOException("backup close failed after native commit") }
                }
            } }
            service = provide()
            assertFailsWith<IOException> { service.restoreConfigured(path, "retained") }
            assertEquals(1, closed)
            assertEquals("captured", scalar("select body->>'name' from workops.tasks", "recovered"))
            assertRecoveryScratchClean()
            assertFailsWith<IllegalStateException> { service.restoreConfigured(path, "retained") }
            assertEquals(2, closed)
            assertEquals("captured", scalar("select body->>'name' from workops.tasks", "recovered"))
            assertRecoveryScratchClean()
        }
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

    private suspend fun declaredKeyArtifact(key: String = "security.encryption.key"): PostgresBackupManifest {
        val declared = service.capture("instance", output).copy(requiredKeys = mapOf(key to listOf("public.configuration_values")))
        output.resolve("manifest.json").writeText(application.json.encodeToString(PostgresBackupManifest.serializer(), declared))
        return declared
    }

    @Test
    fun `native key preflight rejects a missing declared key before destination clients`(): Unit = runBlocking {
        val manifest = declaredKeyArtifact()
        createTarget()
        val entered = root.resolve("key-preflight-entered")
        psqlTool = writeTool("touch ${quote(entered)}; exit 0")
        encryptionKey = null
        install(configured = false)
        val failure = assertFailsWith<IllegalStateException> { service.preflightRestore(output) }
        assertTrue(failure.message.orEmpty().contains("security.encryption.key"))
        assertFalse(Files.exists(entered))
        assertEquals(manifest, service.verify(output))
        assertNativeRecoveryEmpty()
    }

    @Test
    fun `native key preflight rejects unsupported declared keys before destination clients`(): Unit = runBlocking {
        val manifest = declaredKeyArtifact("plugin.encryption.key")
        createTarget()
        val entered = root.resolve("unknown-key-preflight-entered")
        psqlTool = writeTool("touch ${quote(entered)}; exit 0")
        install(configured = false)
        val failure = assertFailsWith<IllegalArgumentException> { service.preflightRestore(output) }
        assertTrue(failure.message.orEmpty().contains("plugin.encryption.key"))
        assertFalse(Files.exists(entered))
        assertEquals(manifest, service.verify(output))
        assertNativeRecoveryEmpty()
    }

    @Test
    fun `native key preflight repeats availability after a successful earlier check`(): Unit = runBlocking {
        val manifest = declaredKeyArtifact()
        createTarget()
        assertEquals(manifest, service.preflightRestore(output))
        val entered = root.resolve("key-restore-entered")
        restoreTool = writeTool("touch ${quote(entered)}; exit 0")
        encryptionKey = null
        install(configured = false)
        val failure = assertFailsWith<IllegalStateException> { service.restore(output) }
        assertTrue(failure.message.orEmpty().contains("security.encryption.key"))
        assertFalse(Files.exists(entered))
        assertEquals(manifest, service.verify(output))
        assertNativeRecoveryEmpty()
    }

    @Test
    fun `native key preflight leaves capture verification and transport independent of keys`(): Unit = runBlocking {
        createKeyInventoryTables()
        sql("insert into configuration_values values (decode('0102', 'hex'))")
        encryptionKey = null
        install()
        val manifest = service.capture("instance", output)
        assertTrue(manifest.requiredKeys.containsKey("security.encryption.key"))
        provide<ObjectStorageServiceFactory>().withStorage(
            StorageConfiguration("fs", "", basePath = root.resolve("key-retained").toString()),
        ) { backup ->
            assertEquals(manifest, service.upload(output, backup, "key-portability"))
            pool.close()
            sql("drop database source with (force)", "postgres")
            assertTrue(output.toFile().deleteRecursively())
            install(configured = false)
            val downloaded = root.resolve("key-download")
            assertEquals(manifest, service.download(backup, "key-portability", downloaded))
            assertEquals(manifest, service.verify(downloaded))
            val failure = assertFailsWith<IllegalStateException> { service.preflightRestore(downloaded) }
            assertTrue(failure.message.orEmpty().contains("security.encryption.key"))
            assertEquals(manifest, service.verify(downloaded))
        }
    }

    @Test
    fun `configured native key preflight rejects missing keys before destination clients`(): Unit = runBlocking {
        val entered = root.resolve("configured-key-preflight-entered")
        withNativeRecovery(
            beforeCapture = {
                createKeyInventoryTables()
                sql("insert into configuration_values values (decode('0102', 'hex'))")
            },
            beforeRecovery = {
                encryptionKey = null
                psqlTool = writeTool("touch ${quote(entered)}; exit 0")
            },
        ) { manifest, path ->
            assertTrue(manifest.requiredKeys.containsKey("security.encryption.key"))
            val failure = assertFailsWith<IllegalStateException> { service.restoreConfigured(path, "retained") }
            assertTrue(failure.message.orEmpty().contains("security.encryption.key"))
            assertFalse(Files.exists(entered))
            assertNativeRecoveryEmpty()
        }
    }

    @Test
    fun `queued native key preflight records failure without destination writes`(): Unit = runBlocking {
        withNativeRecovery(
            beforeCapture = {
                createKeyInventoryTables()
                sql("insert into configuration_values values (decode('0102', 'hex'))")
            },
            beforeRecovery = { encryptionKey = null },
        ) { _, path ->
            PostgresBackupPipelineFixture.create(application, provide<ConnectionPool>(), root, graph = nativeRestorePipeline()).use { pipeline ->
                val initial = pipeline.start(PipelineValue.ofJson(JsonPrimitive(path)))
                pipeline.execute(pipeline.next())
                pipeline.fail(pipeline.next(), retry = false)
                val failed = pipeline.get(initial.id)
                assertEquals(PipelineRunStatus.FAILED, failed.status)
                assertEquals(null, failed.output)
                assertTrue(failed.error.orEmpty().contains("security.encryption.key"))
                assertNativeRecoveryEmpty()
                pipeline.verifyNoServiceAccountFallback()
            }
        }
    }

    @Test
    fun `native restore preflight rejects occupied and incorrectly named destinations without changing data`(): Unit = runBlocking {
        val manifest = service.capture("instance", output)
        createTarget()
        sql("create table public.existing(id int); insert into public.existing values (123)", "recovered")
        val occupied = assertFailsWith<IllegalStateException> { service.preflightRestore(output) }
        assertTrue(occupied.message.orEmpty().contains("empty"), occupied.toString())
        targetName = "somewhere_else"
        install()
        val named = assertFailsWith<IllegalStateException> { service.preflightRestore(output) }
        assertTrue(named.message.orEmpty().contains("destination database"), named.toString())
        assertEquals("123", scalar("select id::text from public.existing", "recovered"))
        assertEquals(manifest, service.verify(output))
    }

    @Test
    fun `native restore preflight rejects incompatible server encoding and extension requirements`(): Unit = runBlocking {
        val manifest = service.capture("instance", output)
        createTarget()
        for (variant in listOf(
            manifest.copy(serverVersionNumber = 990000),
            manifest.copy(encoding = "LATIN1"),
            manifest.copy(extensions = manifest.extensions + ("vector" to "9999.0")),
        )) {
            output.resolve("manifest.json").writeText(Json.encodeToString(PostgresBackupManifest.serializer(), variant))
            val failure = assertFailsWith<IllegalStateException> { service.preflightRestore(output) }
            assertTrue(failure.message.orEmpty().contains("psql restore preflight"), failure.toString())
            assertEquals("0", scalar("select count(*)::text from pg_class where relnamespace='public'::regnamespace", "recovered"))
        }
    }

    @Test
    fun `native restore preflight requires target configuration and rejects caller transactions`(): Unit = runBlocking {
        val manifest = service.capture("instance", output)
        install(restoreConfigured = false)
        val missing = assertFailsWith<IllegalStateException> { service.preflightRestore(output) }
        assertTrue(missing.message.orEmpty().contains("backup.postgres.restore.service"), missing.toString())
        val entered = root.resolve("destination-entered")
        psqlTool = writeTool("touch ${quote(entered)}; exit 0")
        install()
        val transactionFailure = assertFailsWith<IllegalStateException> {
            withConnectionManager { transaction { service.preflightRestore(output) } }
        }
        assertTrue(transactionFailure.message.orEmpty().contains("transaction"), transactionFailure.toString())
        assertFalse(Files.exists(entered))
        assertEquals(manifest, service.verify(output))
        assertEquals(0, pool.activeConnections)
    }

    @Test
    fun `native restore preflight missing psql preserves target and artifact`(): Unit = runBlocking {
        val manifest = service.capture("instance", output)
        createTarget()
        psqlTool = root.resolve("missing-psql")
        install()
        assertFailsWith<IOException> { service.preflightRestore(output) }
        assertEquals(manifest, service.verify(output))
        assertEquals("0", scalar("select count(*)::text from pg_class where relnamespace='public'::regnamespace", "recovered"))
    }

    @Test
    fun `native restore preflight cancellation stops its local client and preserves data`(): Unit = runBlocking {
        val manifest = service.capture("instance", output)
        createTarget()
        val entered = root.resolve("preflight-process")
        psqlTool = writeTool("echo \$\$ > ${quote(entered)}\nexec sleep 60")
        install()
        val attempt = async { service.preflightRestore(output) }
        awaitFile(entered)
        val pid = entered.readText().trim().toLong()
        withTimeout(5_000) { attempt.cancelAndJoin() }
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        assertEquals(manifest, service.verify(output))
        assertEquals(setOf("database.dump", "manifest.json"), output.toFile().list()?.toSet())
        assertEquals("0", scalar("select count(*)::text from pg_class where relnamespace='public'::regnamespace", "recovered"))
    }

    @Test
    fun `native restore preflight does not bypass later restore checks`(): Unit = runBlocking {
        val manifest = service.capture("instance", output)
        createTarget()
        val entered = root.resolve("restore-entered")
        restoreTool = writeTool("touch ${quote(entered)}; exit 0")
        install()
        assertEquals(manifest, service.preflightRestore(output))
        assertFalse(Files.exists(entered))
        sql("create table public.existing(id int); insert into public.existing values (123)", "recovered")
        val changed = assertFailsWith<IllegalStateException> { service.restore(output) }
        assertTrue(changed.message.orEmpty().contains("empty"), changed.toString())
        assertFalse(Files.exists(entered))
        assertEquals("123", scalar("select id::text from public.existing", "recovered"))
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
        val real = restoreTool
        restoreTool = writeTool("""
            if [ "${'$'}1" = "--list" ]; then exec ${quote(real)} "${'$'}@"; fi
            echo ${'$'}${'$'} > ${quote(entered)}
            exec sleep 60
        """.trimIndent())
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

    @Test
    fun `independent database replacement then object restore refreshes a recovery instance without its source`(): Unit = runBlocking {
        verifyIndependentRecovery()
    }

    @Test
    fun `S3Proxy backups recover PostgreSQL then objects after source removal and database replacement`(): Unit = runBlocking {
        S3ProxyTestStore().use { s3 ->
            S3ProxyTestStore().use { copied ->
                verifyIndependentRecovery(s3, copied)
            }
        }
    }

    private suspend fun verifyIndependentRecovery(s3: S3ProxyTestStore? = null, copied: S3ProxyTestStore? = null) {
        FlywayMigration(pool).migrate(listOf(CoreMigration(), BackupMigration()))
        contentStorage = s3?.configuration("content")
            ?: StorageConfiguration("fs", "", basePath = root.resolve("operation-source").toString())
        retained = s3?.configuration("retained")
            ?: StorageConfiguration("fs", "", basePath = root.resolve("independent-retained").toString())
        recoveryStorage = (copied ?: s3)?.configuration("recovered")
            ?: StorageConfiguration("fs", "", basePath = root.resolve("content-recovered-assets").toString())
        sharedContentStorage = recoveryStorage?.copy(urlPrefix = "https://recovered.invalid")
        install()
        val sourceReads = ConcurrentLinkedQueue<String>()
        val retainedWrites = ConcurrentLinkedQueue<String>()
        val targetWrites = ConcurrentLinkedQueue<String>()
        suspend fun observeTransfers() {
            if (s3 == null) return
            val factory = provide<ObjectStorageServiceFactory>()
            provides<ObjectStorageServiceFactory>(singleton = true, overrideExisting = true) {
                object : ObjectStorageServiceFactory by factory {
                    override suspend fun create(configuration: StorageConfiguration): ManagedObjectStorageService {
                        val actual = factory.create(configuration)
                        return object : ManagedObjectStorageService by actual {
                            override suspend fun getInputStream(path: ObjectPath): InputStream {
                                if (configuration.bucket == "content") sourceReads.add(path.toString())
                                return actual.getInputStream(path)
                            }

                            override suspend fun setInputStream(path: ObjectPath, stream: InputStream, length: Long?): Long {
                                if (configuration.bucket == "retained" && path.toString().startsWith("objects/payloads/")) {
                                    retainedWrites.add(path.toString())
                                }
                                if (configuration.bucket == "recovered") targetWrites.add(path.toString())
                                return actual.setInputStream(path, stream, length)
                            }
                        }
                    }

                    override suspend fun <T> withStorage(
                        configuration: StorageConfiguration,
                        block: suspend (ManagedObjectStorageService) -> T,
                    ): T = create(configuration).use { block(it) }
                }
            }
        }
        observeTransfers()
        provides<Json>(singleton = true, overrideExisting = true) { backupTestJson }
        BackupProviderRegistrar().register()
        val content = PostgresContentRecoveryFixture(root, backupTestJson)
        val image = "10000000-0000-0000-0000-000000000001"
        val publicImage = "10000000-0000-0000-0000-000000000002"
        for ((id, bytes) in listOf(image to "image", publicImage to "other")) {
            sql("insert into metadata (id, version, name, content_type, content_length, uploaded) values ('$id', 7, 'Saved image', 'image/png', 5, now())")
            root.resolve("operation-source/metadata/$id/7/content").also { Files.createDirectories(it.parent) }.writeText(bytes)
        }
        content.seed(::sql)
        if (s3 != null) {
            val sourceRoot = root.resolve("operation-source")
            val files = Files.walk(sourceRoot).use { paths -> paths.filter(Files::isRegularFile).toList() }
            provide<ObjectStorageServiceFactory>().withStorage(assertNotNull(contentStorage)) { source ->
                for (file in files) {
                    Files.newInputStream(file).use { stream ->
                        source.setInputStream(StringObjectPath(sourceRoot.relativize(file).toString()), stream, Files.size(file))
                    }
                }
            }
        }

        suspend fun write(configuration: StorageConfiguration, key: String, value: String) {
            provide<ObjectStorageServiceFactory>().withStorage(configuration) { storage ->
                value.byteInputStream().use { storage.setInputStream(StringObjectPath(key), it) }
            }
        }

        suspend fun retainPoint(id: String): Pair<PostgresBackupManifest, ObjectBackupManifest> {
            val directory = root.resolve("database-$id")
            val database = service.capture("source-instance", directory)
            provide<ObjectStorageServiceFactory>().withStorage(assertNotNull(retained)) { backup ->
                service.upload(directory, backup, "databases/$id")
            }
            directory.toFile().deleteRecursively()
            val capture = provide<ObjectBackupCaptureService>()
            val plan = capture.discoverConfigured(ObjectBackupDiscoveryRequest(
                id, "source-instance", "content", "retained", mapOf("content" to ObjectBackupSource("content-source")),
                "objects/payloads", "objects/$id.json",
            ))
            return database to capture.captureConfigured(plan.key, java.time.OffsetDateTime.MIN)
        }

        val first = retainPoint("first")
        if (s3 != null) {
            assertEquals(first.second.objects.size, sourceReads.size)
            assertEquals(first.second.objects.size, retainedWrites.size)
        }
        sourceReads.clear()
        retainedWrites.clear()
        val unchangedPoint = retainPoint("unchanged")
        assertEquals(first.second.objects, unchangedPoint.second.objects)
        assertTrue(sourceReads.isEmpty())
        assertTrue(retainedWrites.isEmpty())
        sql("update workops.tasks set body='{\"name\":\"later\"}'; update metadata set uploaded=now() where id='$image'")
        write(assertNotNull(contentStorage), "metadata/$image/7/content", "newer")
        val later = retainPoint("later")
        if (s3 != null) {
            assertEquals(listOf("metadata/$image/7/content"), sourceReads.toList())
            assertEquals(1, retainedWrites.size)
        }
        val unchanged = first.second.objects.single { it.key == "metadata/$publicImage/7/content" }
        assertEquals(unchanged, later.second.objects.single { it.key == unchanged.key })
        assertTrue(first.second.objects.single { it.key == "metadata/$image/7/content" }.sha256 !=
            later.second.objects.single { it.key == "metadata/$image/7/content" }.sha256)
        pool.close()
        sql("drop database source with (force)", "postgres")
        if (s3 != null) {
            provide<ObjectStorageServiceFactory>().withStorage(assertNotNull(contentStorage)) { source ->
                for (entry in source.listObjects("", 0, 1000)) source.delete(StringObjectPath(entry.key))
                assertTrue(source.listObjects("", 0, 1000).isEmpty())
            }
        }
        assertTrue(root.resolve("operation-source").toFile().deleteRecursively())
        contentStorage = null
        val restoredBackupKey = if (copied == null) "retained" else "retained-copy"
        if (copied != null) {
            val copiedConfiguration = copied.configuration(restoredBackupKey)
            val factory = provide<ObjectStorageServiceFactory>()
            factory.withStorage(assertNotNull(retained)) { original ->
                factory.withStorage(copiedConfiguration) { destination ->
                    var offset = 0L
                    while (true) {
                        val page = original.listObjects("", offset, 100)
                        if (page.isEmpty()) break
                        for (entry in page) {
                            val path = StringObjectPath(entry.key)
                            original.getInputStream(path).use { destination.setInputStream(path, it, entry.info.length) }
                        }
                        offset += page.size
                    }
                }
            }
            retained = copiedConfiguration
            assertNotNull(s3).close()
        }
        createTarget()

        suspend fun recoverPoint(id: String, expected: Pair<PostgresBackupManifest, ObjectBackupManifest>) {
            val directory = root.resolve("download-$id")
            // Database recovery uses the retained native artifact independently of object progress.
            provide<ObjectStorageServiceFactory>().withStorage(assertNotNull(retained)) { backup ->
                assertEquals(expected.first, service.download(backup, "databases/$id", directory))
            }
            assertEquals(expected.first, service.restore(directory))
            pool = ConnectionPool(ConnectionFactoryImpl(
                ConnectionConfig(jdbcUrl("recovered"), "recovered_owner", password), "independent-recovery",
            ))
            install(configured = false, retainedKey = restoredBackupKey)
            observeTransfers()
            provides<Json>(singleton = true, overrideExisting = true) { backupTestJson }
            BackupProviderRegistrar().register()
            val runs = provide<ObjectBackupRunService>()
            val prepared = runs.prepareRestoreConfigured("recover-$id", "recovery-instance", restoredBackupKey, "objects/$id.json",
                mapOf("content" to "recovery-target"))
            val completed = runs.executeConfigured(prepared.key)
            assertEquals(ObjectBackupRunStatus.COMPLETED, completed.status)
            assertEquals(expected.second.objects.size.toLong(), assertNotNull(completed.result).completed)
        }

        recoverPoint("first", first)
        if (s3 != null) assertEquals(first.second.objects.size, targetWrites.size)
        content.verify(pool)
        write(assertNotNull(recoveryStorage), "outside-selection", "keep")
        sql("update workops.tasks set body='{\"name\":\"destination edit\"}'", "recovered")
        pool.close()
        sql("drop database recovered with (force)", "postgres")
        sql("create database recovered owner recovered_owner template template0", "postgres")
        targetWrites.clear()
        // Replacing PostgreSQL discards its old restore journal; existing target bytes still avoid copying.
        recoverPoint("later", later)
        if (s3 != null) assertEquals(listOf("metadata/$image/7/content"), targetWrites.toList())
        assertEquals("later", scalar("select body->>'name' from workops.tasks", "recovered"))
        provide<ObjectStorageServiceFactory>().withStorage(assertNotNull(recoveryStorage)) { target ->
            for ((key, expected) in mapOf(
                "metadata/$image/7/content" to "newer", "metadata/$publicImage/7/content" to "other", "outside-selection" to "keep",
            )) {
                assertEquals(expected, target.getInputStream(StringObjectPath(key)).use { it.reader().readText() })
            }
        }
        assertFalse(Files.exists(root.resolve("operation-source")))
    }

    @Test
    fun `completed database backup emits its stored artifact location and replays without recapture`(): Unit = runBlocking {
        val runs = runService()
        val events = BackupEventTestDispatcher()
        provides<bosca.pipelines.PipelineEventDispatcher>(singleton = true) { events }
        events.beforeDispatch = { event ->
            assertTrue(Files.exists(root.resolve("retained/${event.path}/manifest.json")))
            assertEquals(PostgresBackupRunStatus.COMPLETED, provide<PostgresBackupRunService>().get(event.key)?.status)
        }
        val receipt = runs.backupConfigured("event-backup", "instance", "retained", "backups")
        val event = BackupCompleted(receipt.key, BackupType.POSTGRES, receipt.storageKey, receipt.path)
        assertEquals(listOf(event), events.events)
        tool = root.resolve("missing-dump")
        assertEquals(receipt, runs.backupConfigured("event-backup", "instance", "retained", "backups"))
        assertEquals(listOf(event, event), events.events)
    }

    @Test
    fun `database event failure leaves a retryable request without a completed receipt`(): Unit = runBlocking {
        val runs = runService()
        val events = BackupEventTestDispatcher()
        provides<bosca.pipelines.PipelineEventDispatcher>(singleton = true) { events }
        events.beforeDispatch = { throw IOException("event enqueue failed") }
        assertFailsWith<IOException> { runs.backupConfigured("event-retry", "instance", "retained", "backups") }
        val failed = withConnectionManager { assertNotNull(runs.get("event-retry")) }
        assertEquals(PostgresBackupRunStatus.FAILED, failed.status)
        assertEquals(null, failed.result)
        assertEquals(null, failed.writerToken)
        assertTrue(events.events.isEmpty())
        events.beforeDispatch = {}
        val receipt = runs.backupConfigured("event-retry", "instance", "retained", "backups")
        assertEquals(listOf(BackupCompleted(receipt.key, BackupType.POSTGRES, "retained", receipt.path)), events.events)
    }

    @Test
    fun `published backup dispatches a catalogued completion event through a durable Pipeline`(): Unit = runBlocking {
        val backups = runService()
        PostgresBackupPipelineFixture.create(application, pool, root).use { pipeline ->
            val copyPipeline = pipeline.backupCompletionTrigger()
            withConnectionManager {
                assertFailsWith<IllegalStateException> {
                    transaction {
                        BackupCompleted("rolled-back", BackupType.POSTGRES, "retained", "unpublished").dispatch()
                        assertEquals(null, pipeline.queue.dequeue(100.milliseconds))
                        error("rollback completion event")
                    }
                }
            }
            assertEquals(null, pipeline.queue.dequeue(100.milliseconds))
            val receipt = backups.backupConfigured("event-trigger", "instance", "retained", "backups")
            assertTrue(pipeline.history(copyPipeline).isEmpty())
            assertTrue(Files.exists(root.resolve("retained/${receipt.path}/database.dump")))
            val dispatch = pipeline.next()
            pipeline.execute(dispatch)
            pipeline.execute(pipeline.next())
            val history = pipeline.history(copyPipeline).single()
            assertEquals(PipelineRunStatus.OK, history.outcome, history.errorMessage)
            assertEquals("bosca.backup.BackupCompleted", history.eventName)
            val consumer = pipeline.get(assertNotNull(history.runId))
            val event = application.json.decodeFromJsonElement(BackupCompleted.serializer(), assertNotNull(consumer.output))
            assertEquals(BackupCompleted(receipt.key, BackupType.POSTGRES, receipt.storageKey, receipt.path), event)
            assertEquals(receipt, withConnectionManager { assertNotNull(backups.get(receipt.key)).result })
        }
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
        faultStorage { actual -> object : ManagedObjectStorageService by actual {
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
        faultStorage { actual -> object : ManagedObjectStorageService by actual {
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
    ) = withConnectionManager {
        transaction {
            provide<ObjectBackupStorageService>().bindConfigured(setOf("retained"))
            val repository = provide<PostgresBackupRunRepository>()
            assertEquals(1, repository.add(key, "instance", "retained", prefix))
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
            setProperty("jdbcUrl", jdbcUrl("source"))
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
    fun `native backup destination contention defers the same Pipeline child without failed attempts`(): Unit = runBlocking {
        val runs = runService()
        seedNativeWriter("owner", "active-writer", prefix = "postgresql")
        val owner = withConnectionManager { assertNotNull(runs.get("owner")) }
        PostgresBackupPipelineFixture.create(application, pool, root).use { pipeline ->
            val initial = pipeline.start()
            pipeline.execute(pipeline.next())
            val delayed = CompletableDeferred<UUID>()
            val delays = AtomicInteger()
            val children = CopyOnWriteArrayList<UUID>()
            val observed = object : JobQueue by pipeline.queue {
                override suspend fun enqueueLater(job: Job, timeout: Duration): UUID {
                    assertEquals(5.seconds, timeout)
                    assertEquals(0, job.serialize().failures)
                    children.add(job.getId())
                    val id = pipeline.queue.enqueueLater(job, timeout)
                    if (delays.incrementAndGet() >= 2) delayed.complete(id)
                    return id
                }
            }
            val runner = pipeline.runner(observed)
            runner.run()
            try {
                val child = withTimeout(20_000) { delayed.await() }
                val key = objectBackupIdentity(ObjectBackupOperation.BACKUP, listOf("postgresql", child.toString(), "backup"))
                assertEquals(setOf(child), children.toSet())
                assertEquals(PipelineRunStatus.SUSPENDED, pipeline.get(initial.id).status)
                assertEquals(owner, withConnectionManager { runs.get("owner") })
                assertEquals(null, withConnectionManager { runs.get(key) })
                assertFalse(Files.exists(root.resolve("retained")))
                assertTrue(withConnectionManager { provide<bosca.pipelines.service.PipelineRunService>().nodeTimeline(initial.id) }.none {
                    it.nodeId == "backup" && it.status == bosca.pipelines.model.NodeExecutionStatus.FAILED
                })
                runs.recover("owner", assertNotNull(owner.writerToken))
                val completed = pipeline.awaitComplete(initial.id)
                assertEquals(PipelineRunStatus.OK, completed.status)
                val receipt = application.json.decodeFromJsonElement(PostgresBackupReceipt.serializer(), assertNotNull(completed.output))
                assertEquals(key, receipt.key)
                val saved = withConnectionManager { assertNotNull(runs.get(key)) }
                assertEquals(1, saved.attempts)
                assertEquals(receipt, saved.result)
                assertEquals("0", scalar("select count(*)::text from backup.object_store_writers"))
                assertTrue(withConnectionManager { provide<bosca.pipelines.service.PipelineRunService>().nodeTimeline(initial.id) }.none {
                    it.nodeId == "backup" && it.status == bosca.pipelines.model.NodeExecutionStatus.FAILED
                })
                provide<ObjectStorageServiceFactory>().withStorage(assertNotNull(retained)) { store -> service.download(store, receipt.path, output) }
                restore()
                assertEquals("captured", scalar("select body->>'name' from workops.tasks", "recovered"))
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

    private fun assertRecoveryScratchClean() {
        assertFalse(root.toFile().list().orEmpty().any { it.startsWith("postgres-restore-") })
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
        faultStorage { actual -> object : ManagedObjectStorageService by actual {
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
        faultStorage { actual -> object : ManagedObjectStorageService by actual {
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

    private suspend fun faultStorage(wrap: (ManagedObjectStorageService) -> ManagedObjectStorageService) {
        val actual = provide<ObjectStorageServiceFactory>()
        provides<ObjectStorageServiceFactory>(singleton = true, overrideExisting = true) {
            object : ObjectStorageServiceFactory by actual {
                override suspend fun create(configuration: StorageConfiguration): ManagedObjectStorageService = wrap(actual.create(configuration))
                override suspend fun <T> withStorage(configuration: StorageConfiguration, block: suspend (ManagedObjectStorageService) -> T): T =
                    create(configuration).use { block(it) }
            }
        }
        BackupProviderRegistrar().register()
    }

    private suspend fun install(configured: Boolean = true, restoreConfigured: Boolean = true, retainedKey: String = "retained") {
        if (::application.isInitialized) application.shutdown()
        val config = buildJsonObject {
            encryptionKey?.let { key -> put("security", buildJsonObject {
                put("encryption", buildJsonObject { put("key", key) })
            }) }
            sharedContentStorage?.let { target -> put("content", buildJsonObject {
                put("storage", Json.encodeToJsonElement(StorageConfiguration.serializer(), target))
                put("urlSigner", buildJsonObject { put("secretKey", "local-recovery-test-signing-key") })
            }) }
            put("backup", buildJsonObject {
                retained?.let { put("objectStores", buildJsonObject {
                    put(retainedKey, Json.encodeToJsonElement(StorageConfiguration.serializer(), it))
                    contentStorage?.let { source -> put("content-source", Json.encodeToJsonElement(StorageConfiguration.serializer(), source)) }
                    recoveryStorage?.let { target -> put("recovery-target", Json.encodeToJsonElement(StorageConfiguration.serializer(), target)) }
                }) }
                put("postgres", buildJsonObject {
                    put("scratchDirectory", scratchDirectory ?: root.toString())
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
        DriverManager.getConnection(jdbcUrl(database), user, password).use { connection ->
            connection.createStatement().use { it.execute(query) }
        }
    }

    private fun scalar(query: String, database: String = "source"): String =
        DriverManager.getConnection(jdbcUrl(database), user, password).use { connection ->
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
