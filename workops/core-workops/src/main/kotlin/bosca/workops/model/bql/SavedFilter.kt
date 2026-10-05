package bosca.workops.model.bql

import bosca.db.annotation.ColumnName
import bosca.graphql.annotations.BatchKey
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A reusable BQL query stored under an owner profile (R10). Phase 6
 * ships owner-only filters; Phase 7's permission scheme adds the
 * shared-with-roles / shared-with-projects / public visibility
 * variants alongside the subscriber-digest job.
 *
 * The persisted shape carries both the human-authored source and
 * the parsed AST: the source survives round-trips for display, the
 * AST avoids re-parsing on every search. The planner re-validates
 * the AST on read so a field-catalog change since save time
 * surfaces immediately as a typed error rather than a stale plan.
 */
@BatchKey("id")
@Serializable
data class SavedFilter(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("owner_profile_id")
    @Contextual
    val ownerProfileId: UUID,
    val name: String,
    val description: String? = null,
    @ColumnName("bql_source")
    val bqlSource: String,
    @ColumnName("parsed_ast")
    val parsedAst: JsonElement,
    @ColumnName("created_at")
    @Contextual
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
    @ColumnName("modified_at")
    @Contextual
    val modifiedAt: OffsetDateTime = OffsetDateTime.now(),
    val version: Long = 0,
)

/** Input for creating / updating a saved filter. The service parses [bqlSource] before insert. */
@Serializable
data class SavedFilterInput(
    val name: String,
    val description: String? = null,
    val bqlSource: String,
)
