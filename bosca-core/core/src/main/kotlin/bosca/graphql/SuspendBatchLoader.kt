package bosca.graphql

import bosca.db.withConnectionManager
import bosca.security.service.AuthenticationContext

class SuspendBatchLoader<K : Any, V>(
    private val authenticationContext: AuthenticationContext,
    private val block: suspend (keys: List<K>, environment: BatchLoaderEnvironment, authenticationContext: AuthenticationContext) -> List<V?>,
) {
    suspend fun load(keys: List<K>, environment: BatchLoaderEnvironment): List<V?> =
        withConnectionManager { block(keys, environment, authenticationContext) }
}
