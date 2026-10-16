package bosca.backup

import bosca.storage.service.ObjectStorageService
import bosca.storage.service.StringObjectPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/** Copies declared backup payloads between independently selected storage services. */
class ObjectBackupTransfer(
    private val source: ObjectStorageService,
    private val destination: ObjectStorageService,
) {
    /**
     * Streams a declared source revision and verifies its persisted destination before returning.
     *
     * The caller selects the source storage for [BackupObject.store], pins the source revision,
     * and serializes writes to the immutable destination [BackupObject.payloadPath]. Existing
     * verified output is reused without opening the source. Missing or mismatching output is
     * copied and verified; other storage errors and cancellation propagate.
     *
     * Changed source bytes fail length/digest verification. Failed transfers may leave an
     * unverified payload for retry; they do not publish a manifest or a completed checkpoint.
     */
    suspend fun copy(entry: BackupObject): Unit = withContext(Dispatchers.IO) {
        entry.validate()
        if (destination.hasVerifiedBackupPayload(entry)) return@withContext

        val context = currentCoroutineContext()
        val input = BackupPayloadInputStream(
            source.getInputStream(StringObjectPath(entry.key)),
            entry,
            context,
        )
        input.use {
            val written = destination.setInputStream(StringObjectPath(entry.payloadPath), it, entry.length)
            it.verifyComplete()
            check(written == entry.length) {
                "Incomplete backup payload write: expected ${entry.length}, wrote $written"
            }
        }

        destination.verifyBackupPayload(entry)
    }
}
