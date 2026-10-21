package bosca.backup

import bosca.storage.service.ObjectNotFoundException
import bosca.storage.service.ObjectStorageEntry
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.StringObjectPath
import java.nio.file.Path
import java.time.OffsetDateTime
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json

/** Observes metadata at saved reference paths for the existing incremental capture service. */
internal class PostgresBackupContentSelector(private val json: Json) {
    suspend fun select(
        directory: Path,
        database: PostgresBackupManifest,
        store: String,
        scope: String,
        source: ObjectStorageService,
        onReference: suspend (PostgresBackupContentSelection) -> Unit,
    ): PostgresBackupContentSelectionSummary {
        val context = currentCoroutineContext()
        val inventory = requireNotNull(database.contentInventory)
        val created = OffsetDateTime.now().toInstant().toString()
        val observed = mutableMapOf<String, ObjectStorageEntry?>()
        val selected = sortedMapOf<String, ObjectStorageEntry>()
        val counts = PostgresBackupContentSelectionStatus.entries.associateWith { 0L }.toMutableMap()
        PostgresBackupContentInventoryFile(json).forEach(directory, inventory) { reference ->
            context.ensureActive()
            val path = contentObjectPath(reference)
            val entry = if (path == null) null else {
                if (path !in observed) {
                    observed[path] = try {
                        ObjectStorageEntry(path, source.getObjectInfo(StringObjectPath(path)))
                    } catch (_: ObjectNotFoundException) {
                        null
                    }
                }
                observed[path]
            }
            val status = when {
                path == null -> PostgresBackupContentSelectionStatus.UNRESOLVED
                entry == null -> PostgresBackupContentSelectionStatus.MISSING
                reference.contentLength != null && reference.contentLength != entry.info.length ->
                    PostgresBackupContentSelectionStatus.LENGTH_MISMATCH
                else -> PostgresBackupContentSelectionStatus.SELECTED.also { selected[entry.key] = entry }
            }
            counts[status] = counts.getValue(status) + 1
            onReference(PostgresBackupContentSelection(reference, path, entry, status))
        }
        val capture = ObjectBackupCaptureInventory(database.id, database.sourceId, scope, created,
            mapOf(store to selected.values.toList()))
        capture.validate()
        return PostgresBackupContentSelectionSummary(capture, counts.toMap())
    }
}
