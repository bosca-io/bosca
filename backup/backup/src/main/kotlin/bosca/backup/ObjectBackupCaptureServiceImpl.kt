package bosca.backup

import bosca.db.connectionOrNull
import bosca.db.transaction
import bosca.db.withConnectionManager
import bosca.service.annotation.ServiceImplementation
import bosca.storage.service.ObjectNotFoundException
import bosca.storage.service.ObjectStorageEntry
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.StorageDispatcher
import bosca.storage.service.StringObjectPath
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.OffsetDateTime
import java.util.UUID

/** Streams a selected retained revision into new independent backup storage and verifies it. */
@ServiceImplementation
class ObjectBackupCaptureServiceImpl(
    private val progressService: ObjectBackupProgressService,
    private val json: Json,
) : ObjectBackupCaptureService {
    override suspend fun captureInventory(
        inventory: ObjectBackupCaptureInventory,
        destinationId: String,
        sources: Map<String, ObjectStorageService>,
        destination: ObjectStorageService,
        payloadPrefix: String,
        manifestPath: String,
        verifiedSince: OffsetDateTime?,
    ): ObjectBackupManifest {
        val selected = inventory.copy(stores = inventory.stores.mapValues { (_, entries) -> entries.toList() })
        val capturedSources = sources.toMap()
        selected.validate()
        require(destinationId.isNotBlank()) { "Object backup destination identity is required" }
        check(connectionOrNull()?.inTransaction != true) {
            "Inventory capture must run outside an active database transaction"
        }
        for (path in listOf(payloadPrefix, manifestPath)) {
            require(path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) {
                "Invalid inventory capture output path: $path"
            }
        }
        require(payloadPrefix != manifestPath && !payloadPrefix.startsWith("$manifestPath/")) {
            "Manifest output conflicts with the retained payload namespace: $manifestPath"
        }
        for (store in selected.stores.keys) {
            val source = requireNotNull(capturedSources[store]) { "Selected source store is missing: $store" }
            require(source !== destination) { "Backup storage must be independent of the source: $store" }
        }
        val entries = selected.stores.toSortedMap().flatMap { (store, entries) ->
            entries.sortedWith(compareBy({ it.key }, { it.info.version })).map { store to it }
        }
        withConnectionManager {
            for ((store, entry) in entries) {
                val known = progressService.get(
                    ObjectBackupOperation.BACKUP, selected.sourceId, destinationId, selected.scope,
                    store, entry.key, requireNotNull(entry.info.version),
                ) ?: continue
                check(known.status != ObjectBackupProgressStatus.RUNNING) {
                    "Object capture is already running: ${known.key}"
                }
                require(known.payload.length == entry.info.length) { "Conflicting captured revision length: ${known.key}" }
                require(known.payload.payloadPath != manifestPath) {
                    "Manifest path conflicts with a captured payload: $manifestPath"
                }
            }
        }
        val objects = entries.map { (store, entry) ->
            currentCoroutineContext().ensureActive()
            val path = "$payloadPrefix/${UUID.randomUUID()}"
            require(path != manifestPath) { "Manifest path conflicts with a new payload: $path" }
            captureTracked(
                selected.sourceId, destinationId, selected.scope, store, entry,
                capturedSources.getValue(store), destination, path, verifiedSince,
            ).payload
        }
        val manifest = ObjectBackupManifest(selected.id, selected.sourceId, selected.scope, selected.created, objects)
        ObjectBackupManifestStore(destination, json).saveTracked(
            StringObjectPath(manifestPath), manifest, destinationId, progressService, verifiedSince,
        )
        return manifest
    }

    override suspend fun captureTracked(
        sourceId: String,
        destinationId: String,
        scope: String,
        store: String,
        entry: ObjectStorageEntry,
        source: ObjectStorageService,
        destination: ObjectStorageService,
        payloadPath: String,
        verifiedSince: OffsetDateTime?,
    ): ObjectBackupProgress {
        require(sourceId.isNotBlank() && destinationId.isNotBlank() && scope.isNotBlank()) {
            "Object capture source, destination and scope identities are required"
        }
        val version = validateCapture(store, entry, source, destination, payloadPath)
        val known = withConnectionManager {
            progressService.get(ObjectBackupOperation.BACKUP, sourceId, destinationId, scope, store, entry.key, version)
        }
        if (known == null) {
            val (captured, objectVersion) = capturePayload(store, entry, source, destination, payloadPath)
            currentCoroutineContext().ensureActive()
            return withConnectionManager {
                transaction {
                    val prepared = progressService.prepare(
                        ObjectBackupOperation.BACKUP, sourceId, destinationId, scope, captured,
                    )
                    check(prepared.status == ObjectBackupProgressStatus.PENDING && prepared.attempts == 0) {
                        "Capture checkpoint changed before acknowledgement: ${prepared.key}"
                    }
                    val running = progressService.start(prepared.key, prepared.version)
                    progressService.completeVerified(running.key, running.version, objectVersion)
                }
            }
        }
        require(known.payload.length == entry.info.length) { "Conflicting captured revision length: ${known.key}" }
        val pending = when (known.status) {
            ObjectBackupProgressStatus.RUNNING -> error("Object capture is already running: ${known.key}")
            ObjectBackupProgressStatus.COMPLETED -> {
                val observed = try {
                    withContext(StorageDispatcher) { destination.observeBackupPayload(known.payload) }
                } catch (_: ObjectNotFoundException) {
                    null
                }
                if (observed != null && known.canReuseVerification(observed, verifiedSince)) return known
                val verified = if (observed == null) false else try {
                    withContext(StorageDispatcher) { destination.verifyBackupPayload(known.payload, observed) }
                    true
                } catch (_: ObjectNotFoundException) {
                    false
                } catch (_: BackupPayloadMismatchException) {
                    false
                }
                if (verified) {
                    val objectVersion = observed?.version
                    if (objectVersion == null && known.verifiedVersion == null) return known
                    return withConnectionManager {
                        progressService.recordVerification(known.key, known.version, objectVersion)
                    }
                }
                withConnectionManager {
                    progressService.invalidate(
                        known.key, known.version,
                        "Captured payload is missing or does not match its recorded length or digest",
                    )
                }
            }
            ObjectBackupProgressStatus.PENDING, ObjectBackupProgressStatus.FAILED -> known
        }
        val running = withConnectionManager { progressService.start(pending.key, pending.version) }
        return try {
            ObjectBackupTransfer(mapOf(store to source), destination).copyVersion(known.payload, version)
            val objectVersion = withContext(StorageDispatcher) {
                val observed = destination.observeBackupPayload(known.payload)
                destination.verifyBackupPayload(known.payload, observed)
                observed.version
            }
            currentCoroutineContext().ensureActive()
            withConnectionManager { progressService.completeVerified(running.key, running.version, objectVersion) }
        } catch (failure: Exception) {
            try {
                withContext(NonCancellable) {
                    withConnectionManager {
                        progressService.fail(
                            running.key, running.version,
                            failure.message?.takeIf { it.isNotBlank() } ?: failure.javaClass.name,
                        )
                    }
                }
            } catch (recordingFailure: Exception) {
                if (recordingFailure !== failure) failure.addSuppressed(recordingFailure)
            }
            throw failure
        }
    }

    override suspend fun capture(
        store: String,
        entry: ObjectStorageEntry,
        source: ObjectStorageService,
        destination: ObjectStorageService,
        payloadPath: String,
    ): BackupObject = capturePayload(store, entry, source, destination, payloadPath).first

    private suspend fun capturePayload(
        store: String,
        entry: ObjectStorageEntry,
        source: ObjectStorageService,
        destination: ObjectStorageService,
        payloadPath: String,
    ): Pair<BackupObject, String?> {
        val version = validateCapture(store, entry, source, destination, payloadPath)
        val path = StringObjectPath(payloadPath)
        var writeStarted = false
        try {
            return withContext(StorageDispatcher) {
                val exists = try {
                    destination.getObjectInfo(path)
                    true
                } catch (_: ObjectNotFoundException) {
                    false
                }
                check(!exists) { "Object capture payload already exists: $payloadPath" }
                currentCoroutineContext().ensureActive()
                val input = BackupPayloadInputStream(
                    source.getInputStream(StringObjectPath(entry.key), version),
                    entry.info.length,
                    payloadPath,
                    currentCoroutineContext(),
                )
                val sha256 = input.use {
                    currentCoroutineContext().ensureActive()
                    writeStarted = true
                    val written = destination.setInputStream(path, it, entry.info.length)
                    val digest = it.verifyComplete()
                    if (written != entry.info.length) {
                        throw BackupPayloadMismatchException("Incomplete capture write: $payloadPath")
                    }
                    digest
                }
                val captured = BackupObject(store, entry.key, version, entry.info.length, sha256, payloadPath)
                captured.validate()
                val observed = destination.observeBackupPayload(captured)
                destination.verifyBackupPayload(captured, observed)
                currentCoroutineContext().ensureActive()
                captured to observed.version
            }
        } catch (failure: Throwable) {
            if (writeStarted) {
                try {
                    withContext(NonCancellable + StorageDispatcher) {
                        destination.delete(path)
                        val remains = try {
                            destination.getObjectInfo(path)
                            true
                        } catch (_: ObjectNotFoundException) {
                            false
                        }
                        check(!remains) { "Object capture cleanup left output: $payloadPath" }
                    }
                } catch (cleanupFailure: Throwable) {
                    failure.addSuppressed(cleanupFailure)
                }
            }
            throw failure
        }
    }

    private suspend fun validateCapture(
        store: String,
        entry: ObjectStorageEntry,
        source: ObjectStorageService,
        destination: ObjectStorageService,
        payloadPath: String,
    ): String {
        require(store.isNotBlank() && entry.key.isNotBlank()) { "Object store and key are required" }
        val version = requireNotNull(entry.info.version?.takeIf { it.isNotBlank() }) {
            "A retained provider object version is required"
        }
        require(entry.key.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) {
            "Invalid capture object key: ${entry.key}"
        }
        require(payloadPath.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) {
            "Invalid backup payload path: $payloadPath"
        }
        require(source !== destination) { "Backup storage must be independent of the source" }
        check(connectionOrNull()?.inTransaction != true) {
            "Object capture must run outside an active database transaction"
        }

        return version
    }
}
