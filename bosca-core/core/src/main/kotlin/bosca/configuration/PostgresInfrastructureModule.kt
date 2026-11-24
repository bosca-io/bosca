package bosca.configuration

import bosca.db.ConnectionPool
import bosca.db.PostgresInfrastructurePoolProvider
import bosca.db.DatabaseDispatcher
import bosca.db.use
import bosca.di.provideProvider
import bosca.server.BoscaApplication
import bosca.server.BoscaApplicationModule
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory

/** Reclaims expired operational records independently of traffic. */
class PostgresInfrastructureModule : BoscaApplicationModule {
    override suspend fun install(application: BoscaApplication) {
        val infrastructurePool = provideProvider<ConnectionPool>(PostgresInfrastructurePoolProvider.NAME)
        if (!infrastructurePool.exists) return
        val scope = CoroutineScope(SupervisorJob() + DatabaseDispatcher)
        val job = scope.launch {
            while (isActive) {
                delay(60_000)
                try {
                    val pool = infrastructurePool.get()
                    for (table in listOf("cache_entries", "counters", "locks")) {
                        pool.connection().use { manager -> manager.useStatement("delete from infrastructure.$table where expires <= clock_timestamp()") { it.executeUpdate() } }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { LoggerFactory.getLogger(javaClass).warn("PostgreSQL infrastructure cleanup failed", e) }
            }
        }
        application.onShutdown { scope.cancel(); job.join() }
    }
}
