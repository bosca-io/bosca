package bosca.storage.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * Opens a stream on [StorageDispatcher], closing it if the caller is cancelled before it gets it.
 *
 * `withContext` discards a finished result when cancellation lands while handing it back (its
 * prompt-cancellation guarantee), which would leak the stream's connection or file handle. The
 * stream is remembered so that case can close it; the cancellation is still rethrown.
 */
internal suspend fun openOnStorageDispatcher(open: () -> InputStream): InputStream {
    var opened: InputStream? = null
    try {
        return withContext(StorageDispatcher) { open().also { opened = it } }
    } catch (e: CancellationException) {
        // A failing close must not turn the cancellation into a failure.
        try {
            opened?.close()
        } catch (closeFailure: Exception) {
            e.addSuppressed(closeFailure)
        }
        throw e
    }
}
