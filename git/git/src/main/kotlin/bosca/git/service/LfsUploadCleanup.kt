package bosca.git.service

import java.io.InputStream
import java.io.OutputStream

/** Closes both pipe ends, preserving an existing failure and suppressing subsequent close failures. */
internal fun closeLfsUploadPipe(input: InputStream, output: OutputStream, failure: Throwable? = null) {
    var primary = failure
    for (stream in arrayOf(input, output)) {
        try {
            stream.close()
        } catch (cleanup: Throwable) {
            val existing = primary
            if (existing == null) {
                primary = cleanup
            } else if (existing !== cleanup) {
                existing.addSuppressed(cleanup)
            }
        }
    }
    if (failure == null) primary?.let { throw it }
}
