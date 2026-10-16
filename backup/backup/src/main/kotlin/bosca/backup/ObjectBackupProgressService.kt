package bosca.backup

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * Manages per-revision progress using the caller's connection-manager context.
 *
 * Callers own exclusive payload-writer access and must verify destination persistence before
 * recording completion. Recover a running attempt only after its previous writer has stopped.
 */
class ObjectBackupProgressService(private val repository: ObjectBackupProgressRepository) {
    /** Prepares immutable intent without resetting an existing revision's progress. */
    suspend fun prepare(
        sourceId: String,
        destinationId: String,
        scope: String,
        payload: BackupObject,
    ): ObjectBackupProgress {
        validateIdentity(sourceId, destinationId, scope)
        payload.validate()

        val identity = Json.encodeToString(
            ListSerializer(String.serializer()),
            listOf(sourceId, destinationId, scope, payload.store, payload.key, payload.revision),
        )
        val key = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).toHexString()
        repository.add(ObjectBackupProgress(key, sourceId, destinationId, scope, payload))

        val progress = checkNotNull(repository.get(key)) {
            "Prepared object backup progress is missing: $key"
        }
        require(
            progress.sourceId == sourceId &&
                progress.destinationId == destinationId &&
                progress.scope == scope &&
                progress.payload == payload
        ) {
            "Conflicting object backup revision intent: $key"
        }
        return progress
    }

    /** Reads persisted progress without changing its state. */
    suspend fun get(key: String): ObjectBackupProgress? {
        require(key.isNotBlank()) {
            "Object backup progress key is required"
        }
        return repository.get(key)
    }

    /** Lists progress within one source/destination scope. */
    suspend fun list(
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
        return repository.list(sourceId, destinationId, scope, offset, limit)
    }

    /** Starts a pending or failed record while checking the observed version. */
    suspend fun start(key: String, expectedVersion: Long): ObjectBackupProgress {
        validateVersion(key, expectedVersion)
        return updated(repository.start(key, expectedVersion), key, expectedVersion)
    }

    /** Records completion after the caller verifies the stored payload. */
    suspend fun complete(key: String, expectedVersion: Long): ObjectBackupProgress {
        validateVersion(key, expectedVersion)
        return updated(repository.complete(key, expectedVersion), key, expectedVersion)
    }

    /** Records a failed or interrupted attempt after its writer has stopped. */
    suspend fun fail(key: String, expectedVersion: Long, error: String): ObjectBackupProgress {
        validateVersion(key, expectedVersion)
        require(error.isNotBlank()) {
            "Object backup failure detail is required"
        }
        return updated(repository.fail(key, expectedVersion, error), key, expectedVersion)
    }

    /** Marks completed output as failed after missing or corrupt bytes have been established. */
    suspend fun invalidate(key: String, expectedVersion: Long, error: String): ObjectBackupProgress {
        validateVersion(key, expectedVersion)
        require(error.isNotBlank()) {
            "Object backup invalidation detail is required"
        }
        return updated(repository.invalidate(key, expectedVersion, error), key, expectedVersion)
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
