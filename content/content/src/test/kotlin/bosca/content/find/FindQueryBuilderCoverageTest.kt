package bosca.content.find

import bosca.attributes.AttributeLocation
import bosca.attributes.AttributeType
import bosca.content.collection.model.CollectionType
import bosca.content.ordering.Order
import bosca.content.ordering.Ordering
import bosca.db.query.QueryType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Coverage-focused tests for [FindQueryBuilder]. The sibling FindQueryBuilderTest only exercises
 * a handful of buildOrderByClause happy paths; this file covers buildFindQuery in full plus the
 * remaining buildOrderByClause branches (skip rules, all AttributeType casts, fieldMapping,
 * collection/metadata column fallbacks, location default, empty result).
 */
class FindQueryBuilderCoverageTest {

    private fun order(
        field: String? = null,
        path: List<String>? = null,
        location: AttributeLocation? = null,
        type: AttributeType? = null,
        order: Order = Order.ASCENDING,
    ) = Ordering(field = field, path = path, location = location, order = order, type = type)

    // ---------------------------------------------------------------------------------------
    // buildOrderByClause branch coverage
    // ---------------------------------------------------------------------------------------

    @Test
    fun `buildOrderByClause returns empty when all rules invalid`() {
        val names = mutableListOf<String>()
        val values = mutableListOf<Any?>()
        val (sql, index) = FindQueryBuilder.buildOrderByClause(
            startIndex = 3,
            ordering = listOf(
                order(field = null, path = null),
                order(field = "", path = emptyList()),
                order(field = "   ", path = emptyList()),
            ),
            names = names,
            values = values,
            relationshipAttributesColumn = "rel.attrs",
            collectionItemAttributesColumn = "col.attrs",
            metadataItemAttributesColumn = "meta.attrs",
            tableAlias = "tbl",
        )
        assertEquals("", sql)
        assertEquals(3, index)
        assertTrue(names.isEmpty())
        assertTrue(values.isEmpty())
    }

    @Test
    fun `buildOrderByClause skips invalid rule then emits valid rule`() {
        val names = mutableListOf<String>()
        val values = mutableListOf<Any?>()
        val (sql, _) = FindQueryBuilder.buildOrderByClause(
            startIndex = 0,
            ordering = listOf(
                order(field = "", path = null),
                order(field = "name", order = Order.DESCENDING),
            ),
            names = names,
            values = values,
            relationshipAttributesColumn = "rel.attrs",
            collectionItemAttributesColumn = "col.attrs",
            metadataItemAttributesColumn = "meta.attrs",
            tableAlias = "tbl",
        )
        assertEquals("order by tbl.name desc", sql.trim())
    }

    @Test
    fun `buildOrderByClause path with null location defaults to relationship`() {
        val names = mutableListOf<String>()
        val values = mutableListOf<Any?>()
        val (sql, index) = FindQueryBuilder.buildOrderByClause(
            startIndex = 1,
            ordering = listOf(order(path = listOf("k"), location = null, type = AttributeType.STRING)),
            names = names,
            values = values,
            relationshipAttributesColumn = "rel.attrs",
            collectionItemAttributesColumn = "col.attrs",
            metadataItemAttributesColumn = "meta.attrs",
            tableAlias = "tbl",
        )
        assertEquals("order by (rel.attrs->>?)::varchar asc", sql.trim())
        assertEquals(2, index)
        assertEquals(listOf<Any?>("k"), values)
    }

    @Test
    fun `buildOrderByClause item location with only collection column`() {
        val names = mutableListOf<String>()
        val values = mutableListOf<Any?>()
        val (sql, _) = FindQueryBuilder.buildOrderByClause(
            startIndex = 1,
            ordering = listOf(order(path = listOf("k"), location = AttributeLocation.ITEM)),
            names = names,
            values = values,
            relationshipAttributesColumn = "rel.attrs",
            collectionItemAttributesColumn = "col.attrs",
            metadataItemAttributesColumn = "",
            tableAlias = "tbl",
        )
        assertEquals("order by (col.attrs->>?)::varchar asc", sql.trim())
    }

