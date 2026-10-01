package bosca.content.metadata.repository

import bosca.content.find.FindQueryBuilder
import bosca.content.find.FindQueryInput
import bosca.content.metadata.model.Metadata
import bosca.db.connection
import bosca.db.mapper.DefaultMappers
import bosca.serialization.UUID
import kotlinx.serialization.json.Json
import java.sql.ResultSet
import kotlin.reflect.KClass

class MetadataFindRepository(json: Json) {

    private val mappers = DefaultMappers(json)

    suspend fun find(query: FindQueryInput): List<Metadata> {
        val names = mutableListOf<String>()
        val (query, values) = FindQueryBuilder.buildFindQuery(
            "metadata",
            "select m.* from metadata as m ",
            "m",
            "attributes",
            "attributes",
            query,
            query.categoryIds,
            query.traitIds,
            false,
            names,
        )
        val connection = connection()
        return connection.useStatement(query.sql) { stmt ->
            values.forEachIndexed { index, value ->
                stmt.setObject(index + 1, value)
            }
            stmt.executeQuery().use {
                map(it)
            }
        }
    }

    suspend fun findBySystem(query: FindQueryInput): List<Metadata> {
        val names = mutableListOf<String>()
        val (query, values) = FindQueryBuilder.buildFindQuery(
            "metadata",
            "select m.* from metadata as m ",
            "m",
            "system_attributes",
            "system_attributes",
            query,
            query.categoryIds,
            query.traitIds,
            false,
            names,
        )
        val connection = connection()
        return connection.useStatement(query.sql) {
            values.forEachIndexed { index, value ->
                it.setObject(index + 1, value)
            }
            it.executeQuery().use {
                map(it)
            }
        }
    }

    suspend fun findCount(query: FindQueryInput): Long {
        val names = mutableListOf<String>()
        val (query, values) = FindQueryBuilder.buildFindQuery(
            "metadata",
            "select count(*) from metadata as m ",
            "m",
            "attributes",
            "attributes",
            query,
            query.categoryIds,
            query.traitIds,
            true,
            names,
        )
        val connection = connection()
        return connection.useStatement(query.sql) { stmt ->
            values.forEachIndexed { index, value ->
                stmt.setObject(index + 1, value)
            }
            stmt.executeQuery().use {
                if (it.next()) {
                    it.getLong(1)
                } else {
                    0L
                }
            }
        }
    }

    private fun map(row: ResultSet): List<Metadata> {
        val result = mutableListOf<Metadata>()
        while (row.next()) {
            val id = mappers.uuid.map(UUID::class, emptyList<KClass<*>>(), row, "id") ?: error("id is required")
            val version = mappers.int.map(Int::class, emptyList<KClass<*>>(), row, "version") ?: error("version is required")
            val activeVersion = mappers.int.map(Int::class, emptyList<KClass<*>>(), row, "active_version") ?: error("activeVersion is required")
            val parentId = mappers.uuid.map(UUID::class, emptyList<KClass<*>>(), row, "parent_id")
            val name = mappers.string.map(String::class, emptyList<KClass<*>>(), row, "name") ?: error("name is required")
            val type = bosca.content.metadata.model.MetadataTypeMapper.map(bosca.content.metadata.model.MetadataType::class, emptyList<KClass<*>>(), row, "type") ?: error("type is required")
            val contentType = mappers.string.map(String::class, emptyList<KClass<*>>(), row, "content_type") ?: error("contentType is required")
            val contentLength = mappers.long.map(Long::class, emptyList<KClass<*>>(), row, "content_length")
            val languageTag = mappers.string.map(String::class, emptyList<KClass<*>>(), row, "language_tag") ?: error("languageTag is required")
            @Suppress("UNCHECKED_CAST") val labels = mappers.array.map(Array::class, listOf(kotlin.String::class), row, "labels")?.toList() as List<String>
            val attributes = mappers.jsonElement.map(kotlinx.serialization.json.JsonElement::class, emptyList<KClass<*>>(), row, "attributes")
            val systemAttributes = mappers.jsonElement.map(kotlinx.serialization.json.JsonElement::class, emptyList<KClass<*>>(), row, "system_attributes")
            val deleted = mappers.boolean.map(Boolean::class, emptyList<KClass<*>>(), row, "deleted") ?: error("deleted is required")
            val public = mappers.boolean.map(Boolean::class, emptyList<KClass<*>>(), row, "public") ?: error("public is required")
            val publicContent = mappers.boolean.map(Boolean::class, emptyList<KClass<*>>(), row, "public_content") ?: error("publicContent is required")
            val publicSupplementary = mappers.boolean.map(Boolean::class, emptyList<KClass<*>>(), row, "public_supplementary") ?: error("publicSupplementary is required")
            val created = mappers.offsetDateTime.map(bosca.serialization.OffsetDateTime::class, emptyList<KClass<*>>(), row, "created") ?: error("created is required")
            val modified = mappers.offsetDateTime.map(bosca.serialization.OffsetDateTime::class, emptyList<KClass<*>>(), row, "modified")
            val uploaded = mappers.offsetDateTime.map(bosca.serialization.OffsetDateTime::class, emptyList<KClass<*>>(), row, "uploaded")
            val ready = mappers.offsetDateTime.map(bosca.serialization.OffsetDateTime::class, emptyList<KClass<*>>(), row, "ready")
            val workflowStateId = mappers.string.map(String::class, emptyList<KClass<*>>(), row, "workflow_state_id") ?: error("workflowStateId is required")
            val workflowStatePendingId = mappers.string.map(String::class, emptyList<KClass<*>>(), row, "workflow_state_pending_id")
            val workflowStateValid = mappers.offsetDateTime.map(bosca.serialization.OffsetDateTime::class, emptyList<KClass<*>>(), row, "workflow_state_valid")
            val sourceId = mappers.uuid.map(UUID::class, emptyList<KClass<*>>(), row, "source_id")
            val sourceIdentifier = mappers.string.map(String::class, emptyList<KClass<*>>(), row, "source_identifier")
            val sourceUrl = mappers.string.map(String::class, emptyList<KClass<*>>(), row, "source_url")
            val sourceStatus = bosca.content.metadata.model.SourceStatusMapper.map(bosca.content.metadata.model.SourceStatus::class, emptyList<KClass<*>>(), row, "source_status")
            val deleteWorkflowId = mappers.string.map(String::class, emptyList<KClass<*>>(), row, "delete_workflow_id")
            val permissionMutation = mappers.int.map(Int::class, emptyList<KClass<*>>(), row, "permission_mutation") ?: error("permissionMutation is required")
            val etag = mappers.string.map(String::class, emptyList<KClass<*>>(), row, "etag")
            val locked = mappers.boolean.map(Boolean::class, emptyList<KClass<*>>(), row, "locked") ?: error("locked is required")
            result += Metadata(
                id,
                version,
                activeVersion,
                parentId,
                name,
                type,
                contentType,
                contentLength,
                languageTag,
                labels,
                attributes,
                systemAttributes,
                deleted,
                public,
                publicContent,
                publicSupplementary,
                created,
                modified,
                uploaded,
                ready,
                workflowStateId,
                workflowStatePendingId,
                workflowStateValid,
                sourceId,
                sourceIdentifier,
                sourceUrl,
                sourceStatus,
                deleteWorkflowId,
                permissionMutation,
                etag,
                locked
            )
        }
        return result
    }
}