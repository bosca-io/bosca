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
    override suspend fun inspect(
        path: ObjectPath,
        backup: ObjectStorageService,
        destinations: Map<String, ObjectStorageService>,
        destinationId: String,
    ): ObjectBackupRestoreInspection {
        require(destinationId.isNotBlank()) {
            "Object restore destination identity is required"
        }
        val inventory = ObjectBackupManifestStore(backup, json).load(path)
        return inspectInventory(inventory, backup, destinations, destinationId)
    }

    private suspend fun inspectInventory(
        selected: ObjectBackupManifest,
        backup: ObjectStorageService,
        destinations: Map<String, ObjectStorageService>,
        destinationId: String,
    ): ObjectBackupRestoreInspection {
        require(destinationId.isNotBlank()) {
            "Object restore destination identity is required"
        }

        val targets = destinations.toMap()
        val manifests = ObjectBackupManifestStore(backup, json)
        val inventory = selected.copy(objects = selected.objects.toList())
        inventory.validate()
        val required = inventory.restoreObjects()
        validateDestinations(required, targets)

        manifests.verify(inventory)

        val context = currentCoroutineContext()
        val objects = inventory.objects.map { entry ->
            context.ensureActive()
            val targetPayload = entry.copy(payloadPath = entry.key)
            val checkpoint = withConnectionManager {
                progress.get(
                    ObjectBackupOperation.RESTORE, inventory.sourceId, destinationId, inventory.scope, targetPayload,
                )
            }
            val status = inspectTarget(targets.getValue(entry.store), targetPayload, checkpoint)
            ObjectBackupRestoreObject(entry, status, checkpoint)
        }

        context.ensureActive()
        return ObjectBackupRestoreInspection(inventory, objects)
    }

    override suspend fun restore(
        path: ObjectPath,
        backup: ObjectStorageService,
        destinations: Map<String, ObjectStorageService>,
        destinationId: String,
    ): ObjectBackupManifest {
        val targets = destinations.toMap()
        val inspection = inspect(path, backup, targets, destinationId)
        return restoreInventory(inspection, backup, targets, destinationId)
    }

    override suspend fun restore(
        inventory: ObjectBackupManifest,
        backup: ObjectStorageService,
        destinations: Map<String, ObjectStorageService>,
        destinationId: String,
    ): ObjectBackupManifest {
        val targets = destinations.toMap()
        val inspection = inspectInventory(inventory, backup, targets, destinationId)
        return restoreInventory(inspection, backup, targets, destinationId)
    }

    private suspend fun restoreInventory(
        inspection: ObjectBackupRestoreInspection,
        backup: ObjectStorageService,
        targets: Map<String, ObjectStorageService>,
        destinationId: String,
    ): ObjectBackupManifest {
        for (item in inspection.objects) {
            check(item.status != ObjectBackupRestoreObjectStatus.CONFLICTING) {
                "Existing destination object conflicts with restore: ${item.payload.store}/${item.payload.key}"
            }
        }

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
        destinations: Map<String, ObjectStorageService>,
    ) {
        val physicalKeys = IdentityHashMap<ObjectStorageService, MutableSet<String>>()

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
        destination: ObjectStorageService,
        payload: BackupObject,
        checkpoint: ObjectBackupProgress?,
    ): ObjectBackupRestoreObjectStatus = withContext(Dispatchers.IO) {
        try {
            destination.verifyBackupPayload(payload)
            ObjectBackupRestoreObjectStatus.MATCHING
        } catch (_: ObjectNotFoundException) {
            ObjectBackupRestoreObjectStatus.MISSING
        } catch (_: BackupPayloadMismatchException) {
            if ((checkpoint?.attempts ?: 0) > 0) {
                ObjectBackupRestoreObjectStatus.REPAIRABLE
            } else {
                ObjectBackupRestoreObjectStatus.CONFLICTING
            }
        }
    }
}