    @Test
    fun `buildOrderByClause item location with only metadata column`() {
        val names = mutableListOf<String>()
        val values = mutableListOf<Any?>()
        val (sql, _) = FindQueryBuilder.buildOrderByClause(
            startIndex = 1,
            ordering = listOf(order(path = listOf("k"), location = AttributeLocation.ITEM)),
            names = names,
            values = values,
            relationshipAttributesColumn = "rel.attrs",
            collectionItemAttributesColumn = "",
            metadataItemAttributesColumn = "meta.attrs",
            tableAlias = "tbl",
        )
        assertEquals("order by (meta.attrs->>?)::varchar asc", sql.trim())
    }

    @Test
    fun `buildOrderByClause item location with no columns still emits ordering`() {
        val names = mutableListOf<String>()
        val values = mutableListOf<Any?>()
        val (sql, _) = FindQueryBuilder.buildOrderByClause(
            startIndex = 1,
            ordering = listOf(order(path = listOf("k"), location = AttributeLocation.ITEM)),
            names = names,
            values = values,
            relationshipAttributesColumn = "rel.attrs",
            collectionItemAttributesColumn = "",
            metadataItemAttributesColumn = "",
            tableAlias = "tbl",
        )
        // both columns empty: no column text is appended but the path/cast still emit
        assertEquals("order by (->>?)::varchar asc", sql.trim())
    }

    @Test
    fun `buildOrderByClause covers every AttributeType cast`() {
        data class Case(val type: AttributeType?, val cast: String)
        val cases = listOf(
            Case(AttributeType.STRING, "varchar"),
            Case(AttributeType.INT, "bigint"),
            Case(AttributeType.FLOAT, "double precision"),
            Case(AttributeType.DATE, "int"),
            Case(AttributeType.DATE_TIME, "bigint"),
            Case(AttributeType.DATETIME, "bigint"),
            Case(AttributeType.PROFILE, "uuid"),
            Case(AttributeType.METADATA, "uuid"),
            Case(AttributeType.COLLECTION, "uuid"),
            Case(null, "varchar"), // null defaults to STRING
        )
        for (case in cases) {
            val names = mutableListOf<String>()
            val values = mutableListOf<Any?>()
            val (sql, _) = FindQueryBuilder.buildOrderByClause(
                startIndex = 1,
                ordering = listOf(order(path = listOf("k"), location = AttributeLocation.RELATIONSHIP, type = case.type)),
                names = names,
                values = values,
                relationshipAttributesColumn = "rel.attrs",
                collectionItemAttributesColumn = "col.attrs",
                metadataItemAttributesColumn = "meta.attrs",
                tableAlias = "tbl",
            )
            assertEquals("order by (rel.attrs->>?)::${case.cast} asc", sql.trim(), "type=${case.type}")
        }
    }

    @Test
    fun `buildOrderByClause uses fieldMapping when present`() {
        val names = mutableListOf<String>()
        val values = mutableListOf<Any?>()
        val (sql, _) = FindQueryBuilder.buildOrderByClause(
            startIndex = 0,
            ordering = listOf(order(field = "created", order = Order.DESCENDING)),
            names = names,
            values = values,
            relationshipAttributesColumn = "rel.attrs",
            collectionItemAttributesColumn = "col.attrs",
            metadataItemAttributesColumn = "meta.attrs",
            tableAlias = "tbl",
            fieldMapping = mapOf("created" to "tbl.created_at"),
        )
        assertEquals("order by tbl.created_at desc", sql.trim())
    }

    @Test
    fun `buildOrderByClause fieldMapping miss falls back to tableAlias field`() {
        val names = mutableListOf<String>()
        val values = mutableListOf<Any?>()
        val (sql, _) = FindQueryBuilder.buildOrderByClause(
            startIndex = 0,
            ordering = listOf(order(field = "name")),
            names = names,
            values = values,
            relationshipAttributesColumn = "rel.attrs",
            collectionItemAttributesColumn = "col.attrs",
            metadataItemAttributesColumn = "meta.attrs",
            tableAlias = "tbl",
            fieldMapping = mapOf("other" to "tbl.other_col"),
        )
        assertEquals("order by tbl.name asc", sql.trim())
    }

    @Test
    fun `buildOrderByClause path takes precedence over field`() {
        val names = mutableListOf<String>()
        val values = mutableListOf<Any?>()
        val (sql, _) = FindQueryBuilder.buildOrderByClause(
            startIndex = 1,
            ordering = listOf(order(field = "name", path = listOf("k"), location = AttributeLocation.RELATIONSHIP)),
            names = names,
            values = values,
            relationshipAttributesColumn = "rel.attrs",
            collectionItemAttributesColumn = "col.attrs",
            metadataItemAttributesColumn = "meta.attrs",
            tableAlias = "tbl",
        )
        // path present => field branch is not taken
        assertEquals("order by (rel.attrs->>?)::varchar asc", sql.trim())
    }

