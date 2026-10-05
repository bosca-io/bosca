package bosca.workops.service

import bosca.db.connection
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.workops.model.bql.BqlBoundParameter
import bosca.workops.model.bql.BqlBoundType
import bosca.workops.model.bql.BqlFieldCatalog
import bosca.workops.model.bql.BqlFunctionCatalog
import bosca.workops.model.bql.BqlFunctionResolver
import bosca.workops.model.bql.BqlNameResolver
import bosca.workops.model.bql.BqlParseException
import bosca.workops.model.bql.BqlParser
import bosca.workops.model.bql.BqlPlanner
import bosca.workops.model.bql.BqlValidator
import bosca.workops.model.spec.Spec
import bosca.workops.repository.ProjectRepository
import bosca.workops.repository.StatusRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.sql.Timestamp

@ServiceImplementation
class SpecQueryServiceImpl(
    private val statusRepository: StatusRepository,
    private val projectRepository: ProjectRepository,
) : SpecQueryService {

    private val fields = BqlFieldCatalog.spec()
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
    ): SpecSearchResult {
        val safeLimit = limit.coerceIn(1, 200)
        val safeOffset = offset.coerceAtLeast(0)

        if (bqlSource.isBlank()) {
            val sql = "select * from workops.spec where deleted_at is null order by modified_at desc limit ? offset ?"
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
            "select * from workops.spec where deleted_at is null and ($whereSql) $orderBy limit ? offset ?"
        return executeSearch(sql, plan.parameters, safeLimit, safeOffset, plan.freeTextTerms)
    }

    private suspend fun executeSearch(
        sql: String,
        params: List<BqlBoundParameter>,
        limit: Int,
        offset: Long,
        freeTextTerms: List<String>,
    ): SpecSearchResult {
        val rows = mutableListOf<Spec>()
        connection().useStatement(sql) { stmt ->
            params.forEach { param ->
                bindParameter(stmt, param)
            }
            stmt.setInt(params.size + 1, limit)
            stmt.setLong(params.size + 2, offset)
            stmt.executeQuery().use { rs ->
                while (rs.next()) rows += specFromRow(rs)
            }
        }
        return SpecSearchResult(rows = rows, freeTextTerms = freeTextTerms)
    }

    internal fun bindParameter(stmt: java.sql.PreparedStatement, param: BqlBoundParameter) {
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

    internal fun specFromRow(rs: java.sql.ResultSet): Spec {
        return Spec(
            id = uuid(rs, "id") ?: error("missing spec id"),
            key = rs.getString("key"),
            metadataId = uuid(rs, "metadata_id") ?: error("missing spec metadata_id"),
            programId = uuid(rs, "program_id"),
            projectId = uuid(rs, "project_id"),
            statusId = uuid(rs, "status_id") ?: error("missing spec status_id"),
            workflowId = uuid(rs, "workflow_id") ?: error("missing spec workflow_id"),
            ownerProfileId = uuid(rs, "owner_profile_id") ?: error("missing spec owner_profile_id"),
            parentSpecId = uuid(rs, "parent_spec_id"),
            sortOrder = rs.getInt("sort_order"),
            childCount = rs.getInt("child_count"),
            childDoneCount = rs.getInt("child_done_count"),
            gitRepositoryId = uuid(rs, "git_repository_id"),
            gitPath = rs.getString("git_path"),
            watcherProfileIds = uuidArray(rs, "watcher_profile_ids"),
            labelIds = uuidArray(rs, "label_ids"),
            externalReferences = rs.getString("external_references")?.let {
                Json.parseToJsonElement(it)
            },
            public = rs.getBoolean("public"),
            publicContent = rs.getBoolean("public_content"),
            publicList = rs.getBoolean("public_list"),
            publicSupplementary = rs.getBoolean("public_supplementary"),
            createdAt = odt(rs, "created_at") ?: error("missing spec created_at"),
            modifiedAt = odt(rs, "modified_at") ?: error("missing spec modified_at"),
            createdByPrincipalId = uuid(rs, "created_by_principal_id")
                ?: error("missing spec created_by_principal_id"),
            modifiedByPrincipalId = uuid(rs, "modified_by_principal_id")
                ?: error("missing spec modified_by_principal_id"),
            deletedAt = odt(rs, "deleted_at"),
            version = rs.getLong("version"),
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

    internal fun nameResolver(): BqlNameResolver = BqlNameResolver { field, name ->
        when (field.column) {
            "status_id" -> statusRepository.getAll().firstOrNull { it.name.equals(name, ignoreCase = true) }?.id
            "project_id" -> projectRepository.getByKey(name.uppercase())?.id
            else -> null
        }
    }

    internal fun functionResolver(actingProfileId: UUID?): BqlFunctionResolver = object : BqlFunctionResolver {
        override suspend fun currentUserProfileId(): UUID? = actingProfileId
        override suspend fun now(): OffsetDateTime = OffsetDateTime.now()
    }
}
