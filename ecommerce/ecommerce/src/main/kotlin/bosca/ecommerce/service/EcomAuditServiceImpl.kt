package bosca.ecommerce.service

import bosca.ecommerce.model.Audit
import bosca.ecommerce.repository.AuditRepository
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Writes audit entries to `ecom.audit` via [AuditRepository]. */
@ServiceImplementation
class EcomAuditServiceImpl(
    private val auditRepository: AuditRepository,
    json: Json,
) : EcomAuditService {

    /**
     * The primary [json] with `encodeDefaults` forced on, used only for entity-snapshot encoding.
     * Derived from the injected `Json` so it inherits the contextual serializers (UUID,
     * OffsetDateTime, …) and all other config — overriding nothing but default handling, so snapshots
     * capture default-valued fields the primary `Json` would otherwise omit.
     */
    private val snapshotJson = Json(json) { encodeDefaults = true }

    override suspend fun record(
        entityType: String,
        entityId: UUID,
        action: String,
        before: JsonElement?,
        after: JsonElement?,
        principalId: UUID?,
        profileId: UUID?,
        storeId: UUID?,
        details: JsonElement?,
    ) {
        auditRepository.add(
            Audit(
                principalId = principalId,
                profileId = profileId,
                storeId = storeId,
                entityType = entityType,
                entityId = entityId,
                action = action,
                before = before,
                after = after,
                details = details,
            ),
        )
    }

    override suspend fun <T> record(
        entityType: String,
        entityId: UUID,
        action: String,
        serializer: KSerializer<T>,
        before: T?,
        after: T?,
        principalId: UUID?,
        profileId: UUID?,
        storeId: UUID?,
        details: JsonElement?,
    ) = record(
        entityType = entityType,
        entityId = entityId,
        action = action,
        before = before?.let { snapshotJson.encodeToJsonElement(serializer, it) },
        after = after?.let { snapshotJson.encodeToJsonElement(serializer, it) },
        principalId = principalId,
        profileId = profileId,
        storeId = storeId,
        details = details,
    )

    override suspend fun list(
        entityType: String?,
        entityId: UUID?,
        storeId: UUID?,
        action: String?,
        offset: Int,
        limit: Int,
    ): List<Audit> = auditRepository.list(entityType, entityId, storeId, action, offset, limit)
}