    // ---------------------------------------------------------------------------------------
    // buildFindQuery coverage
    // ---------------------------------------------------------------------------------------

    private fun baseQuery() = "select * from metadata m"

    @Test
    fun `buildFindQuery minimal empty filter compiles to select`() {
        val names = mutableListOf<String>()
        val (compiled, values) = FindQueryBuilder.buildFindQuery(
            baseType = "metadata",
            query = baseQuery(),
            tableAlias = "m",
            itemAttributesColumn = "attributes",
            relationshipAttributesColumn = "rel_attributes",
            findQuery = FindQueryInput(),
            categoryIds = null,
            traitIds = null,
            count = false,
            names = names,
        )
        assertEquals(QueryType.SELECT, compiled.type)
        assertTrue(compiled.sql.contains("m.deleted = false"))
        assertTrue(values.isEmpty())
    }

    @Test
    fun `buildFindQuery with empty categoryIds and traitIds adds nothing`() {
        val names = mutableListOf<String>()
        val (_, values) = FindQueryBuilder.buildFindQuery(
            baseType = "metadata",
            query = baseQuery(),
            tableAlias = "m",
            itemAttributesColumn = "attributes",
            relationshipAttributesColumn = "rel_attributes",
            findQuery = FindQueryInput(),
            categoryIds = emptyList(),
            traitIds = emptyList(),
            count = false,
            names = names,
        )
        assertTrue(values.isEmpty())
    }

    @Test
    fun `buildFindQuery joins categoryIds and traitIds`() {
        val names = mutableListOf<String>()
        val cat1 = Uuid.random()
        val cat2 = Uuid.random()
        val (compiled, values) = FindQueryBuilder.buildFindQuery(
            baseType = "metadata",
            query = baseQuery(),
            tableAlias = "m",
            itemAttributesColumn = "attributes",
            relationshipAttributesColumn = "rel_attributes",
            findQuery = FindQueryInput(),
            categoryIds = listOf(cat1, cat2),
            traitIds = listOf("trait-a"),
            count = true,
            names = names,
        )
        assertTrue(compiled.originalQuery.contains("metadata_categories"))
        assertTrue(compiled.originalQuery.contains("metadata_traits"))
        assertEquals(listOf<Any?>(cat1, cat2, "trait-a"), values)
    }

    @Test
    fun `buildFindQuery every extensionFilter arm`() {
        val map = mapOf(
            ExtensionFilterType.DOCUMENT to "documents d",
            ExtensionFilterType.DOCUMENT_TEMPLATE to "document_templates dt",
            ExtensionFilterType.GUIDE to "guides g",
            ExtensionFilterType.GUIDE_TEMPLATE to "guide_templates gt",
            ExtensionFilterType.COLLECTION_TEMPLATE to "collection_templates ct",
        )
        for ((filter, fragment) in map) {
            val names = mutableListOf<String>()
            val (compiled, _) = FindQueryBuilder.buildFindQuery(
                baseType = "metadata",
                query = baseQuery(),
                tableAlias = "m",
                itemAttributesColumn = "attributes",
                relationshipAttributesColumn = "rel_attributes",
                findQuery = FindQueryInput(extensionFilter = filter),
                categoryIds = null,
                traitIds = null,
                count = true,
                names = names,
            )
            assertTrue(compiled.originalQuery.contains(fragment), "filter=$filter")
        }
    }

    @Test
    fun `buildFindQuery null extensionFilter adds no join`() {
        val names = mutableListOf<String>()
        val (compiled, _) = FindQueryBuilder.buildFindQuery(
            baseType = "metadata",
            query = baseQuery(),
            tableAlias = "m",
            itemAttributesColumn = "attributes",
            relationshipAttributesColumn = "rel_attributes",
            findQuery = FindQueryInput(extensionFilter = null),
            categoryIds = null,
            traitIds = null,
            count = true,
            names = names,
        )
        assertFalse(compiled.originalQuery.contains("inner join documents"))
        assertFalse(compiled.originalQuery.contains("inner join guides"))
    }

