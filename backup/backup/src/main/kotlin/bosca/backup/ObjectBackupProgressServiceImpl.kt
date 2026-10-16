package bosca.backup

import bosca.service.annotation.ServiceImplementation
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** Persists guarded per-revision progress through the backup repository. */
@ServiceImplementation
class ObjectBackupProgressServiceImpl(
    private val repository: ObjectBackupProgressRepository,
) : ObjectBackupProgressService {
    override suspend fun prepare(
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

    override suspend fun get(key: String): ObjectBackupProgress? {
        require(key.isNotBlank()) {
            "Object backup progress key is required"
        }
        return repository.get(key)
    }

    override suspend fun list(
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
