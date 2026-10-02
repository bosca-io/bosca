package bosca.git.service

import bosca.db.transaction
import bosca.db.withConnectionManager
import bosca.lock.DistributedLockFactory
import bosca.serialization.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Owns the transaction so its write lock cannot be released while a caller's savepoint remains open. */
internal suspend fun <T : Any> withRefSynchronizationTransaction(
    repositoryId: UUID, locks: DistributedLockFactory, block: suspend () -> T,
): T = withConnectionManager {
    locks.withRepositoryWriteLock(repositoryId, RepositoryWriteLock.API_WRITE_WAIT_MILLIS) { lock ->
        withContext(RefSynchronizationLock(repositoryId, lock)) {
            transaction {
                val result = block()
                lock.ensureHeld()
                result
            }
        }
    } ?: throw RepositoryWriteBusyException(repositoryId)
}

internal suspend fun <T : Any> withRefSynchronizationLock(
    repositoryId: UUID, locks: DistributedLockFactory, block: suspend (RepositoryWriteLockHandle) -> T,
): T {
    val held = currentCoroutineContext()[RefSynchronizationLock]
    if (held?.repositoryId == repositoryId) return block(held.handle)
    return locks.withRepositoryWriteLock(repositoryId, RepositoryWriteLock.API_WRITE_WAIT_MILLIS, block)
        ?: throw RepositoryWriteBusyException(repositoryId)
}

private class RefSynchronizationLock(val repositoryId: UUID, val handle: RepositoryWriteLockHandle) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RefSynchronizationLock>
}
