package bosca.content.find

import bosca.attributes.AttributeLocation
import bosca.attributes.AttributeType
import bosca.content.ordering.Order
import bosca.content.ordering.Ordering
import kotlin.test.Test
import kotlin.test.assertEquals

class FindQueryBuilderTest {

    @Test
    fun `buildOrderByClause handles simple field`() {
        val ordering = listOf(
            Ordering(field = "name", order = Order.ASCENDING)
        )
        val names = mutableListOf<String>()
        val values = mutableListOf<Any?>()

        val (sql, index) = FindQueryBuilder.buildOrderByClause(
            startIndex = 1,
            ordering = ordering,
            names = names,
            values = values,
            relationshipAttributesColumn = "rel.attrs",
            collectionItemAttributesColumn = "col.attrs",
            metadataItemAttributesColumn = "meta.attrs",
            tableAlias = "tbl"
        )

        assertEquals("order by tbl.name asc", sql.trim())
        assertEquals(1, index)
    }

    @Test
    fun `buildOrderByClause handles JSON path with relationship`() {
        val ordering = listOf(
            Ordering(
                path = listOf("sort"),
                location = AttributeLocation.RELATIONSHIP,
                order = Order.DESCENDING,
                type = AttributeType.INT
            )
        )
        val names = mutableListOf<String>()
        val values = mutableListOf<Any?>()

        val (sql, index) = FindQueryBuilder.buildOrderByClause(
            startIndex = 1,
            ordering = ordering,
            names = names,
            values = values,
            relationshipAttributesColumn = "rel.attrs",
            collectionItemAttributesColumn = "col.attrs",
            metadataItemAttributesColumn = "meta.attrs",
            tableAlias = "tbl"
        )

        assertEquals("order by (rel.attrs->>?)::bigint desc", sql.trim())
        assertEquals(2, index)
        assertEquals("sort", values[0])
    }

    @Test
    fun `buildOrderByClause handles JSON path with item`() {
        val ordering = listOf(
            Ordering(
                path = listOf("user", "rank"),
                location = AttributeLocation.ITEM,
                order = Order.ASCENDING,
                type = AttributeType.FLOAT
            )
        )
        val names = mutableListOf<String>()
        val values = mutableListOf<Any?>()

        val (sql, index) = FindQueryBuilder.buildOrderByClause(
            startIndex = 1,
            ordering = ordering,
            names = names,
            values = values,
            relationshipAttributesColumn = "rel.attrs",
            collectionItemAttributesColumn = "col.attrs",
            metadataItemAttributesColumn = "meta.attrs",
            tableAlias = "tbl"
        )

        val expected = "order by ((case when col.attrs is null then meta.attrs else col.attrs end)->>?->>?)::double precision asc"
        assertEquals(expected, sql.trim())
        assertEquals(3, index)
        assertEquals("user", values[0])
        assertEquals("rank", values[1])
    }

    @Test
    fun `buildOrderByClause handles multiple rules`() {
        val ordering = listOf(
            Ordering(path = listOf("priority"), location = AttributeLocation.RELATIONSHIP, order = Order.DESCENDING, type = AttributeType.INT),
            Ordering(field = "name", order = Order.ASCENDING)
        )
        val names = mutableListOf<String>()
        val values = mutableListOf<Any?>()

        val (sql, index) = FindQueryBuilder.buildOrderByClause(
            startIndex = 1,
            ordering = ordering,
            names = names,
            values = values,
            relationshipAttributesColumn = "rel.attrs",
            collectionItemAttributesColumn = "col.attrs",
            metadataItemAttributesColumn = "meta.attrs",
            tableAlias = "tbl"
        )

        assertEquals("order by (rel.attrs->>?)::bigint desc, tbl.name asc", sql.trim())
        assertEquals(2, index)
        assertEquals("priority", values[0])
    }

    @Test
    fun `buildOrderByClause parameters increment correctly`() {
        val ordering = listOf(
            Ordering(path = listOf("a"), location = AttributeLocation.RELATIONSHIP, order = Order.ASCENDING),
            Ordering(path = listOf("b"), location = AttributeLocation.RELATIONSHIP, order = Order.DESCENDING)
        )
        val names = mutableListOf<String>()
        val values = mutableListOf<Any?>()

        val (sql, index) = FindQueryBuilder.buildOrderByClause(
            startIndex = 5,
            ordering = ordering,
            names = names,
            values = values,
            relationshipAttributesColumn = "rel.attrs",
            collectionItemAttributesColumn = "col.attrs",
            metadataItemAttributesColumn = "meta.attrs",
            tableAlias = "tbl"
        )

        assertEquals("order by (rel.attrs->>?)::varchar asc, (rel.attrs->>?)::varchar desc", sql.trim())
        assertEquals(7, index)
        assertEquals("a", values[0])
        assertEquals("b", values[1])
    }
}
