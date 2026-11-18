package bosca.kubernetes.controller.models

import bosca.lock.DistributedLock
import bosca.lock.DistributedLockFactory
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.sync.Mutex

/** In-process substitute for the shared store, retaining contention between independent lock handles. */
internal fun testServingLocks(): DistributedLockFactory {
    val mutex = Mutex()
    val factory = mockk<DistributedLockFactory>()
    coEvery { factory.create("kubernetes-serving-model-lifecycle") } answers {
        val owner = Any()
        val lock = mockk<DistributedLock>()
        coEvery { lock.tryAcquire(any()) } answers { mutex.tryLock(owner) }
        coEvery { lock.renew(any()) } answers { mutex.holdsLock(owner) }
        coEvery { lock.release() } answers { mutex.unlock(owner); true }
        lock
    }
    return factory
}
