package bosca.backup

import bosca.db.withConnectionManager
import bosca.service.annotation.ServiceImplementation
import bosca.storage.service.ObjectNotFoundException
import bosca.storage.service.ObjectPath
import bosca.storage.service.ObjectStorageService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.IdentityHashMap

/** Restores independently retained payloads through the shared transfer checkpoints. */
@ServiceImplementation
class ObjectBackupRestoreServiceImpl(
    private val progress: ObjectBackupProgressService,
    private val json: Json,
) : ObjectBackupRestoreService {
    override suspend fun restore(
        path: ObjectPath,
        backup: ObjectStorageService,
        destinations: Map<String, ObjectStorageService>,
        destinationId: String,
    ): ObjectBackupManifest {
        require(destinationId.isNotBlank()) {
            "Object restore destination identity is required"
        }

        val targets = destinations.toMap()
        val manifests = ObjectBackupManifestStore(backup, json)
        val inventory = manifests.load(path)
        val required = inventory.objects.map { it.copy(payloadPath = it.key) }
        validateDestinations(required, targets)

        manifests.verify(inventory)

        val context = currentCoroutineContext()
        val checkpoints = required.map { entry ->
            context.ensureActive()
            withConnectionManager {
                progress.prepare(inventory.sourceId, destinationId, inventory.scope, entry)
            }
        }

        for (checkpoint in checkpoints) {
            context.ensureActive()
            val destination = targets.getValue(checkpoint.payload.store)
            validateExistingTarget(destination, checkpoint)
        }

        for ((index, entry) in required.withIndex()) {
            context.ensureActive()
            val transfer = ObjectBackupTransfer(
                mapOf(entry.store to backup),
                targets.getValue(entry.store),
            )
            transfer.copy(
                inventory.sourceId,
                destinationId,
                inventory.scope,
                entry,
                progress,
                inventory.objects[index].payloadPath,
            )
        }

        context.ensureActive()
        return inventory
    }

    private fun validateDestinations(
        required: List<BackupObject>,
        destinations: Map<String, ObjectStorageService>,
    ) {
        val logicalKeys = mutableSetOf<Pair<String, String>>()
        val physicalKeys = IdentityHashMap<ObjectStorageService, MutableSet<String>>()

        for (entry in required) {
            entry.validate()
            val destination = requireNotNull(destinations[entry.store]) {
                "No destination object storage configured for store: ${entry.store}"
            }
            require(logicalKeys.add(entry.store to entry.key)) {
                "Multiple revisions target the same object: ${entry.store}/${entry.key}"
            }
            require(physicalKeys.getOrPut(destination) { mutableSetOf() }.add(entry.key)) {
                "Multiple logical stores target the same destination object: ${entry.key}"
            }
        }
    }

    private suspend fun validateExistingTarget(
        destination: ObjectStorageService,
        checkpoint: ObjectBackupProgress,
    ): Unit = withContext(Dispatchers.IO) {
        try {
            destination.verifyBackupPayload(checkpoint.payload)
        } catch (_: ObjectNotFoundException) {
            // Missing objects can be materialized by this restore.
        } catch (_: BackupPayloadMismatchException) {
            check(checkpoint.attempts > 0) {
                "Existing destination object conflicts with restore: ${checkpoint.payload.store}/${checkpoint.payload.key}"
            }
        }
    }
}
