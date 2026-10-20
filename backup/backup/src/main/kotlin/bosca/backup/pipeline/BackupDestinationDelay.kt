package bosca.backup.pipeline

import bosca.backup.ObjectBackupDestinationBusyException
import bosca.sharedqueue.jobs.DelayException
import bosca.sharedqueue.jobs.jobOrNull
import kotlin.time.Duration.Companion.seconds

/** Lets the queue defer destination contention; inline callers retain the original error. */
internal suspend fun <T> deferBusyBackupDestination(block: suspend () -> T): T = try {
    block()
} catch (busy: ObjectBackupDestinationBusyException) {
    if (jobOrNull() == null) throw busy
    throw DelayException(5.seconds).also { it.initCause(busy) }
}
