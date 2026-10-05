package bosca.workops.service

import bosca.db.connection
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.workops.model.bql.BqlBoundType
import bosca.workops.model.bql.BqlField
import bosca.workops.model.bql.BqlFieldCatalog
import bosca.workops.model.bql.BqlFieldType
import bosca.workops.model.bql.BqlFunctionCatalog
import bosca.workops.model.bql.BqlFunctionResolver
import bosca.workops.model.bql.BqlNameResolver
import bosca.workops.model.bql.BqlParseException
import bosca.workops.model.bql.BqlParser
import bosca.workops.model.bql.BqlPlanner
import bosca.workops.model.bql.BqlQuery
import bosca.workops.model.bql.BqlValidator
import bosca.workops.model.task.Task
import bosca.workops.repository.PriorityRepository
import bosca.workops.repository.ProjectRepository
import bosca.workops.repository.ResolutionRepository
import bosca.workops.repository.StatusRepository
import bosca.workops.repository.TaskTypeRepository
import java.sql.Timestamp

@ServiceImplementation
class TaskQueryServiceImpl(
    private val priorityRepository: PriorityRepository,
    private val statusRepository: StatusRepository,
    private val taskTypeRepository: TaskTypeRepository,
    private val resolutionRepository: ResolutionRepository,
    private val projectRepository: ProjectRepository,
) : TaskQueryService {

    private val fields = BqlFieldCatalog.default()
    private val functions = BqlFunctionCatalog.default()

    override suspend fun validate(bqlSource: String): List<bosca.workops.model.bql.BqlError> {
        if (bqlSource.isBlank()) return emptyList()
        val parsed = BqlParser(bqlSource).parse()
        if (parsed.errors.isNotEmpty()) return parsed.errors
        val ast = parsed.query ?: return emptyList()
        return BqlValidator(fields, functions).validate(ast)
    }

    override suspend fun search(
        bqlSource: String,
        actingProfileId: UUID?,
        offset: Long,
        limit: Int,
    ): TaskSearchResult {
        val safeLimit = limit.coerceIn(1, 200)
        val safeOffset = offset.coerceAtLeast(0)

        if (bqlSource.isBlank()) {
            val sql = "select * from workops.task where deleted_at is null order by modified_at desc limit ? offset ?"
            return executeSearch(sql, emptyList(), safeLimit, safeOffset, emptyList())
        }

        val parsed = BqlParser(bqlSource).parse()
        val ast = parsed.query
        if (parsed.errors.isNotEmpty() || ast == null) {
            throw BqlParseException(parsed.errors)
        }
        val validatorErrors = BqlValidator(fields, functions).validate(ast)
        if (validatorErrors.isNotEmpty()) throw BqlParseException(validatorErrors)

        val planner = BqlPlanner(
            fields = fields,
            nameResolver = nameResolver(),
            functionResolver = functionResolver(actingProfileId),
        )
        val plan = planner.plan(ast)
        val whereSql = if (plan.whereSql.isBlank()) "true" else plan.whereSql
        val orderBy = if (plan.orderBySql.isBlank()) "order by modified_at desc" else plan.orderBySql
        val sql =
            "select * from workops.task where deleted_at is null and ($whereSql) $orderBy limit ? offset ?"
        return executeSearch(sql, plan.parameters, safeLimit, safeOffset, plan.freeTextTerms)
    }

    private suspend fun executeSearch(
        sql: String,
        params: List<bosca.workops.model.bql.BqlBoundParameter>,
        limit: Int,
        offset: Long,
        freeTextTerms: List<String>,
    ): TaskSearchResult {
        val rows = mutableListOf<Task>()
        connection().useStatement(sql) { stmt ->
            // Bind every planner-supplied parameter.
            params.forEach { param ->
                bindParameter(stmt, param)
            }
            stmt.setInt(params.size + 1, limit)
            stmt.setLong(params.size + 2, offset)
            stmt.executeQuery().use { rs ->
                while (rs.next()) rows += taskFromRow(rs)
            }
        }
        return TaskSearchResult(rows = rows, freeTextTerms = freeTextTerms)
    }

    private fun bindParameter(stmt: java.sql.PreparedStatement, param: bosca.workops.model.bql.BqlBoundParameter) {
        val ord = param.ordinal
        when (param.sqlType) {
            BqlBoundType.TEXT -> stmt.setString(ord, param.value as String)
            BqlBoundType.UUID -> {
                val uuid = param.value as UUID
                stmt.setObject(ord, java.util.UUID.fromString(uuid.toString()))
            }
            BqlBoundType.NUMBER -> {
                when (val n = param.value) {
                    is Long -> stmt.setLong(ord, n)
                    is Int -> stmt.setInt(ord, n)
                    is Double -> stmt.setDouble(ord, n)
                    is Float -> stmt.setFloat(ord, n.toDouble().toFloat())
                    else -> stmt.setObject(ord, n)
                }
            }
            BqlBoundType.BOOLEAN -> stmt.setBoolean(ord, param.value as Boolean)
            BqlBoundType.TIMESTAMP -> {
                val ts = param.value as OffsetDateTime
                stmt.setTimestamp(ord, Timestamp.from(ts.toInstant()))
            }
            BqlBoundType.UUID_ARRAY -> {
                @Suppress("UNCHECKED_CAST")
                val ids = param.value as Array<UUID>
                val converted = ids.map { java.util.UUID.fromString(it.toString()) }.toTypedArray()
                stmt.setArray(ord, stmt.connection.createArrayOf("uuid", converted))
            }
            BqlBoundType.TEXT_ARRAY -> {
                @Suppress("UNCHECKED_CAST")
                val texts = param.value as Array<String>
                stmt.setArray(ord, stmt.connection.createArrayOf("varchar", texts))
            }
        }
    }

    private fun taskFromRow(rs: java.sql.ResultSet): Task {
        // The dynamic-SQL search bypasses Bosca's KSP-generated row
        // mapper, so this method maps every Task column manually.
        // Keep aligned with workops.task's column set; the migration
        // tests catch column-rename drift.
        val id = uuid(rs, "id")!!
        val key = rs.getString("key")
        val projectId = uuid(rs, "project_id")!!
        val taskTypeId = uuid(rs, "task_type_id")!!
        val statusId = uuid(rs, "status_id")!!
        val priorityId = uuid(rs, "priority_id")!!
        val summary = rs.getString("summary")
        val descriptionMarkdown = rs.getString("description_markdown")
        val descriptionHtml = rs.getString("description_html")
        val reporterProfileId = uuid(rs, "reporter_profile_id")!!
        val assigneeProfileId = uuid(rs, "assignee_profile_id")
        val parentTaskId = uuid(rs, "parent_task_id")
        val epicTaskId = uuid(rs, "epic_task_id")
        val sprintId = uuid(rs, "sprint_id")
        val milestoneId = uuid(rs, "milestone_id")
        val affectsVersionIds = uuidArray(rs, "affects_version_ids")
        val fixVersionIds = uuidArray(rs, "fix_version_ids")
        val componentIds = uuidArray(rs, "component_ids")
        val labelIds = uuidArray(rs, "label_ids")
        val originalEstimateSeconds = rs.getObject("original_estimate_seconds") as Long?
        val remainingEstimateSeconds = rs.getObject("remaining_estimate_seconds") as Long?
        val timeSpentSeconds = rs.getLong("time_spent_seconds")
        val dueDate = odt(rs, "due_date")
        val startDate = odt(rs, "start_date")
        val resolutionId = uuid(rs, "resolution_id")
        val resolutionAt = odt(rs, "resolution_at")
        val slaDueAt = odt(rs, "sla_due_at")
        val contentItemId = uuid(rs, "content_item_id")
        val collectionId = uuid(rs, "collection_id")
        val customFieldJson = rs.getString("custom_field_values")
        val customFieldValues = if (customFieldJson == null) {
            kotlinx.serialization.json.JsonObject(emptyMap())
        } else {
            kotlinx.serialization.json.Json.parseToJsonElement(customFieldJson) as kotlinx.serialization.json.JsonObject
        }
        val epicTotalEstimateSeconds = rs.getLong("epic_total_estimate_seconds")
        val epicTotalRemainingSeconds = rs.getLong("epic_total_remaining_seconds")
        val epicTotalSpentSeconds = rs.getLong("epic_total_spent_seconds")
        val epicChildCount = rs.getInt("epic_child_count")
        val epicChildDoneCount = rs.getInt("epic_child_done_count")
        val watcherProfileIds = uuidArray(rs, "watcher_profile_ids")
        val voteCount = rs.getInt("vote_count")
        val externalReferences = rs.getString("external_references")?.let {
            kotlinx.serialization.json.Json.parseToJsonElement(it)
        }
        val isPublic = rs.getBoolean("public")
        val isPublicContent = rs.getBoolean("public_content")
        val isPublicList = rs.getBoolean("public_list")
        val isPublicSupplementary = rs.getBoolean("public_supplementary")
        val createdAt = odt(rs, "created_at")!!
        val modifiedAt = odt(rs, "modified_at")!!
        val createdByPrincipalId = uuid(rs, "created_by_principal_id")!!
        val modifiedByPrincipalId = uuid(rs, "modified_by_principal_id")!!
        val deletedAt = odt(rs, "deleted_at")
        val version = rs.getLong("version")

        return Task(
            id = id,
            key = key,
            projectId = projectId,
            taskTypeId = taskTypeId,
            statusId = statusId,
            priorityId = priorityId,
            summary = summary,
            descriptionMarkdown = descriptionMarkdown,
            descriptionHtml = descriptionHtml,
            reporterProfileId = reporterProfileId,
            assigneeProfileId = assigneeProfileId,
            parentTaskId = parentTaskId,
            epicTaskId = epicTaskId,
            sprintId = sprintId,
            milestoneId = milestoneId,
            affectsVersionIds = affectsVersionIds,
            fixVersionIds = fixVersionIds,
            componentIds = componentIds,
            labelIds = labelIds,
            originalEstimateSeconds = originalEstimateSeconds,
            remainingEstimateSeconds = remainingEstimateSeconds,
            timeSpentSeconds = timeSpentSeconds,
            dueDate = dueDate,
            startDate = startDate,
            resolutionId = resolutionId,
            resolutionAt = resolutionAt,
            slaDueAt = slaDueAt,
            contentItemId = contentItemId,
            collectionId = collectionId,
            customFieldValues = customFieldValues,
            epicTotalEstimateSeconds = epicTotalEstimateSeconds,
            epicTotalRemainingSeconds = epicTotalRemainingSeconds,
            epicTotalSpentSeconds = epicTotalSpentSeconds,
            epicChildCount = epicChildCount,
            epicChildDoneCount = epicChildDoneCount,
            watcherProfileIds = watcherProfileIds,
            voteCount = voteCount,
            externalReferences = externalReferences,
            public = isPublic,
            publicContent = isPublicContent,
            publicList = isPublicList,
            publicSupplementary = isPublicSupplementary,
            createdAt = createdAt,
            modifiedAt = modifiedAt,
            createdByPrincipalId = createdByPrincipalId,
            modifiedByPrincipalId = modifiedByPrincipalId,
            deletedAt = deletedAt,
            version = version,
        )
    }

    private fun uuid(rs: java.sql.ResultSet, column: String): UUID? {
        val raw = rs.getObject(column) as? java.util.UUID ?: return null
        return UUID.fromLongs(raw.mostSignificantBits, raw.leastSignificantBits)
    }

    private fun uuidArray(rs: java.sql.ResultSet, column: String): List<UUID> {
        val arr = rs.getArray(column) ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        val raws = arr.array as Array<java.util.UUID>
        return raws.map { UUID.fromLongs(it.mostSignificantBits, it.leastSignificantBits) }
    }

    private fun odt(rs: java.sql.ResultSet, column: String): OffsetDateTime? {
        val ts = rs.getTimestamp(column) ?: return null
        return OffsetDateTime.ofInstant(ts.toInstant(), java.time.ZoneOffset.UTC)
    }

    private fun nameResolver(): BqlNameResolver = BqlNameResolver { field, name ->
        when (field.column) {
            "priority_id" -> priorityRepository.getAll().firstOrNull { it.name.equals(name, ignoreCase = true) }?.id
            "status_id" -> statusRepository.getAll().firstOrNull { it.name.equals(name, ignoreCase = true) }?.id
            "task_type_id" -> taskTypeRepository.getAll().firstOrNull { it.name.equals(name, ignoreCase = true) }?.id
            "resolution_id" -> resolutionRepository.getAll().firstOrNull { it.name.equals(name, ignoreCase = true) }?.id
            "project_id" -> projectRepository.getByKey(name.uppercase())?.id
            else -> null
        }
    }

    private fun functionResolver(actingProfileId: UUID?): BqlFunctionResolver = object : BqlFunctionResolver {
        override suspend fun currentUserProfileId(): UUID? = actingProfileId
        override suspend fun now(): OffsetDateTime = OffsetDateTime.now()
    }
}
