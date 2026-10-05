package bosca.content.collection.repository

import bosca.cache.ServiceCache
import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionFindResult
import bosca.content.find.ExpandCacheId
import bosca.content.find.ExpandCacheKeySerializer
import bosca.content.find.FindQueryBuilder
import bosca.content.find.FindQueryInput
import bosca.content.ordering.Ordering
import bosca.db.connection
import bosca.db.mapper.DefaultMappers
import bosca.serialization.UUID
import kotlinx.serialization.json.Json
import java.sql.ResultSet
import java.sql.Types
import kotlin.reflect.KClass
import kotlin.uuid.toJavaUuid

class CollectionFindRepository(json: Json) {

    private val mappers = DefaultMappers(json)

    private val expandedCache = ServiceCache<ExpandCacheId, List<CollectionFindResult>>(cacheName = "collection:expand", ExpandCacheKeySerializer) { key ->
        val names = mutableListOf<String>()
        val values = mutableListOf<Any?>()
        key.state?.let { values.add(it) }
        val (order, _) = key.ordering?.takeIf { it.isNotEmpty() }?.let {
            FindQueryBuilder.buildOrderByClause(
                key.state?.let { 3 } ?: 2,
                it,
                names,
                values,
                "coalesce(child.attributes, parent.attributes)",
                "",
                "metadata.attributes",
                "metadata",
            )
        } ?: Pair("", 0)
        val query = buildString {
            append("with data as (select coalesce(child.child_collection_id, parent.collection_id) as child_collection_id, coalesce(child.child_metadata_id, parent.child_metadata_id) as child_metadata_id, child.attributes as attributes, ")
            append("row_number() over (")
            append(order)
            append(") as row_num from collection_items parent left join collection_items as child on (parent.child_collection_id = child.collection_id and parent.child_collection_id is not null) ")
            if (key.state != null) {
                append(" left join metadata on ((parent.child_metadata_id = metadata.id or child.child_metadata_id = metadata.id) and metadata.workflow_state_id = ?) ")
            } else {
                append(" left join metadata on (parent.child_metadata_id = metadata.id or child.child_metadata_id = metadata.id) ")
            }
            append(" where parent.collection_id = ? and (metadata.id is not null and (metadata.deleted is null or metadata.deleted = false)) ")
            append(" ) select * from (select distinct on (child_collection_id, child_metadata_id) child_collection_id, child_metadata_id, attributes, row_num from data) as a order by row_num ")
            append(" offset ? limit ?")
        }
        values.add(key.id)
        values.add(key.offset)
        values.add(key.limit)
        val connection = connection()
        connection.useStatement(query) { stmt ->
            values.forEachIndexed { index, value ->
                if (value == null) {
                    stmt.setNull(index + 1, Types.VARCHAR)
                } else {
                    if (value is UUID) stmt.setObject(index + 1, value.toJavaUuid())
                    else stmt.setObject(index + 1, value)
                }
            }
            stmt.executeQuery().use { row ->
                val results = mutableListOf<CollectionFindResult>()
                while (row.next()) {
                    val childCollectionId = mappers.uuid.map(UUID::class, emptyList<KClass<*>>(), row, "child_collection_id")
                    val childMetadataId = mappers.uuid.map(UUID::class, emptyList<KClass<*>>(), row, "child_metadata_id")
                    val attributes = mappers.jsonElement.map(kotlinx.serialization.json.JsonElement::class, emptyList<KClass<*>>(), row, "attributes")
                    results += CollectionFindResult(childCollectionId, childMetadataId, attributes)
                }
                results
            }
        }
    }

    private val expandedCountCache = ServiceCache(cacheName = "collection:expand:count", ExpandCacheKeySerializer) {
        val values = mutableListOf<Any?>()
        it.state?.let { values.add(it) }
        values.add(it.id)
        val query = buildString {
            append("select count( distinct coalesce(child.child_collection_id, parent.collection_id)::varchar || coalesce(child.child_metadata_id, parent.child_metadata_id)::varchar ) from collection_items parent left join collection_items as child on (parent.child_collection_id = child.collection_id and parent.child_collection_id is not null) ")
            if (it.state != null) {
                append(" left join metadata on ((parent.child_metadata_id = metadata.id or child.child_metadata_id = metadata.id) and metadata.workflow_state_id = ?) ")
            } else {
                append(" left join metadata on (parent.child_metadata_id = metadata.id or child.child_metadata_id = metadata.id) ")
            }
            append(" where parent.collection_id = ? and (metadata.id is not null and (metadata.deleted is null or metadata.deleted = false)) ")
        }
        val connection = connection()
        connection.useStatement(query) { stmt ->
            values.forEachIndexed { index, value ->
                if (value == null) {
                    stmt.setNull(index + 1, Types.VARCHAR)
                } else {
                    if (value is UUID) stmt.setObject(index + 1, value.toJavaUuid())
                    else stmt.setObject(index + 1, value)
                }
            }
            stmt.executeQuery().use {
                if (it.next()) {
                    it.getLong(1)
                } else {
                    0
                }
            }
        }
    }

    suspend fun removeFromCache(id: UUID) {
        expandedCache.remove(ExpandCacheId(id), keyPrefix = true)
        expandedCountCache.remove(ExpandCacheId(id), keyPrefix = true)
    }

    suspend fun expandMetadata(
        collectionId: UUID,
        ordering: List<Ordering>,
        state: String?,
        offset: Long,
        limit: Int
    ): List<CollectionFindResult> = expandedCache.get(
        ExpandCacheId(
            id = collectionId,
            state = state,
            ordering = ordering,
            offset = offset,
            limit = limit
        )
    ) ?: emptyList()

