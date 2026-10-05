package bosca.workops.model.audit

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * One field's change captured inside a [TaskHistoryEntry] (R17). The
 * old and new values are stored as opaque JSON elements so any field
 * type — strings, UUIDs, JSON blobs from custom fields — round-trips
 * cleanly without per-type history columns.
 *
 * @property fieldKey canonical identifier for the field that changed.
 *                    For built-in columns this is the column name
 *                    (`status_id`, `summary`, …). For Phase 4 custom
 *                    fields this is the `core-forms` field key.
 *                    Special meta keys: `comment`, `worklog`,
 *                    `attachment`, `link`, `subtask` capture
 *                    related-row mutations alongside scalar field
 *                    changes (R17).
 * @property fromValue the value before the change. `null` indicates
 *                     unset; a JSON literal `null` indicates the
 *                     field was already null and the writer was
 *                     making that explicit.
 * @property toValue the value after the change.
 */
@Serializable
data class FieldChange(
    @ColumnName("field_key")
    val fieldKey: String,
    @ColumnName("from_value")
    val fromValue: JsonElement? = null,
    @ColumnName("to_value")
    val toValue: JsonElement? = null,
)

/**
 * One immutable record of "who changed what when" on a task (R17).
 * Multi-field updates produce **one** entry with multiple
 * [FieldChange]s rather than one entry per field; this keeps the
 * audit log readable as a chronology of logical changes rather than
 * a noise stream of column writes.
 *
 * Per the Implementation Decisions section, the storage table is
 * append-only at the database layer — the application role lacks
 * UPDATE / DELETE grants on `workops.task_history`. A dedicated
 * retention role (used only by the retention job) holds them. This
 * is one of the Excellence Bar non-negotiables.
 *
 * Phase 2 captures basic field-change history; Phase 3 extends this
 * shape with `transitionId` (workflow attribution), and Phase 8 adds
 * `automationRuleId` / `automationRuleName` denormalized columns.
 *
 * @property changedByPrincipalId the security principal that made the
 *                                change — works even when the actor
 *                                is a service principal (automation,
 *                                MCP tool, AI agent) without a
 *                                profile.
 * @property changedByProfileId the user-facing profile attribution.
 *                              Null when the principal is a service
 *                              principal that has no profile.
 * @property changes the raw `jsonb` column as it lives in storage —
 *                   a JSON array of [FieldChange] objects. The
 *                   typed view is provided by [decodedChanges]; the
 *                   GraphQL controller decodes lazily so the read
 *                   path doesn't pay the deserialization cost when
 *                   the caller projects only the entry id or
 *                   `changedAt`.
 */
@Serializable
data class TaskHistoryEntry(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("task_id")
    @Contextual
    val taskId: UUID,
    @ColumnName("changed_at")
    @Contextual
    val changedAt: OffsetDateTime = OffsetDateTime.now(),
    @ColumnName("changed_by_principal_id")
    @Contextual
    val changedByPrincipalId: UUID,
    @ColumnName("changed_by_profile_id")
    @Contextual
    val changedByProfileId: UUID? = null,
    val changes: JsonElement,
)
