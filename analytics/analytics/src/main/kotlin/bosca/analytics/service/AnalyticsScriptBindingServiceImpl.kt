package bosca.analytics.service

import bosca.analytics.model.AnalyticsScriptBinding
import bosca.analytics.repository.AnalyticsScriptBindingRepository
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation

/**
 * Default [AnalyticsScriptBindingService]: thin CRUD over
 * [AnalyticsScriptBindingRepository] plus a short-TTL cache for [enabledBindings].
 *
 * The cache mirrors the gate that `PipelineServiceImpl.triggeredEventTypes()` uses: the processor's
 * inline script transform reads [enabledBindings] once per event batch, so an uncached read would
 * add a database round-trip to every batch on the "cheap" path. Writes invalidate the cache in the
 * writing process; other processes converge within [CACHE_TTL_MS].
 */
@ServiceImplementation
class AnalyticsScriptBindingServiceImpl(
    private val repository: AnalyticsScriptBindingRepository,
) : AnalyticsScriptBindingService {

    @Volatile
    private var enabledCache: Pair<List<AnalyticsScriptBinding>, Long>? = null

    override suspend fun list(): List<AnalyticsScriptBinding> = repository.getAll()

    override suspend fun get(id: UUID): AnalyticsScriptBinding? = repository.getById(id)

    override suspend fun add(
        scriptId: UUID,
        transform: Boolean,
        enabled: Boolean,
        ordinal: Int,
    ): AnalyticsScriptBinding {
        val binding = repository.insert(UUID.random(), scriptId, transform, enabled, ordinal)
            ?: error("Failed to create analytics script binding")
        invalidate()
        return binding
    }

    override suspend fun update(
        id: UUID,
        scriptId: UUID,
        transform: Boolean,
        enabled: Boolean,
        ordinal: Int,
    ): AnalyticsScriptBinding {
        val binding = repository.update(id, scriptId, transform, enabled, ordinal)
            ?: error("Analytics script binding not found: $id")
        invalidate()
        return binding
    }

    override suspend fun delete(id: UUID) {
        repository.delete(id)
        invalidate()
    }

    override suspend fun enabledBindings(): List<AnalyticsScriptBinding> {
        val now = System.currentTimeMillis()
        enabledCache?.let { (bindings, expires) -> if (now < expires) return bindings }
        val bindings = repository.getEnabled()
        enabledCache = bindings to (now + CACHE_TTL_MS)
        return bindings
    }

    private fun invalidate() {
        enabledCache = null
    }

    companion object {
        /** How long [enabledBindings] stays cached before re-reading; matches the pipeline trigger gate. */
        private const val CACHE_TTL_MS = 15_000L
    }
}