    @Test
    fun `buildFindQuery collection type only applied when baseType collection`() {
        val names = mutableListOf<String>()
        val (compiled, values) = FindQueryBuilder.buildFindQuery(
            baseType = "collection",
            query = "select * from collection c",
            tableAlias = "c",
            itemAttributesColumn = "attributes",
            relationshipAttributesColumn = "rel_attributes",
            findQuery = FindQueryInput(collectionType = CollectionType.FOLDER),
            categoryIds = null,
            traitIds = null,
            count = true,
            names = names,
        )
        assertTrue(compiled.originalQuery.contains("c.type ="))
        assertEquals(listOf<Any?>("folder"), values)
    }

    @Test
    fun `buildFindQuery collectionType ignored for non-collection baseType`() {
        val names = mutableListOf<String>()
        val (compiled, values) = FindQueryBuilder.buildFindQuery(
            baseType = "metadata",
            query = baseQuery(),
            tableAlias = "m",
            itemAttributesColumn = "attributes",
            relationshipAttributesColumn = "rel_attributes",
            findQuery = FindQueryInput(collectionType = CollectionType.FOLDER),
            categoryIds = null,
            traitIds = null,
            count = true,
            names = names,
        )
        assertFalse(compiled.originalQuery.contains("::collection_type"))
        assertTrue(values.isEmpty())
    }

    @Test
    fun `buildFindQuery collection baseType with null collectionType`() {
        val names = mutableListOf<String>()
        val (compiled, values) = FindQueryBuilder.buildFindQuery(
            baseType = "collection",
            query = "select * from collection c",
            tableAlias = "c",
            itemAttributesColumn = "attributes",
            relationshipAttributesColumn = "rel_attributes",
            findQuery = FindQueryInput(collectionType = null),
            categoryIds = null,
            traitIds = null,
            count = true,
            names = names,
        )
        assertFalse(compiled.originalQuery.contains("::collection_type"))
        assertTrue(values.isEmpty())
    }

    @Test
    fun `buildFindQuery attributes single group multiple attrs`() {
        val names = mutableListOf<String>()
        val (compiled, values) = FindQueryBuilder.buildFindQuery(
            baseType = "metadata",
            query = baseQuery(),
            tableAlias = "m",
            itemAttributesColumn = "attributes",
            relationshipAttributesColumn = "rel_attributes",
            findQuery = FindQueryInput(
                attributes = listOf(
                    FindAttributesInput(
                        attributes = listOf(
                            FindAttributeInput(key = "color", value = "red"),
                            FindAttributeInput(key = "size", value = "large"),
                        )
                    )
                )
            ),
            categoryIds = null,
            traitIds = null,
            count = true,
            names = names,
        )
        assertTrue(compiled.originalQuery.contains("->>"))
        assertEquals(listOf<Any?>("color", "red", "size", "large"), values)
    }

    @Test
    fun `buildFindQuery attributes multiple groups joined with or`() {
        val names = mutableListOf<String>()
        val (compiled, values) = FindQueryBuilder.buildFindQuery(
            baseType = "metadata",
            query = baseQuery(),
            tableAlias = "m",
            itemAttributesColumn = "attributes",
            relationshipAttributesColumn = "rel_attributes",
            findQuery = FindQueryInput(
                attributes = listOf(
                    FindAttributesInput(attributes = listOf(FindAttributeInput(key = "a", value = "1"))),
                    // empty group is skipped by inner continue
                    FindAttributesInput(attributes = emptyList()),
                    FindAttributesInput(attributes = listOf(FindAttributeInput(key = "b", value = "2"))),
                )
            ),
            categoryIds = null,
            traitIds = null,
            count = true,
            names = names,
        )
        assertTrue(compiled.originalQuery.contains(" or "))
        assertEquals(listOf<Any?>("a", "1", "b", "2"), values)
    }

    @Test
    fun `buildFindQuery attributes all groups empty adds no clause`() {
        val names = mutableListOf<String>()
        val (compiled, values) = FindQueryBuilder.buildFindQuery(
            baseType = "metadata",
            query = baseQuery(),
            tableAlias = "m",
            itemAttributesColumn = "attributes",
            relationshipAttributesColumn = "rel_attributes",
            findQuery = FindQueryInput(
                attributes = listOf(FindAttributesInput(attributes = emptyList()))
            ),
            categoryIds = null,
            traitIds = null,
            count = true,
            names = names,
        )
        assertFalse(compiled.originalQuery.contains("->>"))
        assertTrue(values.isEmpty())
    }

