package bosca.analytics.delivery

import bosca.core.analytics.InstallationIdProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

/** Generates one process-local identity and never contacts or persists to the collector. */
class AnonymousInstallationIdProvider : InstallationIdProvider {
    private val mutex = Mutex()
    private var id: String? = null

    override suspend fun getOrCreate(): String = mutex.withLock {
        id ?: Uuid.random().toString().also { id = it }
    }
}
