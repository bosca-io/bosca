package bosca.backup

import bosca.db.withConnectionManager
import bosca.service.annotation.ServiceImplementation
import bosca.storage.service.ObjectNotFoundException
import bosca.storage.service.ObjectPath
import bosca.storage.service.ManagedObjectStorageService
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
    override suspend fun inspect(
        path: ObjectPath,
        backup: ManagedObjectStorageService,
        destinations: Map<String, ManagedObjectStorageService>,
        destinationId: String,
    ): ObjectBackupRestoreInspection {
        require(destinationId.isNotBlank()) {
            "Object restore destination identity is required"
        }
        val inventory = ObjectBackupManifestStore(backup, json).load(path)
        return inspect(inventory, backup, destinations, destinationId)
    }

    override suspend fun inspect(
        inventory: ObjectBackupManifest,
        backup: ManagedObjectStorageService,
        destinations: Map<String, ManagedObjectStorageService>,
        destinationId: String,
    ): ObjectBackupRestoreInspection {
        require(destinationId.isNotBlank()) {
            "Object restore destination identity is required"
        }

        val targets = destinations.toMap()
        val selected = inventory.copy(objects = inventory.objects.toList())
        selected.validate()
        val required = selected.restoreObjects()
        validateDestinations(required, targets)

        val context = currentCoroutineContext()
        val objects = selected.objects.map { entry ->
            context.ensureActive()
            val targetPayload = entry.copy(payloadPath = entry.key)
            val checkpoint = withConnectionManager {
                progress.get(
                    ObjectBackupOperation.RESTORE, selected.sourceId, destinationId, selected.scope, targetPayload,
                )
            }
            val status = inspectTarget(targets.getValue(entry.store), targetPayload)
            if (status != ObjectBackupRestoreObjectStatus.MATCHING) {
                withContext(Dispatchers.IO) { backup.verifyBackupPayload(entry) }
            }
            ObjectBackupRestoreObject(entry, status, checkpoint)
        }

        context.ensureActive()
        return ObjectBackupRestoreInspection(selected, objects)
    }

    override suspend fun restore(
        path: ObjectPath,
        backup: ManagedObjectStorageService,
        destinations: Map<String, ManagedObjectStorageService>,
        destinationId: String,
    ): ObjectBackupManifest {
        val targets = destinations.toMap()
        val inspection = inspect(path, backup, targets, destinationId)
        return restoreInventory(inspection, backup, targets, destinationId)
    }

    override suspend fun restore(
        inventory: ObjectBackupManifest,
        backup: ManagedObjectStorageService,
        destinations: Map<String, ManagedObjectStorageService>,
        destinationId: String,
    ): ObjectBackupManifest {
        val targets = destinations.toMap()
        val inspection = inspect(inventory, backup, targets, destinationId)
        return restoreInventory(inspection, backup, targets, destinationId)
    }

    private suspend fun restoreInventory(
        inspection: ObjectBackupRestoreInspection,
        backup: ManagedObjectStorageService,
        targets: Map<String, ManagedObjectStorageService>,
        destinationId: String,
    ): ObjectBackupManifest {
        val inventory = inspection.inventory
        val required = inventory.restoreObjects()
        val context = currentCoroutineContext()

        for (entry in required) {
            context.ensureActive()
            withConnectionManager {
                progress.prepare(
                    ObjectBackupOperation.RESTORE, inventory.sourceId, destinationId, inventory.scope, entry,
                )
            }
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
                ObjectBackupOperation.RESTORE,
            )
        }

        context.ensureActive()
        return inventory
    }

    private fun validateDestinations(
        required: List<BackupObject>,
        destinations: Map<String, ManagedObjectStorageService>,
    ) {
        val physicalKeys = IdentityHashMap<ManagedObjectStorageService, MutableSet<String>>()

        for (entry in required) {
            entry.validate()
            val destination = requireNotNull(destinations[entry.store]) {
                "No destination object storage configured for store: ${entry.store}"
            }
            require(physicalKeys.getOrPut(destination) { mutableSetOf() }.add(entry.key)) {
                "Multiple logical stores target the same destination object: ${entry.key}"
            }
        }
    }

    private suspend fun inspectTarget(
        destination: ManagedObjectStorageService,
        payload: BackupObject,
    ): ObjectBackupRestoreObjectStatus = withContext(Dispatchers.IO) {
        try {
            destination.verifyBackupPayload(payload)
            ObjectBackupRestoreObjectStatus.MATCHING
        } catch (_: ObjectNotFoundException) {
            ObjectBackupRestoreObjectStatus.MISSING
        } catch (_: BackupPayloadMismatchException) {
            ObjectBackupRestoreObjectStatus.DIFFERENT
        }
    }
}
