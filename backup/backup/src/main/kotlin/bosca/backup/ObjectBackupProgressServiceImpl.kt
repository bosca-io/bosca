package bosca.backup

import bosca.db.transaction
import bosca.service.annotation.ServiceImplementation

/** Persists guarded per-revision progress through the backup repository. */
@ServiceImplementation
class ObjectBackupProgressServiceImpl(
    private val repository: ObjectBackupProgressRepository,
) : ObjectBackupProgressService {
    override suspend fun prepare(
        operation: ObjectBackupOperation,
        sourceId: String,
        destinationId: String,
        scope: String,
        payload: BackupObject,
    ): ObjectBackupProgress {
        val key = revisionKey(operation, sourceId, destinationId, scope, payload)
        return transaction {
            val existing = get(operation, sourceId, destinationId, scope, payload)
            if (operation == ObjectBackupOperation.BACKUP) {
                repository.addPayload(destinationId, payload.payloadPath, payload.length, payload.sha256)
                require(repository.hasPayload(destinationId, payload.payloadPath, payload.length, payload.sha256)) {
                    "Conflicting retained payload: $destinationId/${payload.payloadPath}"
                }
            }
            if (existing != null) return@transaction existing

            repository.add(ObjectBackupProgress(key, sourceId, destinationId, scope, operation, payload))
            checkNotNull(get(operation, sourceId, destinationId, scope, payload)) {
                "Prepared object backup progress is missing: $key"
            }
        }
    }

    override suspend fun reserveManifest(destinationId: String, path: String, runKey: String) {
        require(destinationId.isNotBlank()) { "Object backup destination identity is required" }
        require(runKey.isNotBlank()) { "Object backup run key is required" }
        require(path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) {
            "Invalid object backup manifest path: $path"
        }
        repository.addManifest(destinationId, path, runKey)
        require(repository.hasManifest(destinationId, path, runKey)) {
            "Conflicting retained manifest path: $destinationId/$path"
        }
    }

    override suspend fun get(key: String): ObjectBackupProgress? {
        require(key.isNotBlank()) {
            "Object backup progress key is required"
        }
        return repository.get(key)
    }

    override suspend fun get(
        operation: ObjectBackupOperation,
        sourceId: String,
        destinationId: String,
        scope: String,
        payload: BackupObject,
    ): ObjectBackupProgress? {
        val key = revisionKey(operation, sourceId, destinationId, scope, payload)
        val progress = repository.get(key) ?: return null
        require(
            progress.sourceId == sourceId &&
                progress.destinationId == destinationId &&
                progress.scope == scope &&
                progress.operation == operation &&
                progress.payload == payload
        ) {
            "Conflicting object backup revision intent: $key"
        }
        return progress
    }

    override suspend fun list(
        operation: ObjectBackupOperation,
        sourceId: String,
        destinationId: String,
        scope: String,
        offset: Long,
        limit: Int,
    ): List<ObjectBackupProgress> {
        validateIdentity(sourceId, destinationId, scope)
        require(offset >= 0) {
            "Object backup progress offset must not be negative"
        }
        require(limit > 0) {
            "Object backup progress limit must be positive"
        }
        return repository.list(operation, sourceId, destinationId, scope, offset, limit)
    }

    override suspend fun start(key: String, expectedVersion: Long): ObjectBackupProgress {
        validateVersion(key, expectedVersion)
        return updated(repository.start(key, expectedVersion), key, expectedVersion)
    }

    override suspend fun complete(key: String, expectedVersion: Long): ObjectBackupProgress {
        validateVersion(key, expectedVersion)
        return updated(repository.complete(key, expectedVersion), key, expectedVersion)
    }

    override suspend fun fail(key: String, expectedVersion: Long, error: String): ObjectBackupProgress {
        validateVersion(key, expectedVersion)
        require(error.isNotBlank()) {
            "Object backup failure detail is required"
        }
        return updated(repository.fail(key, expectedVersion, error), key, expectedVersion)
    }

    override suspend fun invalidate(key: String, expectedVersion: Long, error: String): ObjectBackupProgress {
        validateVersion(key, expectedVersion)
        require(error.isNotBlank()) {
            "Object backup invalidation detail is required"
        }
        return updated(repository.invalidate(key, expectedVersion, error), key, expectedVersion)
    }

    private fun revisionKey(
        operation: ObjectBackupOperation,
        sourceId: String,
        destinationId: String,
        scope: String,
        payload: BackupObject,
    ): String {
        validateIdentity(sourceId, destinationId, scope)
        payload.validate()

        return objectBackupIdentity(
            operation,
            listOf(sourceId, destinationId, scope, payload.store, payload.key, payload.revision),
        )
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

    private fun validateVersion(key: String, expectedVersion: Long) {
        require(key.isNotBlank()) {
            "Object backup progress key is required"
        }
        require(expectedVersion >= 0) {
            "Object backup progress version must not be negative"
        }
    }

    private fun updated(
        progress: ObjectBackupProgress?,
        key: String,
        expectedVersion: Long,
    ): ObjectBackupProgress = checkNotNull(progress) {
        "Object backup progress state or version changed: $key at version $expectedVersion"
    }
}
