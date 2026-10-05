package bosca.ecommerce.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * One row in `ecom.audit` — the module's single append-only accountability log (the modernized port
 * of legacy `bosca.audit`). Carries structured [entityType]/[entityId]/[action], acting
 * [principalId]/[profileId], optional [storeId], and [before]/[after] jsonb entity snapshots:
 * [before] null on create, [after] null on delete, both null for action-only entries. [details]
 * holds optional context for non-mutation entries.
 */
@Serializable
data class Audit(
    @Contextual
    val id: UUID = UUID.NIL,
    @Contextual
    @ColumnName("principal_id")
    val principalId: UUID? = null,
    @Contextual
    @ColumnName("profile_id")
    val profileId: UUID? = null,
    @Contextual
    @ColumnName("store_id")
    val storeId: UUID? = null,
    @ColumnName("entity_type")
    val entityType: String,
    @Contextual
    @ColumnName("entity_id")
    val entityId: UUID,
    val action: String,
    val before: JsonElement? = null,
    val after: JsonElement? = null,
    val details: JsonElement? = null,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
)
