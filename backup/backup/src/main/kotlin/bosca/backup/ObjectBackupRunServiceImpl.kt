package bosca.backup

import bosca.db.connectionOrNull
import bosca.db.transaction
import bosca.db.withConnectionManager
import bosca.service.annotation.ServiceImplementation
import bosca.storage.configuration.StorageConfiguration
import bosca.storage.service.ObjectNotFoundException
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.StringObjectPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Persists and executes object inventory runs through the selected storage services. */
@ServiceImplementation
class ObjectBackupRunServiceImpl(
    private val repository: ObjectBackupRunRepository,
    private val progressService: ObjectBackupProgressService,
    private val restoreService: ObjectBackupRestoreService,
    private val json: Json,
    private val storageService: ObjectBackupStorageService,
) : ObjectBackupRunService {
    override suspend fun executeConfigured(key: String): ObjectBackupRun =
        executeConfiguredRun(key) { storageService.configurations(it) }

    override suspend fun executeConfigured(key: String, expectedVersion: Long): ObjectBackupRun {
        validateVersion(key, expectedVersion)
        return executeConfiguredRun(key, expectedVersion) { storageService.configurations(it) }
    }

    override suspend fun configureStorage(
        key: String,
        backupKey: String,
        stores: Map<String, String>,
    ): ObjectBackupRunStorage {
        validateKey(key)
        require(backupKey.isNotBlank()) { "Backup storage identity is required" }
        val captured = stores.toMap()
        require(captured.values.all { it.isNotBlank() }) { "Mapped storage identities are required" }
        return transaction {
            val run = checkNotNull(repository.getForUpdate(key)) { "Object backup run is missing: $key" }
            require(captured.keys == run.inventory.objects.map { it.store }.toSet()) {
                "Storage mapping must match the declared logical stores: $key"
            }
            require(backupKey !in captured.values) { "Backup storage must be independent of mapped content stores" }
            if (run.operation == ObjectBackupOperation.BACKUP) {
                require(backupKey == run.destinationId) { "Backup storage must match the run destination: $key" }
            } else {
                val claims = mutableSetOf<Pair<String, String>>()
                for (entry in run.inventory.objects) {
                    val target = captured.getValue(entry.store) to entry.key
                    require(claims.add(target)) {
                        "Multiple logical stores target the same destination object: ${entry.key}"
                    }
                }
            }
            val existing = repository.getStorage(key)
            if (existing != null) {
                require(existing.backupKey == backupKey && existing.stores == captured) {
                    "Conflicting object run storage mapping: $key"
                }
                existing
            } else {
                check(run.status == ObjectBackupRunStatus.PENDING && run.attempts == 0) {
                    "Storage can only be configured before the first run attempt: $key"
                }
                for (storageKey in (captured.values + backupKey).toSortedSet()) {
                    requireNotNull(storageService.get(storageKey)) { "Backup storage is not bound: $storageKey" }
                }
                check(repository.addStorage(ObjectBackupRunStorage(key, backupKey, captured)) == 1) {
                    "Object run storage mapping was not recorded: $key"
                }
                val restoreDestinationId = run.destinationId.takeIf { run.operation == ObjectBackupOperation.RESTORE }
                for ((store, storageKey) in captured.toSortedMap()) {
                    if (restoreDestinationId != null) {
                        repository.addRestoreStore(restoreDestinationId, store, storageKey)
                        require(repository.hasRestoreStore(restoreDestinationId, store, storageKey)) {
                            "Conflicting restore destination storage: $restoreDestinationId/$store"
                        }
                    }
                    check(repository.addStore(key, store, storageKey, restoreDestinationId) == 1) {
                        "Object run store mapping was not recorded: $key/$store"
                    }
                }
                checkNotNull(repository.getStorage(key)) { "Object run storage mapping is missing: $key" }
            }
        }
    }

    override suspend fun getStorage(key: String): ObjectBackupRunStorage? {
        validateKey(key)
        return repository.getStorage(key)
    }

    override suspend fun executeConfigured(
        key: String,
        configurations: Map<String, StorageConfiguration>,
    ): ObjectBackupRun {
        val captured = configurations.toMap()
        return executeConfiguredRun(key) { captured }
    }

    private suspend fun executeConfiguredRun(
        key: String,
        expectedVersion: Long? = null,
        configurations: suspend (Set<String>) -> Map<String, StorageConfiguration>,
    ): ObjectBackupRun {
        check(connectionOrNull()?.inTransaction != true) {
            "Configured object runs must execute outside an active database transaction"
        }
        val prepared = withConnectionManager {
            checkNotNull(get(key)) { "Object backup run is missing: $key" }
        }
        if (!matchesDispatch(prepared, expectedVersion)) return prepared
        when (prepared.status) {
            ObjectBackupRunStatus.COMPLETED, ObjectBackupRunStatus.CANCELLED -> return prepared
            ObjectBackupRunStatus.RUNNING -> error("Object run is already running: $key")
            else -> Unit
        }
        val mapping = withConnectionManager {
            checkNotNull(getStorage(key)) { "Object run storage is not configured: $key" }
        }
        val required = (mapping.stores.values + mapping.backupKey).toSet()
        val available = configurations(required)
        val selected = required.associateWith { storageKey ->
            requireNotNull(available[storageKey]) { "Missing storage configuration: $storageKey" }
        }
        return executeRun(key, prepared.operation, expectedVersion) { run ->
            storageService.withStorages(selected) { opened ->
                val stores = mapping.stores.mapValues { (_, storageKey) -> opened.getValue(storageKey) }
                val retained = opened.getValue(mapping.backupKey)
                when (run.operation) {
                    ObjectBackupOperation.BACKUP -> backup(run, stores, retained)
                    ObjectBackupOperation.RESTORE -> restore(run, retained, stores)
                }
            }
        }
    }

    override suspend fun prepare(inventory: ObjectBackupManifest, destinationId: String): ObjectBackupRun =
        prepare(ObjectBackupOperation.BACKUP, inventory, destinationId, null)

    override suspend fun prepareRestore(
        inventory: ObjectBackupManifest,
        destinationId: String,
        path: String,
    ): ObjectBackupRun = prepare(ObjectBackupOperation.RESTORE, inventory, destinationId, path)

    private suspend fun prepare(
        operation: ObjectBackupOperation,
        inventory: ObjectBackupManifest,
        destinationId: String,
        manifestPath: String?,
    ): ObjectBackupRun {
        val captured = inventory.copy(objects = inventory.objects.toList())
        captured.validate()
        validateIdentity(captured.sourceId, destinationId, captured.scope)

        var totalBytes = 0L
        for (entry in captured.objects) {
            require(entry.length <= Long.MAX_VALUE - totalBytes) {
                "Object backup inventory total exceeds the supported byte count"
            }
            totalBytes += entry.length
        }

        val key = objectBackupIdentity(
            operation,
            listOf(captured.sourceId, destinationId, captured.scope, captured.id),
        )
        val path = when (operation) {
            ObjectBackupOperation.BACKUP -> "manifests/$key.json"
            ObjectBackupOperation.RESTORE -> requireNotNull(manifestPath) { "Restore manifest path is required" }
        }
        require(path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) {
            "Invalid object run manifest path: $path"
        }
        require(captured.objects.none { it.payloadPath == path }) {
            "Manifest path conflicts with a payload: $path"
        }
        val required = when (operation) {
            ObjectBackupOperation.BACKUP -> captured.objects
            ObjectBackupOperation.RESTORE -> captured.restoreObjects()
        }

        return transaction {
            if (operation == ObjectBackupOperation.BACKUP) {
                progressService.reserveManifest(destinationId, path, key)
            }
            val inserted = repository.add(ObjectBackupRun(
                key, captured.sourceId, destinationId, captured.scope, operation, captured, path,
            ))
            val run = checkNotNull(repository.get(key)) {
                "Prepared object backup run is missing: $key"
            }
            require(
                run.sourceId == captured.sourceId &&
                    run.destinationId == destinationId &&
                    run.scope == captured.scope &&
                    run.operation == operation &&
                    run.inventory == captured &&
                    run.path == path
            ) {
                "Conflicting object backup run intent: $key"
            }

            if (inserted > 0) {
                for ((position, entry) in required.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val progress = progressService.prepare(
                        operation, captured.sourceId, destinationId, captured.scope, entry,
                    )
                    check(repository.addItem(key, position, progress.key) == 1) {
                        "Object backup run item was not associated: $key at $position"
                    }
                }
            }
            run
        }
    }

    override suspend fun get(key: String): ObjectBackupRun? {
        validateKey(key)
        return repository.get(key)
    }

    override suspend fun list(
        operation: ObjectBackupOperation,
        sourceId: String,
        destinationId: String,
        scope: String,
        offset: Long,
        limit: Int,
    ): List<ObjectBackupRun> {
        validateIdentity(sourceId, destinationId, scope)
        validatePagination(offset, limit)
        return repository.list(operation, sourceId, destinationId, scope, offset, limit)
    }

    override suspend fun lastSuccessful(
        operation: ObjectBackupOperation,
        sourceId: String,
        destinationId: String,
        scope: String,
    ): ObjectBackupRun? {
        validateIdentity(sourceId, destinationId, scope)
        return repository.lastSuccessful(operation, sourceId, destinationId, scope)
    }

    override suspend fun items(key: String, offset: Long, limit: Int): List<ObjectBackupProgress> {
        validateKey(key)
        validatePagination(offset, limit)
        return repository.items(key, offset, limit)
    }

    override suspend fun summary(key: String): ObjectBackupRunSummary {
        validateKey(key)
        return checkNotNull(repository.summary(key)) {
            "Object backup run is missing: $key"
        }
    }

    override suspend fun start(key: String, expectedVersion: Long): ObjectBackupRun {
        validateVersion(key, expectedVersion)
        return transaction {
            val run = updated(repository.start(key, expectedVersion), key, expectedVersion)
            val storage = repository.getStorage(key)
            if (storage != null) {
                val destinations = when (run.operation) {
                    ObjectBackupOperation.BACKUP -> setOf(storage.backupKey)
                    ObjectBackupOperation.RESTORE -> storage.stores.values.toSet()
                }
                for (storageKey in destinations.sorted()) {
                    if (repository.claimWriter(storageKey, key) != 1) {
                        throw ObjectBackupDestinationBusyException(storageKey)
                    }
                }
            }
            run
        }
    }

    override suspend fun retry(key: String, expectedVersion: Long): ObjectBackupRun {
        validateVersion(key, expectedVersion)
        return updated(repository.retry(key, expectedVersion), key, expectedVersion)
    }

    override suspend fun complete(key: String, expectedVersion: Long): ObjectBackupRun {
        validateVersion(key, expectedVersion)
        return finish(key, expectedVersion, null, cancelled = false, pendingOnly = false)
    }

    override suspend fun fail(key: String, expectedVersion: Long, error: String): ObjectBackupRun =
        finishFailure(key, expectedVersion, error, cancelled = false, pendingOnly = false)

    override suspend fun cancel(key: String, expectedVersion: Long, error: String): ObjectBackupRun =
        finishFailure(key, expectedVersion, error, cancelled = true, pendingOnly = false)

    override suspend fun cancelPending(key: String, expectedVersion: Long, reason: String): ObjectBackupRun =
        finishFailure(key, expectedVersion, reason, cancelled = true, pendingOnly = true)

    override suspend fun execute(
        key: String,
        sources: Map<String, ObjectStorageService>,
        destination: ObjectStorageService,
    ): ObjectBackupRun = executeRun(key, ObjectBackupOperation.BACKUP) { run ->
        backup(run, sources, destination)
    }

    private suspend fun backup(
        run: ObjectBackupRun,
        sources: Map<String, ObjectStorageService>,
        destination: ObjectStorageService,
    ) {
        val transfer = ObjectBackupTransfer(sources, destination)
        transfer.copy(run.inventory, run.destinationId, progressService)
        publish(run, ObjectBackupManifestStore(destination, json))
    }

    override suspend fun executeRestore(
        key: String,
        backup: ObjectStorageService,
        destinations: Map<String, ObjectStorageService>,
    ): ObjectBackupRun = executeRun(key, ObjectBackupOperation.RESTORE) { run ->
        restore(run, backup, destinations)
    }

    private suspend fun restore(
        run: ObjectBackupRun,
        backup: ObjectStorageService,
        destinations: Map<String, ObjectStorageService>,
    ) {
        val path = StringObjectPath(run.path)
        val inventory = ObjectBackupManifestStore(backup, json).load(path)
        check(inventory == run.inventory) {
            "Stored restore manifest differs from prepared inventory: ${run.key}"
        }
        restoreService.restore(run.inventory, backup, destinations, run.destinationId)
    }

    private suspend fun executeRun(
        key: String,
        operation: ObjectBackupOperation,
        expectedVersion: Long? = null,
        transfer: suspend (ObjectBackupRun) -> Unit,
    ): ObjectBackupRun {
        val (running, started) = withConnectionManager {
            transaction {
                val prepared = checkNotNull(repository.getForUpdate(key)) { "Object backup run is missing: $key" }
                require(prepared.operation == operation) {
                    "Object run operation is ${prepared.operation}; expected $operation: $key"
                }
                if (!matchesDispatch(prepared, expectedVersion)) {
                    prepared to false
                } else {
                    when (prepared.status) {
                        ObjectBackupRunStatus.COMPLETED, ObjectBackupRunStatus.CANCELLED -> prepared to false
                        ObjectBackupRunStatus.RUNNING -> error("Object run is already running: $key")
                        else -> start(prepared.key, prepared.version) to true
                    }
                }
            }
        }
        if (!started) return running
        return try {
            transfer(running)
            currentCoroutineContext().ensureActive()

            withConnectionManager { complete(running.key, running.version) }
        } catch (failure: Exception) {
            try {
                withContext(NonCancellable) {
                    withConnectionManager {
                        val error = failure.message?.takeIf { it.isNotBlank() } ?: failure.javaClass.name
                        if (failure is CancellationException) {
                            cancel(running.key, running.version, error)
                        } else {
                            fail(running.key, running.version, error)
                        }
                    }
                }
            } catch (recordingFailure: Exception) {
                failure.addSuppressed(recordingFailure)
            }
            throw failure
        }
    }

    /** Reuses matching published output; malformed JSON at the run's reserved path is repairable staging. */
    private suspend fun publish(run: ObjectBackupRun, manifests: ObjectBackupManifestStore) {
        val path = StringObjectPath(run.path)
        val existing = try {
            manifests.load(path)
        } catch (_: ObjectNotFoundException) {
            null
        } catch (_: SerializationException) {
            null
        }

        val published = if (existing == null) {
            manifests.save(path, run.inventory)
            manifests.load(path)
        } else {
            existing
        }
        check(published == run.inventory) {
            "Published object backup inventory conflicts with run: ${run.key}"
        }
        if (existing != null) manifests.verify(published)
    }

    private suspend fun finishFailure(
        key: String,
        expectedVersion: Long,
        error: String,
        cancelled: Boolean,
        pendingOnly: Boolean,
    ): ObjectBackupRun {
        validateVersion(key, expectedVersion)
        require(error.isNotBlank()) {
            "Object backup run failure detail is required"
        }
        return finish(key, expectedVersion, error, cancelled, pendingOnly)
    }

    private suspend fun finish(
        key: String,
        expectedVersion: Long,
        error: String?,
        cancelled: Boolean,
        pendingOnly: Boolean,
    ): ObjectBackupRun = transaction {
        val outcome = repository.finish(key, expectedVersion, error, cancelled, pendingOnly)
        val run = if (pendingOnly) {
            checkNotNull(outcome) { "Object backup run must match the observed pending version: $key at $expectedVersion" }
        } else {
            updated(outcome, key, expectedVersion)
        }
        repository.releaseWriters(key)
        run
    }

    private fun validateKey(key: String) {
        require(key.isNotBlank()) {
            "Object backup run key is required"
        }
    }

    private fun matchesDispatch(run: ObjectBackupRun, expectedVersion: Long?): Boolean =
        expectedVersion == null || (run.version == expectedVersion && run.status == ObjectBackupRunStatus.PENDING)

    private fun validateVersion(key: String, expectedVersion: Long) {
        validateKey(key)
        require(expectedVersion >= 0) {
            "Object backup run version must not be negative"
        }
    }

    private fun validateIdentity(sourceId: String, destinationId: String, scope: String) {
        require(sourceId.isNotBlank()) {
            "Object backup source identity is required"
        }
        require(destinationId.isNotBlank()) {
            "Object backup destination identity is required"
        }
        require(scope.isNotBlank()) {
            "Object backup scope is required"
        }
    }

    private fun validatePagination(offset: Long, limit: Int) {
        require(offset >= 0) {
            "Object backup run offset must not be negative"
        }
        require(limit > 0) {
            "Object backup run limit must be positive"
        }
    }

    private fun updated(run: ObjectBackupRun?, key: String, expectedVersion: Long): ObjectBackupRun =
        checkNotNull(run) { "Object backup run state, version or required inventory changed: $key at $expectedVersion" }
}
