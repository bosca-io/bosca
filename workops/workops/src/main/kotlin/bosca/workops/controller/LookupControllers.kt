package bosca.workops.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.workops.model.audit.FieldChange
import bosca.workops.model.audit.TaskHistoryEntry
import bosca.workops.model.task.Priority
import bosca.workops.model.task.Resolution
import bosca.workops.model.task.TaskType
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/*
 * Type controllers for the Phase 2 scalar-only types.
 */

@TypeController(type = "WorkOpsTaskType")
class TaskTypeFieldsController : GraphQLController<TaskType> {
    @Field fun id(t: TaskType) = t.id
    @Field fun name(t: TaskType) = t.name
    @Field fun description(t: TaskType) = t.description
    @Field fun iconKey(t: TaskType) = t.iconKey
    @Field fun colorHex(t: TaskType) = t.colorHex
    @Field fun hierarchyLevel(t: TaskType) = t.hierarchyLevel
    @Field fun version(t: TaskType) = t.version
}

@TypeController(type = "WorkOpsPriority")
class PriorityTypeController : GraphQLController<Priority> {
    @Field fun id(p: Priority) = p.id
    @Field fun name(p: Priority) = p.name
    @Field fun description(p: Priority) = p.description
    @Field fun iconKey(p: Priority) = p.iconKey
    @Field fun colorHex(p: Priority) = p.colorHex
    @Field fun displayOrder(p: Priority) = p.displayOrder
    @Field fun version(p: Priority) = p.version
}

@TypeController(type = "WorkOpsResolution")
class ResolutionTypeController : GraphQLController<Resolution> {
    @Field fun id(r: Resolution) = r.id
    @Field fun name(r: Resolution) = r.name
    @Field fun description(r: Resolution) = r.description
    @Field fun displayOrder(r: Resolution) = r.displayOrder
    @Field fun version(r: Resolution) = r.version
}

/**
 * The entity stores `changes` as a raw `jsonb` column ([kotlinx
 * .serialization.json.JsonElement]) because the KSP repository
 * binder lacks a path that round-trips a typed
 * `List<@Serializable>` through a Postgres `jsonb` column without
 * fighting the array adapter. The GraphQL surface promises a typed
 * `[FieldChange!]!` though, so the controller decodes lazily.
 */
@TypeController(type = "WorkOpsTaskHistoryEntry")
class TaskHistoryEntryTypeController(
    private val json: Json,
) : GraphQLController<TaskHistoryEntry> {

    @Field fun id(e: TaskHistoryEntry) = e.id
    @Field fun taskId(e: TaskHistoryEntry) = e.taskId
    @Field fun changedAt(e: TaskHistoryEntry) = e.changedAt
    @Field fun changedByPrincipalId(e: TaskHistoryEntry) = e.changedByPrincipalId
    @Field fun changedByProfileId(e: TaskHistoryEntry) = e.changedByProfileId

    @Field
    fun changes(entry: TaskHistoryEntry): List<FieldChange> =
        json.decodeFromJsonElement(ListSerializer(FieldChange.serializer()), entry.changes)
}

@TypeController(type = "WorkOpsFieldChange")
class FieldChangeTypeController : GraphQLController<FieldChange> {
    @Field fun fieldKey(c: FieldChange) = c.fieldKey
    @Field fun fieldName(c: FieldChange) = c.fieldKey
    @Field fun fromValue(c: FieldChange): kotlinx.serialization.json.JsonElement? = c.fromValue
    @Field fun oldValue(c: FieldChange): kotlinx.serialization.json.JsonElement? = c.fromValue
    @Field fun toValue(c: FieldChange): kotlinx.serialization.json.JsonElement? = c.toValue
    @Field fun newValue(c: FieldChange): kotlinx.serialization.json.JsonElement? = c.toValue
}
