package bosca.backup

import bosca.db.connection
import bosca.db.connectionOrNull
import bosca.db.withConnectionManager
import bosca.server.BoscaApplication
import bosca.service.annotation.ServiceImplementation
import bosca.storage.service.StorageDispatcher
import bosca.storage.service.ObjectStorageService
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.slf4j.LoggerFactory

/** Produces a native database artifact from one exported PostgreSQL snapshot. */
@ServiceImplementation
class PostgresBackupServiceImpl(private val application: BoscaApplication) : PostgresBackupService {
    override suspend fun upload(
        directory: Path,
        backup: ObjectStorageService,
        prefix: String,
    ): PostgresBackupManifest = withContext(StorageDispatcher) {
        check(connectionOrNull()?.inTransaction != true) { "PostgreSQL upload must run outside an active caller transaction" }
        val store = PostgresBackupArtifactStore(backup, application.json, prefix)
        store.upload(directory, verify(directory))
    }

    override suspend fun download(
        backup: ObjectStorageService,
        prefix: String,
        directory: Path,
    ): PostgresBackupManifest = withContext(StorageDispatcher) {
        check(connectionOrNull()?.inTransaction != true) { "PostgreSQL download must run outside an active caller transaction" }
        PostgresBackupArtifactStore(backup, application.json, prefix).download(directory)
    }

    override suspend fun capture(sourceId: String, directory: Path): PostgresBackupManifest = withContext(StorageDispatcher) {
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
                    runDump(executable, service, serviceFile, metadata.snapshotId, dump, destination.resolve("pg_dump.stderr"))
                    val completed = connection.useStatement("select clock_timestamp()") { statement ->
                        statement.executeQuery().use { rows ->
                            check(rows.next()) { "PostgreSQL capture completion time is missing" }
                            rows.getObject(1, OffsetDateTime::class.java).toInstant().toString()
                        }
                    }
                    metadata.copy(completed = completed)
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
                    for (name in listOf("database.dump.tmp", "database.dump", "pg_dump.stderr", "manifest.json.tmp", "manifest.json")) {
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

    private suspend fun runDump(executable: String, service: String, serviceFile: String?, snapshot: String, dump: Path, errors: Path) {
        currentCoroutineContext().ensureActive()
        val escapedService = service.replace("\\", "\\\\").replace("'", "\\'")
        val process = ProcessBuilder(
            executable, "--format=custom", "--no-password", "--dbname=service='$escapedService'",
            "--snapshot=$snapshot", "--file=$dump", "--lock-wait-timeout=10s",
        ).apply {
            redirectOutput(ProcessBuilder.Redirect.DISCARD)
            redirectError(errors.toFile())
            if (serviceFile != null) environment()["PGSERVICEFILE"] = serviceFile
        }.start()
        var failure: Throwable? = null
        try {
            process.outputStream.close()
            val exit = runInterruptible(StorageDispatcher) { process.waitFor() }
            val diagnostics = Files.newInputStream(errors).use { it.readNBytes(8192).toString(Charsets.UTF_8) }.trim()
            check(exit == 0) { "pg_dump exited with status $exit: $diagnostics" }
            if (diagnostics.isNotEmpty()) log.warn("pg_dump completed with diagnostics: {}", diagnostics)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            withContext(NonCancellable) {
                try {
                    if (process.isAlive) {
                        process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
                        process.destroyForcibly().waitFor()
                    }
                    process.inputStream.close()
                    process.errorStream.close()
                    Files.deleteIfExists(errors)
                } catch (cleanup: Throwable) {
                    failure?.addSuppressed(cleanup) ?: throw cleanup
                }
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(PostgresBackupServiceImpl::class.java)
    }
}
