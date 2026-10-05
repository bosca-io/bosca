package bosca.analytics.service

import bosca.analytics.model.AnalyticsScriptBinding
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Manages [AnalyticsScriptBinding]s — the configurable set of scripts that run inline over every
 * analytics event batch.
 *
 * CRUD operations back the admin GraphQL surface; [enabledBindings] is the hot-path read used by
 * the processor's transform on every batch and is expected to be cheaply cached.
 */
interface AnalyticsScriptBindingService : Service {

    /** All bindings (enabled and disabled), ordered by [AnalyticsScriptBinding.ordinal]. */
    suspend fun list(): List<AnalyticsScriptBinding>

    /** The binding with [id], or null if none exists. */
    suspend fun get(id: UUID): AnalyticsScriptBinding?

    /** Creates a binding of [scriptId]. Returns the persisted row with its generated id and timestamps. */
    suspend fun add(
        scriptId: UUID,
        transform: Boolean = true,
        enabled: Boolean = true,
        ordinal: Int = 0,
    ): AnalyticsScriptBinding

    /** Replaces the mutable fields of the binding [id]. Throws if no such binding exists. */
    suspend fun update(
        id: UUID,
        scriptId: UUID,
        transform: Boolean = true,
        enabled: Boolean = true,
        ordinal: Int = 0,
    ): AnalyticsScriptBinding

    /** Deletes the binding [id]. Idempotent — deleting a missing binding is a no-op. */
    suspend fun delete(id: UUID)

    /**
     * The enabled bindings ordered by [AnalyticsScriptBinding.ordinal], for the processor's inline
     * script transform. Implementations cache this with a short TTL: the transform consults it on
     * every event batch, so an uncached read per batch would dominate the "cheap" path this feature
     * exists to provide.
     */
    suspend fun enabledBindings(): List<AnalyticsScriptBinding>
}
