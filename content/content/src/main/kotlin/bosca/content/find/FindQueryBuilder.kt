package bosca.content.find

import bosca.attributes.AttributeLocation
import bosca.attributes.AttributeType
import bosca.content.ordering.IOrdering
import bosca.content.ordering.Order
import bosca.db.query.CompiledQuery
import bosca.db.query.QueryCompiler
import bosca.serialization.UUID

object FindQueryBuilder {

    fun buildOrderByClause(
        startIndex: Int = 0,
        ordering: List<IOrdering>,
        names: MutableList<String>,
        values: MutableList<Any?>,
        relationshipAttributesColumn: String,
        collectionItemAttributesColumn: String,
        metadataItemAttributesColumn: String,
        tableAlias: String,
        fieldMapping: Map<String, String> = emptyMap(),
    ): Pair<String, Int> {
        buildOrderingNames(ordering, names)
        var index = startIndex
        val buf = StringBuilder("order by ")
        var n = 0
        var addedRules = 0

        for (attr in ordering) {
            val field = attr.field
            val path = attr.path
            // Skip rule if both field and path are null/empty (per spec: EC-001)
            val hasValidField = !field.isNullOrBlank()
            val hasValidPath = path != null && path.isNotEmpty()
            if (!hasValidField && !hasValidPath) {
                continue
            }

            if (addedRules > 0) {
                buf.append(", ")
            }

            var hasOrdering = false

            path?.takeIf { it.isNotEmpty() }?.let {
                buf.append('(')
                when (attr.location ?: AttributeLocation.RELATIONSHIP) {
                    AttributeLocation.RELATIONSHIP -> {
                        buf.append(relationshipAttributesColumn)
                    }

                    else -> {
                        if (collectionItemAttributesColumn.isNotEmpty() && metadataItemAttributesColumn.isNotEmpty()) {
                            buf.append("(case when $collectionItemAttributesColumn is null then $metadataItemAttributesColumn else $collectionItemAttributesColumn end)")
                        } else if (collectionItemAttributesColumn.isNotEmpty()) {
                            buf.append(collectionItemAttributesColumn)
                        } else if (metadataItemAttributesColumn.isNotEmpty()) {
                            buf.append(metadataItemAttributesColumn)
                        }
                    }
                }

                it.forEach { _ ->
                    val name = names[n]
                    n++
                    values.add(name)
                    buf.append("->>?")
                    index++
                }

                buf.append(")::")
                when (attr.type ?: AttributeType.STRING) {
                    AttributeType.STRING -> buf.append("varchar")
                    AttributeType.INT -> buf.append("bigint")
                    AttributeType.FLOAT -> buf.append("double precision")
                    AttributeType.DATE -> buf.append("int")
                    AttributeType.DATE_TIME, AttributeType.DATETIME -> buf.append("bigint")
                    AttributeType.PROFILE -> buf.append("uuid")
                    AttributeType.METADATA -> buf.append("uuid")
                    AttributeType.COLLECTION -> buf.append("uuid")
                }
                hasOrdering = true
            } ?: field?.takeIf { it.isNotBlank() }?.let { fieldName ->
                if (fieldMapping.containsKey(fieldName)) {
                    buf.append(fieldMapping[fieldName])
                } else {
                    buf.append(tableAlias)
                    buf.append('.')
                    buf.append(fieldName)
                }
                hasOrdering = true
            }

            if (hasOrdering) {
                buf.append(' ')
                buf.append(if (attr.order == Order.ASCENDING) "asc" else "desc")
                addedRules++
            }
        }

        if (addedRules == 0) {
            return Pair("", index)
        }

        return Pair(buf.toString(), index)
    }

    private fun buildOrderingNames(ordering: List<IOrdering>, names: MutableList<String>) {
        for (attr in ordering) {
            val field = attr.field
            val path = attr.path
            // Skip invalid rules (per spec: EC-001)
            val hasValidField = !field.isNullOrBlank()
            val hasValidPath = !path.isNullOrEmpty()
            if (!hasValidField && !hasValidPath) {
                continue
            }

            path?.let {
                for (p in it) {
                    names.add(p)
                }
            }
        }
    }

