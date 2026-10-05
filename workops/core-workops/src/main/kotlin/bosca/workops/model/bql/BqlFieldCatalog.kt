package bosca.workops.model.bql

import kotlinx.serialization.Serializable

/**
 * Type kinds the BQL planner knows how to translate into SQL. Each
 * field in the catalog binds to one of these so the planner emits
 * the correct cast / comparison shape.
 */
@Serializable
enum class BqlFieldType {
    /** Free text — falls through to the Meilisearch leg of the planner via `~`. */
    TEXT,

    /** UUID column. Comparisons accept string or UUID literals. */
    UUID,

    /** Bigint / int. */
    NUMBER,

    /** Boolean. */
    BOOLEAN,

    /** Postgres timestamptz. Function literals (`now()`, `startOfDay()`) substitute server-side. */
    TIMESTAMP,

    /**
     * `text[]` / `uuid[]` array column. `=` becomes `ANY`,
     * `in (…)` becomes overlap.
     */
    ARRAY_UUID,

    /**
     * Enum reference resolved by name to a UUID — e.g. `priority`,
     * `status`. The planner translates the bareword value through
     * the registered name-to-id resolver.
     */
    NAME_REFERENCE,
}

/**
 * One catalog entry bound to a Postgres column. The
 * [resolveNameToId] hook is consulted for `NAME_REFERENCE` typed
 * fields so authors can write `priority = High` and the planner
 * substitutes the seeded priority's UUID.
 */
@Serializable
data class BqlField(
    val key: String,
    val column: String,
    val type: BqlFieldType,
    /**
     * When false, the field accepts comparisons but doesn't take
     * part in `ORDER BY`. Phase 6's catalog leaves all fields
     * sortable by default; later phases may flip individual fields
     * (e.g. multi-valued arrays) to false.
     */
    val sortable: Boolean = true,
    val description: String? = null,
) {
    init {
        require(column.matches(Regex("^[a-z_][a-z0-9_]*$"))) {
            "BqlField column must be a valid SQL identifier, got '$column'"
        }
    }
}

/**
 * The set of fields BQL is allowed to filter on. Phase 6 ships the
 * built-in Task columns; Phase 4-style custom fields land via a
 * follow-up that registers `core-forms` field bindings into the
 * catalog at runtime.
 *
 * Field keys are lowercase; planner / validator look up case-
 * insensitively so authors can write `Status = Done` without
 * caring about case.
 */
class BqlFieldCatalog(initial: List<BqlField> = emptyList()) {

    private val byKey: MutableMap<String, BqlField> = initial.associateBy { it.key.lowercase() }.toMutableMap()

    /** Register a new field. Late additions land before Phase 4 custom fields. */
    fun register(field: BqlField) {
        byKey[field.key.lowercase()] = field
    }

    fun lookup(key: String): BqlField? = byKey[key.lowercase()]

    fun all(): List<BqlField> = byKey.values.toList()