    @Test
    fun `buildFindQuery empty attributes list adds no clause`() {
        val names = mutableListOf<String>()
        val (_, values) = FindQueryBuilder.buildFindQuery(
            baseType = "metadata",
            query = baseQuery(),
            tableAlias = "m",
            itemAttributesColumn = "attributes",
            relationshipAttributesColumn = "rel_attributes",
            findQuery = FindQueryInput(attributes = emptyList()),
            categoryIds = null,
            traitIds = null,
            count = true,
            names = names,
        )
        assertTrue(values.isEmpty())
    }

    @Test
    fun `buildFindQuery content types in clause`() {
        val names = mutableListOf<String>()
        val (compiled, values) = FindQueryBuilder.buildFindQuery(
            baseType = "metadata",
            query = baseQuery(),
            tableAlias = "m",
            itemAttributesColumn = "attributes",
            relationshipAttributesColumn = "rel_attributes",
            findQuery = FindQueryInput(contentTypes = listOf("text/plain", "application/json")),
            categoryIds = null,
            traitIds = null,
            count = true,
            names = names,
        )
        assertTrue(compiled.originalQuery.contains("m.content_type in ("))
        assertEquals(listOf<Any?>("text/plain", "application/json"), values)
    }

    @Test
    fun `buildFindQuery empty content types adds nothing`() {
        val names = mutableListOf<String>()
        val (compiled, values) = FindQueryBuilder.buildFindQuery(
            baseType = "metadata",
            query = baseQuery(),
            tableAlias = "m",
            itemAttributesColumn = "attributes",
            relationshipAttributesColumn = "rel_attributes",
            findQuery = FindQueryInput(contentTypes = emptyList()),
            categoryIds = null,
            traitIds = null,
            count = true,
            names = names,
        )
        assertFalse(compiled.originalQuery.contains("content_type in"))
        assertTrue(values.isEmpty())
    }

    @Test
    fun `buildFindQuery language tags in clause`() {
        val names = mutableListOf<String>()
        val (compiled, values) = FindQueryBuilder.buildFindQuery(
            baseType = "metadata",
            query = baseQuery(),
            tableAlias = "m",
            itemAttributesColumn = "attributes",
            relationshipAttributesColumn = "rel_attributes",
            findQuery = FindQueryInput(languageTags = listOf("en", "es")),
            categoryIds = null,
            traitIds = null,
            count = true,
            names = names,
        )
        assertTrue(compiled.originalQuery.contains("m.language_tag in ("))
        assertEquals(listOf<Any?>("en", "es"), values)
    }

    @Test
    fun `buildFindQuery empty language tags adds nothing`() {
        val names = mutableListOf<String>()
        val (compiled, values) = FindQueryBuilder.buildFindQuery(
            baseType = "metadata",
            query = baseQuery(),
            tableAlias = "m",
            itemAttributesColumn = "attributes",
            relationshipAttributesColumn = "rel_attributes",
            findQuery = FindQueryInput(languageTags = emptyList()),
            categoryIds = null,
            traitIds = null,
            count = true,
            names = names,
        )
        assertFalse(compiled.originalQuery.contains("language_tag in"))
        assertTrue(values.isEmpty())
    }

    @Test
    fun `buildFindQuery ordering non-empty appends order by clause and advances pos`() {
        val names = mutableListOf<String>()
        val (compiled, values) = FindQueryBuilder.buildFindQuery(
            baseType = "metadata",
            query = baseQuery(),
            tableAlias = "m",
            itemAttributesColumn = "attributes",
            relationshipAttributesColumn = "rel_attributes",
            findQuery = FindQueryInput(
                ordering = listOf(
                    bosca.content.ordering.OrderingInput(
                        path = listOf("sort"),
                        location = AttributeLocation.RELATIONSHIP,
                        order = Order.DESCENDING,
                        type = AttributeType.INT,
                    )
                ),
                offset = 10L,
                limit = 5,
            ),
            categoryIds = null,
            traitIds = null,
            count = false,
            names = names,
        )
        assertTrue(compiled.originalQuery.contains("order by"))
        // ordering value name, then offset, then limit
        assertEquals(listOf<Any?>("sort", 10L, 5), values)
    }

