package bosca.backup

import bosca.db.transaction
import bosca.db.withConnectionManager
import bosca.service.annotation.ServiceImplementation
import bosca.storage.service.ObjectNotFoundException
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.StringObjectPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** Persists and executes object inventory runs through the selected storage services. */
@ServiceImplementation
class ObjectBackupRunServiceImpl(
    private val repository: ObjectBackupRunRepository,
    private val progressService: ObjectBackupProgressService,
    private val json: Json,
) : ObjectBackupRunService {
    override suspend fun prepare(inventory: ObjectBackupManifest, destinationId: String): ObjectBackupRun {
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

        val identity = Json.encodeToString(
            ListSerializer(String.serializer()),
            listOf(captured.sourceId, destinationId, captured.scope, captured.id),
        )
        val key = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).toHexString()
        val path = "manifests/$key.json"
        require(captured.objects.none { it.payloadPath == path }) {
            "Manifest path conflicts with a payload: $path"
        }

        return transaction {
            val inserted = repository.add(ObjectBackupRun(
                key, captured.sourceId, destinationId, captured.scope, captured, path,
            ))
            val run = checkNotNull(repository.get(key)) {
                "Prepared object backup run is missing: $key"
            }
            require(
                run.sourceId == captured.sourceId &&
                    run.destinationId == destinationId &&
                    run.scope == captured.scope &&
                    run.inventory == captured &&
                    run.path == path
            ) {
                "Conflicting object backup run intent: $key"
            }

            if (inserted > 0) {
                for ((position, entry) in captured.objects.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val progress = progressService.prepare(captured.sourceId, destinationId, captured.scope, entry)
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
        sourceId: String,
        destinationId: String,
        scope: String,
        offset: Long,
        limit: Int,
    ): List<ObjectBackupRun> {
        validateIdentity(sourceId, destinationId, scope)
        validatePagination(offset, limit)
        return repository.list(sourceId, destinationId, scope, offset, limit)
    }

    override suspend fun lastSuccessful(sourceId: String, destinationId: String, scope: String): ObjectBackupRun? {
        validateIdentity(sourceId, destinationId, scope)
        return repository.lastSuccessful(sourceId, destinationId, scope)
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
        return updated(repository.start(key, expectedVersion), key, expectedVersion)
    }

    override suspend fun retry(key: String, expectedVersion: Long): ObjectBackupRun {
        validateVersion(key, expectedVersion)
        return updated(repository.retry(key, expectedVersion), key, expectedVersion)
    }

    override suspend fun complete(key: String, expectedVersion: Long): ObjectBackupRun {
        validateVersion(key, expectedVersion)
        return updated(repository.finish(key, expectedVersion, null, false), key, expectedVersion)
    }

    override suspend fun fail(key: String, expectedVersion: Long, error: String): ObjectBackupRun =
        finishFailure(key, expectedVersion, error, false)

    override suspend fun cancel(key: String, expectedVersion: Long, error: String): ObjectBackupRun =
        finishFailure(key, expectedVersion, error, true)

    override suspend fun execute(
        key: String,
        sources: Map<String, ObjectStorageService>,
        destination: ObjectStorageService,
    ): ObjectBackupRun {
        val prepared = withConnectionManager {
            checkNotNull(get(key)) { "Object backup run is missing: $key" }
        }
        val ready = when (prepared.status) {
            ObjectBackupRunStatus.COMPLETED, ObjectBackupRunStatus.CANCELLED -> return prepared

            ObjectBackupRunStatus.RUNNING -> withConnectionManager {
                fail(prepared.key, prepared.version, "Previous run stopped before acknowledgement")
            }

            ObjectBackupRunStatus.PENDING,
            ObjectBackupRunStatus.PARTIAL,
            ObjectBackupRunStatus.FAILED -> prepared
        }
        val running = withConnectionManager { start(ready.key, ready.version) }
        val transfer = ObjectBackupTransfer(sources, destination)
        val manifests = ObjectBackupManifestStore(destination, json)

        return try {
            transfer.copy(running.inventory, running.destinationId, progressService)
            publish(running, manifests)
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
    ): ObjectBackupRun {
        validateVersion(key, expectedVersion)
        require(error.isNotBlank()) {
            "Object backup run failure detail is required"
        }
        return updated(repository.finish(key, expectedVersion, error, cancelled), key, expectedVersion)
    }

    private fun validateKey(key: String) {
        require(key.isNotBlank()) {
            "Object backup run key is required"
        }
    }

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