    companion object {
        fun spec(): BqlFieldCatalog = BqlFieldCatalog(
            listOf(
                BqlField("key", "key", BqlFieldType.TEXT, description = "Spec key (e.g. SPEC-42)."),
                BqlField("status", "status_id", BqlFieldType.NAME_REFERENCE, description = "Status by name."),
                BqlField("owner", "owner_profile_id", BqlFieldType.UUID, description = "Owner profile."),
                BqlField("project", "project_id", BqlFieldType.NAME_REFERENCE, description = "Project key (e.g. BOSCAWEB)."),
                BqlField("program", "program_id", BqlFieldType.UUID),
                BqlField("parent", "parent_spec_id", BqlFieldType.UUID),
                BqlField("label", "label_ids", BqlFieldType.ARRAY_UUID),
                BqlField("watcher", "watcher_profile_ids", BqlFieldType.ARRAY_UUID),
                BqlField("created", "created_at", BqlFieldType.TIMESTAMP),
                BqlField("modified", "modified_at", BqlFieldType.TIMESTAMP),
            ),
        )

        /**
         * The seeded catalog covering Task columns the planner has
         * SQL templates for in Phase 6. Custom fields, links,
         * watchers, and history-axis filters land in their owning
         * phases.
         */
        fun default(): BqlFieldCatalog = BqlFieldCatalog(
            listOf(
                BqlField("project", "project_id", BqlFieldType.NAME_REFERENCE, description = "Project key (e.g. BOS)."),
                BqlField("type", "task_type_id", BqlFieldType.NAME_REFERENCE, description = "Task type by name (e.g. Bug)."),
                BqlField("status", "status_id", BqlFieldType.NAME_REFERENCE, description = "Status by name."),
                BqlField("priority", "priority_id", BqlFieldType.NAME_REFERENCE, description = "Priority by name."),
                BqlField("resolution", "resolution_id", BqlFieldType.NAME_REFERENCE, description = "Resolution by name."),
                BqlField("assignee", "assignee_profile_id", BqlFieldType.UUID),
                BqlField("reporter", "reporter_profile_id", BqlFieldType.UUID),
                BqlField("summary", "summary", BqlFieldType.TEXT, description = "Free-text title; routes through Meilisearch on `~`."),
                BqlField("description", "description_markdown", BqlFieldType.TEXT, description = "Free-text body; Meilisearch."),
                BqlField("dueDate", "due_date", BqlFieldType.TIMESTAMP),
                BqlField("startDate", "start_date", BqlFieldType.TIMESTAMP),
                BqlField("created", "created_at", BqlFieldType.TIMESTAMP),
                BqlField("modified", "modified_at", BqlFieldType.TIMESTAMP),
                BqlField("sprint", "sprint_id", BqlFieldType.UUID),
                BqlField("epic", "epic_task_id", BqlFieldType.UUID),
                BqlField("parent", "parent_task_id", BqlFieldType.UUID),
                BqlField("milestone", "milestone_id", BqlFieldType.UUID),
                BqlField("component", "component_ids", BqlFieldType.ARRAY_UUID),
                BqlField("label", "label_ids", BqlFieldType.ARRAY_UUID),
                BqlField("affectsVersion", "affects_version_ids", BqlFieldType.ARRAY_UUID),
                BqlField("fixVersion", "fix_version_ids", BqlFieldType.ARRAY_UUID),
                BqlField("watcher", "watcher_profile_ids", BqlFieldType.ARRAY_UUID),
            ),
        )
    }
}

/**
 * Function names BQL knows how to substitute. Each function carries
 * its argument arity so the validator catches `currentUser(foo)`
 * before the planner sees it. The arity is stored as
 * `[minArity, maxArity]` because kotlinx-serialization doesn't
 * ship a serializer for `IntRange`.
 */
@Serializable
data class BqlFunctionDescriptor(
    val name: String,
    val minArity: Int,
    val maxArity: Int,
    val description: String? = null,
) {
    fun acceptsArity(actual: Int): Boolean = actual in minArity..maxArity
}

class BqlFunctionCatalog(initial: List<BqlFunctionDescriptor> = emptyList()) {
    private val byName = initial.associateBy { it.name.lowercase() }.toMutableMap()

    fun register(fn: BqlFunctionDescriptor) {
        byName[fn.name.lowercase()] = fn
    }

    fun lookup(name: String): BqlFunctionDescriptor? = byName[name.lowercase()]

    companion object {
        /** Phase 6 defaults: zero-arg helpers covering the load-bearing 80% of saved filters. */
        fun default(): BqlFunctionCatalog = BqlFunctionCatalog(
            listOf(
                BqlFunctionDescriptor("currentUser", 0, 0, "The acting profile id at execution time."),
                BqlFunctionDescriptor("now", 0, 0, "Server-side `now()` timestamp."),
                BqlFunctionDescriptor("startOfDay", 0, 0, "00:00 of the server's local day."),
                BqlFunctionDescriptor("endOfDay", 0, 0, "23:59:59 of the server's local day."),
                BqlFunctionDescriptor("startOfWeek", 0, 0, "00:00 of the current week's Monday."),
                BqlFunctionDescriptor("endOfWeek", 0, 0, "23:59:59 of the current week's Sunday."),
            ),
        )
    }
}