    fun buildFindQuery(
        baseType: String,
        query: String,
        tableAlias: String,
        itemAttributesColumn: String,
        relationshipAttributesColumn: String,
        findQuery: FindQueryInput,
        categoryIds: List<UUID>?,
        traitIds: List<String>?,
        count: Boolean,
        names: MutableList<String>
    ): Pair<CompiledQuery, MutableList<Any?>> {
        val q = StringBuilder(query)
        val values = mutableListOf<Any?>()
        var pos = 1

        categoryIds?.let { ids ->
            if (ids.isNotEmpty()) {
                for (categoryId in ids) {
                    q.append(" inner join ${baseType}_categories as cid on (cid.${baseType}_id = $tableAlias.id and cid.category_id = $$pos) ")
                    pos++
                    values.add(categoryId)
                }
            }
        }

        traitIds?.let { ids ->
            if (ids.isNotEmpty()) {
                for (traitId in ids) {
                    q.append(" inner join ${baseType}_traits as tid on (tid.${baseType}_id = $tableAlias.id and tid.trait_id = $$pos) ")
                    pos++
                    values.add(traitId)
                }
            }
        }

        when (findQuery.extensionFilter) {
            ExtensionFilterType.DOCUMENT -> {
                q.append(" inner join documents d on ($tableAlias.id = d.metadata_id and $tableAlias.version = d.version) ")
            }

            ExtensionFilterType.DOCUMENT_TEMPLATE -> {
                q.append(" inner join document_templates dt on ($tableAlias.id = dt.metadata_id and $tableAlias.version = dt.version) ")
            }

            ExtensionFilterType.GUIDE -> {
                q.append(" inner join guides g on ($tableAlias.id = g.metadata_id and $tableAlias.version = g.version) ")
            }

            ExtensionFilterType.GUIDE_TEMPLATE -> {
                q.append(" inner join guide_templates gt on ($tableAlias.id = gt.metadata_id and $tableAlias.version = gt.version) ")
            }

            ExtensionFilterType.COLLECTION_TEMPLATE -> {
                q.append(" inner join collection_templates ct on ($tableAlias.id = ct.metadata_id and $tableAlias.version = ct.version) ")
            }

            null -> { /* do nothing */
            }
        }

        q.append(" where $tableAlias.deleted = false ")

        if (baseType == "collection") {
            findQuery.collectionType?.let { collectionType ->
                q.append(" and $tableAlias.type = $$pos::collection_type ")
                pos++
                values.add(collectionType.name.lowercase())
            }
        }

        findQuery.attributes?.let { attributes ->
            if (attributes.isNotEmpty() && attributes.any { it.attributes.isNotEmpty() }) {
                q.append(" and ")
                for ((i, attrs) in attributes.withIndex()) {
                    if (attrs.attributes.isEmpty()) {
                        continue
                    }
                    if (i > 0) {
                        q.append(" or ")
                    }
                    q.append(" ( ")
                    for ((j, attr) in attrs.attributes.withIndex()) {
                        if (j > 0) {
                            q.append(" and ")
                        }
                        q.append(" ($tableAlias.$itemAttributesColumn->>($$pos::varchar))::varchar = $${pos + 1}::varchar ")
                        pos += 2
                        values.add(attr.key)
                        values.add(attr.value)
                    }
                    q.append(" ) ")
                }
            }
        }

        findQuery.contentTypes?.let { contentTypes ->
            if (contentTypes.isNotEmpty()) {
                q.append(" and $tableAlias.content_type in (")
                for ((ix, contentType) in contentTypes.withIndex()) {
                    if (ix > 0) {
                        q.append(", ")
                    }
                    q.append("$$pos")
                    pos++
                    values.add(contentType)
                }
                q.append(") ")
            }
        }

        findQuery.languageTags?.let { languageTags ->
            if (languageTags.isNotEmpty()) {
                q.append(" and $tableAlias.language_tag in (")
                for ((ix, languageTag) in languageTags.withIndex()) {
                    if (ix > 0) {
                        q.append(", ")
                    }
                    q.append("$$pos")
                    pos++
                    values.add(languageTag)
                }
                q.append(") ")
            }
        }

        if (!count) {
            findQuery.ordering?.let { ordering ->
                if (ordering.isNotEmpty()) {
                    val (orderingSql, index) = buildOrderByClause(
                        pos,
                        ordering,
                        names,
                        values,
                        relationshipAttributesColumn,
                        "",
                        itemAttributesColumn,
                        tableAlias
                    )
                    pos = index
                    if (orderingSql.isNotEmpty()) {
                        q.append(orderingSql)
                    }
                } else {
                    q.append(" order by lower($tableAlias.name) asc ")
                    // TODO: when adding MetadataIndex & CollectionIndex, make this configurable so it is based on an index
                }
            }

            findQuery.offset?.let { offset ->
                q.append(" offset $$pos")
                values.add(offset)
                pos++
            }

            findQuery.limit?.let { limit ->
                q.append(" limit $$pos")
                values.add(limit)
            }
        }

        return Pair(QueryCompiler.compileQuery(q.toString(), '$'), values)
    }

}