    @Test
    fun `buildFindQuery ordering that produces empty sql is skipped`() {
        val names = mutableListOf<String>()
        val (compiled, values) = FindQueryBuilder.buildFindQuery(
            baseType = "metadata",
            query = baseQuery(),
            tableAlias = "m",
            itemAttributesColumn = "attributes",
            relationshipAttributesColumn = "rel_attributes",
            findQuery = FindQueryInput(
                // an ordering rule that is invalid -> buildOrderByClause returns empty string
                ordering = listOf(bosca.content.ordering.OrderingInput(field = "", path = emptyList())),
            ),
            categoryIds = null,
            traitIds = null,
            count = false,
            names = names,
        )
        assertFalse(compiled.originalQuery.contains("order by"))
        assertTrue(values.isEmpty())
    }

    @Test
    fun `buildFindQuery empty ordering falls back to default name order`() {
        val names = mutableListOf<String>()
        val (compiled, _) = FindQueryBuilder.buildFindQuery(
            baseType = "metadata",
            query = baseQuery(),
            tableAlias = "m",
            itemAttributesColumn = "attributes",
            relationshipAttributesColumn = "rel_attributes",
            findQuery = FindQueryInput(ordering = emptyList()),
            categoryIds = null,
            traitIds = null,
            count = false,
            names = names,
        )
        assertTrue(compiled.originalQuery.contains("order by lower(m.name) asc"))
    }

    @Test
    fun `buildFindQuery null ordering does not add default order by`() {
        val names = mutableListOf<String>()
        val (compiled, _) = FindQueryBuilder.buildFindQuery(
            baseType = "metadata",
            query = baseQuery(),
            tableAlias = "m",
            itemAttributesColumn = "attributes",
            relationshipAttributesColumn = "rel_attributes",
            findQuery = FindQueryInput(ordering = null),
            categoryIds = null,
            traitIds = null,
            count = false,
            names = names,
        )
        assertFalse(compiled.originalQuery.contains("order by"))
    }

    @Test
    fun `buildFindQuery offset and limit applied`() {
        val names = mutableListOf<String>()
        val (compiled, values) = FindQueryBuilder.buildFindQuery(
            baseType = "metadata",
            query = baseQuery(),
            tableAlias = "m",
            itemAttributesColumn = "attributes",
            relationshipAttributesColumn = "rel_attributes",
            findQuery = FindQueryInput(offset = 20L, limit = 15),
            categoryIds = null,
            traitIds = null,
            count = false,
            names = names,
        )
        assertTrue(compiled.originalQuery.contains("offset"))
        assertTrue(compiled.originalQuery.contains("limit"))
        assertEquals(listOf<Any?>(20L, 15), values)
    }

    @Test
    fun `buildFindQuery count true skips ordering offset and limit`() {
        val names = mutableListOf<String>()
        val (compiled, values) = FindQueryBuilder.buildFindQuery(
            baseType = "metadata",
            query = baseQuery(),
            tableAlias = "m",
            itemAttributesColumn = "attributes",
            relationshipAttributesColumn = "rel_attributes",
            findQuery = FindQueryInput(
                ordering = emptyList(),
                offset = 20L,
                limit = 15,
            ),
            categoryIds = null,
            traitIds = null,
            count = true,
            names = names,
        )
        assertFalse(compiled.originalQuery.contains("order by"))
        assertFalse(compiled.originalQuery.contains("offset"))
        assertFalse(compiled.originalQuery.contains("limit"))
        assertTrue(values.isEmpty())
    }

    @Test
    fun `buildFindQuery combines all filters and orders values by position`() {
        val names = mutableListOf<String>()
        val cat = Uuid.random()
        val (compiled, values) = FindQueryBuilder.buildFindQuery(
            baseType = "collection",
            query = "select * from collection c",
            tableAlias = "c",
            itemAttributesColumn = "attributes",
            relationshipAttributesColumn = "rel_attributes",
            findQuery = FindQueryInput(
                collectionType = CollectionType.STANDARD,
                attributes = listOf(
                    FindAttributesInput(attributes = listOf(FindAttributeInput(key = "k", value = "v")))
                ),
                contentTypes = listOf("text/plain"),
                languageTags = listOf("en"),
                extensionFilter = ExtensionFilterType.GUIDE,
                offset = 3L,
                limit = 7,
            ),
            categoryIds = listOf(cat),
            traitIds = listOf("t"),
            count = false,
            names = names,
        )
        assertEquals(QueryType.SELECT, compiled.type)
        assertEquals(
            listOf<Any?>(cat, "t", "standard", "k", "v", "text/plain", "en", 3L, 7),
            values,
        )
    }
}