    suspend fun expandMetadataCount(collectionId: UUID, state: String?): Long = expandedCountCache.get(
        ExpandCacheId(
            id = collectionId,
            state = state
        )
    ) ?: 0L

    suspend fun find(query: FindQueryInput): List<Collection> {
        val names = mutableListOf<String>()
        val (query, values) = FindQueryBuilder.buildFindQuery(
            "collection",
            "select c.* from collections as c ",
            "c",
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
            stmt.executeQuery().use { map(it) }
        }
    }

    suspend fun findBySystem(query: FindQueryInput): List<Collection> {
        val names = mutableListOf<String>()
        val (query, values) = FindQueryBuilder.buildFindQuery(
            "collection",
            "select c.* from collections as c ",
            "c",
            "system_attributes",
            "system_attributes",
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
            stmt.executeQuery().use { map(it) }
        }
    }

    suspend fun findCount(query: FindQueryInput): Long {
        val names = mutableListOf<String>()
        val (query, values) = FindQueryBuilder.buildFindQuery(
            "collection",
            "select count(*) from collections as c ",
            "c",
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

    private fun map(row: ResultSet): List<Collection> {
        val items = mutableListOf<Collection>()
        while (row.next()) {
            val id = mappers.uuid.map(UUID::class, emptyList<KClass<*>>(), row, "id") ?: error("id is required")
            val name = mappers.string.map(String::class, emptyList<KClass<*>>(), row, "name") ?: error("name is required")
            val languageTag = mappers.string.map(String::class, emptyList<KClass<*>>(), row, "language_tag") ?: error("languageTag is required")
            val type = bosca.content.collection.model.CollectionTypeMapper.map(bosca.content.collection.model.CollectionType::class, emptyList<KClass<*>>(), row, "type") ?: error("type is required")
            val description = mappers.string.map(String::class, emptyList<KClass<*>>(), row, "description")
            val attributes = mappers.jsonElement.map(kotlinx.serialization.json.JsonElement::class, emptyList<KClass<*>>(), row, "attributes")
            val systemAttributes = mappers.jsonElement.map(kotlinx.serialization.json.JsonElement::class, emptyList<KClass<*>>(), row, "system_attributes")
            @Suppress("UNCHECKED_CAST") val labels = mappers.array.map(Array::class, listOf(kotlin.String::class), row, "labels")?.toList() as kotlin.collections.List<kotlin.String>
            val created = mappers.offsetDateTime.map(bosca.serialization.OffsetDateTime::class, emptyList<KClass<*>>(), row, "created") ?: error("created is required")
            val modified = mappers.offsetDateTime.map(bosca.serialization.OffsetDateTime::class, emptyList<KClass<*>>(), row, "modified") ?: error("modified is required")
            val ready = mappers.offsetDateTime.map(bosca.serialization.OffsetDateTime::class, emptyList<KClass<*>>(), row, "ready")
            val etag = mappers.string.map(String::class, emptyList<KClass<*>>(), row, "etag")
            val enabled = mappers.boolean.map(Boolean::class, emptyList<KClass<*>>(), row, "enabled") ?: error("enabled is required")
            val ordering = mappers.jsonElement.map(kotlinx.serialization.json.JsonElement::class, emptyList<KClass<*>>(), row, "ordering")
            val workflowStateId = mappers.string.map(String::class, emptyList<KClass<*>>(), row, "workflow_state_id") ?: error("workflowStateId is required")
            val workflowStatePendingId = mappers.string.map(String::class, emptyList<KClass<*>>(), row, "workflow_state_pending_id")
            val workflowStateValid = mappers.offsetDateTime.map(bosca.serialization.OffsetDateTime::class, emptyList<KClass<*>>(), row, "workflow_state_valid")
            val deleteWorkflowId = mappers.string.map(String::class, emptyList<KClass<*>>(), row, "delete_workflow_id")
            val public = mappers.boolean.map(Boolean::class, emptyList<KClass<*>>(), row, "public") ?: error("public is required")
            val publicList = mappers.boolean.map(Boolean::class, emptyList<KClass<*>>(), row, "public_list") ?: error("publicList is required")
            val publicSupplementary = mappers.boolean.map(Boolean::class, emptyList<KClass<*>>(), row, "public_supplementary") ?: error("publicSupplementary is required")
            val locked = mappers.boolean.map(Boolean::class, emptyList<KClass<*>>(), row, "locked") ?: error("locked is required")
            val itemsLocked = mappers.boolean.map(Boolean::class, emptyList<KClass<*>>(), row, "items_locked") ?: error("itemsLocked is required")
            val deleted = mappers.boolean.map(Boolean::class, emptyList<KClass<*>>(), row, "deleted") ?: error("deleted is required")
            val templateMetadataId = mappers.uuid.map(UUID::class, emptyList<KClass<*>>(), row, "template_metadata_id")
            val templateMetadataVersion = mappers.int.map(Int::class, emptyList<KClass<*>>(), row, "template_metadata_version")
            items += Collection(
                id,
                name,
                languageTag,
                type,
                description,
                attributes,
                systemAttributes,
                labels,
                created,
                modified,
                ready,
                etag,
                enabled,
                ordering,
                workflowStateId,
                workflowStatePendingId,
                workflowStateValid,
                deleteWorkflowId,
                public,
                publicList,
                publicSupplementary,
                locked,
                itemsLocked,
                deleted,
                templateMetadataId,
                templateMetadataVersion
            )
        }
        return items
    }
}