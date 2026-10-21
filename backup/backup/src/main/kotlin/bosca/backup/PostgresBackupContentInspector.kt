package bosca.backup

import bosca.content.model.ContentStorageReference
import bosca.content.model.ContentStorageReferenceKind
import bosca.storage.service.ObjectNotFoundException
import bosca.storage.service.ObjectStorageService
import java.nio.file.Path
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json

/** Inspects retained integrity without claiming that a database view froze object storage. */
internal class PostgresBackupContentInspector(private val json: Json) {
    suspend fun inspect(
        directory: Path,
        database: PostgresBackupManifest,
        objects: ObjectBackupManifest,
        store: String,
        backup: ObjectStorageService,
        onReference: suspend (PostgresBackupContentInspection) -> Unit,
    ): PostgresBackupContentInspectionSummary {
        val context = currentCoroutineContext()
        val inventory = requireNotNull(database.contentInventory)
        val byPath = objects.objects.filter { it.store == store }.groupBy { it.key }
        val checked = mutableMapOf<String, PostgresBackupContentStatus>()
        val counts = PostgresBackupContentStatus.entries.associateWith { 0L }.toMutableMap()
        PostgresBackupContentInventoryFile(json).forEach(directory, inventory) { reference ->
            context.ensureActive()
            val path = resolve(reference)
            val candidates = path?.let { byPath[it] }.orEmpty()
            val payload = candidates.singleOrNull()
            val status = when {
                path == null -> PostgresBackupContentStatus.UNRESOLVED
                candidates.isEmpty() -> PostgresBackupContentStatus.NOT_RETAINED
                payload == null -> PostgresBackupContentStatus.AMBIGUOUS
                reference.contentLength != null && reference.contentLength != payload.length ->
                    PostgresBackupContentStatus.LENGTH_MISMATCH
                else -> checked[payload.payloadPath] ?: inspectPayload(backup, payload).also {
                    checked[payload.payloadPath] = it
                }
            }
            counts[status] = counts.getValue(status) + 1
            onReference(PostgresBackupContentInspection(reference, path, status, payload))
        }
        return PostgresBackupContentInspectionSummary(database.id, objects.id, store, counts.toMap())
    }

    private fun resolve(reference: ContentStorageReference): String? = when (reference.kind) {
        ContentStorageReferenceKind.METADATA, ContentStorageReferenceKind.METADATA_VERSION ->
            if (reference.supplementaryId == null) {
                reference.version?.let { metadataObjectPath(reference.contentId, it) }
            } else null
        ContentStorageReferenceKind.METADATA_SUPPLEMENTARY,
        ContentStorageReferenceKind.METADATA_VERSION_SUPPLEMENTARY -> reference.version?.let { version ->
            reference.supplementaryId?.let { metadataObjectPath(reference.contentId, version, it) }
        }
        ContentStorageReferenceKind.COLLECTION_SUPPLEMENTARY ->
            reference.supplementaryId?.let { collectionObjectPath(reference.contentId, it) }
    }

    private suspend fun inspectPayload(backup: ObjectStorageService, payload: BackupObject): PostgresBackupContentStatus =
        try {
            backup.verifyBackupPayload(payload)
            PostgresBackupContentStatus.VERIFIED
        } catch (_: ObjectNotFoundException) {
            PostgresBackupContentStatus.MISSING
        } catch (_: BackupPayloadMismatchException) {
            PostgresBackupContentStatus.CORRUPT
        }
}
