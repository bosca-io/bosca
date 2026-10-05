package bosca.ecommerce.service

import bosca.ecommerce.model.Audit
import bosca.serialization.UUID
import bosca.service.Service
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement

/**
 * The single write path for the module's audit log (`ecom.audit`). Services call [record] on
 * significant mutations — credit/balance changes, price changes, promotion rule edits, payment
 * lifecycle transitions, subscription status changes, admin config edits — capturing before/after
 * entity snapshots and actor attribution. The audit log replaces per-entity history tables AND
 * per-row created_by/modified_by columns (recorded deviation).
 */
interface EcomAuditService : Service {

    /**
     * Append one audit entry. [before] is null on create, [after] null on delete, both null for
     * action-only entries; [details] carries optional context for non-mutation entries. [principalId]
     * is null for system/job actions.
     */
    suspend fun record(
        entityType: String,
        entityId: UUID,
        action: String,
        before: JsonElement? = null,
        after: JsonElement? = null,
        principalId: UUID? = null,
        profileId: UUID? = null,
        storeId: UUID? = null,
        details: JsonElement? = null,
    )

    /**
     * Append one audit entry capturing typed entity snapshots. [before]/[after] are encoded with the
     * audit service's own `Json` (which forces `encodeDefaults = true`) so a snapshot always carries
     * the entity's full state — including fields whose value equals their model default (e.g. a
     * company at the default `INCHES`/`POUNDS` units, or a product at the default `weight = 1.0`).
     * This is the preferred overload for entity mutations; the [JsonElement]-based [record] remains
     * for ad-hoc [details] payloads and action-only entries.
     */
    suspend fun <T> record(
        entityType: String,
        entityId: UUID,
        action: String,
        serializer: KSerializer<T>,
        before: T? = null,
        after: T? = null,
        principalId: UUID? = null,
        profileId: UUID? = null,
        storeId: UUID? = null,
        details: JsonElement? = null,
    )

    /**
     * The admin read path over the audit log, newest first. Every filter is optional (null matches
     * all). The log has no company column, so scope by [storeId] (store-bound entries) and/or
     * [entityType]/[entityId].
     */
    suspend fun list(
        entityType: String? = null,
        entityId: UUID? = null,
        storeId: UUID? = null,
        action: String? = null,
        offset: Int = 0,
        limit: Int = 50,
    ): List<Audit>
}
