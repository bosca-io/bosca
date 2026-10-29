package bosca.backup

import bosca.db.connection
import bosca.db.connectionOrNull
import bosca.db.withConnectionManager
import bosca.di.ObjectProvider
import bosca.server.BoscaApplication
import bosca.service.annotation.ServiceImplementation
import bosca.storage.service.ManagedObjectStorageService
import bosca.storage.service.StorageDispatcher
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/** Captures, transfers and restores native PostgreSQL database artifacts. */
@ServiceImplementation
class PostgresBackupServiceImpl(
    private val application: BoscaApplication,
    private val storageService: ObjectProvider<ObjectBackupStorageService>,
) : PostgresBackupService {
    override suspend fun preflightRestore(directory: Path): PostgresBackupManifest = withContext(StorageDispatcher) {
        check(connectionOrNull()?.inTransaction != true) { "PostgreSQL restore preflight must run outside an active caller transaction" }
        PostgresBackupRestore(application).preflight(verify(directory))
    }

    override suspend fun restoreConfigured(path: String, backupStorageKey: String): PostgresBackupManifest = withContext(StorageDispatcher) {
        check(connectionOrNull()?.inTransaction != true) { "Configured PostgreSQL restore must run outside an active caller transaction" }
        require(path.isNotBlank() && path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) {
            "Native backup path must be a canonical relative prefix"
        }
        require(backupStorageKey.isNotBlank()) { "Backup storage identity is required" }
        val storage = storageService.get()
        val configurations = storage.configurations(setOf(backupStorageKey))
        withRestoreScratch { scratch ->
            storage.withStorages(configurations) { stores ->
                val directory = scratch.resolve("database")
                download(stores.getValue(backupStorageKey), path, directory)
                restore(directory)
            }
        }
    }

    private suspend fun <T> withRestoreScratch(block: suspend (Path) -> T): T {
        val parent = application.environment.config.propertyOrNull("backup.postgres.scratchDirectory")?.getString()
        require(parent == null || parent.isNotBlank()) { "PostgreSQL scratch directory must not be blank" }
        currentCoroutineContext().ensureActive()
        val scratch = if (parent == null) Files.createTempDirectory("postgres-restore-")
            else Files.createTempDirectory(Path.of(parent), "postgres-restore-")
        val directory = scratch.resolve("database")
        var failure: Throwable? = null
        try {
            return block(scratch)
        } catch (cause: Throwable) {
            failure = cause
            throw cause
        } finally {
            withContext(NonCancellable) {
                try {
                    for (name in listOf(
                        "database.dump", "database.dump.tmp", "manifest.json", "manifest.json.tmp",
                    )) {
                        Files.deleteIfExists(directory.resolve(name))
                    }
                    Files.deleteIfExists(directory)
                    Files.delete(scratch)
                } catch (cleanup: Throwable) {
                    failure?.addSuppressed(cleanup) ?: throw cleanup
                }
            }
        }
    }

    override suspend fun restore(directory: Path): PostgresBackupManifest = withContext(StorageDispatcher) {
        check(connectionOrNull()?.inTransaction != true) { "PostgreSQL restore must run outside an active caller transaction" }
        PostgresBackupRestore(application).restore(directory, verify(directory))
    }

    override suspend fun upload(
        directory: Path,
        backup: ManagedObjectStorageService,
        prefix: String,
    ): PostgresBackupManifest = withContext(StorageDispatcher) {
        check(connectionOrNull()?.inTransaction != true) { "PostgreSQL upload must run outside an active caller transaction" }
        val store = PostgresBackupArtifactStore(backup, application.json, prefix)
        store.upload(directory, verify(directory))
    }

    override suspend fun download(
        backup: ManagedObjectStorageService,
        prefix: String,
        directory: Path,
    ): PostgresBackupManifest = withContext(StorageDispatcher) {
        check(connectionOrNull()?.inTransaction != true) { "PostgreSQL download must run outside an active caller transaction" }
        PostgresBackupArtifactStore(backup, application.json, prefix).download(directory)
    }

    override suspend fun capture(
        sourceId: String,
        directory: Path,
    ): PostgresBackupManifest = withContext(StorageDispatcher) {
        check(connectionOrNull()?.inTransaction != true) { "PostgreSQL capture must run outside an active caller transaction" }
        require(sourceId.isNotBlank()) { "PostgreSQL backup source identity is required" }
        val config = application.environment.config
        val service = checkNotNull(config.propertyOrNull("backup.postgres.service")) {
            "Missing backup.postgres.service configuration"
        }.getString()
        require(service.isNotBlank()) { "PostgreSQL libpq service is required" }
        val executable = config.propertyOrNull("backup.postgres.pgDump")?.getString() ?: "pg_dump"
        require(executable.isNotBlank()) { "PostgreSQL pg_dump executable is required" }
        val serviceFile = config.propertyOrNull("backup.postgres.serviceFile")?.getString()
        require(serviceFile == null || serviceFile.isNotBlank()) { "PostgreSQL libpq service file must not be blank" }
        val destination = directory.toAbsolutePath().normalize()
        currentCoroutineContext().ensureActive()
        Files.createDirectory(destination)
        var published = false
        var failure: Throwable? = null
        try {
            val dump = destination.resolve("database.dump.tmp")
            val boundary = withConnectionManager {
                val connection = connection()
                connection.beginTransaction()
                var snapshotFailure: Throwable? = null
                try {
                    connection.useStatement("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY") { it.execute() }
                    val metadata = connection.useStatement("""
                        select pg_export_snapshot(), pg_current_snapshot()::text, clock_timestamp(),
                               current_database(), current_setting('server_version'),
                               current_setting('server_version_num')::integer, current_setting('server_encoding'),
                               coalesce((select jsonb_object_agg(extname, extversion) from pg_extension), '{}'::jsonb)::text,
                               (select count(*) from pg_foreign_table), pg_is_in_recovery()
                    """.trimIndent()) { statement ->
                        statement.executeQuery().use { rows ->
                            check(rows.next()) { "PostgreSQL snapshot metadata is missing" }
                            require(rows.getLong(9) == 0L) { "Foreign table data requires explicit capture coverage" }
                            require(!rows.getBoolean(10)) { "Native PostgreSQL capture requires a primary database" }
                            PostgresBackupManifest(
                                id = UUID.randomUUID().toString(), sourceId = sourceId, database = rows.getString(4),
                                created = rows.getObject(3, OffsetDateTime::class.java).toInstant().toString(),
                                completed = "", snapshotId = rows.getString(1), snapshot = rows.getString(2),
                                serverVersion = rows.getString(5), serverVersionNumber = rows.getInt(6), encoding = rows.getString(7),
                                extensions = application.json.decodeFromString(
                                    MapSerializer(String.serializer(), String.serializer()), rows.getString(8),
                                ), length = 0, sha256 = "",
                            )
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    val requiredKeys = postgresBackupRequiredKeys()
                    currentCoroutineContext().ensureActive()
                    runDump(executable, service, serviceFile, metadata.snapshotId, dump, destination.resolve("pg_dump.stderr"))
                    val completed = connection.useStatement("select clock_timestamp()") { statement ->
                        statement.executeQuery().use { rows ->
                            check(rows.next()) { "PostgreSQL capture completion time is missing" }
                            rows.getObject(1, OffsetDateTime::class.java).toInstant().toString()
                        }
                    }
                    metadata.copy(completed = completed, requiredKeys = requiredKeys)
                } catch (error: Throwable) {
                    snapshotFailure = error
                    throw error
                } finally {
                    try {
                        connection.rollbackTransaction()
                    } catch (rollback: Throwable) {
                        snapshotFailure?.addSuppressed(rollback) ?: throw rollback
                    }
                }
            }
            requireNativePostgresDump(dump)
            val length = Files.size(dump)
            val digest = digest(dump, length)
            val manifest = boundary.copy(length = length, sha256 = digest)
            manifest.validate()
            Files.move(dump, destination.resolve(manifest.payloadPath))
            val manifestFile = destination.resolve("manifest.json.tmp")
            Files.writeString(manifestFile, application.json.encodeToString(PostgresBackupManifest.serializer(), manifest))
            currentCoroutineContext().ensureActive()
            Files.move(manifestFile, destination.resolve("manifest.json"))
            published = true
            manifest
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            if (!published) withContext(NonCancellable) {
                try {
                    for (name in listOf(
                        "database.dump.tmp", "database.dump", "pg_dump.stderr",
                        "manifest.json.tmp", "manifest.json",
                    )) {
                        Files.deleteIfExists(destination.resolve(name))
                    }
                    Files.delete(destination)
                } catch (cleanup: Throwable) {
                    failure?.addSuppressed(cleanup) ?: throw cleanup
                }
            }
        }
    }

    override suspend fun verify(directory: Path): PostgresBackupManifest = withContext(StorageDispatcher) {
        val manifestFile = directory.resolve("manifest.json")
        if (!Files.exists(manifestFile, NOFOLLOW_LINKS)) throw java.nio.file.NoSuchFileException(manifestFile.toString())
        require(Files.isRegularFile(manifestFile, NOFOLLOW_LINKS)) { "PostgreSQL manifest must be a regular file" }
        val manifest = application.json.decodeFromString(PostgresBackupManifest.serializer(), Files.readString(manifestFile))
        manifest.validate()
        val dump = directory.resolve(manifest.payloadPath)
        require(Files.isRegularFile(dump, NOFOLLOW_LINKS)) { "PostgreSQL dump must be a regular file" }
        val actual = digest(dump, manifest.length)
        if (actual != manifest.sha256) throw BackupPayloadMismatchException("PostgreSQL dump SHA-256 mismatch")
        requireNativePostgresDump(dump)
        manifest
    }

    private suspend fun digest(path: Path, length: Long): String =
        BackupPayloadInputStream(Files.newInputStream(path), length, path.fileName.toString(), currentCoroutineContext()).use {
            it.transferTo(OutputStream.nullOutputStream())
            it.verifyComplete()
        }

    private suspend fun runDump(
        executable: String,
        service: String,
        serviceFile: String?,
        snapshot: String,
        dump: Path,
        errors: Path,
    ) {
        val escapedService = service.replace("\\", "\\\\").replace("'", "\\'")
        runPostgresBackupProcess(
            "pg_dump", executable,
            listOf(
                "--format=custom", "--no-password", "--dbname=service='$escapedService'",
                "--snapshot=$snapshot", "--file=$dump", "--lock-wait-timeout=10s",
            ),
            serviceFile, errors,
        )
    }
}
