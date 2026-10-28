package bosca.backup

import bosca.db.connectionOrNull
import bosca.db.transaction
import bosca.db.withConnectionManager
import bosca.server.BoscaApplication
import bosca.service.annotation.ServiceImplementation
import bosca.storage.configuration.StorageConfiguration
import bosca.storage.service.StorageDispatcher
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Retains each attempt independently and publishes one durable receipt after storage closes. */
@ServiceImplementation
class PostgresBackupRunServiceImpl(
    private val repository: PostgresBackupRunRepository,
    private val artifacts: PostgresBackupService,
    private val storage: ObjectBackupStorageService,
    private val application: BoscaApplication,
) : PostgresBackupRunService {
    override suspend fun backupConfigured(
        key: String,
        sourceId: String,
        storageKey: String,
        prefix: String,
        checkpointKey: String?,
    ): PostgresBackupReceipt = withContext(StorageDispatcher) {
        outsideTransaction()
        validateKey(key)
        require(checkpointKey == null || checkpointKey.isNotBlank() && ':' !in checkpointKey) {
            "Job checkpoint identity must be nonblank and contain no colon"
        }
        require(sourceId.isNotBlank() && storageKey.isNotBlank()) { "Source and backup storage identities are required" }
        require(prefix.isNotBlank() && prefix.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) {
            "PostgreSQL backup prefix must be a canonical relative path"
        }
        fun checkIntent(run: PostgresBackupRun) {
            require(
                run.sourceId == sourceId && run.storageKey == storageKey && run.prefix == prefix,
            ) {
                "Conflicting PostgreSQL backup request: $key"
            }
        }
        val previous = withConnectionManager { repository.get(key) }
        previous?.let {
            checkIntent(it)
            it.result?.let { result ->
                withConnectionManager {
                    transaction { BackupCompleted(key, BackupType.POSTGRES, storageKey, result.path).dispatch() }
                }
                return@withContext result
            }
        }
        val configurations = storage.configurations(setOf(storageKey))
        val attempt = UUID.randomUUID().toString()
        val token = checkpointKey?.let { "$it:$attempt" } ?: attempt
        var acquired = false
        var completed = false
        try {
            withContext(NonCancellable) {
                withConnectionManager {
                    transaction {
                        for ((id, configuration) in configurations.toSortedMap()) storage.bind(id, configuration)
                        repository.add(key, sourceId, storageKey, prefix)
                        val current = checkNotNull(repository.get(key))
                        checkIntent(current)
                        val started = repository.begin(key, token) == 1
                        val resumed = !started && checkpointKey != null && repository.resume(key, checkpointKey, token) == 1
                        check(started || resumed) { "PostgreSQL backup request is already running or completed: $key" }
                        if (repository.claim(key, storageKey) != 1) throw ObjectBackupDestinationBusyException(storageKey)
                    }
                }
                acquired = true
            }
            currentCoroutineContext().ensureActive()
            val receipt = capture(key, sourceId, storageKey, prefix, configurations)
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                withConnectionManager {
                    transaction {
                        check(repository.complete(key, token, receipt) == 1) { "PostgreSQL backup writer changed: $key" }
                        check(repository.release(key) == 1) { "PostgreSQL destination ownership is missing: $key" }
                        BackupCompleted(key, BackupType.POSTGRES, storageKey, receipt.path).dispatch()
                    }
                }
                completed = true
            }
            receipt
        } catch (cause: Throwable) {
            if (acquired && !completed) {
                try {
                    withContext(NonCancellable) {
                        withConnectionManager {
                            transaction {
                                val status = if (cause is CancellationException) PostgresBackupRunStatus.CANCELLED else PostgresBackupRunStatus.FAILED
                                val message = cause.message?.takeIf { it.isNotBlank() } ?: "PostgreSQL backup attempt failed"
                                check(repository.fail(key, token, status, message) == 1) { "PostgreSQL backup writer changed: $key" }
                                check(repository.release(key) == 1) { "PostgreSQL destination ownership is missing: $key" }
                            }
                        }
                    }
                } catch (recordFailure: Throwable) {
                    if (recordFailure !== cause) cause.addSuppressed(recordFailure)
                }
            }
            throw cause
        }
    }

    private suspend fun capture(
        key: String,
        sourceId: String,
        storageKey: String,
        prefix: String,
        configurations: Map<String, StorageConfiguration>,
    ): PostgresBackupReceipt {
        val configured = application.environment.config.propertyOrNull("backup.postgres.scratchDirectory")?.getString()
        require(configured == null || configured.isNotBlank()) { "PostgreSQL scratch directory must not be blank" }
        val scratch = if (configured == null) Files.createTempDirectory("postgres-backup-")
            else Files.createTempDirectory(Path.of(configured), "postgres-backup-")
        val directory = scratch.resolve("capture")
        var failure: Throwable? = null
        try {
            val path = "$prefix/$key/${UUID.randomUUID()}"
            val manifest = artifacts.capture(sourceId, directory)
            storage.withStorage(storageKey, configurations.getValue(storageKey)) { backup ->
                check(artifacts.upload(directory, backup, path) == manifest) { "Transferred PostgreSQL artifact changed" }
            }
            return PostgresBackupReceipt(key, storageKey, path, manifest)
        } catch (cause: Throwable) {
            failure = cause
            throw cause
        } finally {
            withContext(NonCancellable) {
                try {
                    for (file in listOf(
                        "database.dump", "database.dump.tmp", "manifest.json", "manifest.json.tmp", "pg_dump.stderr",
                    )) {
                        Files.deleteIfExists(directory.resolve(file))
                    }
                    Files.deleteIfExists(directory)
                    Files.delete(scratch)
                } catch (cleanup: Throwable) {
                    failure?.addSuppressed(cleanup) ?: throw cleanup
                }
            }
        }
    }

    override suspend fun get(key: String): PostgresBackupRun? {
        validateKey(key)
        return repository.get(key)
    }

    override suspend fun list(sourceId: String, storageKey: String, offset: Long, limit: Int): List<PostgresBackupRun> {
        require(sourceId.isNotBlank() && storageKey.isNotBlank()) { "Source and backup storage identities are required" }
        require(offset >= 0 && limit in 1..100) { "Invalid PostgreSQL backup page" }
        return repository.list(sourceId, storageKey, offset, limit)
    }

    override suspend fun recover(key: String, expectedToken: String) {
        outsideTransaction()
        validateKey(key)
        require(expectedToken.isNotBlank()) { "Observed PostgreSQL writer token is required" }
        withConnectionManager {
            transaction {
                check(repository.fail(key, expectedToken, PostgresBackupRunStatus.FAILED, "Stopped PostgreSQL backup writer recovered") == 1) {
                    "PostgreSQL backup writer must match the observed execution token: $key"
                }
                check(repository.release(key) == 1) { "PostgreSQL destination ownership is missing: $key" }
            }
        }
    }

    private fun validateKey(key: String) {
        require(key.matches(Regex("[a-zA-Z0-9_-]{1,64}"))) { "Invalid PostgreSQL backup request key" }
    }

    private suspend fun outsideTransaction() {
        check(connectionOrNull()?.inTransaction != true) { "Configured PostgreSQL backup must run outside an active caller transaction" }
    }
}
